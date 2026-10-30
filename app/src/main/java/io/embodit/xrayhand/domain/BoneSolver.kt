package io.embodit.xrayhand.domain

/**
 * Where one bone's node goes this frame. The node is never scaled (its collider and label stay true, design
 * `container.notes`): it sits at the bone's centre, [rotation] holds the bone's axes (+Y along the bone, +Z palmar).
 * The mesh child sits at [meshOffset] in the node frame with [meshScale]; [length] is the joint distance of a long
 * bone (0 for a carpal), [radius] the collider's radius, metres.
 */
data class BonePose(val id: String, val position: Vec3, val rotation: Quat4, val meshOffset: Vec3, val meshScale: Vec3, val length: Float, val radius: Float)

/** The hand's own frame this frame: origin, axes (columns) and size relative to the model hand. */
data class HandFrame(val origin: Vec3, val axes: Mat3, val scale: Float)

/**
 * Puts the 27 NIH bones on a set of tracked joints (design `bones.asset.runtimeAlign`):
 * - the hand frame is built from the joints the same way for the rig's rest joints and for the tracked ones, and the
 *   rig is carried over by the difference, so a tilt in how the frame is defined cancels out;
 * - long bones span their two joints exactly (scaled along the bone to the joint distance, across by hand size);
 * - carpals keep their CT rest pose in the hand frame, scaled by hand size; [carpalZoom] spreads them 1.8x from
 *   their centre and draws them 1.5x (design `spatialLayout.carpalZoom`).
 * The left hand is the mirror of the right in the rig's X.
 */
class BoneSolver(private val rig: HandRig, private val catalog: BoneCatalog) {
    private val restRight = rig.restJoints
    private val restLeft = restRight.mapValues { (_, v) -> Vec3(-v.x, v.y, v.z) }
    private val rigFrameRight = frameOf(restRight, right = true)!!
    private val rigFrameLeft = frameOf(restLeft, right = false)!!

    /** The hand frame of [joints] in the rig's convention, or null when a needed joint is missing / degenerate. */
    fun frame(joints: HandJoints): HandFrame? {
        val f = frameOf(joints.at, joints.right) ?: return null
        val rigF = if (joints.right) rigFrameRight else rigFrameLeft
        // rotation that takes rig-root axes onto world axes
        val r = f.axes * rigF.axes.transposed()
        val o = joints[Joint.MIDDLE_METACARPAL] ?: return null
        val p = joints[Joint.MIDDLE_PROXIMAL] ?: return null
        val s = (p - o).length / rig.handLengthM
        if (!s.isFinite() || s < 0.3f || s > 3f) return null
        return HandFrame(o, r, s)
    }

    fun solve(joints: HandJoints, carpalZoom: Boolean = false, tipInsetM: Float = 0f): List<BonePose>? {
        val frame = frame(joints) ?: return null
        val out = ArrayList<BonePose>(27)
        val zRoot = frame.axes.c2
        val sign = if (joints.right) 1f else -1f
        val center = rig.carpalCenter.let { Vec3(it.x * sign, it.y, it.z) }
        for (bone in catalog.bones) {
            val rb = rig.bones[bone.id] ?: continue
            if (rb.long) {
                val a = joints[rb.from!!] ?: return null
                var b = joints[rb.to!!] ?: return null
                if (tipInsetM > 0f && rb.to.name.endsWith("_TIP")) b = b - (b - a).normalized() * tipInsetM
                val axis = b - a
                val len = axis.length
                if (len < 1e-4f) return null
                val y = axis / len
                var z = zRoot.rejectFrom(y)
                if (z.length < 1e-4f) z = frame.axes.c0.cross(y)
                z = z.normalized()
                val x = y.cross(z).normalized()
                val rot = Quat4.fromBasis(x, y, z)
                val r = maxOf(bone.halfWidth * frame.scale * 1.2f, MIN_LONG_RADIUS_M)
                out += BonePose(bone.id, a + axis * 0.5f, rot, Vec3(0f, -len / 2f, 0f), Vec3(frame.scale, len / rb.l0, frame.scale), len, r)
            } else {
                var origin = Vec3(rb.restOrigin.x * sign, rb.restOrigin.y, rb.restOrigin.z)
                val basis = if (joints.right) rb.basis else rb.basis.mirroredX()
                var k = frame.scale
                if (carpalZoom) {
                    origin = center + (origin - center) * ZOOM_SPREAD
                    k *= ZOOM_SIZE
                }
                val pos = frame.origin + frame.axes * (origin * frame.scale)
                val rot = (frame.axes * basis).quat()
                out += BonePose(bone.id, pos, rot, Vec3.ZERO, Vec3(k, k, k), 0f, bone.radius * k * 0.85f)
            }
        }
        return out
    }

    companion object {
        /** Long-bone collider radius floor (design `entities3d.bone.collider`: max(width x 0.6, 7 mm)). */
        const val MIN_LONG_RADIUS_M = 0.007f
        const val ZOOM_SPREAD = 1.8f
        const val ZOOM_SIZE = 1.5f

        /**
         * Frame from joints: Y along the middle metacarpal, Z = h x (across x Y) where across runs from the little
         * to the index metacarpal base (h = +1 right, -1 left: palmar for both), X = Y x Z.
         */
        fun frameOf(j: Map<Joint, Vec3>, right: Boolean): HandFrame? {
            val o = j[Joint.MIDDLE_METACARPAL] ?: return null
            val p = j[Joint.MIDDLE_PROXIMAL] ?: return null
            val idx = j[Joint.INDEX_METACARPAL] ?: return null
            val lit = j[Joint.LITTLE_METACARPAL] ?: return null
            val yv = p - o
            if (yv.length < 1e-4f) return null
            val y = yv.normalized()
            val across = (idx - lit).rejectFrom(y)
            if (across.length < 1e-4f) return null
            val z = across.normalized().cross(y).normalized() * (if (right) 1f else -1f)
            val x = y.cross(z).normalized()
            return HandFrame(o, Mat3(x, y, z), 1f)
        }
    }
}
