package io.embodit.xrayhand.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the demo hand may stand (taste profile D1 "move to the marker", D14 clearance, D20 content never between the
 * eyes and the panel). Nothing here follows the head: the hand goes to a marker the user put on the floor with the
 * controller ray or gaze + pinch, and stays there for the whole session. Before there is a marker it waits beside
 * and beyond the main panel.
 */
object Placement {
    /** Hand centre above a floor marker: about chest height for a standing user, eye height for a seated one. */
    const val HAND_ABOVE_FLOOR_M = 1.05f

    /** The parking spot is this much farther than the panel and this far (degrees) to the panel's other side. */
    const val PARK_BEYOND_M = 0.5f
    const val PARK_OFF_DEG = 55f

    /** Opacity of content that sits between the eyes and the panel (D20: at most 30 %). */
    const val FADED = 0.3f

    /** Minimum horizontal distance of the demo hand from the head (D14). */
    const val CLEAR_M = 0.6f

    fun handOverMarker(marker: Vec3): Vec3 = Vec3(marker.x, marker.y + HAND_ABOVE_FLOOR_M, marker.z)

    /**
     * Parking spot before the first marker, from where the panel stands as seen from [viewer]: the panel's bearing turned
     * [PARK_OFF_DEG] towards the other side ([panelLeft] = the panel is on the viewer's left), [PARK_BEYOND_M] farther
     * than the panel, at the panel's height. So it is both >= 30 degrees off the panel and >= 0.5 m beyond it.
     */
    fun park(viewer: Vec3, panel: Vec3, panelLeft: Boolean = true): Vec3 {
        val flat = P2.of(panel - viewer)
        val dist = flat.length.coerceAtLeast(0.3f) + PARK_BEYOND_M
        val dir = flat.normalized().turned(if (panelLeft) PARK_OFF_DEG else -PARK_OFF_DEG)
        return Vec3(viewer.x + dir.x * dist, panel.y, viewer.z + dir.z * dist)
    }

    /** Pushes [c] out to [CLEAR_M] horizontally from [head] (D14), keeping its height. */
    fun keepClear(c: Vec3, head: Vec3, fallbackDir: P2): Vec3 {
        val flat = P2.of(c - head)
        if (flat.length >= CLEAR_M) return c
        val dir = if (flat.length < 1e-3f) fallbackDir.normalized() else flat.normalized()
        return Vec3(head.x + dir.x * CLEAR_M, c.y, head.z + dir.z * CLEAR_M)
    }

    /**
     * True when a sphere ([p], [radius]) sits between [eye] and the panel rectangle (centre [center], unit [right] /
     * [up] in its plane, half sizes [halfW] / [halfH]): the line from the eye through the sphere meets the panel's
     * plane behind the sphere, inside the rectangle grown by the sphere's projected radius. Content behind the panel
     * (farther than it) never blocks it.
     */
    fun blocksPanel(eye: Vec3, p: Vec3, radius: Float, center: Vec3, right: Vec3, up: Vec3, halfW: Float, halfH: Float): Boolean {
        val n = right.cross(up).normalized()
        val toP = p - eye
        val denom = toP.dot(n)
        if (kotlin.math.abs(denom) < 1e-6f) return false
        val t = (center - eye).dot(n) / denom
        // t <= 1: the panel plane is nearer than the content (content behind the panel) or behind the eye
        if (t <= 1f) return false
        val q = eye + toP * t - center
        val grow = radius * t
        return kotlin.math.abs(q.dot(right)) <= halfW + grow && kotlin.math.abs(q.dot(up)) <= halfH + grow
    }

    /** Yaw (radians) that turns a palm (+Z) towards [viewer] from [center], about the vertical only. */
    fun palmYaw(center: Vec3, viewer: Vec3): Float = kotlin.math.atan2(viewer.x - center.x, viewer.z - center.z)

    /** The hand root rotation for a yaw: fingers up, palm (+Z) along (sin yaw, 0, cos yaw), thumb side X = Y x Z. */
    fun palmRotation(yaw: Float): Quat4 {
        val z = Vec3(sin(yaw), 0f, cos(yaw))
        return Quat4.fromBasis(Vec3.UP.cross(z).normalized(), Vec3.UP, z)
    }
}
