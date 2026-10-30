package io.embodit.xrayhand.ui.hand

import android.content.pm.ApplicationInfo
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LocalAudioEffectPlayer
import com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager
import com.pico.spatial.ui.platform.SpatialSoundEffect
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.UpperLimbRenderModeChangeListener
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
    val viewModel: XrViewModel = viewModel(factory = XrViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val scene = remember(viewModel) { XrScene(context.applicationContext, viewModel::onEvent) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        // the one per-frame system; every start has its stop in onDispose
        registerSystem<XrFrameSystem>()
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
