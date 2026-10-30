package io.embodit.xrayhand.domain

import kotlin.math.PI

/** The demo hand's four poses (design `panels.demoControls`) and the poses the debug hooks inject. */
enum class PoseName(val key: String) {
    OPEN("open"),
    FIST("fist"),
    PINCH("pinch"),
    FLIP("flip");

    companion object {
        fun of(key: String): PoseName? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Joint angles of a pose, degrees, relative to the CT hand (which rests with its middle finger joints bent about 35): per finger (index, middle, ring, little) the flexion at the knuckle, the middle
 * and the end joint; the thumb's swing across the palm ([thumbOpp], about the hand's long axis), its flexion at the
 * base and the end joint; and [flip], the whole hand turned about its long axis (180 = back of the hand to you).
 * Poses blend by blending these numbers, so a turn never collapses through the hand (design `entities3d.demoHand`).
 */
data class PoseParams(val fingers: List<Triple<Float, Float, Float>>, val thumbOpp: Float, val thumbFlex1: Float, val thumbFlex2: Float, val flip: Float) {
    fun lerp(o: PoseParams, t: Float): PoseParams {
        fun l(a: Float, b: Float) = a + (b - a) * t
        return PoseParams(
            fingers.indices.map { i -> Triple(l(fingers[i].first, o.fingers[i].first), l(fingers[i].second, o.fingers[i].second), l(fingers[i].third, o.fingers[i].third)) },
            l(thumbOpp, o.thumbOpp),
            l(thumbFlex1, o.thumbFlex1),
            l(thumbFlex2, o.thumbFlex2),
            l(flip, o.flip),
        )
    }
}

/**
 * Builds joint sets from the rig's rest joints (the CT hand itself, open): every finger chain is bent about the hand's
 * lateral axis, the thumb is swung about the hand's long axis through its base and bent; nothing is read from a
 * recording yet (design `preconditions.deviceSpike` step 4 records real poses for v0.2).
 */
class DemoPoses(private val rig: HandRig) {
    private val rest = rig.restJoints
    private val fingers =
        listOf(
            listOf(Joint.INDEX_PROXIMAL, Joint.INDEX_INTERMEDIATE, Joint.INDEX_DISTAL, Joint.INDEX_TIP),
            listOf(Joint.MIDDLE_PROXIMAL, Joint.MIDDLE_INTERMEDIATE, Joint.MIDDLE_DISTAL, Joint.MIDDLE_TIP),
            listOf(Joint.RING_PROXIMAL, Joint.RING_INTERMEDIATE, Joint.RING_DISTAL, Joint.RING_TIP),
            listOf(Joint.LITTLE_PROXIMAL, Joint.LITTLE_INTERMEDIATE, Joint.LITTLE_DISTAL, Joint.LITTLE_TIP),
        )
    private val thumb = listOf(Joint.THUMB_METACARPAL, Joint.THUMB_PROXIMAL, Joint.THUMB_DISTAL, Joint.THUMB_TIP)

    /** The thumb swing that brings the thumb tip closest to the bent little fingertip (opposition), found once. */
    private val pinchThumb: Triple<Float, Float, Float> by lazy {
        val base = params(PoseName.PINCH, thumb = Triple(0f, 0f, 0f))
        var best = Triple(0f, 0f, 0f)
        var bestD = Float.MAX_VALUE
        var opp = -90f
        while (opp <= 90f) {
            var f1 = 0f
            while (f1 <= 90f) {
                var f2 = 0f
                while (f2 <= 80f) {
                    val j = joints(base.copy(thumbOpp = opp, thumbFlex1 = f1, thumbFlex2 = f2), right = true)
                    val d = (j.at.getValue(Joint.THUMB_TIP) - j.at.getValue(Joint.LITTLE_TIP)).length
                    if (d < bestD) {
                        bestD = d
                        best = Triple(opp, f1, f2)
                    }
                    f2 += 10f
                }
                f1 += 10f
            }
            opp += 5f
        }
        best
    }

    /** Thumb swing for the fist: tucked across the bent fingers (the side of the swing that moves it palmar). */
    private val fistThumb: Triple<Float, Float, Float> by lazy { Triple(pinchThumb.first * 0.55f, 35f, 45f) }

    fun params(name: PoseName, thumb: Triple<Float, Float, Float>? = null): PoseParams =
        when (name) {
            PoseName.OPEN -> PoseParams(List(4) { Triple(0f, -30f, 0f) }, 0f, 0f, 0f, 0f)
            PoseName.FLIP -> PoseParams(List(4) { Triple(0f, -30f, 0f) }, 0f, 0f, 0f, 180f)
            PoseName.FIST -> {
                val t = thumb ?: fistThumb
                PoseParams(List(4) { Triple(85f, 65f, 60f) }, t.first, t.second, t.third, 0f)
            }
            PoseName.PINCH -> {
                val t = thumb ?: pinchThumb
                PoseParams(listOf(Triple(5f, -25f, 0f), Triple(8f, -20f, 3f), Triple(30f, 10f, 15f), Triple(60f, 35f, 30f)), t.first, t.second, t.third, 0f)
            }
        }

    /** Joint positions in the hand root frame (metres, model hand size) for [p]; the left hand is the mirror in X. */
    fun joints(p: PoseParams, right: Boolean): HandJoints {
        val m = HashMap(rest)
        val lateral = Vec3.RIGHT
        for ((i, chain) in fingers.withIndex()) {
            val (a1, a2, a3) = p.fingers[i]
            bend(m, chain, lateral, floatArrayOf(a1, a2, a3))
        }
        // thumb: swing the whole chain about the hand's long axis through its base, then bend its two outer joints
        val base = m.getValue(Joint.THUMB_METACARPAL)
        val swing = Mat3.axisAngle(Vec3.UP, rad(p.thumbOpp))
        for (j in thumb.drop(1)) m[j] = base + swing * (m.getValue(j) - base)
        val thumbLateral = swing * Vec3.RIGHT
        bend(m, thumb.drop(1), thumbLateral, floatArrayOf(p.thumbFlex1, p.thumbFlex2))
        if (p.flip != 0f) {
            val turn = Mat3.axisAngle(Vec3.UP, rad(p.flip))
            for (k in m.keys.toList()) m[k] = turn * m.getValue(k)
        }
        val out = if (right) m else m.mapValues { (_, v) -> Vec3(-v.x, v.y, v.z) }
        return HandJoints(right, out)
    }

    fun joints(name: PoseName, right: Boolean): HandJoints = joints(params(name), right)

    private fun bend(m: MutableMap<Joint, Vec3>, chain: List<Joint>, axis: Vec3, angles: FloatArray) {
        val pts = chain.map { m.getValue(it) }
        var acc = 0f
        var at = pts[0]
        for (k in 1 until pts.size) {
            acc += angles.getOrElse(k - 1) { 0f }
            val seg = pts[k] - pts[k - 1]
            at = at + Mat3.axisAngle(axis, rad(acc)) * seg
            m[chain[k]] = at
        }
    }

    private fun rad(d: Float): Float = (d * PI / 180.0).toFloat()
}

/** A hand placed in the room: rig-frame joints carried to [origin] with [rotation] at [scale]. */
fun HandJoints.placed(origin: Vec3, rotation: Quat4, scale: Float = 1f): HandJoints = mapped { origin + rotation.rotate(it * scale) }
