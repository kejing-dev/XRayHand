package io.embodit.xrayhand.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Says a bone's name with the system's text-to-speech engine, offline (design `offline.tts`). Whether a Chinese
 * and an English voice exist is logged once (`TTS zh= en=`, spike log 3); without one the name is only shown.
 * Every call is logged as `SPEAK <id> <lang>` (the emulator check of T6).
 */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile var zhOk = false
        private set
    @Volatile var enOk = false
        private set

    init {
        tts =
            runCatching {
                TextToSpeech(context.applicationContext) { status ->
                    ready = status == TextToSpeech.SUCCESS
                    val t = tts
                    if (ready && t != null) {
                        zhOk = runCatching { t.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
                        enOk = runCatching { t.isLanguageAvailable(Locale.US) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
                    }
                    XrLog.i("TTS init status=$status zh=$zhOk en=$enOk engine=${runCatching { t?.defaultEngine }.getOrNull()}")
                }
            }.onFailure { XrLog.w("TTS unavailable: $it") }.getOrNull()
    }

    /** Says [zh] and / or [en] (both: Chinese first); returns false when nothing could be said. */
    fun say(id: String, zh: String?, en: String?): Boolean {
        XrLog.i("SPEAK $id ${listOfNotNull(zh?.let { "zh" }, en?.let { "en" }).joinToString("+")} ready=$ready zh=$zhOk en=$enOk")
        val t = tts ?: return false
        if (!ready) return false
        var said = false
        var mode = TextToSpeech.QUEUE_FLUSH
        if (zh != null && zhOk) {
            t.language = Locale.SIMPLIFIED_CHINESE
            t.speak(zh, mode, null, "$id-zh")
            mode = TextToSpeech.QUEUE_ADD
            said = true
        }
        if (en != null && enOk) {
            // a second utterance in another language: the engine switches per call
            if (said) t.playSilentUtterance(150, TextToSpeech.QUEUE_ADD, "$id-gap")
            t.language = Locale.US
            t.speak(en, mode, null, "$id-en")
            said = true
        }
        return said
    }

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
