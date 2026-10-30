package io.embodit.xrayhand.domain

import org.json.JSONObject

/** The 26 tracked joints, named as the SDK's `HandJoint.Index` (OpenXR order). */
enum class Joint {
    PALM, WRIST,
    THUMB_METACARPAL, THUMB_PROXIMAL, THUMB_DISTAL, THUMB_TIP,
    INDEX_METACARPAL, INDEX_PROXIMAL, INDEX_INTERMEDIATE, INDEX_DISTAL, INDEX_TIP,
    MIDDLE_METACARPAL, MIDDLE_PROXIMAL, MIDDLE_INTERMEDIATE, MIDDLE_DISTAL, MIDDLE_TIP,
    RING_METACARPAL, RING_PROXIMAL, RING_INTERMEDIATE, RING_DISTAL, RING_TIP,
    LITTLE_METACARPAL, LITTLE_PROXIMAL, LITTLE_INTERMEDIATE, LITTLE_DISTAL, LITTLE_TIP,
}

/** One hand's joint positions (Stage metres, or rig metres for poses), with how far it pinches (0..1). */
data class HandJoints(val right: Boolean, val at: Map<Joint, Vec3>, val pinch: Float = 0f) {
    operator fun get(j: Joint): Vec3? = at[j]

    fun mapped(f: (Vec3) -> Vec3): HandJoints = copy(at = at.mapValues { f(it.value) })
}

/**
 * One bone of `hand_rig.json` (built by the design station from NIH 3D 3DPX-016815): long bones run from
 * joint [from] to joint [to] and are [l0] long in the model; every bone has its rest pose in the hand root
 * frame (origin at MIDDLE_METACARPAL, +Y along the 3rd metacarpal, +Z palmar, +X = Y x Z), [basis] holding the
 * bone's local X / Y / Z axes as columns. Right hand; the left hand is its mirror in X.
 */
data class RigBone(val id: String, val long: Boolean, val l0: Float, val from: Joint?, val to: Joint?, val restOrigin: Vec3, val basis: Mat3)

/** 3 x 3 rotation; [c0], [c1], [c2] are its columns. */
data class Mat3(val c0: Vec3, val c1: Vec3, val c2: Vec3) {
    operator fun times(v: Vec3): Vec3 = c0 * v.x + c1 * v.y + c2 * v.z

    operator fun times(m: Mat3): Mat3 = Mat3(this * m.c0, this * m.c1, this * m.c2)

    fun transposed(): Mat3 = Mat3(Vec3(c0.x, c1.x, c2.x), Vec3(c0.y, c1.y, c2.y), Vec3(c0.z, c1.z, c2.z))

    fun quat(): Quat4 = Quat4.fromBasis(c0, c1, c2)

    /** The mirror image in X of this rotation (M_x R M_x): still a proper rotation. */
    fun mirroredX(): Mat3 = Mat3(Vec3(c0.x, -c0.y, -c0.z), Vec3(-c1.x, c1.y, c1.z), Vec3(-c2.x, c2.y, c2.z))

    companion object {
        val IDENTITY = Mat3(Vec3.RIGHT, Vec3.UP, Vec3(0f, 0f, 1f))

        fun axisAngle(axis: Vec3, rad: Float): Mat3 {
            val q = Quat4.axisAngle(axis.normalized(), rad)
            return Mat3(q.rotate(Vec3.RIGHT), q.rotate(Vec3.UP), q.rotate(Vec3(0f, 0f, 1f)))
        }
    }
}

class HandRig(val bones: Map<String, RigBone>, val modelHandLengthM: Float) {
    /**
     * Rest positions of the joints in the root frame of the right hand. Where one long bone ends and the next starts,
     * the CT leaves the joint space (2 - 6 mm of cartilage) between them: the joint is the middle of that gap, which is
     * what hand tracking reports as the joint centre.
     */
    val restJoints: Map<Joint, Vec3> by lazy {
        val ends = HashMap<Joint, Vec3>()
        val starts = HashMap<Joint, Vec3>()
        for (b in bones.values) if (b.long) {
            ends[b.to!!] = b.restOrigin + b.basis.c1 * b.l0
            starts[b.from!!] = b.restOrigin
        }
        val m = HashMap<Joint, Vec3>()
        for (j in ends.keys + starts.keys) {
            val e = ends[j]
            val st = starts[j]
            m[j] = if (e != null && st != null) (e + st) * 0.5f else (e ?: st)!!
        }
        val carpals = bones.values.filter { !it.long }
        val c = carpals.fold(Vec3.ZERO) { a, b -> a + b.restOrigin } / carpals.size.toFloat()
        m[Joint.WRIST] = c + (c - (m[Joint.MIDDLE_METACARPAL] ?: Vec3.ZERO)) * 0.6f
        m[Joint.PALM] = ((m[Joint.MIDDLE_METACARPAL] ?: Vec3.ZERO) + (m[Joint.MIDDLE_PROXIMAL] ?: Vec3.ZERO)) * 0.5f
        m
    }

    /**
     * The model hand's length between the joints the solver measures a tracked hand by (MIDDLE_METACARPAL to
     * MIDDLE_PROXIMAL, joint centres): the hand size of 1.0. [modelHandLengthM] (the 3rd metacarpal's own length) is
     * 4 - 5 % shorter because it stops at the bone.
     */
    val handLengthM: Float by lazy { restJoints.getValue(Joint.MIDDLE_PROXIMAL).distanceTo(restJoints.getValue(Joint.MIDDLE_METACARPAL)) }

    /** Centre of the eight carpals in the root frame (the carpal zoom spreads from here). */
    val carpalCenter: Vec3 by lazy {
        val carpals = bones.values.filter { !it.long }
        carpals.fold(Vec3.ZERO) { a, b -> a + b.restOrigin } / carpals.size.toFloat()
    }

    companion object {
        fun parse(json: String): HandRig {
            val root = JSONObject(json)
            val bones = root.getJSONObject("bones")
            val map = LinkedHashMap<String, RigBone>()
            for (id in bones.keys()) {
                val o = bones.getJSONObject(id)
                val long = o.getString("kind") == "long"
                val joints = o.optJSONArray("joints")
                val org = o.getJSONArray("restOrigin")
                val b = o.getJSONArray("restBasis")
                // rows of the JSON matrix; its columns are the bone's local axes (build_nih_hand.py)
                fun row(i: Int) = b.getJSONArray(i)
                fun col(j: Int) = Vec3(row(0).getDouble(j).toFloat(), row(1).getDouble(j).toFloat(), row(2).getDouble(j).toFloat())
                map[id] =
                    RigBone(
                        id = id,
                        long = long,
                        l0 = o.optDouble("L0", 0.0).toFloat(),
                        from = joints?.let { Joint.valueOf(it.getString(0)) },
                        to = joints?.let { Joint.valueOf(it.getString(1)) },
                        restOrigin = Vec3(org.getDouble(0).toFloat(), org.getDouble(1).toFloat(), org.getDouble(2).toFloat()),
                        basis = Mat3(col(0), col(1), col(2)),
                    )
            }
            return HandRig(map, root.getDouble("modelHandLengthM").toFloat())
        }
    }
}
