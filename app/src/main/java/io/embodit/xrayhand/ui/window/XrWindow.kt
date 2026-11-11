package io.embodit.xrayhand.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import io.embodit.xrayhand.R
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.hand.Tab
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrViewModel
import io.embodit.xrayhand.ui.hand.components.LocalLang
import io.embodit.xrayhand.ui.hand.components.LocalXrResources
import io.embodit.xrayhand.ui.hand.components.MainPanel
import io.embodit.xrayhand.ui.hand.components.PanelSizes
import io.embodit.xrayhand.ui.hand.components.SettingsPage
import io.embodit.xrayhand.ui.hand.components.localizedResources
import io.embodit.xrayhand.ui.hand.debug.LocalProbePanel
import io.embodit.xrayhand.ui.theme.XrayTheme
import java.util.Locale

/**
 * v0.1.4 (profile D8 / D21, factory native-window-move §3.6; headset feedback "UI 菜单一打开就斜着向下"): all 2D
 * content of the app is this one system Planar window, opened from the Stage once the head is level ([WindowCtl]).
 * Pages 认骨 / 测验 / 设置 are switched by the SpatialUI TabBar; the system moves, closes and minimises the window, and
 * gaze + pinch / controller ray on it are the system's own (the hands as content still click the UI, D22).
 */
@Composable
fun XrWindow() {
    val context = LocalContext.current
    val vm = remember(context) { XrViewModel.shared(context.applicationContext) }
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> WindowCtl.onLifecycle(e.name) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val converter = LocalPhysicalLengthConverter.current
    LaunchedEffect(converter) {
        if (WindowCtl.dpLogged) return@LaunchedEffect
        WindowCtl.dpLogged = true
        runCatching { converter.dpToLength(1000.dp, LengthUnit.Meters) / 1000f }.getOrNull()?.let {
            XrLog.i(String.format(Locale.US, "WINDOW dp=%.5f m (%.2f x %.2f m)", it, PanelSizes.WINDOW_W * it, PanelSizes.WINDOW_H * it))
        }
    }
    val lang = state.prefs.lang
    val resources = remember(lang, context) { localizedResources(context, lang) }
    // the TabBar is a system window of its own: its labels are taken in the app's language here (gate tabbar-lang)
    val tabs =
        listOf(
            MainWindowTab(Tab.EXPLORE.name, resources.getString(R.string.tab_explore), R.drawable.ic_hand_outline),
            MainWindowTab(Tab.QUIZ.name, resources.getString(R.string.tab_quiz), R.drawable.ic_quiz),
            MainWindowTab(Tab.SETTINGS.name, resources.getString(R.string.settings), R.drawable.ic_settings),
        )
    XrayTheme {
        CompositionLocalProvider(LocalXrResources provides resources, LocalLang provides lang, LocalProbePanel provides "window") {
            MainWindow(
                tabs = tabs,
                current = state.tab.name,
                showTabs = state.loaded && !state.welcome,
                onSelect = { id -> if (id != state.tab.name) vm.onEvent(XrEvent.TabPicked(Tab.valueOf(id))) },
            ) {
                if (state.tab == Tab.SETTINGS) SettingsPage(state, vm::onEvent) else MainPanel(state, vm.catalog, vm::onEvent)
            }
        }
    }
}
