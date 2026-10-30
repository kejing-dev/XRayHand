package io.embodit.xrayhand.ui.hand.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import io.embodit.xrayhand.ui.hand.Popup
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.debug.LocalProbePanel
import io.embodit.xrayhand.ui.hand.debug.Probe
import io.embodit.xrayhand.ui.hand.scene.PanelIds
import io.embodit.xrayhand.ui.hand.scene.XrScene

/**
 * The Stage content: one full-size SpatialView with the bones (pinch targets) and the two pooled panels. Tap and
 * drag recognizers live in separate pointerInput blocks (Spatial Ruler). Window model: one main panel + the settings
 * popup (design `container.windowModel`); bone labels are textured plates in the scene.
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
            AttachmentPanel(PanelIds.MAIN) {
                PoolPanel(PanelIds.MAIN, scene) { inView -> MainPanel(state = if (inView) state else null, catalog = catalog, onEvent = onEvent) }
            }
            AttachmentPanel(PanelIds.SETTINGS) {
                PoolPanel(PanelIds.SETTINGS, scene) { inView -> SettingsPanel(state = if (inView && state.popup == Popup.SETTINGS) state else null, onEvent = onEvent) }
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
