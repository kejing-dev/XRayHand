package io.embodit.xrayhand

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.xrayhand.ui.hand.XrScreen
import io.embodit.xrayhand.ui.theme.XrayTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full
 * Space with passthrough holds the whole product. There is no WindowContainer and no Shared Space
 * version: hand tracking data only exists in Full Space (design `container`). The real hands are hidden from the
 * rendering (`pico.spatial.stage.upperlimb` = 2) so the bones draw over the passthrough hands.
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            XrayTheme {
                XrScreen()
            }
        }
    }
