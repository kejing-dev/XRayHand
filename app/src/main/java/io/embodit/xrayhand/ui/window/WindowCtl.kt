package io.embodit.xrayhand.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.xrayhand.domain.LevelGate
import io.embodit.xrayhand.platform.XrLog
import java.util.Locale

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "XrayWindow"
const val PANEL_TAG = "main"

/**
 * v0.1.4: the main window's state shared by the Stage and the window (one process, factory native-window-move §3.4;
 * ported from sign-hands v0.1.5): the window reports its lifecycle, the Stage opens / recalls it with its navigator.
 * Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    /** Bumped on every ON_CREATE: the scene re-estimates where the system put the window. */
    var createdEpoch by mutableIntStateOf(0)
        private set

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var askedAtMs = 0L

    /**
     * D9 (§7.9): the system opens the window square to the line of sight, head pitch included, and nothing can re-level
     * it afterwards; so every open (first, chip, recall) waits here for a level head. The Stage steps it each frame
     * ([stepGate]) and shows "平视前方" while [levelHint] is on.
     */
    private val gate = LevelGate()
    private var lastStepMs = 0L

    /** The Stage's "平视前方 / Look straight ahead" hint: an open waits and the head is not level. */
    var levelHint by mutableStateOf(false)
        private set

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    /** An open was sent and the window has not reported ON_CREATE yet (the system takes ~1-6 s). */
    private var opening = false

    /** Set once the window logged its dp size (the converter is handed out again on every recomposition). */
    var dpLogged = false

    /**
     * The Stage shows "打开面板" while the window is closed / minimised, or never came up within 8 s of asking; not in
     * the second between an open and the window's ON_CREATE (the chip flashed there).
     */
    fun chipWanted(now: Long): Boolean =
        if (gate.waiting) false
        else when (panelState) {
            "ON_DESTROY", "ON_STOP" -> !recallPending && !(opening && now - askedAtMs < 8000)
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    /** Ask for the window: it opens once the head is level (or at once when the Stage sends no frames, [watchdog]). */
    fun openWindow(why: String) {
        val now = SystemClock.elapsedRealtime()
        XrLog.i("WINDOW ask why=$why state=$panelState (opens when the head is level)")
        gate.ask(why, now)
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** One Stage frame: [pitchDeg] = head pitch (+ up), null while the head pose is not valid yet. */
    fun stepGate(now: Long, pitchDeg: Float?) {
        lastStepMs = now
        if (!gate.waiting) {
            if (levelHint) levelHint = false
            return
        }
        val hint = gate.hintWanted(pitchDeg)
        if (hint != levelHint) {
            levelHint = hint
            XrLog.i(String.format(Locale.US, "LEVEL hint=%s pitch=%.1f waited=%d ms", hint, pitchDeg ?: Float.NaN, gate.waitedMs(now)))
        }
        val why = gate.step(now, pitchDeg) ?: return
        levelHint = false
        XrLog.i(String.format(Locale.US, "LEVEL ok pitch=%.1f -> open", pitchDeg ?: Float.NaN))
        openNow(why)
    }

    /**
     * No Stage frames for a while: do not hold the window back. Before the first frame that is [FIRST_FRAME_MS] after
     * the ask (headset cold start: the Stage's frames start ~6 s after launch); after it, [WATCHDOG_MS] without one.
     */
    private val watchdog =
        object : Runnable {
            override fun run() {
                if (!gate.waiting) return
                val now = SystemClock.elapsedRealtime()
                val stalled = if (lastStepMs == 0L) gate.waitedMs(now) > FIRST_FRAME_MS else now - lastStepMs > WATCHDOG_MS
                if (stalled) {
                    val why = gate.pending ?: return
                    gate.cancel()
                    levelHint = false
                    openNow("$why/noframes")
                } else main.postDelayed(this, WATCHDOG_MS)
            }
        }

    private fun openNow(why: String) {
        val nav = navigator ?: return XrLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        opening = true
        XrLog.i("WINDOW open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { XrLog.w("WINDOW open failed: $it") }
    }

    /** The chip: a minimised window is restored where it was, a closed one opens in front of you. */
    fun reopen(why: String) {
        if (panelState == "ON_STOP" && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
            XrLog.i("WINDOW restore why=$why")
            return
        }
        openWindow(why)
    }

    /**
     * "叫回面前": the system has no call to move a window, and a second open of an open window only focuses it, so a
     * recall closes it and opens it again once it reported ON_DESTROY (an open right after close is swallowed, §3.5).
     */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        XrLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { XrLog.w("WINDOW close failed: $it"); recallPending = false }
    }

    fun onLifecycle(event: String) {
        panelState = event
        XrLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") {
            createdEpoch++
            opening = false
        }
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            // the chip must not flash "打开面板" in the moment before the reopen asks (xray-hand v0.1.4 emulator log)
            opening = true
            askedAtMs = SystemClock.elapsedRealtime()
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage in the Shared Space, §7.7). */
    fun closeForQuit() {
        recallPending = false
        gate.cancel()
        levelHint = false
        main.removeCallbacks(watchdog)
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }

    private const val WATCHDOG_MS = 3000L
    private const val FIRST_FRAME_MS = 15000L
}
