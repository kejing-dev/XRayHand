package io.embodit.xrayhand.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Pose of the head in Stage metres. */
data class HeadPose(val position: Vec3, val rotation: Quat4) {
    val forward: Vec3
        get() = rotation.rotate(Vec3.AHEAD)

    val up: Vec3
        get() = rotation.rotate(Vec3.UP)

    val right: Vec3
        get() = rotation.rotate(Vec3.RIGHT)

    /** Unit vector of the view direction projected on the floor; [fallback] when looking straight down / up. */
    fun level(fallback: P2 = P2(0f, -1f)): P2 {
        val f = forward
        val l = hypot(f.x, f.z)
        return if (l < 0.05f) fallback else P2(f.x / l, f.z / l)
    }
}

/** How a room panel is turned: yaw about the vertical and pitch towards the viewer, radians; no roll. */
data class PanelFacing(val yaw: Float, val pitch: Float) {
    val rotation: Quat4
        get() = Quat4.axisAngle(Vec3.UP, yaw) * Quat4.axisAngle(Vec3.RIGHT, -pitch)

    /** Unit vector out of the panel's face, towards the viewer. */
    val normal: Vec3
        get() = Vec3(sin(yaw) * cos(pitch), sin(pitch), cos(yaw) * cos(pitch))

    val right: Vec3
        get() = Vec3(cos(yaw), 0f, -sin(yaw))

    val up: Vec3
        get() = normal.cross(right)
}

/**
 * Facing rule for panels that stand in the room: turn about the vertical towards the head, never pitch, never roll
 * (D9, v0.1.4: the v0.1.3 pitch towards the head made the main panel lean back, "斜着向下"). Straight above or below,
 * the last yaw is kept.
 */
object FacePanel {
    private const val KEEP_YAW_WITHIN_M = 0.1f

    fun facing(panel: Vec3, head: Vec3, lastYaw: Float?): PanelFacing {
        val to = head - panel
        val flat = hypot(to.x, to.z)
        val yaw = if (flat < KEEP_YAW_WITHIN_M && lastYaw != null) lastYaw else if (flat < 1e-4f) 0f else atan2(to.x, to.z)
        return PanelFacing(yaw, 0f)
    }
}

/** A point or direction on the floor plane (Stage x, z). */
data class P2(val x: Float, val z: Float) {
    operator fun plus(o: P2) = P2(x + o.x, z + o.z)

    operator fun minus(o: P2) = P2(x - o.x, z - o.z)

    operator fun times(s: Float) = P2(x * s, z * s)

    operator fun unaryMinus() = P2(-x, -z)

    fun dot(o: P2): Float = x * o.x + z * o.z

    val length: Float
        get() = hypot(x, z)

    fun normalized(): P2 {
        val l = length
        return if (l < 1e-9f) this else P2(x / l, z / l)
    }

    fun at(y: Float): Vec3 = Vec3(x, y, z)

    /** The direction turned by [deg] degrees clockwise seen from above (right of the viewer is +). */
    fun turned(deg: Float): P2 {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        // right-hand of a direction d is (-d.z, d.x) in Stage coordinates (y up, -z ahead)
        val right = P2(-z, x)
        return P2(x * c + right.x * s, z * c + right.z * s)
    }

    /** Right-hand unit vector of this direction, seen from above. */
    val right: P2
        get() = P2(-z, x)

    fun isFinite(): Boolean = x.isFinite() && z.isFinite()

    companion object {
        val ZERO = P2(0f, 0f)

        fun of(v: Vec3) = P2(v.x, v.z)
    }
}

/** Wraps an angle in degrees into (-180, 180]. */
fun wrapDeg(a: Float): Float {
    var v = a
    while (v > 180f) v -= 360f
    while (v <= -180f) v += 360f
    return v
}

/**
 * Where a panel rests (taste profile D9 / D10): from the head's current position and yaw (pitch and roll ignored),
 * [leftDeg] to the left, [upDeg] above the horizon (negative = below), [dist] metres away.
 */
object PanelRest {
    fun at(h: HeadPose, leftDeg: Float, upDeg: Float, dist: Float): Vec3 {
        val f = h.level()
        val l = Math.toRadians(leftDeg.toDouble()).toFloat()
        val u = Math.toRadians(upDeg.coerceIn(-80f, 80f).toDouble()).toFloat()
        val left = P2(f.z, -f.x)
        val d = (f * cos(l) + left * sin(l)).normalized()
        return h.position + d.at(0f) * (dist * cos(u)) + Vec3(0f, dist * sin(u), 0f)
    }
}
