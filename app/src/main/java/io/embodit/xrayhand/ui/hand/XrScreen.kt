package io.embodit.xrayhand.ui.hand

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.UpperLimbRenderModeChangeListener
import io.embodit.xrayhand.ui.window.WindowCtl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.hand.components.LocalLang
import io.embodit.xrayhand.ui.hand.components.LocalXrResources
import io.embodit.xrayhand.ui.hand.components.XrStage
import io.embodit.xrayhand.ui.hand.components.localizedResources
import io.embodit.xrayhand.ui.hand.components.sceneStrings
import io.embodit.xrayhand.ui.hand.debug.Probe
import io.embodit.xrayhand.ui.hand.scene.XrFrameSystem
import io.embodit.xrayhand.ui.hand.scene.XrScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space: hand tracking only exists there). It wires the ViewModel
 * (state), the [XrScene] (bones, per-frame work, pinches) and [XrStage] (the panels), and owns nothing.
 */
@Composable
fun XrScreen() {
    val context = LocalContext.current
    val viewModel: XrViewModel = remember(context) { XrViewModel.shared(context.applicationContext) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { XrScene(context.applicationContext, viewModel::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val navigator = LocalSpatialNavigator.current

    DisposableEffect(scene, lifecycle, navigator) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<XrFrameSystem>()
        WindowCtl.navigator = navigator
        viewModel.onRecall = { WindowCtl.recall("reopen") }
        viewModel.onExit = { exitApp(context, scene, navigator) }
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): XrViewModel = viewModel

                override fun scene(): XrScene = scene
            }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> scene.pause()
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        XrLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.onRecall = null
            viewModel.onExit = null
            if (WindowCtl.navigator === navigator) WindowCtl.navigator = null
            Probe.host = null
            unregisterSystem<XrFrameSystem>()
            scene.detach()
            XrLog.i("STAGE disposed")
        }
    }

    // the real hands only in the passthrough, the bones drawn over them (design `container.upperLimbRenderMode`: Hidden)
    val limbs = LocalStageUpperLimbRenderModelManager.current
    DisposableEffect(limbs) {
        val listener =
            object : UpperLimbRenderModeChangeListener {
                override fun onUpperLimbRenderModeChanged(upperLimbRenderMode: UpperLimbRenderMode) {
                    XrLog.i("LIMBS mode=$upperLimbRenderMode")
                }
            }
        runCatching { limbs.addUpperLimbRenderModeChangeListener(listener) }
        val result = runCatching { limbs.setUpperLimbRenderMode(UpperLimbRenderMode.Hidden) }
        XrLog.i("LIMBS set Hidden result=${result.getOrNull() ?: result.exceptionOrNull()}")
        onDispose { runCatching { limbs.removeUpperLimbRenderModeChangeListener(listener) } }
    }

    LaunchedEffect(scene, state) { scene.render(state) }
    // the system window opens once the head is level (D9, factory native-window-move §7.9)
    LaunchedEffect(Unit) { WindowCtl.openWindow("stage") }

    val audio = LocalAudioEffectPlayer.current
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                // the title-bar speaker switch silences every sound of the app (profile D15)
                is XrEffect.Feedback ->
                    if (viewModel.state.value.prefs.speech) runCatching {
                        audio.playSystem(
                            when (effect.kind) {
                                "right" -> SpatialSoundEffect.StateSuccess
                                "wrong" -> SpatialSoundEffect.StateFailure
                                else -> SpatialSoundEffect.StateSelected
                            }
                        )
                    }.onFailure { XrLog.w("FEEDBACK failed: $it") }
            }
        }
    }

    val lang = state.prefs.lang
    val resources = remember(lang, context) { localizedResources(context, lang) }
    LaunchedEffect(scene, context) { scene.setStrings(sceneStrings(localizedResources(context, Lang.ZH)), sceneStrings(localizedResources(context, Lang.EN))) }
    CompositionLocalProvider(LocalXrResources provides resources, LocalLang provides lang) {
        XrStage(state = state, scene = scene, catalog = viewModel.catalog, onEvent = viewModel::onEvent)
    }
}

/**
 * Settings > Exit, confirmed (D26): the scene and its trackers are released first, then the system window and the
 * Stage are closed (the window would otherwise outlive the Stage, native-window-move §7.7 / D44), then every task of
 * the app is finished and removed, then the process ends.
 */
private fun exitApp(context: Context, scene: XrScene, navigator: SpatialNavigator) {
    XrLog.i("EXIT releasing the scene, closing the window and the Stage")
    runCatching { scene.detach() }.onFailure { XrLog.e("EXIT detach failed: $it", it) }
    WindowCtl.closeForQuit()
    // a scope of its own: closing the Stage disposes the Stage's composition (and its scope) half-way
    MainScope().launch {
        runCatching { navigator.closeStage() }.onFailure { XrLog.w("EXIT closeStage: $it") }
        var activity: Context? = context
        while (activity is ContextWrapper && activity !is Activity) activity = activity.baseContext
        runCatching { (activity as? Activity)?.finishAndRemoveTask() }
        runCatching { context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() } }
        XrLog.i("EXIT tasks finished, ending the process")
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, EXIT_DELAY_MS)
    }
}

private const val EXIT_DELAY_MS = 400L
