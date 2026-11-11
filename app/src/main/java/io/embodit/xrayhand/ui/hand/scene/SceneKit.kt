package io.embodit.xrayhand.ui.hand.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.xrayhand.domain.Billboard
import io.embodit.xrayhand.domain.Quat4
import io.embodit.xrayhand.domain.Vec3
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.theme.XrColors
import kotlin.math.max

// ---------------------------------------------------------------------------------------------
// domain <-> SDK types. The scene works in Stage metres: every entity hangs below the calibrated
// stage root, whose local space equals Stage space (Spatial Ruler).
// ---------------------------------------------------------------------------------------------

internal fun Vec3.sdk(): Vector3 = Vector3(x, y, z)

internal fun Vector3.domain(): Vec3 = Vec3(x, y, z)

internal fun Quat4.sdk(): Quat = Quat(x, y, z, w)

internal fun Quat.domain(): Quat4 = Quat4(x, y, z, w)

internal fun Color.color4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

internal val Entity.transformOrNull: TransformComponent?
    get() = components[TransformComponent::class.java]

internal fun Entity.setLocal(position: Vec3? = null, rotation: Quat4? = null, scale: Vec3? = null) {
    val t = transformOrNull ?: return
    if (position != null) t.setPosition(position.sdk())
    if (rotation != null) t.setQuaternion(rotation.sdk())
    if (scale != null) t.setScaleVector(scale.sdk())
}

/** Sets [material] on every mesh at or below this entity. */
internal fun Entity.setMaterialDeep(material: Material) {
    components[ModelComponent::class.java]?.let { mc -> for (i in 0 until mc.materials.size) mc.materials[i] = material }
    runCatching { getChildren() }.getOrNull()?.forEach { it.setMaterialDeep(material) }
}

internal fun Entity.setMaterial(material: Material) {
    components[ModelComponent::class.java]?.materials?.set(0, material)
}

/** Overall opacity of an entity and its children. */
internal fun Entity.setOpacity(opacity: Float) {
    val existing = components[OpacityControllerComponent::class.java]
    if (existing != null) existing.opacity = opacity.coerceIn(0f, 1f) else components.set(OpacityControllerComponent(opacity.coerceIn(0f, 1f)))
}

internal fun Entity.named(name: String): Entity {
    runCatching { setName(name) }
    return this
}

/** Opacity of one bone surface in the faded demo hand (see [BoneMaterials.faded]). */
internal const val FADED_SURFACE = 0.16f

/** Metres per dp of an attachment panel at scale 1 (Spatial Ruler feasibility spike). */
internal const val DP_M = 0.0008f

internal fun boxCollision(size: Vec3) = CollisionComponent(collisionShape = listOf(ShapeResource.createBox(Vector3(size.x, size.y, size.z))), physicsMaterial = PhysicsMaterialResource())

internal fun sphereCollision(radius: Float) = CollisionComponent(collisionShape = listOf(ShapeResource.createSphere(radius)), physicsMaterial = PhysicsMaterialResource())

/**
 * GPU-side resource creation (label textures) paced over frames: each call blocks the main thread until the
 * render thread takes it (startup ANR seen on sibling apps). One task per owner: a newer one replaces the pending one.
 */
internal class WorkQueue {
    private val tasks = LinkedHashMap<Any, () -> Unit>()

    fun enqueue(owner: Any, task: () -> Unit) {
        tasks.remove(owner)
        tasks[owner] = task
    }

    fun cancel(owner: Any) {
        tasks.remove(owner)
    }

    fun run(n: Int): Int {
        var left = n
        while (left > 0 && tasks.isNotEmpty()) {
            val key = tasks.keys.first()
            val task = tasks.remove(key) ?: continue
            runCatching(task).onFailure { XrLog.e("WORK task failed: $it", it) }
            left--
        }
        return tasks.size
    }

    val pending: Int
        get() = tasks.size

    fun clear() = tasks.clear()
}

/**
 * Resources shared by entities that come and go. The SDK releases a resource when its last user is destroyed
 * (Spatial Ruler), so shared ones are made global and closed when the scene goes away.
 */
internal class SharedResources {
    private val owned = ArrayList<Resource>()

    fun <T : Resource> keep(resource: T): T {
        resource.toGlobal()
        owned += resource
        return resource
    }

    fun drop(resource: Resource) {
        if (owned.remove(resource)) runCatching { resource.close() }
    }

    fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }
}

/** How a bone looks this frame (design `visual.states`). */
enum class BoneLook { NORMAL, SELECTED, GROUP, CORRECT, WRONG, PULSE, FOLLOW }

/**
 * The bone materials (design `visual.boneLook`): ivory (default, real CT shape, slight self-light so passthrough
 * does not eat it) or X-ray blue (dark base, cyan glow). Selected = brighter glow; group focus = the group colour
 * as glow (the design's outline: the SDK has no outline material, deviation logged); right / wrong flashes; the quiz
 * pulse is one material whose glow the scene breathes every frame.
 */
internal class BoneMaterials(private val shared: SharedResources) {
    private fun pbr(base: Color, glow: Color, glowK: Float, roughness: Float = 0.5f): PhysicallyBasedMaterial =
        shared.keep(
            PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
                setBaseColor(base.color4(1f))
                setMetallic(0f)
                setRoughness(roughness)
                setEmissiveColor(Color4(glow.red * glowK, glow.green * glowK, glow.blue * glowK, 1f))
                runCatching { setCullingMode(MaterialCullingMode.NONE) }
            }
        )

    val ivory = pbr(XrColors.BoneBase, XrColors.BoneBase, 0.15f)
    val ivorySelected = pbr(XrColors.BoneBase, XrColors.BoneIvory, 0.8f)
    val xray = pbr(XrColors.XrayBase, XrColors.XrayCyan, 0.55f, roughness = 0.35f)
    val xraySelected = pbr(XrColors.XrayBase, XrColors.XrayCyan, 1.0f, roughness = 0.35f)
    val carpal = pbr(XrColors.BoneBase, XrColors.CarpalAmber, 0.6f)
    val metacarpal = pbr(XrColors.BoneBase, XrColors.MetacarpalTeal, 0.6f)
    val phalanx = pbr(XrColors.BoneBase, XrColors.PhalanxBlue, 0.6f)
    val correct = pbr(XrColors.Passable, XrColors.Passable, 0.9f)
    val wrong = pbr(XrColors.Error, XrColors.Error, 0.9f)
    val follow = pbr(XrColors.BoneBase, XrColors.CarpalAmber, 0.7f)
    val pulse = pbr(XrColors.BoneBase, XrColors.CarpalAmber, 0.5f)

    /** 1.2 Hz breathing of the quiz target (design `entities3d.quizPulse`). */
    fun breathe(nowMs: Long) {
        val t = (nowMs % 833L) / 833f
        val k = 0.25f + 0.75f * (0.5f - 0.5f * kotlin.math.cos(t * 2f * Math.PI.toFloat()))
        val c = XrColors.CarpalAmber
        runCatching { pulse.setEmissiveColor(Color4(c.red * k, c.green * k, c.blue * k, 1f)) }
    }

    fun of(look: BoneLook, xrayLook: Boolean, group: io.embodit.xrayhand.domain.Group): Material =
        when (look) {
            BoneLook.NORMAL -> if (xrayLook) xray else ivory
            BoneLook.SELECTED -> if (xrayLook) xraySelected else ivorySelected
            BoneLook.GROUP ->
                when (group) {
                    io.embodit.xrayhand.domain.Group.CARPAL -> carpal
                    io.embodit.xrayhand.domain.Group.METACARPAL -> metacarpal
                    io.embodit.xrayhand.domain.Group.PHALANX -> phalanx
                }
            BoneLook.CORRECT -> correct
            BoneLook.WRONG -> wrong
            BoneLook.PULSE -> pulse
            BoneLook.FOLLOW -> follow
        }

    /** A bitmap-driven label material (crisp, masked). */
    fun textured(): UnlitMaterial =
        shared.keep(
            UnlitMaterial.create(BlendingMode.MASKED).apply {
                setBaseColor(Color4(1f, 1f, 1f, 1f))
                runCatching { setOpacityThreshold(0.5f) }
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
            }
        )

    val lead: Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(XrColors.White.color4(1f))
                setOpacity(0.8f)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    /**
     * The demo hand while it stands between the eyes and a panel (profile D20): every bone, whatever its highlight,
     * in this 30 % see-through material. Entity opacity alone is not enough: it is lost on bones whose material is
     * swapped for a highlight (QA #2 q2-1).
     */
    val faded: Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(XrColors.BoneIvory.color4(1f))
                // per surface well under 30 %: front faces only, and bones overlap in view (fingers over palm), so the
                // stacked result stays about 30 % (QA #2 retest: 0.3 two-sided read as nearly solid)
                setOpacity(FADED_SURFACE)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.BACK)
                setDepthWrite(false)
            }
        )

    /** The floor marker (x-ray cyan) and the ghost ring that follows the controller ray while placing. */
    val marker: Material = sheer(XrColors.XrayCyan, 0.9f)
    val ghost: Material = sheer(XrColors.XrayCyan, 0.45f)

    private fun sheer(c: Color, opacity: Float): Material =
        shared.keep(
            UnlitMaterial.create(BlendingMode.TRANSPARENT).apply {
                setBaseColor(c.color4(1f))
                setOpacity(opacity)
                runCatching { setApplyToneMapping(false) }
                setCullingMode(MaterialCullingMode.NONE)
                setDepthWrite(false)
            }
        )

    fun release(m: Material) = shared.drop(m)
}

/** Programmatic meshes: a label quad and a lead-line strip. */
internal object MeshFactory {
    private val FRONT = Vector3(0f, 0f, 1f)

    /** A unit quad in the local XY plane facing +Z: label plates, corners and UVs from [Billboard] (v = 1 on top). */
    fun unitQuad(name: String): MeshResource {
        val positions = Billboard.CORNERS.map { it.sdk() }
        val uv = Billboard.UV.map { (u, v) -> Vector2(u, v) }
        val model = MeshModel(positions = positions, triangleIndices = Billboard.TRIANGLES, normals = List(4) { FRONT }, uv0 = uv)
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.001f)), name = name)
    }

    /** Unit disc (radius 0.5) in the local XZ plane facing +Y. */
    fun unitDisc(name: String, segments: Int = 40): MeshResource {
        val positions = ArrayList<Vector3>(segments + 1)
        val indices = ArrayList<Int>(segments * 3)
        positions += Vector3(0f, 0f, 0f)
        for (i in 0 until segments) {
            val a = 2 * Math.PI * i / segments
            positions += Vector3((Math.cos(a) * 0.5).toFloat(), 0f, (Math.sin(a) * 0.5).toFloat())
        }
        for (i in 0 until segments) {
            indices += 0
            indices += 1 + (i + 1) % segments
            indices += 1 + i
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { Vector3(0f, 1f, 0f) })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Flat ring (inner [inner], outer 0.5) in the local XZ plane facing +Y (the floor marker). */
    fun unitRing(name: String, inner: Float = 0.38f, segments: Int = 48): MeshResource {
        val positions = ArrayList<Vector3>()
        val indices = ArrayList<Int>()
        for (i in 0 until segments) {
            val s0 = 2 * Math.PI * i / segments
            val e = 2 * Math.PI * (i + 1) / segments
            val b = positions.size
            positions += Vector3((Math.cos(s0) * inner).toFloat(), 0f, (Math.sin(s0) * inner).toFloat())
            positions += Vector3((Math.cos(s0) * 0.5).toFloat(), 0f, (Math.sin(s0) * 0.5).toFloat())
            positions += Vector3((Math.cos(e) * 0.5).toFloat(), 0f, (Math.sin(e) * 0.5).toFloat())
            positions += Vector3((Math.cos(e) * inner).toFloat(), 0f, (Math.sin(e) * inner).toFloat())
            indices += listOf(b, b + 2, b + 1, b, b + 3, b + 2)
        }
        val model = MeshModel(positions = positions, triangleIndices = indices, normals = List(positions.size) { Vector3(0f, 1f, 0f) })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0f, 0f, 0f), halfExtent = Vector3(0.5f, 0.001f, 0.5f)), name = name)
    }

    /** Strip x 0..1, y -0.5..0.5 facing +Z: scaled to any length and width (lead lines). */
    fun unitBand(name: String): MeshResource {
        val positions = listOf(Vector3(0f, -0.5f, 0f), Vector3(1f, -0.5f, 0f), Vector3(1f, 0.5f, 0f), Vector3(0f, 0.5f, 0f))
        val model = MeshModel(positions = positions, triangleIndices = listOf(0, 1, 2, 0, 2, 3), normals = List(4) { FRONT })
        return MeshResource.createWithMeshModel(model = model, bounds = BoundingBox(center = Vector3(0.5f, 0f, 0f), halfExtent = Vector3(0.5f, 0.5f, 0.002f)), name = name)
    }
}

internal class KitMeshes(shared: SharedResources) {
    val quad: MeshResource = shared.keep(MeshFactory.unitQuad("unitQuad"))
    val band: MeshResource = shared.keep(MeshFactory.unitBand("unitBand"))
    val ring: MeshResource = shared.keep(MeshFactory.unitRing("unitRing"))
    val disc: MeshResource = shared.keep(MeshFactory.unitDisc("unitDisc"))
}

/** A mesh entity on a shared unit mesh, placed by centre and size (the floor marker). */
internal class Part(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material).named(name)
    private var on = true

    init {
        parent.addChild(entity)
    }

    fun place(center: Vec3, size: Vec3, rotation: Quat4 = Quat4.IDENTITY) {
        entity.setLocal(position = center, rotation = rotation, scale = size)
        show(true)
    }

    fun show(visible: Boolean) {
        if (visible != on) {
            entity.enabled = visible
            on = visible
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/** A straight strip between two points with its face turned towards a viewer (lead lines of the group labels). */
internal class Strip(parent: Entity, mesh: MeshResource, material: Material, name: String) {
    private val entity: Entity = ModelEntity(mesh, material).named(name)
    private var on = true

    init {
        parent.addChild(entity)
        hide()
    }

    fun draw(from: Vec3, to: Vec3, width: Float, viewer: Vec3) {
        val full = to - from
        val length = full.length
        if (length < 1e-4f) {
            hide()
            return
        }
        val dir = full / length
        var z = (viewer - from).rejectFrom(dir)
        if (z.length < 1e-4f) z = Vec3.UP.rejectFrom(dir)
        z = z.normalized()
        entity.setLocal(position = from, rotation = Quat4.fromBasis(dir, z.cross(dir).normalized(), z), scale = Vec3(length, width, 1f))
        if (!on) {
            entity.enabled = true
            on = true
        }
    }

    fun hide() {
        if (on) {
            entity.enabled = false
            on = false
        }
    }

    fun destroy() {
        runCatching { entity.destroy(true) }
    }
}

/**
 * A bitmap-textured quad (bone labels, design `panels.boneLabel`): [heightM] tall per line of [Plates.LINE_PX]
 * pixels, turned about the vertical towards the viewer only (profile D10: never pitched, never mirrored). The
 * bitmap is re-made only when its text changes.
 */
internal class TextPlate(parent: Entity, meshes: KitMeshes, private val materials: BoneMaterials, private val queue: WorkQueue, name: String) {
    private val material: UnlitMaterial = materials.textured()
    private val entity: Entity = ModelEntity(meshes.quad, material).named(name)
    private var texture: TextureResource? = null
    private var key: String? = null
    private var aspect = 1f
    private var lines = 1f
    private var on = true

    /** Where the plate stands this frame (centre, Stage metres) and its height per line. */
    var center: Vec3 = Vec3.ZERO
    var heightM = 0.012f

    /** Half the plate's width in metres (for lead lines), valid once [ready]. */
    val halfWidthM: Float
        get() = heightM * lines * aspect / 2f

    init {
        parent.addChild(entity)
        show(false)
    }

    var ready = false
        private set

    fun content(key: String, paint: () -> Bitmap): Boolean {
        if (key == this.key) return false
        this.key = key
        queue.enqueue(this) {
            val bitmap = paint()
            aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            lines = bitmap.height.toFloat() / Plates.LINE_PX
            val old = texture
            val tex = runCatching { TextureResource.create(bitmap) }.onFailure { XrLog.e("PLATE texture failed: $it", it) }.getOrNull()
            if (tex != null) {
                material.setBaseColorTexture(tex)
                texture = tex
                ready = true
                old?.let { runCatching { it.close() } }
            }
            bitmap.recycle()
        }
        return true
    }

    /** Per frame: at [center], yaw towards [viewer], positive scale. */
    fun face(viewer: Vec3, opacity: Float = 1f) {
        if (!ready) return
        show(true)
        val h = heightM * lines
        entity.setLocal(position = center, rotation = Billboard.yawTowards(center, viewer), scale = Vec3(h * aspect, h, 1f))
        entity.setOpacity(opacity)
    }

    fun hide() = show(false)

    private fun show(v: Boolean) {
        if (v != on) {
            entity.enabled = v
            on = v
        }
    }

    val shown: Boolean
        get() = on

    fun destroy() {
        queue.cancel(this)
        runCatching { entity.destroy(true) }
        texture?.let { runCatching { it.close() } }
        texture = null
        materials.release(material)
    }
}

/** Label bitmaps: dark plate, group colour dot, name lines (design `panels.boneLabel`). */
internal object Plates {
    const val LINE_PX = 64
    private const val PAD = 22

    private fun paint(size: Float, color: Int, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /** [first] in 40 px bold, an optional smaller [second] line; a dot in [dot] on the left. */
    fun label(first: String, second: String?, dot: Color): Bitmap {
        val p1 = paint(40f, XrColors.White.toArgb(), bold = true)
        val p2 = paint(32f, XrColors.OnInkSecondary.toArgb(), bold = false)
        val dotW = 28
        val w = (PAD + dotW + max(p1.measureText(first), second?.let { p2.measureText(it) } ?: 0f) + PAD).toInt()
        val h = if (second == null) LINE_PX else LINE_PX * 2 - 8
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = XrColors.PlateInk.toArgb() })
        c.drawCircle(PAD + 8f, LINE_PX / 2f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot.toArgb() })
        c.drawText(first, (PAD + dotW).toFloat(), LINE_PX / 2f + 14f, p1)
        if (second != null) c.drawText(second, (PAD + dotW).toFloat(), LINE_PX + LINE_PX / 2f + 2f, p2)
        return bmp
    }
}
