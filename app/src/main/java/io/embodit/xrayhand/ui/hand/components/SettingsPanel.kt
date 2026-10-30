package io.embodit.xrayhand.ui.hand.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.xrayhand.BuildConfig
import io.embodit.xrayhand.R
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.Look
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.debug.probe
import io.embodit.xrayhand.ui.theme.XrType

/** The settings popup (design `panels.settings`, S16): language, look, clear quiz records (with a confirm row), about. */
@Composable
internal fun SettingsPanel(state: XrUiState?, onEvent: (XrEvent) -> Unit) {
    Column(
        modifier =
            Modifier
                .size(PanelSizes.SETTINGS_W.dp, PanelSizes.SETTINGS_H.dp)
                .clip(RoundedCornerShape(28.dp))
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state == null) return@Column
        val ink = PicoTheme.colorScheme.labelPrimary
        val grey = PicoTheme.colorScheme.labelSecondary
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.settings), color = ink, style = XrType.title, maxLines = 1, modifier = Modifier.weight(1f))
            Key("settings_close", R.drawable.ic_cross, rs(R.string.cd_close)) { onEvent(XrEvent.SettingsClose) }
        }
        val w = (PanelSizes.SETTINGS_W - 36f - 92f).dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.lang), color = ink, style = XrType.status, maxLines = 1, modifier = Modifier.weight(1f))
            Segments(items = Lang.entries, selected = state.prefs.lang, width = w, name = { "lang_" + it.key }, onSelect = { onEvent(XrEvent.LangPicked(it)) }) { l ->
                Text(text = rs(when (l) { Lang.ZH -> R.string.lang_zh; Lang.EN -> R.string.lang_en; Lang.BOTH -> R.string.lang_both }), style = XrType.segment, maxLines = 1, softWrap = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = rs(R.string.look), color = ink, style = XrType.status, maxLines = 1, modifier = Modifier.weight(1f))
            Segments(items = Look.entries, selected = state.prefs.look, width = w, name = { "look_" + it.key }, onSelect = { onEvent(XrEvent.LookPicked(it)) }) { l ->
                Text(text = rs(if (l == Look.IVORY) R.string.look_ivory else R.string.look_xray), style = XrType.segment, maxLines = 1, softWrap = false)
            }
        }
        if (state.confirmClear) {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = rs(R.string.clear_confirm), color = ink, style = XrType.status, maxLines = 1, modifier = Modifier.probe("clear_confirm"))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) { Secondary("clear_cancel", rs(R.string.cancel)) { onEvent(XrEvent.ClearCancel) } }
                    Box(modifier = Modifier.weight(1f)) { Primary("clear_yes", rs(R.string.clear_yes)) { onEvent(XrEvent.ClearConfirm) } }
                }
            }
        } else {
            Secondary("clear", rs(R.string.clear)) { onEvent(XrEvent.ClearAsk) }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = rs(R.string.about), color = ink, style = XrType.label, maxLines = 1)
        Text(text = rs(R.string.about_text, BuildConfig.VERSION_NAME), color = grey, style = XrType.label, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.probe("about"))
    }
}
