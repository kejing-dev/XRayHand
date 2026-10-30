package io.embodit.xrayhand.domain

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rig, the solver, the demo poses and the quiz, on the real assets (design `acceptance.mustPass`). */
class HandDomainTest {
    private fun asset(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name"))
        return candidates.first { it.exists() }.readText()
    }

    private val catalog = BoneCatalog.parse(asset("bones.json"))
    private val rig = HandRig.parse(asset("hand_rig.json"))
    private val solver = BoneSolver(rig, catalog)
    private val poses = DemoPoses(rig)

    @Test
    fun catalogHas27BonesInThreeGroups() {
        assertEquals(27, catalog.bones.size)
        assertEquals(8, catalog.of(Group.CARPAL).size)
        assertEquals(5, catalog.of(Group.METACARPAL).size)
        assertEquals(14, catalog.of(Group.PHALANX).size)
        assertEquals(27, rig.bones.size)
        for (b in catalog.bones) assertNotNull("rig has ${b.id}", rig.bones[b.id])
    }

    /** Every long bone spans its two joints to within 2 mm, for every pose, both hands, placed anywhere. */
    @Test
    fun longBonesSpanTheirJoints() {
        val turn = Quat4.axisAngle(Vec3(0.3f, 1f, 0.2f).normalized(), 1.1f)
        for (p in PoseName.entries) for (right in listOf(true, false)) {
            val j = poses.joints(p, right).placed(Vec3(0.2f, 1.3f, -0.4f), turn, 1.15f)
            val out = solver.solve(j)
            assertNotNull("$p right=$right", out)
            for (bp in out!!) {
                val rb = rig.bones.getValue(bp.id)
                if (!rb.long) continue
                val axis = bp.rotation.rotate(Vec3.UP)
                val a = bp.position - axis * (bp.length / 2f)
                val b = bp.position + axis * (bp.length / 2f)
                assertTrue("${bp.id} start $p", a.distanceTo(j[rb.from!!]!!) < 0.002f)
                assertTrue("${bp.id} end $p", b.distanceTo(j[rb.to!!]!!) < 0.002f)
            }
        }
    }

    /** At rest the solver puts every bone back where the CT had it (identity frame). */
    @Test
    fun restPoseReproducesTheRig() {
        val j = HandJoints(true, rig.restJoints)
        val out = solver.solve(j)!!
        for (bp in out) {
            val rb = rig.bones.getValue(bp.id)
            if (rb.long) continue
            assertTrue("${bp.id} at rest", bp.position.distanceTo(rb.restOrigin) < 0.0005f)
        }
        assertEquals(1f, solver.frame(j)!!.scale, 1e-4f)
    }

    /** Pisiform and the hamate's hook sit on the palm side (+Z of the hand frame) for both hands. */
    @Test
    fun pisiformIsPalmarOnBothHands() {
        for (right in listOf(true, false)) {
            val j = poses.joints(PoseName.OPEN, right).placed(Vec3.ZERO, Quat4.axisAngle(Vec3.UP, 0.7f))
            val f = solver.frame(j)!!
            val pis = solver.solve(j)!!.first { it.id == "pisiform" }
            assertTrue("right=$right", (pis.position - f.origin).dot(f.axes.c2) > 0.005f)
        }
    }

    /** The left hand is the mirror of the right: same distances, mirrored side. */
    @Test
    fun leftIsMirrorOfRight() {
        val r = solver.solve(poses.joints(PoseName.FIST, true))!!.associateBy { it.id }
        val l = solver.solve(poses.joints(PoseName.FIST, false))!!.associateBy { it.id }
        for ((id, rp) in r) {
            val lp = l.getValue(id)
            assertEquals(id, -rp.position.x, lp.position.x, 1e-4f)
            assertEquals(id, rp.position.y, lp.position.y, 1e-4f)
            assertEquals(id, rp.position.z, lp.position.z, 1e-4f)
        }
    }

    @Test
    fun fistCurlsFingersAndPinchMeetsLittleFinger() {
        val open = poses.joints(PoseName.OPEN, true)
        val fist = poses.joints(PoseName.FIST, true)
        val palm = rig.restJoints.getValue(Joint.PALM)
        assertTrue(fist[Joint.MIDDLE_TIP]!!.distanceTo(palm) < open[Joint.MIDDLE_TIP]!!.distanceTo(palm) * 0.6f)
        val pinch = poses.joints(PoseName.PINCH, true)
        assertTrue("thumb meets little finger", pinch[Joint.THUMB_TIP]!!.distanceTo(pinch[Joint.LITTLE_TIP]!!) < 0.03f)
        // the fist's fingertips go to the palm side
        assertTrue(fist[Joint.MIDDLE_TIP]!!.z > 0f)
        // open is nearly flat: the fingertip is not more than 2 cm in front of the palm plane
        assertTrue(open[Joint.MIDDLE_TIP]!!.z < 0.02f)
    }

    @Test
    fun flipTurnsThePalmAway() {
        val flip = poses.joints(PoseName.FLIP, true)
        val f = solver.frame(flip)!!
        assertTrue(f.axes.c2.z < -0.9f)
    }

    @Test
    fun quizHasTheDesignMix() {
        for (seed in 1L..40L) {
            val q = QuizMaker.make(catalog, seed)
            assertEquals(10, q.size)
            assertEquals(10, q.map { it.boneId }.toSet().size)
            assertEquals(6, q.count { it.kind == QuestionKind.LOCATE })
            assertEquals(4, q.count { it.kind == QuestionKind.IDENTIFY })
            val groups = q.groupingBy { catalog[it.boneId]!!.group }.eachCount()
            assertEquals(4, groups[Group.CARPAL])
            assertEquals(3, groups[Group.METACARPAL])
            assertEquals(3, groups[Group.PHALANX])
            for (x in q.filter { it.kind == QuestionKind.IDENTIFY }) {
                assertEquals(3, x.options.toSet().size)
                assertTrue(x.boneId in x.options)
                assertTrue(x.options.all { catalog[it]!!.group == catalog[x.boneId]!!.group })
            }
        }
        assertEquals(QuizMaker.make(catalog, 7L), QuizMaker.make(catalog, 7L))
    }

    @Test
    fun billboardFacesViewerWithoutMirror() {
        val q = Billboard.yawTowards(Vec3(0f, 1f, -1f), Vec3(0f, 1.6f, 0f))
        val front = q.rotate(Vec3(0f, 0f, 1f))
        val right = q.rotate(Vec3.RIGHT)
        assertTrue(front.z > 0.99f)
        assertTrue(right.x > 0.99f)
        assertEquals(0f, front.y, 1e-5f)
    }
}
