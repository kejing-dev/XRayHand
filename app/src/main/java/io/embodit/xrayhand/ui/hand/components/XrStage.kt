package io.embodit.xrayhand.ui.hand.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.xrayhand.R
import io.embodit.xrayhand.ui.hand.debug.probe
import io.embodit.xrayhand.ui.theme.XrType
import io.embodit.xrayhand.ui.window.WindowCtl
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.debug.LocalProbePanel
import io.embodit.xrayhand.ui.hand.debug.Probe
import io.embodit.xrayhand.ui.hand.scene.PanelIds
import io.embodit.xrayhand.ui.hand.scene.XrScene

/**
 * The Stage content: one full-size SpatialView with the bones (pinch targets) and the one pooled chip ("打开面板" /
 * "平视前方"). Tap and drag recognizers live in separate pointerInput blocks (Spatial Ruler); the drag moves only the
 * demo hand (D21 allows the 3D object's own drag), the panel is the system window (v0.1.4). Bone labels are textured
 * plates in the scene.
 */
@Composable
internal fun XrStage(state: XrUiState, scene: XrScene, catalog: BoneCatalog, onEvent: (XrEvent) -> Unit) {
    val context = LocalContext.current
    val bindTick = scene.bindTick.intValue
    SpatialView(
        modifier =
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectSpatialTapGesture(context = context, targetedToEntity = TargetEntity.any()) { value -> scene.onSpatialTap(value) }
                }
                .pointerInput(Unit) {
                    detectSpatialDragGesture(
                        context = context,
                        targetedToEntity = TargetEntity.any(),
                        onDragStart = { start, value -> scene.onSpatialDrag("start", start, value) },
                        onDragEnd = { value -> scene.onSpatialDrag("end", null, value) },
                        onDragCancel = { value -> scene.onSpatialDrag("cancel", null, value) },
                    ) { value -> scene.onSpatialDrag("move", null, value) }
                },
        attachments = {
            AttachmentPanel(PanelIds.OPEN) {
                PoolPanel(PanelIds.OPEN, scene) { inView -> if (inView) OpenChip() }
            }
        },
        update = { _, attachments ->
            // reading the tick makes this block run again while panel entities are still missing
            @Suppress("UNUSED_EXPRESSION") bindTick
            scene.bindPanels(attachments)
        },
        initial = { content, attachments -> scene.attach(content, attachments) },
    )
}

/**
 * The Stage's one chip: "平视前方 / 面板就出来" while the window waits for a level head (D9), else "打开面板" while the
 * window is closed (system x) or minimised. The scene shows it only then, upright and yaw only.
 */
@Composable
private fun OpenChip() {
    Box(
        modifier = Modifier.size(PanelSizes.CHIP_W.dp, PanelSizes.CHIP_H.dp).clip(RoundedCornerShape(28.dp)).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh()).padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (WindowCtl.levelHint) {
            Text(text = rs(R.string.level_hint), color = PicoTheme.colorScheme.labelPrimary, style = XrType.big, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.probe("level_hint"), onTextLayout = fits("chip/level_hint"))
        } else {
            Primary("open_panel", rs(R.string.open_panel)) { WindowCtl.reopen("chip") }
        }
    }
}

/** Root of a pooled panel: [content] gets `inView` (a pooled panel draws only once it is in view, Spatial Ruler). */
@Composable
private fun PoolPanel(id: String, scene: XrScene, content: @Composable (inView: Boolean) -> Unit) {
    val inView = scene.panelShown[id] == true
    val logged = Probe.enabled
    val watcher =
        Modifier.pointerInput(id, logged) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move) continue
                    if (event.type == PointerEventType.Press) scene.onPanelPointer()
                    if (logged) {
                        val p = event.changes.firstOrNull()?.position
                        XrLog.i(String.format(java.util.Locale.US, "PTR panel=%s ev=%s x=%.1f y=%.1f", id, event.type, p?.x ?: 0f, p?.y ?: 0f))
                    }
                }
            }
        }
    CompositionLocalProvider(LocalProbePanel provides id) {
        Box(modifier = watcher) { content(inView) }
    }
}
