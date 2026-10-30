package io.embodit.xrayhand.ui.hand.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.xrayhand.R
import io.embodit.xrayhand.domain.Bone
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.ui.hand.scene.SceneStrings
import java.util.Locale

/**
 * Copy for the language chosen in the app (中文 / English / 中英), switched at run time without restarting the
 * Stage: the screen provides a Resources object for that locale and every panel reads its strings from it
 * (Spatial Ruler). 中英 shows the interface in Chinese and the bone names in both (design `i18n.note`).
 */
val LocalXrResources = staticCompositionLocalOf<Resources> { error("LocalXrResources not provided") }

val LocalLang = staticCompositionLocalOf { Lang.ZH }

fun localizedResources(context: Context, lang: Lang): Resources {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(if (lang == Lang.EN) "en" else "zh-CN"))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalXrResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalXrResources.current.getString(id, *args)

/** A bone's name in the chosen language(s): first line and an optional second line. */
@Composable
@ReadOnlyComposable
fun boneName(b: Bone): Pair<String, String?> =
    when (LocalLang.current) {
        Lang.ZH -> b.zh to null
        Lang.EN -> b.en to null
        Lang.BOTH -> b.zh to b.en
    }

/** The one-line form ("月骨 Lunate" in 中英). */
@Composable
@ReadOnlyComposable
fun boneNameLine(b: Bone): String = boneName(b).let { (a, c) -> if (c == null) a else "$a $c" }

@Composable
@ReadOnlyComposable
fun boneFact(b: Bone): String = if (LocalLang.current == Lang.EN) b.factEn else b.factZh

@Composable
@ReadOnlyComposable
fun groupName(g: Group): String =
    rs(
        when (g) {
            Group.CARPAL -> R.string.group_carpal_short
            Group.METACARPAL -> R.string.group_metacarpal_short
            Group.PHALANX -> R.string.group_phalanx_short
        }
    )

fun sceneStrings(r: Resources): SceneStrings =
    SceneStrings(r.getString(R.string.label_proximal), r.getString(R.string.label_middle), r.getString(R.string.label_distal), r.getString(R.string.label_thumb_two))

/** Panel sizes in dp (design `panels`: 400 dp wide main panel at 0.8 m, 1 dp = 0.8 mm). */
object PanelSizes {
    const val MAIN_W = 440f
    const val MAIN_H = 476f
    const val SETTINGS_W = 380f
    const val SETTINGS_H = 380f
    const val DISABLED_ALPHA = 0.4f
}
