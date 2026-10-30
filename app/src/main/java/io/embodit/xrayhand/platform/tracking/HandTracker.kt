package io.embodit.xrayhand.platform.tracking

import android.os.Build
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.xrayhand.domain.HandJoints
import io.embodit.xrayhand.domain.Joint
import io.embodit.xrayhand.domain.Vec3
import io.embodit.xrayhand.platform.XrLog

/** True on the PICO emulator (Spatial Ruler `SystemPointer.onEmulator`). */
object Device {
    val onEmulator: Boolean = Build.HARDWARE == "ranchu" || Build.DEVICE.contains("emulator") || Build.FINGERPRINT.contains("emulator")
}

/**
 * The real hands: `HandTrackingProvider.latestData`, read once per frame by the ECS system on the main thread
 * (never through Compose state, design `container.notes`). 26 joints per hand in Stage metres, and the pinch value
 * (for the self-pinch guard).
 *
 * Not started on the emulator unless asked for (`handSource live`): after the provider ran there, the emulator's
 * gaze + pinch stopped reaching any app until it was restarted (Spatial Ruler, 2026-10-02).
 */
class HandTracker {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    /** Debug: report this support state instead of the provider's (`support unsupported`). */
    @Volatile var forcedSupport: String? = null

    val running: Boolean
        get() = provider != null

    fun start() {
        if (provider != null) return
        val p = HandTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        XrLog.i("TRACK hand start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { runCatching { it.stop() } }
        if (provider != null) XrLog.i("TRACK hand stop")
        provider = null
    }

    /** The support state's name (`SUPPORTED`, `NOT_IN_FULL_SPACE`, ...), or null while not started. */
    fun support(): String? {
        forcedSupport?.let { return it }
        val p = provider ?: return null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != lastSupport) {
            lastSupport = s
            XrLog.i("TRACK hand support=$s")
        }
        return s?.name
    }

    /** Both hands this frame (null where a hand is not tracked). */
    fun sample(): Pair<HandJoints?, HandJoints?> {
        val p = provider ?: return null to null
        if (support() != DataProvider.SupportState.SUPPORTED.name) return null to null
        val data = runCatching { p.latestData }.getOrNull() ?: return null to null
        return hand(data.left, data.leftPinch, false) to hand(data.right, data.rightPinch, true)
    }

    private fun hand(pose: HandPose?, pinch: HandPinch?, right: Boolean): HandJoints? {
        val joints = pose?.handJoints ?: return null
        if (joints.size < 26) return null
        val map = HashMap<Joint, Vec3>(32)
        for (j in joints) {
            val v = Vec3(j.position.x, j.position.y, j.position.z)
            if (!v.isFinite()) return null
            map[JOINTS[j.index.ordinal]] = v
        }
        // the provider hands out a pose at the origin before it has seen a hand
        val mid = map[Joint.MIDDLE_METACARPAL] ?: return null
        if (mid.length < PLACEHOLDER_M) return null
        val strength = if (pinch != null && pinch.isReady) pinch.pinchValue else 0f
        return HandJoints(right, map, strength)
    }

    private companion object {
        const val PLACEHOLDER_M = 0.001f

        /** SDK index -> our joint, by name (both follow the OpenXR order). */
        val JOINTS: Array<Joint> = HandJoint.Index.entries.map { Joint.valueOf(it.name) }.toTypedArray()
    }
}
