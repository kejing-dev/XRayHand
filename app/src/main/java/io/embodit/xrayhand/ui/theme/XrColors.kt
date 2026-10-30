package io.embodit.xrayhand.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme

/**
 * The design's exact colours (design-package `palette`). This is the only file with colour literals; the four
 * semantic roles are injected into `PicoTheme` by [XrayTheme], the brand tokens are used directly (3D materials, labels).
 */
object XrColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFF4FB8D9) // design-style: fixed-figma-color interaction: x-ray cyan action
    val Passable = Color(0xFF5FD3A4) // design-style: fixed-figma-color passable: right answer
    val Alert = Color(0xFFE9B44C) // design-style: fixed-figma-color alert: amber
    val Error = Color(0xFFFF6B5E) // design-style: fixed-figma-color error: wrong answer

    // ---- brand tokens outside ColorScheme
    val BoneIvory = Color(0xFFEAE2CF) // design-style: fixed-figma-color boneIvory
    val BoneBase = Color(0xFFD6C4A1) // design-style: fixed-figma-color bone base colour (visual.boneLook.ivory)
    val XrayBase = Color(0xFF1C2A33) // design-style: fixed-figma-color x-ray deep base
    val XrayCyan = Color(0xFF73D9FF) // design-style: fixed-figma-color x-ray glow (visual.boneLook.xray)
    val CarpalAmber = Color(0xFFE9B44C) // design-style: fixed-figma-color carpalAmber
    val MetacarpalTeal = Color(0xFF6FC3B2) // design-style: fixed-figma-color metacarpalTeal
    val PhalanxBlue = Color(0xFF8FA8FF) // design-style: fixed-figma-color phalanxBlue
    val LightboxSlate = Color(0xFF3A4350) // design-style: fixed-figma-color lightboxSlate: the app icon
    val PlateInk = Color(0xF016181C) // design-style: fixed-figma-color plateInk: 3D label plates
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color labelPrimaryLight
    val OnInkSecondary = Color(0xB8FFFFFF) // design-style: fixed-figma-color onInkSecondary white 72 %
    val OnAccent = Color(0xFF0E1A20) // design-style: fixed-figma-color ink on the cyan / green / amber fills

    fun group(g: io.embodit.xrayhand.domain.Group): Color =
        when (g) {
            io.embodit.xrayhand.domain.Group.CARPAL -> CarpalAmber
            io.embodit.xrayhand.domain.Group.METACARPAL -> MetacarpalTeal
            io.embodit.xrayhand.domain.Group.PHALANX -> PhalanxBlue
        }
}

/** Type roles of the design (`typography.roles`): tabular figures wherever a number is shown. */
object XrType {
    /** 22/28: the picked bone's name, the quiz question, the score (design `typography.roles.titleLarge`). */
    val big: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val score: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val instruction: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)

    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val hint: TextStyle
        @Composable get() = PicoTheme.typography.bodyLargeMultiline.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

    val status: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    val segment: TextStyle
        @Composable get() = PicoTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
}
