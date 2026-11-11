package io.embodit.xrayhand.domain

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D9 (headset v0.1.3 "UI 菜单为什么一打开就是斜着向下的"): whatever the head does, a panel put down from it stands upright
 * (its up is the world up within 1 degree), 10 - 20 degrees below the line of sight, 0.8 - 1.2 m away. Three head
 * poses: down 30 degrees, up 30 degrees, rolled 20 degrees; each also turned 40 degrees in yaw so yaw is kept.
 */
class UprightTest {
    private val eye = Vec3(0.3f, 1.62f, -0.2f)
    private val yaw = Quat4.axisAngle(Vec3.UP, rad(40f))

    private fun rad(d: Float) = Math.toRadians(d.toDouble()).toFloat()

    private fun deg(a: Vec3, b: Vec3) = Math.toDegrees(acos(a.normalized().dot(b.normalized()).coerceIn(-1f, 1f).toDouble())).toFloat()

    private val heads =
        mapOf(
            "down30" to HeadPose(eye, yaw * Quat4.axisAngle(Vec3.RIGHT, rad(-30f))),
            "up30" to HeadPose(eye, yaw * Quat4.axisAngle(Vec3.RIGHT, rad(30f))),
            "roll20" to HeadPose(eye, yaw * Quat4.axisAngle(Vec3.AHEAD, rad(20f))),
            "level" to HeadPose(eye, yaw),
        )

    @Test
    fun chipStandsUprightForEveryHeadPose() {
        val level = Upright.chip(heads.getValue("level"))
        for ((name, h) in heads) {
            val p = Upright.chip(h)
            assertTrue("$name up off by ${deg(p.facing.up, Vec3.UP)}", deg(p.facing.up, Vec3.UP) < 1f)
            assertEquals("$name no pitch", 0f, p.facing.pitch, 1e-6f)
            val to = p.position - h.position
            val flat = hypot(to.x, to.z)
            val below = Math.toDegrees(atan2(-to.y, flat).toDouble()).toFloat()
            assertTrue("$name below sight $below", below in 10f..20f)
            assertTrue("$name dist ${to.length}", to.length in 0.8f..1.2f)
            // the same spot as with a level head: pitch / roll of the head change nothing
            assertTrue("$name moved ${p.position.distanceTo(level.position)}", p.position.distanceTo(level.position) < 1e-4f)
            // the front faces the head horizontally
            assertTrue("$name faces head", deg(Vec3(p.facing.normal.x, 0f, p.facing.normal.z), Vec3(-to.x, 0f, -to.z)) < 1f)
        }
    }

    @Test
    fun windowEstimateKeepsOnlyYaw() {
        val w = Upright.window(heads.getValue("down30"))
        assertEquals(eye.y, w.position.y, 1e-4f)
        assertEquals(Upright.WINDOW_M, w.position.distanceTo(eye), 1e-3f)
        assertTrue(deg(w.facing.up, Vec3.UP) < 1f)
    }

    @Test
    fun parkedDemoHandStaysOffTheWindow() {
        // the window was opened from the level head; the hand parks from that head and must not cover the window (D20)
        val opened = heads.getValue("level")
        val w = Upright.window(opened)
        val halfW = 520f * Upright.WINDOW_DP_M / 2f
        val halfH = (500f + 96f + 80f) * Upright.WINDOW_DP_M / 2f // window + TabBar above + system controls below
        val c = Placement.parkBeside(opened)
        for ((name, h) in heads) {
            assertTrue("$name blocks", !Placement.blocksPanel(h.position, c, 0.11f, w.position, w.facing.right, w.facing.up, halfW, halfH))
        }
        assertTrue("clear of the head ${P2.of(c - eye).length}", P2.of(c - eye).length >= Placement.CLEAR_M)
    }

    @Test
    fun roomPanelsNeverPitchTowardsTheHead() {
        // a panel far below the eye used to pitch up towards it (v0.1.3, clamped to 60 degrees)
        val f = FacePanel.facing(Vec3(0f, 0.9f, -0.8f), eye, null)
        assertEquals(0f, f.pitch, 0f)
        assertTrue(deg(f.up, Vec3.UP) < 1e-3f)
    }

    @Test
    fun pitchReadsTheHeadAndRejectsTheUntrackedFrame() {
        assertEquals(-30f, Upright.pitchDeg(heads.getValue("down30"))!!, 0.01f)
        assertEquals(30f, Upright.pitchDeg(heads.getValue("up30"))!!, 0.01f)
        assertEquals(0f, Upright.pitchDeg(heads.getValue("roll20"))!!, 0.01f)
        assertNull("origin + identity before tracking", Upright.pitchDeg(HeadPose(Vec3.ZERO, Quat4.IDENTITY)))
        assertNull(Upright.pitchDeg(null))
    }

    @Test
    fun windowWaitsForALevelHead() {
        for (name in listOf("down30", "up30")) {
            val g = LevelGate()
            g.ask("stage", 0)
            val p = Upright.pitchDeg(heads.getValue(name))
            var t = 0L
            while (t < 3000) {
                assertNull("$name must not open", g.step(t, p))
                t += 16
            }
            assertTrue("$name hint", g.hintWanted(p))
        }
        val g = LevelGate()
        g.ask("stage", 0)
        var opened: String? = null
        var t = 0L
        while (t < 1000 && opened == null) {
            opened = g.step(t, Upright.pitchDeg(heads.getValue("roll20")))
            t += 16
        }
        assertEquals("rolled but level opens", "stage", opened)
    }
}
