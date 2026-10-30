package io.embodit.xrayhand.platform

import android.content.Context
import io.embodit.xrayhand.domain.HandRig
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.platform.tracking.ControllerStick
import io.embodit.xrayhand.platform.tracking.HandTracker
import io.embodit.xrayhand.platform.tracking.HeadTracker
import java.util.Locale

enum class Lang(val key: String) {
    ZH("zh"),
    EN("en"),
    BOTH("both");

    companion object {
        fun of(key: String?): Lang? = entries.firstOrNull { it.key == key }

        /** First start: Chinese for a Chinese system, English otherwise (design `i18n.setting`). */
        fun system(): Lang = if (Locale.getDefault().language == "zh") ZH else EN
    }
}

enum class Look(val key: String) {
    IVORY("ivory"),
    XRAY("xray");

    companion object {
        fun of(key: String?): Look = entries.firstOrNull { it.key == key } ?: IVORY
    }
}

/** What is kept between runs (design `dataModel.prefs`). */
data class Prefs(val lang: Lang, val speech: Boolean, val look: Look, val bestScore: Int?, val history: List<Int>, val welcomed: Boolean)

/** SharedPreferences-backed store: a handful of values, written on change. */
class PrefsStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("xray_hand", Context.MODE_PRIVATE)

    fun load(): Prefs =
        Prefs(
            lang = Lang.of(sp.getString("lang", null)) ?: Lang.system(),
            speech = sp.getBoolean("speech", true),
            look = Look.of(sp.getString("look", null)),
            bestScore = sp.getInt("best", -1).takeIf { it >= 0 },
            history = sp.getString("history", "")!!.split(',').mapNotNull { it.toIntOrNull() },
            welcomed = sp.getBoolean("welcomed", false),
        )

    fun save(p: Prefs) {
        sp.edit()
            .putString("lang", p.lang.key)
            .putBoolean("speech", p.speech)
            .putString("look", p.look.key)
            .putInt("best", p.bestScore ?: -1)
            .putString("history", p.history.takeLast(5).joinToString(","))
            .putBoolean("welcomed", p.welcomed)
            .apply()
    }

    fun clearWelcome() {
        sp.edit().putBoolean("welcomed", false).apply()
    }
}

/** Process-wide services; the scene starts and stops the trackers (every start has its stop). */
object XrGraph {
    val head = HeadTracker()
    val stick = ControllerStick()
    val hands = HandTracker()

    @Volatile private var catalog: BoneCatalog? = null
    @Volatile private var rig: HandRig? = null
    @Volatile private var prefs: PrefsStore? = null
    @Volatile private var speaker: Speaker? = null

    fun catalog(context: Context): BoneCatalog =
        catalog ?: synchronized(this) { catalog ?: BoneCatalog.parse(asset(context, "bones.json")).also { catalog = it } }

    fun rig(context: Context): HandRig = rig ?: synchronized(this) { rig ?: HandRig.parse(asset(context, "hand_rig.json")).also { rig = it } }

    fun prefs(context: Context): PrefsStore = prefs ?: synchronized(this) { prefs ?: PrefsStore(context).also { prefs = it } }

    fun speaker(context: Context): Speaker = speaker ?: synchronized(this) { speaker ?: Speaker(context).also { speaker = it } }

    fun shutdownSpeaker() {
        speaker?.shutdown()
        speaker = null
    }

    private fun asset(context: Context, name: String): String = context.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
}
