package io.embodit.xrayhand.domain

import kotlin.math.acos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D1 / D14 / D20: marker anchor, parking spot beside and beyond the panel, the eye-to-panel cone. */
class PlacementTest {
    private val eye = Vec3(0f, 1.6f, 0f)

    // the main panel's rest pose: 24 degrees left, 15 down, 0.8 m (XrScene)
    private val panel = PanelRest.at(HeadPose(eye, Quat4.IDENTITY), 24f, -15f, 0.8f)
    private val facing = FacePanel.facing(panel, eye, null)

    private fun blocks(p: Vec3, r: Float = 0.1f) = Placement.blocksPanel(eye, p, r, panel, facing.right, facing.up, 0.176f, 0.16f)

    @Test
    fun parkIsBeyondAndOffThePanel() {
        val park = Placement.park(eye, panel)
        val dPanel = P2.of(panel - eye).length
        val dPark = P2.of(park - eye).length
        assertTrue("0.5 m beyond", dPark >= dPanel + 0.5f - 1e-4f)
        val cos = P2.of(panel - eye).normalized().dot(P2.of(park - eye).normalized())
        val deg = Math.toDegrees(acos(cos.toDouble())).toFloat()
        assertTrue("off by $deg", deg >= 30f)
        assertFalse("park does not block", blocks(park, 0.12f))
    }

    @Test
    fun contentBetweenEyeAndPanelBlocks() {
        val mid = eye + (panel - eye) * 0.5f
        assertTrue(blocks(mid))
        // the same line beyond the panel: behind it, never in the way
        assertFalse(blocks(eye + (panel - eye) * 1.8f))
        // straight ahead (24 degrees from a 440 dp panel's centre) at 0.6 m: clear
        assertFalse(blocks(Vec3(0f, 1.4f, -0.6f), 0.08f))
    }

    @Test
    fun markerAnchorAndClearance() {
        val m = Vec3(0.4f, 0f, -1.2f)
        val c = Placement.handOverMarker(m)
        assertEquals(1.05f, c.y, 1e-5f)
        assertEquals(m.x, c.x, 1e-6f)
        val near = Placement.keepClear(Vec3(0.1f, 1.0f, -0.2f), eye, P2(0f, -1f))
        assertEquals(0.6f, P2.of(near - eye).length, 1e-4f)
        assertEquals(1.0f, near.y, 1e-6f)
    }

    @Test
    fun palmRotationFacesTheViewer() {
        val c = Vec3(1f, 1f, -1f)
        val q = Placement.palmRotation(Placement.palmYaw(c, eye))
        val palm = q.rotate(Vec3(0f, 0f, 1f))
        val toEye = Vec3(eye.x - c.x, 0f, eye.z - c.z).normalized()
        assertTrue(palm.dot(toEye) > 0.999f)
        assertTrue(q.rotate(Vec3.UP).y > 0.999f)
    }
}
