package io.embodit.xrayhand.ui.hand.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import io.embodit.xrayhand.BuildConfig
import io.embodit.xrayhand.R
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.Look
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.debug.probe
import io.embodit.xrayhand.ui.theme.XrType

/**
 * The settings page of the system window (design `panels.settings`, S16; v0.1.4 a TabBar page, no longer a popup in
 * front of the main panel): language, look, reopen the panel, clear quiz records (confirm row), about, and Exit with
 * its confirm row at the bottom (D26). The window is sized so all of it, Exit included, is on the first screen (D16).
 */
@Composable
internal fun SettingsPage(state: XrUiState, onEvent: (XrEvent) -> Unit) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val grey = PicoTheme.colorScheme.labelSecondary
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = PagePad, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = rs(R.string.settings), color = ink, style = XrType.title, maxLines = 1, modifier = Modifier.height(40.dp).probe("settings_title"), onTextLayout = fits("settings/title"))
        val w = (PanelSizes.WINDOW_W - 48f - 120f).dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.lang), color = ink, style = XrType.status, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("settings/lang"))
            Segments(items = Lang.entries, selected = state.prefs.lang, width = w, name = { "lang_" + it.key }, onSelect = { onEvent(XrEvent.LangPicked(it)) }) { l ->
                Text(text = rs(when (l) { Lang.ZH -> R.string.lang_zh; Lang.EN -> R.string.lang_en; Lang.BOTH -> R.string.lang_both }), style = XrType.segment, maxLines = 1, softWrap = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.look), color = ink, style = XrType.status, maxLines = 1, modifier = Modifier.weight(1f), onTextLayout = fits("settings/look"))
            Segments(items = Look.entries, selected = state.prefs.look, width = w, name = { "look_" + it.key }, onSelect = { onEvent(XrEvent.LookPicked(it)) }) { l ->
                Text(text = rs(if (l == Look.IVORY) R.string.look_ivory else R.string.look_xray), style = XrType.segment, maxLines = 1, softWrap = false)
            }
        }
        Secondary("recall", rs(R.string.menu_recall)) { onEvent(XrEvent.RecallPanel) }
        if (state.confirmClear) {
            Confirm("clear_confirm", rs(R.string.clear_confirm), rs(R.string.cancel), rs(R.string.clear_yes), "clear_cancel", "clear_yes", { onEvent(XrEvent.ClearCancel) }) { onEvent(XrEvent.ClearConfirm) }
        } else {
            Secondary("clear", rs(R.string.clear)) { onEvent(XrEvent.ClearAsk) }
        }
        // a confirm row takes the about block's room, so Exit and its confirm stay on the first screen at 48 dp (D16 / B19)
        if (!state.confirmExit && !state.confirmClear) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = rs(R.string.about), color = ink, style = XrType.label, maxLines = 1, onTextLayout = fits("settings/about_title"))
            Text(text = rs(R.string.about_text, BuildConfig.VERSION_NAME), color = grey, style = XrType.label, maxLines = 7, modifier = Modifier.probe("about"), onTextLayout = fits("settings/about"))
        }
        Spacer(modifier = Modifier.weight(1f))
        if (state.confirmExit) {
            Confirm("exit_confirm", rs(R.string.exit_confirm), rs(R.string.cancel), rs(R.string.exit_yes), "exit_cancel", "exit_yes", { onEvent(XrEvent.ExitCancel) }) { onEvent(XrEvent.ExitConfirm) }
        } else {
            Secondary("exit", rs(R.string.exit_app)) { onEvent(XrEvent.ExitAsk) }
        }
    }
}

/** A confirm row in the page (no second panel, D8): the question, [cancel] and [yes]. */
@Composable
private fun Confirm(name: String, question: String, no: String, yes: String, noName: String, yesName: String, onNo: () -> Unit, onYes: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(10.dp).probe(name),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = question, color = PicoTheme.colorScheme.labelPrimary, style = XrType.status, maxLines = 2, onTextLayout = fits("settings/$name"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) { Secondary(noName, no, onClick = onNo) }
            Box(modifier = Modifier.weight(1f)) { Primary(yesName, yes, onYes) }
        }
    }
}
