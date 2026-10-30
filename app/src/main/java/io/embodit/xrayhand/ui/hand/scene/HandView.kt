package io.embodit.xrayhand.ui.hand.scene

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.resource.Material
import io.embodit.xrayhand.domain.Bone
import io.embodit.xrayhand.domain.BoneCatalog
import io.embodit.xrayhand.domain.BonePose
import io.embodit.xrayhand.domain.Quat4
import io.embodit.xrayhand.domain.Vec3
import io.embodit.xrayhand.platform.XrLog
import io.embodit.xrayhand.ui.hand.HandId
import kotlin.math.abs

/**
 * One hand of 27 NIH bones (design `container.notes`): a `boneNode` per bone that is never scaled and carries the
 * collider, `InteractableComponent` and `HoverEffectComponent` (so the entity the system names in a tap is the one it
 * highlights), with the GLB's mesh node moved below it and scaled to the joints.
 *
 * The left hand uses `hand_bones_left.glb`, which the export baked as the point mirror of the right mesh in each
 * node's frame (its nodes carry an extra 180 degree turn about X); [mirror] puts that turn on the mesh child so that the
 * mesh becomes the X-mirror of the right bone in the node frame the solver computes.
 */
internal class HandView(val id: HandId, parent: Entity, glb: Entity, private val mirror: Boolean, catalog: BoneCatalog) {
    val root: Entity = Entity().named("hand:${id.name.lowercase()}")

    inner class Node(val bone: Bone, val node: Entity, val mesh: Entity?) {
        var length = -1f
        var radius = -1f
        var material: Material? = null
        var opacity = 1f
        var pose: BonePose? = null
    }

    private val nodes = LinkedHashMap<String, Node>()
    private val byEntity = HashMap<Long, String>()
    private var shown = true
    var missing = 0
        private set

    init {
        parent.addChild(root)
        for (bone in catalog.bones) {
            val node = Entity().named("bone:${id.name.lowercase()}:${bone.id}")
            node.components.set(InteractableComponent())
            runCatching { node.components.set(HoverEffectComponent()) }.onFailure { XrLog.w("HOVER effect failed: $it") }
            root.addChild(node)
            val mesh = find(glb, bone.id)
            if (mesh == null) {
                missing++
                XrLog.e("MODEL bone ${bone.id} not found in GLB for $id")
            } else {
                runCatching { mesh.removeFromParent() }
                node.addChild(mesh)
                mesh.setLocal(position = Vec3.ZERO, rotation = if (mirror) FLIP_X else Quat4.IDENTITY, scale = Vec3(1f, 1f, 1f))
            }
            nodes[bone.id] = Node(bone, node, mesh)
            byEntity[node.id] = bone.id
            mesh?.let { m -> collectIds(m, bone.id) }
        }
        runCatching { glb.destroy(true) }
        XrLog.i("MODEL hand=$id bones=${nodes.size} missing=$missing mirror=$mirror")
    }

    private fun collectIds(e: Entity, boneId: String) {
        byEntity[e.id] = boneId
        runCatching { e.getChildren() }.getOrNull()?.forEach { collectIds(it, boneId) }
    }

    private fun find(e: Entity, name: String): Entity? {
        if (runCatching { e.getName() }.getOrNull() == name) return e
        for (c in runCatching { e.getChildren() }.getOrNull() ?: emptyArray()) find(c, name)?.let { return it }
        return null
    }

    /** The bone id an entity of this hand stands for (the node or anything below it), or null. */
    fun boneOf(entity: Entity): String? = byEntity[entity.id]

    fun node(boneId: String): Node? = nodes[boneId]

    val all: Collection<Node>
        get() = nodes.values

    val visible: Boolean
        get() = shown

    /** Writes this frame's poses; null hides the hand. */
    fun apply(poses: List<BonePose>?) {
        if (poses == null) {
            show(false)
            return
        }
        show(true)
        for (p in poses) {
            val n = nodes[p.id] ?: continue
            n.pose = p
            n.node.setLocal(position = p.position, rotation = p.rotation)
            n.mesh?.setLocal(position = p.meshOffset, scale = p.meshScale)
            if (abs(p.length - n.length) > RESHAPE_M || abs(p.radius - n.radius) > RESHAPE_M) {
                n.length = p.length
                n.radius = p.radius
                val shape =
                    if (n.bone.isLong) boxCollision(Vec3(p.radius * 2f, p.length, p.radius * 2f)) else sphereCollision(p.radius)
                runCatching { n.node.components.set(shape) }.onFailure { XrLog.w("COLLIDER ${p.id} failed: $it") }
            }
        }
    }

    fun look(boneId: String, material: Material, opacity: Float) {
        val n = nodes[boneId] ?: return
        if (n.material !== material) {
            n.material = material
            n.mesh?.setMaterialDeep(material)
        }
        if (n.opacity != opacity) {
            n.opacity = opacity
            n.node.setOpacity(opacity)
        }
    }

    private var fadeAlpha = 1f

    /**
     * Whole-hand opacity (profile D20: 30 % while between the eyes and a panel), multiplied into every bone's own
     * dimming: an opacity on the hand's root entity did not reach the bones (emulator, SDK 6.1.9).
     */
    fun fade(alpha: Float) {
        if (alpha == fadeAlpha) return
        fadeAlpha = alpha
        // the look comes from the faded material (XrScene.stepLooks); here only the pinch targets
        // a faded hand stands in front of a panel: it must not take the pinches meant for the panel behind it
        interactive(alpha >= 1f)
    }

    private var interactive = true
    private var blocked: Set<String> = emptySet()
    private val nodeOn = HashMap<String, Boolean>()

    private fun interactive(on: Boolean) {
        if (on == interactive) return
        interactive = on
        applyInteractive()
        XrLog.i("HAND ${id.name.lowercase()} interactive=$on")
    }

    /**
     * Profile D22: bones that stand between the eyes and a panel take no pinches, so a gaze + pinch on a panel control
     * behind the user's own hand clicks the control (the system names the nearest target on the gaze line).
     */
    fun yieldTo(ids: Set<String>) {
        if (ids == blocked) return
        if (ids.isNotEmpty() || blocked.isNotEmpty()) XrLog.i("HAND ${id.name.lowercase()} yield=${ids.size} ${ids.sorted().take(6)}")
        blocked = ids
        applyInteractive()
    }

    private fun applyInteractive() {
        for (n in nodes.values) {
            val on = interactive && n.bone.id !in blocked
            if (nodeOn[n.bone.id] == on) continue
            nodeOn[n.bone.id] = on
            runCatching {
                if (on) n.node.components.set(InteractableComponent()) else n.node.components.remove(InteractableComponent::class.java)
            }.onFailure { XrLog.w("INTERACTABLE ${n.bone.id} on=$on failed: $it") }
        }
    }

    val faded: Float
        get() = fadeAlpha

    private fun show(v: Boolean) {
        if (v != shown) {
            root.enabled = v
            shown = v
        }
    }

    fun destroy() {
        runCatching { root.destroy(true) }
        nodes.clear()
        byEntity.clear()
    }

    private companion object {
        val FLIP_X = Quat4(1f, 0f, 0f, 0f)
        const val RESHAPE_M = 0.002f
    }
}
