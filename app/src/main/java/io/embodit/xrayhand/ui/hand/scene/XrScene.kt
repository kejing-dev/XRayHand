package io.embodit.xrayhand.ui.hand.scene

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import com.pico.spatial.ui.foundation.gesture.SpatialDragValue
import com.pico.spatial.ui.foundation.gesture.SpatialTapValue
import com.pico.spatial.ui.geometry.Offset3D
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.domain.BonePose
import io.embodit.xrayhand.domain.BoneSolver
import io.embodit.xrayhand.domain.DemoPoses
import io.embodit.xrayhand.domain.FacePanel
import io.embodit.xrayhand.domain.Group
import io.embodit.xrayhand.domain.HandJoints
import io.embodit.xrayhand.domain.HandRig
import io.embodit.xrayhand.domain.HeadPose
import io.embodit.xrayhand.domain.Joint
import io.embodit.xrayhand.domain.P2
import io.embodit.xrayhand.domain.PanelRest
import io.embodit.xrayhand.domain.Placement
import io.embodit.xrayhand.domain.PoseName
import io.embodit.xrayhand.domain.PoseParams
import io.embodit.xrayhand.domain.QuestionKind
import io.embodit.xrayhand.domain.Quat4
import io.embodit.xrayhand.domain.Vec3
import io.embodit.xrayhand.domain.placed
import io.embodit.xrayhand.platform.Lang
import io.embodit.xrayhand.platform.Look
import io.embodit.xrayhand.platform.XrGraph
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.platform.tracking.Device
import io.embodit.xrayhand.ui.hand.HandId
import io.embodit.xrayhand.ui.hand.Mode
import io.embodit.xrayhand.ui.hand.Popup
import io.embodit.xrayhand.ui.hand.QuizPhase
import io.embodit.xrayhand.ui.hand.Source
import io.embodit.xrayhand.ui.hand.XrEvent
import io.embodit.xrayhand.ui.hand.XrUiState
import io.embodit.xrayhand.ui.hand.components.PanelSizes
import io.embodit.xrayhand.ui.theme.XrColors
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.tan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.json.JSONObject

/**
 * The demo hand's anchor for the whole app session (profile D1: "anchor fixed"): the user's floor marker, the hand
 * centre and turn over it. Process-wide so that leaving and re-entering the Stage or the background keeps it.
 */
internal object SessionAnchor {
    var marker: Vec3? = null
    var center: Vec3? = null
    var rotation: Quat4 = Quat4.IDENTITY
    var floorY: Float? = null
    var appliedTick = 0
}

/** The framework instantiates the system itself, so the running scene is reached through this holder. */
internal object ActiveScene {
    @Volatile var scene: XrScene? = null
}

/** The one per-frame ECS system of the app (design `container.notes`: BoneRigSystem). */
class XrFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        ActiveScene.scene?.onFrame(context)
    }
}

/** Ids of the attachment panels; the Compose side declares exactly these, once. */
object PanelIds {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    val pool: List<String> = listOf(MAIN, SETTINGS)
}

/** The few words the scene draws itself (phalanx group labels), in both languages. */
data class SceneStrings(val proximal: String, val middle: String, val distal: String, val thumbTwo: String)

/**
 * The 3D side: the bones on the two real hands and on the demo hand, their looks and labels, the pinch targets, the
 * demo hand's placing / turning / dragging, and the two panels.
 *
 * Rules (Spatial Ruler / Light Planner): one calibrated stage root; Compose state comes in through [render]
 * (idempotent); per-frame work is in [onFrame] and never goes through Compose (joints are read from the provider
 * there and written straight into the bone transforms), except [panelShown]; everything started in [attach] is
 * stopped in [detach]. Main thread only.
 */
class XrScene(private val context: Context, private val sink: (XrEvent) -> Unit) {

    val panelShown = mutableStateMapOf<String, Boolean>()
    val bindTick = mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private val head = XrGraph.head
    private val stick = XrGraph.stick
    private val hands = XrGraph.hands
    private val catalog: BoneCatalog = XrGraph.catalog(context)
    private val rig: HandRig = XrGraph.rig(context)
    private val solver = BoneSolver(rig, catalog)
    private val demoPoses = DemoPoses(rig)

    private var content: SpatialViewContent? = null
    private var contentProbe: Entity? = null
    private var stageRoot: Entity? = null
    private var panelRig: PanelRig? = null
    private var shared: SharedResources? = null
    private var mats: BoneMaterials? = null
    private var kit: KitMeshes? = null
    private val work = WorkQueue()
    private var scope: CoroutineScope? = null
    private var buildJob: Job? = null
    private var attached = false
    private var built = false
    private var paused = false
    private var ui = XrUiState()
    private var stringsZh: SceneStrings? = null
    private var stringsEn: SceneStrings? = null
    private var pose = HeadPose(Vec3.ZERO, Quat4.IDENTITY)
    private var hasHead = false
    private var frames = 0L
    private var lastFrameMs = -1L
    private var frameIntervalMs = 16L
    private var lastLogMs = 0L
    private var lastPanelPressMs = -1L

    // hands
    private var left: HandView? = null
    private var right: HandView? = null
    private var demo: HandView? = null
    private val lastJoints = HashMap<HandId, HandJoints>()
    private val lastPoses = HashMap<HandId, List<BonePose>>()

    /** Debug: injected world joints (`pose <l>/<r>`), and a pinch value for them (`selfpinch`). */
    private var injectedLeft: HandJoints? = null
    private var injectedRight: HandJoints? = null
    private var injectedPinch = 0f

    /** Debug `handSource live` on the emulator: start the provider anyway. */
    var liveOnEmulator = false

    // tracked-hands debounce (design T14: 1 s)
    private var rawL = false
    private var rawR = false
    private var rawSinceMs = 0L
    private var reportedL: Boolean? = null
    private var reportedR: Boolean? = null
    private var unsupportedSinceMs = -1L
    private var reportedSupport: String? = null
    private var lostCount = 0

    // demo hand placement (design `spatialLayout.demoHand`)
    private var demoCenter: Vec3? = null
    private var demoRot = Quat4.IDENTITY
    private var demoFrom: PoseParams = demoPoses.params(PoseName.OPEN)
    private var demoTo: PoseParams = demoFrom
    private var demoTarget = PoseName.OPEN
    private var demoBlendStartMs = 0L
    private var dragStart: Vec3? = null
    private var dragSum = Vec3.ZERO
    private var dragging = false
    private var demoFaded = false
    private var floorCatcher: Entity? = null
    private var markerRing: Part? = null
    private var markerDot: Part? = null
    private var ghostRing: Part? = null
    private var lastStickLogMs = 0L

    // labels
    private var selLabel: TextPlate? = null
    private var selLabelAt: Vec3? = null
    private val groupLabels = ArrayList<TextPlate>()
    private val leads = ArrayList<Strip>()

    // spike logs (design `preconditions.deviceSpike`)
    private val jitter = HashMap<HandId, ArrayDeque<Pair<Long, Vec3>>>()
    private val palmTrail = HashMap<HandId, ArrayDeque<Pair<Long, Vec3>>>()
    private var lastJitterLogMs = 0L
    private var solveNanos = 0L
    private var solveCount = 0
    private val recorded = HashSet<String>()

    // D21: the main panel is put down once (facing the user) and stays; a drag bar moves it, "call back" re-places it
    private var mainPlaced = false
    private var recallApplied = 0
    private var handle: PanelHandle? = null
    private var panelDrag: Vec3? = null
    private var panelDragSum = Vec3.ZERO
    private var lastPopupShown = false

    // =========================================================================================
    // lifecycle
    // =========================================================================================

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        if (attached || stageRoot != null) detach()
        val t0 = SystemClock.elapsedRealtime()
        this.content = content
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        val probe = Entity().named("contentProbe")
        content.addEntity(probe)
        contentProbe = probe
        // an entity added to the SpatialView is not at the Stage origin (Spatial Ruler): move the root once
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rotation = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply {
            setPosition(offset)
            setQuaternion(rotation)
        }
        stageRoot = root
        XrLog.i("STAGE root offset=${XrLog.pos(offset.domain())}")
        val r = PanelRig(root) { id, shown -> panelShown[id] = shown }
        panelRig = r
        r.world(PanelIds.MAIN, PanelSizes.MAIN_W, PanelSizes.MAIN_H, PANEL_SCALE)
        r.world(PanelIds.SETTINGS, PanelSizes.SETTINGS_W, PanelSizes.SETTINGS_H, PANEL_SCALE)
        head.start()
        stick.start()
        ActiveScene.scene = this
        attached = true
        bindPanels(attachments)
        buildJob =
            scope?.launch {
                try {
                    build(root, t0)
                } catch (t: Throwable) {
                    if (t !is CancellationException) XrLog.e("SCENE build failed: $t", t)
                    throw t
                }
            }
        render(ui)
        XrLog.i("SCENE attached emulator=${Device.onEmulator}")
    }

    /** Materials, two meshes, then the three GLB copies (one per yielded frame), then the label plates. */
    private suspend fun build(root: Entity, t0: Long) {
        delay(300)
        val shared = SharedResources()
        this.shared = shared
        val m = BoneMaterials(shared)
        mats = m
        yield()
        kit = KitMeshes(shared)
        yield()
        // the right GLB is loaded once and cloned for the demo hand (a load costs ~10 s on the idle emulator)
        var rightTemplate: Entity? = null
        val loads = listOf(Triple(HandId.RIGHT, "hand_bones_right.glb", false), Triple(HandId.LEFT, "hand_bones_left.glb", true), Triple(HandId.DEMO, "hand_bones_right.glb", false))
        for ((id, file, mirror) in loads) {
            val t1 = SystemClock.elapsedRealtime()
            val glb =
                if (id == HandId.DEMO && rightTemplate != null) {
                    runCatching { rightTemplate!!.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = false)) }.onFailure { XrLog.e("MODEL clone failed: $it", it) }.getOrNull()
                } else {
                    runCatching { Entity.loadSuspend("asset://$file") }.onFailure { XrLog.e("MODEL $file load failed: $it", it) }.getOrNull()
                } ?: continue
            if (!attached) return
            if (id == HandId.RIGHT) rightTemplate = runCatching { glb.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = false)) }.getOrNull()
            root.addChild(glb)
            val view = HandView(id, root, glb, mirror, catalog)
            view.apply(null)
            when (id) {
                HandId.RIGHT -> right = view
                HandId.LEFT -> left = view
                HandId.DEMO -> demo = view
            }
            XrLog.i("MODEL $file -> $id in ${SystemClock.elapsedRealtime() - t1} ms")
            yield()
            delay(if (frameIntervalMs > SLOW_FRAME_MS) 600L else 50L)
        }
        val k = kit!!
        selLabel = TextPlate(root, k, m, work, "label:selected")
        repeat(GROUP_SLOTS) {
            groupLabels += TextPlate(root, k, m, work, "label:group:$it")
            leads += Strip(root, k.band, m.lead, "lead:$it")
        }
        floorCatcher =
            Entity().named("floor:catcher").also { fc ->
                fc.components.set(boxCollision(Vec3(40f, 0.02f, 40f)))
                fc.components.set(com.pico.spatial.core.ecs.InteractableComponent())
                root.addChild(fc)
                fc.enabled = false
            }
        markerRing = Part(root, k.ring, m.marker, "marker:ring").also { it.show(false) }
        markerDot = Part(root, k.disc, m.marker, "marker:dot").also { it.show(false) }
        ghostRing = Part(root, k.ring, m.ghost, "marker:ghost").also { it.show(false) }
        handle = PanelHandle(root, k.quad, m.handle)
        built = true
        XrLog.i("SCENE built ms=${SystemClock.elapsedRealtime() - t0}")
        render(ui)
        sink(XrEvent.SceneReady)
        // the marker of this session outlives the Stage (re-entry, background): tell the new ViewModel
        if (SessionAnchor.marker != null) sink(XrEvent.MarkerPlaced)
    }

    /** The Stage went to the background: stop every tracker (design `container.lifecycle`). */
    fun pause() {
        if (paused || !attached) return
        paused = true
        hands.stop()
        head.stop()
        stick.stop()
        XrGraph.speaker(context).stop()
        dragging = false
        lastJoints.clear()
        XrLog.i("SCENE paused")
    }

    fun resume() {
        if (!paused || !attached) return
        paused = false
        head.start()
        stick.start()
        syncHandProvider()
        XrLog.i("SCENE resumed")
    }

    fun bindPanels(attachments: SpatialViewAttachments) {
        val r = panelRig ?: return
        if (!r.bind(attachments, PanelIds.pool, SystemClock.elapsedRealtime())) {
            main.removeCallbacks(rebind)
            main.postDelayed(rebind, 250L)
        }
    }

    private val rebind = Runnable { if (stageRoot != null) bindTick.intValue++ }

    fun detach() {
        if (!attached && stageRoot == null) return
        attached = false
        if (ActiveScene.scene === this) ActiveScene.scene = null
        main.removeCallbacksAndMessages(null)
        buildJob?.cancel()
        scope?.cancel()
        scope = null
        hands.stop()
        head.stop()
        stick.stop()
        panelShown.clear()
        work.clear()
        listOfNotNull(left, right, demo).forEach { it.destroy() }
        left = null
        right = null
        demo = null
        selLabel?.destroy()
        selLabel = null
        listOfNotNull(markerRing, markerDot, ghostRing).forEach { it.destroy() }
        markerRing = null
        markerDot = null
        ghostRing = null
        runCatching { floorCatcher?.destroy(true) }
        floorCatcher = null
        handle?.destroy()
        handle = null
        mainPlaced = false
        lastPopupShown = false
        groupLabels.forEach { it.destroy() }
        groupLabels.clear()
        leads.forEach { it.destroy() }
        leads.clear()
        runCatching { panelRig?.release() }
        runCatching { stageRoot?.destroy(true) }
        runCatching { contentProbe?.destroy(true) }
        shared?.close()
        shared = null
        mats = null
        kit = null
        stageRoot = null
        contentProbe = null
        panelRig = null
        content = null
        built = false
        hasHead = false
        lastJoints.clear()
        lastPoses.clear()
        demoCenter = null
        XrLog.i("SCENE detached")
    }

    fun setStrings(zh: SceneStrings, en: SceneStrings) {
        stringsZh = zh
        stringsEn = en
        groupLabels.forEach { it.hide() }
    }

    // =========================================================================================
    // state -> entities
    // =========================================================================================

    fun render(state: XrUiState) {
        val prev = ui
        ui = state
        if (!attached) return
        syncHandProvider()
        if (prev.unsupported != null && state.unsupported == null) retrySupport()
        if (state.demoPose != prev.demoPose || demoTarget != state.demoPose) setDemoTarget(state.demoPose)
        if (state.prefs.lang != prev.prefs.lang) groupLabels.forEach { it.hide() }
        val r = panelRig ?: return
        r[PanelIds.MAIN]?.show()
        if (state.popup != Popup.SETTINGS) r[PanelIds.SETTINGS]?.hide()
    }

    /** The hand provider runs only while real hands are wanted, and never on the emulator unless asked (Spatial Ruler). */
    private fun syncHandProvider() {
        val want = attached && !paused && ui.loaded && (ui.source == Source.LIVE || ui.follow != null) && (!Device.onEmulator || liveOnEmulator)
        if (want && !hands.running) hands.start()
        if (!want && hands.running) hands.stop()
    }

    private fun setDemoTarget(p: PoseName) {
        val now = SystemClock.elapsedRealtime()
        demoFrom = currentDemoParams(now)
        demoTo = demoPoses.params(p)
        demoTarget = p
        demoBlendStartMs = now
        XrLog.i("DEMO pose=${p.key}")
    }

    private fun currentDemoParams(now: Long): PoseParams {
        val t = ((now - demoBlendStartMs) / DEMO_BLEND_MS).coerceIn(0f, 1f)
        val e = t * t * (3f - 2f * t)
        return demoFrom.lerp(demoTo, e)
    }

    // =========================================================================================
    // per frame
    // =========================================================================================

    fun onFrame(context: SceneUpdateContext) {
        val root = stageRoot ?: return
        if (root.scene == null || context.scene != root.scene) return
        val now = SystemClock.elapsedRealtime()
        frames++
        head.pose()?.let {
            pose = it
            hasHead = it.position.length > 1e-3f
        }
        val r = panelRig ?: return
        r.onFrame(now)
        r.stowBehind(pose)
        if (lastFrameMs > 0) frameIntervalMs = (now - lastFrameMs).coerceIn(1L, 5000L)
        val dt = if (lastFrameMs < 0) 0f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        if (!attached || paused) return
        if (built) {
            work.run(if (frameIntervalMs > SLOW_FRAME_MS) 2 else 6)
            val t0 = java.lang.System.nanoTime()
            stepHands(now, dt)
            solveNanos += java.lang.System.nanoTime() - t0
            solveCount++
            stepLooks(now)
            stepLabels(now, dt)
            spikeLogs(now)
        }
        if (hasHead) placePanels(now)
        r.setViewer(pose.position)
        if (now - lastLogMs > LOG_MS) {
            lastLogMs = now
            val us = if (solveCount > 0) solveNanos / solveCount / 1000 else 0
            solveNanos = 0
            solveCount = 0
            XrLog.i("FRAME n=$frames dt=$frameIntervalMs head=${XrLog.pos(pose.position)} source=${ui.source} hands=${lastJoints.keys} bones_us=$us lost=$lostCount provider=${hands.support()} stick=${stick.status()}")
        }
    }

    private fun stepHands(now: Long, dt: Float) {
        val s = ui
        // real hands: live provider or injected joints (design HandPoseSource)
        val (l, rr) =
            when (s.source) {
                Source.LIVE -> hands.sample()
                Source.INJECTED -> injectedLeft to injectedRight
                Source.DEMO -> if (s.follow != null) hands.sample() else (null to null)
            }
        debounceTracked(now, l != null, rr != null)
        if (s.source == Source.LIVE) watchSupport(now)
        val zoom = s.carpalZoomOn
        val tipInset = if (s.source == Source.DEMO) 0f else TIP_INSET_M
        solveInto(HandId.LEFT, left, l, zoom, tipInset)
        solveInto(HandId.RIGHT, right, rr, zoom, tipInset)
        // the demo hand: its own mode, or the follow-me model next to the tracked hand (S09)
        val showDemo = s.loaded && !s.welcome && (s.source == Source.DEMO || s.follow != null)
        val dj =
            if (!showDemo) {
                null
            } else {
                stepDemoPlacement(now, dt)
                val params = if (s.follow != null) followParams(now, s.follow) else currentDemoParams(now)
                val c = demoCenter
                if (c == null) null else demoPoses.joints(params, right = true).placed(c - demoRot.rotate(DEMO_CENTER_OFFSET), demoRot)
            }
        solveInto(HandId.DEMO, demo, dj, zoom && s.follow == null, 0f)
        stepFade()
        stepMarker(now)
    }

    private fun solveInto(id: HandId, view: HandView?, joints: HandJoints?, zoom: Boolean, tipInset: Float) {
        if (joints == null) {
            lastJoints.remove(id)
            lastPoses.remove(id)
            view?.apply(null)
            return
        }
        lastJoints[id] = joints
        val poses = solver.solve(joints, zoom, tipInset)
        if (poses == null) {
            lastPoses.remove(id)
            view?.apply(null)
            return
        }
        lastPoses[id] = poses
        view?.apply(poses)
    }

    private fun debounceTracked(now: Long, l: Boolean, r: Boolean) {
        if (l != rawL || r != rawR) {
            if ((rawL && !l) || (rawR && !r)) lostCount++
            rawL = l
            rawR = r
            rawSinceMs = now
        }
        if ((reportedL != rawL || reportedR != rawR) && now - rawSinceMs >= TRACK_DEBOUNCE_MS) {
            reportedL = rawL
            reportedR = rawR
            XrLog.i("HANDS tracked left=$rawL right=$rawR")
            sink(XrEvent.TrackedChanged(rawL, rawR))
        }
    }

    /** S18: the provider is running but not SUPPORTED for a while (it starts as NOT_IN_FULL_SPACE for a moment). */
    private fun watchSupport(now: Long) {
        if (!hands.running) return
        val s = hands.support()
        val bad = s != null && s != "SUPPORTED"
        if (!bad) {
            unsupportedSinceMs = -1L
            if (reportedSupport != null) {
                reportedSupport = null
                sink(XrEvent.Support(null))
            }
            return
        }
        if (unsupportedSinceMs < 0) unsupportedSinceMs = now
        if (now - unsupportedSinceMs > SUPPORT_GRACE_MS && reportedSupport != s) {
            reportedSupport = s
            XrLog.i("HANDS unsupported state=$s")
            sink(XrEvent.Support(s))
        }
    }

    /** Debug `support <state>` / "try again": forget the reported state so it is re-evaluated. */
    fun retrySupport() {
        reportedSupport = null
        unsupportedSinceMs = -1L
        if (hands.forcedSupport == null && hands.running) {
            hands.stop()
            syncHandProvider()
        }
    }

    // ---- demo hand (profile D1 "move to the marker", D14, D20) -------------------------------------

    /**
     * The demo hand's anchor: where the user's marker says (after "move to the marker"), else a parking spot beside and
     * beyond the main panel. Set once and kept for the whole session (pose changes, quiz, follow-me, background, Stage
     * re-entry all come back to it); only a new marker + "move to the marker" or a drag moves it. Never the head pose.
     */
    private fun stepDemoPlacement(now: Long, dt: Float) {
        // a new "move to the marker" (the ViewModel's tick restarts at 0 with a new Stage: 0 never re-brings)
        if (ui.demoPlaceTick > 0 && ui.demoPlaceTick != SessionAnchor.appliedTick) {
            SessionAnchor.appliedTick = ui.demoPlaceTick
            bringToMarker()
        }
        if (demoCenter == null) SessionAnchor.center?.let {
            demoCenter = it
            demoRot = SessionAnchor.rotation
        }
        if (demoCenter == null) park()
        val x = stick.x(now)
        val a = abs(x)
        if (a > STICK_DEADZONE && !dragging && demoCenter != null) {
            val t = ((a - STICK_DEADZONE) / (1f - STICK_DEADZONE)).coerceIn(0f, 1f)
            val rate = sign(x) * STICK_MAX_DEG_S * t
            demoRot = Quat4.axisAngle(Vec3.UP, Math.toRadians((-rate * dt).toDouble()).toFloat()) * demoRot
            SessionAnchor.rotation = demoRot
            if (now - lastStickLogMs > 1000L) {
                lastStickLogMs = now
                XrLog.i(String.format(Locale.US, "STICK turn x=%.2f", x))
            }
        }
    }

    /** "Move to the marker": the hand stands over the marker, palm turned towards where the user is now (yaw only). */
    private fun bringToMarker() {
        val m = SessionAnchor.marker ?: return
        // D14: never closer than 0.6 m to the head (a safety push, not a placement)
        val c = Placement.keepClear(Placement.handOverMarker(m), pose.position, P2(0f, -1f))
        anchorDemo(c, Placement.palmRotation(Placement.palmYaw(c, pose.position)), "marker")
    }

    /** Before the first marker: beside and beyond the main panel (>= 0.5 m farther, >= 30 degrees off), D20. */
    private fun park() {
        val slot = panelRig?.get(PanelIds.MAIN) ?: return
        if (!slot.shown || !hasHead) return
        val c = Placement.park(pose.position, slot.position)
        anchorDemo(c, Placement.palmRotation(Placement.palmYaw(c, pose.position)), "park")
    }

    private fun anchorDemo(c: Vec3, rot: Quat4, why: String) {
        demoCenter = c
        demoRot = rot
        SessionAnchor.center = c
        SessionAnchor.rotation = rot
        XrLog.i("DEMO anchor why=$why center=${XrLog.pos(c)} marker=${XrLog.pos(SessionAnchor.marker)}")
    }

    /** Per frame: the demo hand fades to 30 % while it stands between the eyes and a shown panel (D20). */
    private fun stepFade() {
        val v = demo ?: return
        val c = demoCenter
        var faded = false
        if (c != null && v.visible && hasHead) {
            val r = panelRig
            for (id in PanelIds.pool) {
                val slot = r?.get(id) ?: continue
                if (!slot.shown) continue
                val f = slot.facing
                val w = (if (id == PanelIds.MAIN) PanelSizes.MAIN_W else PanelSizes.SETTINGS_W) * DP_M * PANEL_SCALE / 2f
                val h = (if (id == PanelIds.MAIN) PanelSizes.MAIN_H else PanelSizes.SETTINGS_H) * DP_M * PANEL_SCALE / 2f
                if (Placement.blocksPanel(pose.position, c, DEMO_RADIUS_M, slot.position, f.right, f.up, w, h)) faded = true
            }
        }
        // the real hands (D22): every bone in the eye -> panel cone gives way to the panel behind it
        for (hv in listOfNotNull(left, right)) {
            val poses = lastPoses[hv.id]
            if (poses == null || !hasHead) {
                hv.yieldTo(emptySet())
                continue
            }
            val r = panelRig
            val ids = HashSet<String>()
            for (id in PanelIds.pool) {
                val slot = r?.get(id) ?: continue
                if (!slot.shown) continue
                val f = slot.facing
                val w = (if (id == PanelIds.MAIN) PanelSizes.MAIN_W else PanelSizes.SETTINGS_W) * DP_M * PANEL_SCALE / 2f
                val h = (if (id == PanelIds.MAIN) PanelSizes.MAIN_H else PanelSizes.SETTINGS_H) * DP_M * PANEL_SCALE / 2f
                for (p in poses) if (Placement.blocksPanel(pose.position, p.position, p.radius, slot.position, f.right, f.up, w, h)) ids += p.id
            }
            hv.yieldTo(ids)
        }
        if (faded != demoFaded) {
            demoFaded = faded
            XrLog.i("DEMO fade=${if (faded) Placement.FADED else 1f}")
        }
        v.fade(if (faded) Placement.FADED else 1f)
    }

    /** Hand root axes: fingers up, palm (+Z) towards [viewer] horizontally, thumb side X = Y x Z. */
    private fun palmTowards(center: Vec3, viewer: Vec3): Quat4 = Placement.palmRotation(Placement.palmYaw(center, viewer))

    // ---- marker on the floor (controller ray or gaze + pinch) ------------------------------------

    /** The floor height: Stage space has its origin on the floor; a seated / origin-at-eye session falls back below the head. */
    private fun floorY(): Float =
        SessionAnchor.floorY ?: (if (pose.position.y > 0.9f) 0f else pose.position.y - 1.2f).also {
            SessionAnchor.floorY = it
            XrLog.i("MARKER floorY=$it")
        }

    /** While placing: the floor takes pinches / triggers; the controller ray (if any) shows a ghost ring where it meets the floor. */
    private fun stepMarker(now: Long) {
        val placing = ui.placing
        val y = if (placing || SessionAnchor.marker != null) floorY() else 0f
        floorCatcher?.let { fc ->
            if (placing) fc.setLocal(position = Vec3(pose.position.x, y - 0.01f, pose.position.z))
            if (fc.enabled != placing) fc.enabled = placing
        }
        val ray = if (placing) stick.ray(now) else null
        val ghostAt = ray?.let { rayFloor(it.first, it.second, y) }
        ghostRing?.let { g ->
            if (ghostAt != null) g.place(ghostAt + Vec3(0f, 0.004f, 0f), Vec3(MARKER_D_M, 1f, MARKER_D_M)) else g.show(false)
        }
        val m = SessionAnchor.marker
        markerRing?.let { r -> if (m != null) r.place(m + Vec3(0f, 0.003f, 0f), Vec3(MARKER_D_M, 1f, MARKER_D_M)) else r.show(false) }
        markerDot?.let { d -> if (m != null) d.place(m + Vec3(0f, 0.004f, 0f), Vec3(MARKER_D_M * 0.25f, 1f, MARKER_D_M * 0.25f)) else d.show(false) }
    }

    private fun rayFloor(origin: Vec3, dir: Vec3, y: Float): Vec3? {
        if (dir.y > -0.05f) return null
        val t = (y - origin.y) / dir.y
        if (t <= 0f || t > 12f) return null
        return origin + dir * t
    }

    /** A pinch / trigger on the floor while placing: the marker goes there (controller ray first, else the tap point). */
    private fun placeMarker(tapStage: Vec3?, now: Long): Boolean {
        val y = floorY()
        val ray = stick.ray(now)
        val at = ray?.let { rayFloor(it.first, it.second, y) } ?: tapStage?.let { Vec3(it.x, y, it.z) } ?: return false
        setMarker(at, if (ray != null) "ray" else "tap")
        return true
    }

    private fun setMarker(at: Vec3, via: String) {
        SessionAnchor.marker = at
        XrLog.i("MARKER at=${XrLog.pos(at)} via=$via")
        sink(XrEvent.MarkerPlaced)
    }

    /** Debug `marker x z`: as if the floor had been pinched there. */
    fun debugMarker(x: Float, z: Float): String {
        setMarker(Vec3(x, floorY(), z), "debug")
        return "marker=${XrLog.pos(SessionAnchor.marker)}"
    }

    /** S09: the demo hand loops the current step: 1 fist, 2 open (from a fist), 3 thumb to little finger. */
    private fun followParams(now: Long, step: Int): PoseParams {
        val t = (now % FOLLOW_LOOP_MS) / FOLLOW_LOOP_MS.toFloat()
        val tri = if (t < 0.5f) t * 2f else 2f - t * 2f
        val e = tri * tri * (3f - 2f * tri)
        return when (step) {
            1 -> demoPoses.params(PoseName.OPEN).lerp(demoPoses.params(PoseName.FIST), e)
            2 -> demoPoses.params(PoseName.FIST).lerp(demoPoses.params(PoseName.OPEN), e)
            else -> demoPoses.params(PoseName.OPEN).lerp(demoPoses.params(PoseName.PINCH), e)
        }
    }

    // ---- looks ------------------------------------------------------------------------------------

    /** The hand the quiz flashes on: the demo hand, else of the tracked ones the one nearer to the main panel. */
    private fun activeHand(): HandId? {
        if (ui.source == Source.DEMO) return HandId.DEMO
        val tracked = listOf(HandId.LEFT, HandId.RIGHT).filter { lastPoses.containsKey(it) }
        if (tracked.size <= 1) return tracked.firstOrNull()
        val panel = panelRig?.get(PanelIds.MAIN)?.position ?: return HandId.RIGHT
        return tracked.minByOrNull { id -> lastJoints[id]?.get(Joint.PALM)?.distanceTo(panel) ?: 9f }
    }

    private fun stepLooks(now: Long) {
        val m = mats ?: return
        val s = ui
        m.breathe(now)
        val xrayLook = s.prefs.look == Look.XRAY
        val active = activeHand()
        val q = s.quiz.current
        val followBones: Set<String> =
            when (s.follow) {
                1, 2 -> catalog.of(Group.PHALANX).map { it.id }.toSet()
                3 -> setOf("mc1", "trapezium")
                else -> emptySet()
            }
        for (view in listOfNotNull(left, right, demo)) {
            if (!view.visible) continue
            for (n in view.all) {
                val b = n.bone
                var look = BoneLook.NORMAL
                var opacity = 1f
                if (s.mode == Mode.EXPLORE) {
                    if (s.group != null) {
                        if (b.group == s.group) look = BoneLook.GROUP else opacity = DIM
                    }
                    if (s.selected?.hand == view.id && s.selected.boneId == b.id) look = BoneLook.SELECTED
                    if (view.id != HandId.DEMO && b.id in followBones) look = BoneLook.FOLLOW
                } else {
                    val last = s.quiz.last
                    when (s.quiz.phase) {
                        QuizPhase.ASKING -> if (q?.kind == QuestionKind.IDENTIFY && view.id == active && b.id == q.boneId) look = BoneLook.PULSE
                        QuizPhase.FEEDBACK ->
                            if (last != null) {
                                if (b.id == last.picked) look = if (last.correct) BoneLook.CORRECT else BoneLook.WRONG
                                if (!last.correct && b.id == last.boneId && view.id == active) look = BoneLook.PULSE
                            }
                        QuizPhase.REVIEW -> if (view.id == active && b.id == s.quiz.wrong.getOrNull(s.quiz.reviewIndex)?.boneId) look = BoneLook.SELECTED
                        else -> Unit
                    }
                }
                // D20: between the eyes and a panel every demo bone is the 30 % see-through material, highlights included
                if (view.id == HandId.DEMO && demoFaded) view.look(b.id, m.faded, 1f) else view.look(b.id, m.of(look, xrayLook, b.group), opacity)
            }
        }
    }

    // ---- labels -------------------------------------------------------------------------------------

    private fun nameLines(boneId: String): Pair<String, String?> {
        val b = catalog[boneId] ?: return boneId to null
        return when (ui.prefs.lang) {
            Lang.ZH -> b.zh to null
            Lang.EN -> b.en to null
            Lang.BOTH -> b.zh to b.en
        }
    }

    /** Which bone of which hand carries the single label this frame (S05, S13 wrong, S15), or null. */
    private fun singleLabel(): Pair<HandId, String>? {
        val s = ui
        if (s.mode == Mode.EXPLORE) return s.selected?.let { it.hand to it.boneId }
        val active = activeHand() ?: return null
        return when (s.quiz.phase) {
            QuizPhase.FEEDBACK -> s.quiz.last?.takeIf { !it.correct }?.let { active to it.boneId }
            QuizPhase.REVIEW -> s.quiz.wrong.getOrNull(s.quiz.reviewIndex)?.let { active to it.boneId }
            else -> null
        }
    }

    private fun stepLabels(now: Long, dt: Float) {
        val viewer = pose.position
        val plate = selLabel ?: return
        val single = singleLabel()
        val node = single?.let { (h, id) -> viewOf(h)?.node(id)?.pose }
        if (single == null || node == null) {
            plate.hide()
            selLabelAt = null
        } else {
            val (a, b) = nameLines(single.second)
            val dot = XrColors.group(catalog[single.second]!!.group)
            plate.content("${single.second}|$a|$b") { Plates.label(a, b, dot) }
            val toHead = (viewer - node.position).normalized()
            val target = node.position + toHead * LABEL_TOWARDS_HEAD_M + Vec3(0f, LABEL_UP_M + if (b != null) 0.006f else 0f, 0f)
            val prev = selLabelAt
            val k = 1f - exp(-dt / LABEL_SMOOTH_S)
            val at = if (prev == null) target else prev.lerp(target, k)
            val speed = if (prev == null || dt <= 0f) 0f else target.distanceTo(prev) / dt
            selLabelAt = at
            plate.center = at
            plate.heightM = LABEL_LINE_M
            // D20: a label on the faded demo hand would sit on the panel: hidden until the hand is out of the way
            if (single.first == HandId.DEMO && demoFaded) plate.hide() else plate.face(viewer, if (speed > FAST_HAND_M_S) 0.3f else 1f)
        }
        // group labels (S06): one column on the outer side of the hand nearer to the panel
        val g = ui.group
        val target = if (ui.mode == Mode.EXPLORE && g != null && ui.selected == null) activeHand() else null
        val items = if (target == null || g == null) emptyList() else groupItems(g)
        val view = target?.let { viewOf(it) }
        if (view == null || items.isEmpty() || (target == HandId.DEMO && demoFaded)) {
            groupLabels.forEach { it.hide() }
            leads.forEach { it.hide() }
            return
        }
        val poses = lastPoses[target] ?: return
        val anchor = poses.fold(Vec3.ZERO) { a, p -> a + p.position } / poses.size.toFloat()
        val f = pose.level()
        val viewerRight = Vec3(-f.z, 0f, f.x)
        val out = if (target == HandId.LEFT) viewerRight * -1f else viewerRight
        val rows = items.size + (if (g == Group.CARPAL) 1 else 0)
        val rowH = LABEL_LINE_M * (if (ui.prefs.lang == Lang.BOTH) 1.9f else 1f) + 0.005f
        val top = anchor.y + (rows - 1) * rowH / 2f
        var row = 0
        for ((i, item) in items.withIndex()) {
            if (g == Group.CARPAL && i == 4) row++
            val labelPlate = groupLabels[i]
            val (boneId, a, b) = item
            val dot = XrColors.group(g ?: return)
            labelPlate.content("g|$boneId|$a|$b") { Plates.label(a, b, dot) }
            labelPlate.heightM = LABEL_LINE_M
            val bonePos = poses.firstOrNull { it.id == boneId }?.position ?: anchor
            val half = if (labelPlate.ready) labelPlate.halfWidthM else 0.03f
            val c = Vec3(anchor.x, top - row * rowH, anchor.z) + out * (GROUP_OUT_M + HAND_HALF_M + half)
            labelPlate.center = c
            labelPlate.face(viewer)
            leads[i].draw(c - out * half, bonePos, LEAD_W_M, viewer)
            row++
        }
        for (i in items.size until groupLabels.size) {
            groupLabels[i].hide()
            leads[i].hide()
        }
    }

    /** (bone, first line, second line) per label of a group (design `spatialLayout.groupLabels`). */
    private fun groupItems(g: Group): List<Triple<String, String, String?>> {
        if (g != Group.PHALANX) {
            val list = catalog.of(g)
            val ordered = if (g == Group.CARPAL) list.filter { it.subgroup == "proximalRow" } + list.filter { it.subgroup != "proximalRow" } else list
            return ordered.map { b -> nameLines(b.id).let { (a, c) -> Triple(b.id, a, c) } }
        }
        val zh = stringsZh ?: return emptyList()
        val en = stringsEn ?: return emptyList()
        fun pick(z: String, e: String): Pair<String, String?> =
            when (ui.prefs.lang) {
                Lang.ZH -> z to null
                Lang.EN -> e to null
                Lang.BOTH -> z to e
            }
        return listOf("pp3" to pick(zh.proximal, en.proximal), "mp3" to pick(zh.middle, en.middle), "dp3" to pick(zh.distal, en.distal), "pp1" to pick(zh.thumbTwo, en.thumbTwo))
            .map { (id, p) -> Triple(id, p.first, p.second) }
    }

    private fun viewOf(id: HandId): HandView? =
        when (id) {
            HandId.LEFT -> left
            HandId.RIGHT -> right
            HandId.DEMO -> demo
        }

    // =========================================================================================
    // panels
    // =========================================================================================

    /**
     * Profile D21: the main panel is put down once in front of the user (24 degrees left, 15 down, 0.8 m, facing them)
     * and then stays locked in the room; it turns towards the user only when it is put somewhere (placed, dragged,
     * called back). The settings popup is put in front of it when it opens.
     */
    private fun placePanels(now: Long) {
        val r = panelRig ?: return
        val mainSlot = r[PanelIds.MAIN] ?: return
        if (!mainPlaced) {
            mainPlaced = true
            val p = PanelRest.at(pose, MAIN_LEFT_DEG, -MAIN_DOWN_DEG, MAIN_M)
            mainSlot.show(at = p)
            XrLog.i("PANEL main placed pos=${XrLog.pos(p)}")
        }
        if (ui.panelRecallTick != recallApplied) {
            recallApplied = ui.panelRecallTick
            val p = PanelRest.at(pose, 0f, -RECALL_DOWN_DEG, RECALL_M)
            mainSlot.show(at = p)
            XrLog.i("PANEL main recalled pos=${XrLog.pos(p)}")
        }
        val popup = ui.popup == Popup.SETTINGS
        if (popup && !lastPopupShown) {
            // over the main panel, nearer to the viewer: a popup on it, never behind it (profile D8)
            r[PanelIds.SETTINGS]?.show(mainSlot.position + mainSlot.facing.normal * POPUP_FRONT_M)
        }
        lastPopupShown = popup
        handle?.let { h ->
            if (mainSlot.shown) {
                val f = mainSlot.facing
                val half = PanelSizes.MAIN_H * DP_M * PANEL_SCALE / 2f
                h.place(mainSlot.position - f.up * (half + HANDLE_GAP_M) + f.normal * 0.005f, f.rotation)
            } else {
                h.hide()
            }
        }
    }

    /** A control of a panel was pressed (kept for the pointer log). */
    fun onPanelPointer() {
        lastPanelPressMs = SystemClock.elapsedRealtime()
    }

    /** Debug `recenter`: same as the menu's "call the panel back". */
    fun recenter() {
        sink(XrEvent.RecallPanel)
    }

    // =========================================================================================
    // input: system taps and drags
    // =========================================================================================

    private fun boneAt(entity: Entity?): Pair<HandId, String>? {
        if (entity == null) return null
        for (v in listOfNotNull(left, right, demo)) v.boneOf(entity)?.let { return v.id to it }
        return null
    }

    fun onSpatialTap(value: SpatialTapValue) {
        val target = value.targetEntity
        val name = runCatching { target?.getName() }.getOrNull() ?: "none"
        val kind = value.interactionKind.name
        val hit = boneAt(target)
        XrLog.i("TAP raw kind=$kind target=$name bone=${hit?.second ?: "-"}")
        if (ui.placing && hit == null && target != null && target === floorCatcher) {
            placeMarker(toStage(value.position.x, value.position.y, value.position.z), SystemClock.elapsedRealtime())
            return
        }
        if (hit == null) return
        sink(XrEvent.Tapped(hit.first, hit.second, kind, pinchOf(hit.first)))
    }

    private fun pinchOf(id: HandId): Float =
        when {
            id == HandId.DEMO -> 0f
            ui.source == Source.INJECTED -> injectedPinch
            else -> lastJoints[id]?.pinch ?: 0f
        }

    /** Debug `tap <bone> <kind>`: a tap of that kind on that bone of the active hand. */
    fun debugTap(boneId: String, kind: String): String {
        val hand = activeHand() ?: return "no hand"
        sink(XrEvent.Tapped(hand, boneId, kind, pinchOf(hand)))
        return "tap $hand:$boneId kind=$kind pinch=${pinchOf(hand)}"
    }

    fun onSpatialDrag(phase: String, start: Offset3D?, value: SpatialDragValue) {
        if (dragPanel(phase, value)) return
        val hit = boneAt(value.targetEntity)
        when (phase) {
            "start" -> {
                dragging = hit?.first == HandId.DEMO && ui.source == Source.DEMO && ui.follow == null && demoCenter != null
                dragStart = demoCenter
                dragSum = Vec3.ZERO
                if (dragging) XrLog.i("DRAG demo start kind=${value.interactionKind.name}")
            }
            "move" -> {
                if (!dragging) return
                val d = value.dragAmount
                val delta = handDelta(Vec3(d.x, d.y, d.z)) ?: return
                dragSum = dragSum + delta
                val s0 = dragStart ?: return
                demoCenter = Placement.keepClear(s0 + dragSum, pose.position, P2(0f, -1f))
            }
            else -> {
                if (dragging) {
                    XrLog.i("DRAG demo end center=${XrLog.pos(demoCenter)} moved=${XrLog.pos(dragSum)}")
                    demoCenter?.let { SessionAnchor.center = it }
                }
                dragging = false
            }
        }
    }

    /** D21: a drag on the bar moves the main panel; it faces the user wherever it is let go. */
    private fun dragPanel(phase: String, value: SpatialDragValue): Boolean {
        val slot = panelRig?.get(PanelIds.MAIN) ?: return false
        when (phase) {
            "start" -> {
                val h = handle ?: return false
                if (value.targetEntity !== h.entity) return false
                panelDrag = slot.position
                panelDragSum = Vec3.ZERO
                XrLog.i("PANEL drag start kind=${value.interactionKind.name} pos=${XrLog.pos(slot.position)}")
                return true
            }
            "move" -> {
                val s0 = panelDrag ?: return false
                val d = value.dragAmount
                handDelta(Vec3(d.x, d.y, d.z))?.let { panelDragSum = panelDragSum + it }
                slot.moveTo(s0 + panelDragSum)
                return true
            }
            else -> {
                if (panelDrag == null) return false
                panelDrag = null
                XrLog.i("PANEL drag end pos=${XrLog.pos(slot.position)} moved=${XrLog.pos(panelDragSum)}")
                return true
            }
        }
    }

    /** Debug `paneldrag dx dy dz`: as a drag on the bar. */
    fun debugPanelDrag(dx: Float, dy: Float, dz: Float): String {
        val slot = panelRig?.get(PanelIds.MAIN) ?: return "no panel"
        slot.moveTo(slot.position + Vec3(dx, dy, dz))
        return "panel pos=${XrLog.pos(slot.position)} handle=${handle != null}"
    }

    private fun handDelta(px: Vec3): Vec3? {
        val moved = toStage(px.x, px.y, px.z) ?: return null
        val origin = toStage(0f, 0f, 0f) ?: return null
        return moved - origin
    }

    /** View-local pixels of a spatial gesture -> Stage metres (Spatial Ruler). */
    private fun toStage(x: Float, y: Float, z: Float): Vec3? {
        val c = content ?: return null
        val probe = contentProbe ?: return null
        return runCatching {
                val local = c.convertPosition(Vector3(x, y, z), ViewCoordinateSpace.Local, c.localSpatialCoordinateSpace)
                probe.convertPositionTo(local, null).domain()
            }
            .getOrNull()
    }

    // =========================================================================================
    // spike logs (design `preconditions.deviceSpike`: logs 1 - 4)
    // =========================================================================================

    private fun spikeLogs(now: Long) {
        if (ui.source != Source.LIVE) return
        for ((id, j) in lastJoints) {
            if (id == HandId.DEMO) continue
            val tip = j[Joint.MIDDLE_TIP] ?: continue
            val palm = j[Joint.PALM] ?: continue
            jitter.getOrPut(id) { ArrayDeque() }.apply {
                addLast(now to tip)
                while (isNotEmpty() && now - first().first > JITTER_WINDOW_MS) removeFirst()
            }
            palmTrail.getOrPut(id) { ArrayDeque() }.apply {
                addLast(now to palm)
                while (isNotEmpty() && now - first().first > JITTER_WINDOW_MS) removeFirst()
            }
            autoRecord(id, j)
        }
        if (now - lastJitterLogMs < LOG_MS) return
        lastJitterLogMs = now
        for ((id, q) in jitter) {
            val trail = palmTrail[id] ?: continue
            if (q.size < 10 || trail.size < 10) continue
            val moved = trail.first().second.distanceTo(trail.last().second)
            if (moved > STILL_M) continue
            val mean = q.fold(Vec3.ZERO) { a, p -> a + p.second } / q.size.toFloat()
            val variance = q.fold(0f) { a, p -> a + (p.second - mean).dot(p.second - mean) } / q.size
            XrLog.i(String.format(Locale.US, "JITTER hand=%s tip_std_mm=%.1f samples=%d palm_moved_mm=%.1f", id, sqrt(variance) * 1000f, q.size, moved * 1000f))
        }
    }

    /** Spike log 4: the first open / fist / pinch / flip of each real hand is written to files/poses/ for v0.2. */
    private fun autoRecord(id: HandId, j: HandJoints) {
        val f = solver.frame(j) ?: return
        val palm = j[Joint.PALM] ?: return
        val tips = listOf(Joint.INDEX_TIP, Joint.MIDDLE_TIP, Joint.RING_TIP, Joint.LITTLE_TIP).mapNotNull { j[it] }
        if (tips.size < 4) return
        val curl = tips.fold(0f) { a, t -> a + t.distanceTo(palm) } / tips.size / f.scale
        val pinch = (j[Joint.THUMB_TIP] ?: return).distanceTo(j[Joint.LITTLE_TIP] ?: return) / f.scale
        val back = f.axes.c2.dot((pose.position - palm).normalized()) < -0.5f
        val name =
            when {
                pinch < 0.03f -> "pinch"
                curl < 0.055f -> "fist"
                back && curl > 0.08f -> "flip"
                curl > 0.085f -> "open"
                else -> return
            }
        val key = "${name}_${if (j.right) "R" else "L"}"
        if (!recorded.add(key)) return
        writePose(key, j)
    }

    fun writePose(name: String, j: HandJoints?): String {
        if (j == null) return "no hand"
        val f = solver.frame(j)
        val o = JSONObject()
        o.put("right", j.right)
        o.put("pinch", j.pinch.toDouble())
        // joints in the hand's own frame, metres at model size, so they can drive the demo hand directly
        val joints = JSONObject()
        for ((k, v) in j.at) {
            val local = if (f == null) v else f.axes.transposed() * ((v - f.origin) / f.scale)
            joints.put(k.name, org.json.JSONArray(listOf(local.x.toDouble(), local.y.toDouble(), local.z.toDouble())))
        }
        o.put("joints", joints)
        o.put("scale", (f?.scale ?: 1f).toDouble())
        val dir = File(context.filesDir, "poses").apply { mkdirs() }
        val file = File(dir, "$name.json")
        runCatching { file.writeText(o.toString(1)) }
        XrLog.i("POSE recorded name=$name file=${file.absolutePath}")
        return file.absolutePath
    }

    // =========================================================================================
    // debug seams
    // =========================================================================================

    /** Debug `pose <l>/<r>` (open, fist, pinch, flip, none): injected hands in front of the head, palms to you. */
    fun inject(l: PoseName?, r: PoseName?): String {
        val h = pose
        val f = h.level()
        val fwd = Vec3(f.x, 0f, f.z)
        val rightDir = Vec3(-f.z, 0f, f.x)
        fun place(p: PoseName, right: Boolean): HandJoints {
            val c = h.position + fwd * INJECT_AHEAD_M + rightDir * (if (right) INJECT_SIDE_M else -INJECT_SIDE_M) + Vec3(0f, -INJECT_DOWN_M, 0f)
            val rot = palmTowards(c, h.position)
            return demoPoses.joints(p, right).placed(c - rot.rotate(DEMO_CENTER_OFFSET), rot).copy(pinch = injectedPinch)
        }
        injectedLeft = l?.let { place(it, false) }
        injectedRight = r?.let { place(it, true) }
        return "inject left=${l?.key ?: "none"} right=${r?.key ?: "none"}"
    }

    fun setInjectedPinch(v: Float) {
        injectedPinch = v
        injectedLeft = injectedLeft?.copy(pinch = v)
        injectedRight = injectedRight?.copy(pinch = v)
    }

    fun forceSupport(state: String?) {
        hands.forcedSupport = state
        retrySupport()
    }

    fun debugStick(x: Float, ms: Long): String {
        stick.debugHold(x, ms)
        return String.format(Locale.US, "stick x=%.2f ms=%d", x, ms)
    }

    /** Debug `drag dx dz`: moves the demo hand like a drag (with the 0.6 m clearance). */
    fun debugDrag(dx: Float, dy: Float, dz: Float): String {
        val c = demoCenter ?: return "no demo hand"
        demoCenter = Placement.keepClear(c + Vec3(dx, dy, dz), pose.position, P2(0f, -1f))
        SessionAnchor.center = demoCenter
        return "demo center=${XrLog.pos(demoCenter)} flat=${"%.3f".format(Locale.US, P2.of(demoCenter!! - pose.position).length)}"
    }

    /**
     * Debug `dump bones`: per visible hand and bone, the node centre, how far each long bone's ends are from its two
     * joints (mm), and for the pisiform / hamate whether it sits on the palm side.
     */
    fun dumpBones(): List<String> {
        val out = ArrayList<String>()
        for ((id, poses) in lastPoses) {
            val j = lastJoints[id] ?: continue
            val f = solver.frame(j) ?: continue
            for (p in poses) {
                val rb = rig.bones[p.id] ?: continue
                var extra = ""
                if (rb.long) {
                    val axis = p.rotation.rotate(Vec3.UP)
                    val a = p.position - axis * (p.length / 2f)
                    val b = p.position + axis * (p.length / 2f)
                    val ea = a.distanceTo(j[rb.from!!]!!) * 1000f
                    val eb = b.distanceTo(j[rb.to!!]!!) * 1000f
                    extra = String.format(Locale.US, " endA_mm=%.2f endB_mm=%.2f len_mm=%.1f", ea, eb, p.length * 1000f)
                } else {
                    val palmar = (p.position - f.origin).dot(f.axes.c2)
                    extra = String.format(Locale.US, " palmar_mm=%.1f", palmar * 1000f)
                }
                val engine = viewOf(id)?.node(p.id)?.node?.let { n -> runCatching { n.convertPositionTo(Vector3.ZERO, stageRoot).domain() }.getOrNull() }
                out += "BONES hand=$id id=${p.id} c=${XrLog.pos(p.position)} engine=${XrLog.pos(engine)} r_mm=${"%.1f".format(Locale.US, p.radius * 1000f)}$extra"
            }
            out += String.format(Locale.US, "BONES hand=%s scale=%.3f frameZ=%s", id, f.scale, XrLog.pos(f.axes.c2))
        }
        if (out.isEmpty()) out += "BONES none"
        return out
    }

    fun poseLine(): String = "POSE hmd pos=${XrLog.pos(pose.position)} ${XrLog.quat(pose.rotation)} fwd=${XrLog.pos(pose.forward)} valid=$hasHead demo=${XrLog.pos(demoCenter)} demoYawDeg=${"%.1f".format(Locale.US, Math.toDegrees(kotlin.math.atan2(demoRot.rotate(Vec3(0f, 0f, 1f)).x.toDouble(), demoRot.rotate(Vec3(0f, 0f, 1f)).z.toDouble())))}"

    fun panelLines(): List<String> = panelRig?.dump() ?: emptyList()

    fun labelLines(): List<String> =
        listOf("LABEL selected shown=${selLabel?.shown} at=${XrLog.pos(selLabel?.center)}") +
            groupLabels.mapIndexedNotNull { i, p -> if (p.shown) "LABEL group$i at=${XrLog.pos(p.center)} halfW=${"%.3f".format(Locale.US, p.halfWidthM)}" else null }

    fun lastJointsOf(right: Boolean): HandJoints? = lastJoints[if (right) HandId.RIGHT else HandId.LEFT]

    companion object {
        const val MAIN_LEFT_DEG = 24f
        const val MAIN_DOWN_DEG = 15f
        const val MAIN_M = 0.8f
        const val PANEL_SCALE = 1f
        const val POPUP_FRONT_M = 0.04f
        const val KEY_HOLD_MS = 600L
        const val RECALL_DOWN_DEG = 15f
        const val RECALL_M = 0.9f
        const val HANDLE_GAP_M = 0.03f
        const val LOG_MS = 5000L
        const val SLOW_FRAME_MS = 60L
        const val TRACK_DEBOUNCE_MS = 1000L
        const val SUPPORT_GRACE_MS = 3000L
        const val TIP_INSET_M = 0.003f
        const val DIM = 0.25f
        const val DEMO_RADIUS_M = 0.11f
        const val MARKER_D_M = 0.24f
        const val DEMO_BLEND_MS = 600f
        const val FOLLOW_LOOP_MS = 2400L
        val DEMO_CENTER_OFFSET = Vec3(0f, 0.045f, 0f)
        const val STICK_DEADZONE = 0.15f
        const val STICK_MAX_DEG_S = 120f
        const val LABEL_TOWARDS_HEAD_M = 0.04f
        const val LABEL_UP_M = 0.015f
        const val LABEL_LINE_M = 0.012f
        const val LABEL_SMOOTH_S = 0.12f
        const val FAST_HAND_M_S = 1f
        const val GROUP_SLOTS = 8
        const val GROUP_OUT_M = 0.06f
        const val HAND_HALF_M = 0.05f
        const val LEAD_W_M = 0.0015f
        const val INJECT_AHEAD_M = 0.38f
        const val INJECT_SIDE_M = 0.12f
        const val INJECT_DOWN_M = 0.22f
        const val JITTER_WINDOW_MS = 1000L
        const val STILL_M = 0.01f
    }
}
