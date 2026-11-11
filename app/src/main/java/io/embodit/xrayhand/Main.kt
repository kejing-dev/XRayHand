package io.embodit.xrayhand

import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.xrayhand.ui.hand.XrScreen
import io.embodit.xrayhand.ui.hand.components.PanelSizes
import io.embodit.xrayhand.ui.theme.XrayTheme
import io.embodit.xrayhand.ui.window.PANEL_ID
import io.embodit.xrayhand.ui.window.XrWindow

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full Space with
 * passthrough holds the bones, because hand tracking data only exists in Full Space (design `container`). The real
 * hands are hidden from the rendering (`pico.spatial.stage.upperlimb` = 2) so the bones draw over the passthrough hands.
 * v0.1.4 (profile D8 / D21, factory native-window-move): all 2D content is one system Planar window opened from the
 * Stage; the system moves / closes / minimises it, its pages are switched by the SpatialUI TabBar.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            XrayTheme {
                XrScreen()
            }
        }
        WindowContainer(
            PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = PanelSizes.WINDOW_W.dp, height = PanelSizes.WINDOW_H.dp),
        ) { XrWindow() }
    }
