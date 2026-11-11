package io.embodit.xrayhand.domain

import kotlin.math.asin
import kotlin.math.atan2

/**
 * D9 (headset feedback v0.1.3: "UI 菜单为什么一打开就是斜着向下的"): every panel / plate the app puts down from the head
 * goes through [place], which keeps only the head's position and yaw. Pitch and roll of the head never reach a panel,
 * and a panel never pitches towards the viewer: its up is the world up.
 *
 * Root cause of the v0.1.3 tilt: the main panel was a Stage panel put 15 degrees below the horizon and then turned
 * towards the head in yaw *and* pitch (`FacePanel`, clamped to 60 degrees), so it leaned back and read as "slanted
 * down". v0.1.4 moves the panel into the system window (opened only while the head is level, [LevelGate]) and makes
 * `FacePanel` yaw-only for what stays on the Stage (the "open panel" chip).
 */
object Upright {
    /** Where the system opens the main window: on the line of sight, this far (factory native-window-move §4). */
    const val WINDOW_M = 1.75f

    /** The window as the system draws it at [WINDOW_M]: metres per dp (sign-hands v0.1.5 emulator fit). */
    const val WINDOW_DP_M = 0.00173f

    /** The chip / level hint: below the line of sight and this far (D9: 10 - 20 degrees down, 0.8 - 1.2 m). */
    const val CHIP_DOWN_DEG = 15f
    const val CHIP_M = 0.9f

    data class Placed(val position: Vec3, val facing: PanelFacing)

    /**
     * [leftDeg] to the left of the head's yaw, [downDeg] below the horizon, [dist] metres from the head; turned about
     * the vertical only, front towards the head.
     */
    fun place(h: HeadPose, leftDeg: Float, downDeg: Float, dist: Float): Placed {
        val p = PanelRest.at(h, leftDeg, -downDeg, dist)
        val yaw = atan2(h.position.x - p.x, h.position.z - p.z)
        return Placed(p, PanelFacing(yaw, 0f))
    }

    /** The chip and the "平视前方" hint. */
    fun chip(h: HeadPose): Placed = place(h, 0f, CHIP_DOWN_DEG, CHIP_M)

    /**
     * Where the system put the main window: it was opened while the head was level, [WINDOW_M] ahead on the line of
     * sight, so it is estimated from the head's position and yaw at that moment (a later system drag is not seen).
     */
    fun window(h: HeadPose): Placed = place(h, 0f, 0f, WINDOW_M)

    /**
     * Head pitch in degrees (+ up), or null while the head pose is not valid: the first frames report the Stage origin
     * and an identity rotation before tracking is up (sign-hands v0.1.2), which would read as "level".
     */
    fun pitchDeg(h: HeadPose?): Float? {
        if (h == null || !valid(h)) return null
        val f = h.forward
        return Math.toDegrees(asin(f.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    }

    fun valid(h: HeadPose): Boolean = h.position.isFinite() && h.position.length >= 1e-3f
}
