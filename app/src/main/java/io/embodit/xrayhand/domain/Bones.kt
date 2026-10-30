package io.embodit.xrayhand.domain

import org.json.JSONObject

/** The three groups of the hand's 27 bones (design `bones`): carpals 8, metacarpals 5, phalanges 14. */
enum class Group(val key: String) {
    CARPAL("carpal"),
    METACARPAL("metacarpal"),
    PHALANX("phalanx");

    companion object {
        fun of(key: String): Group = entries.first { it.key == key }
    }
}

/** One bone: names and one fact in both languages, and the mesh bounds of the right-hand GLB node (bone-local metres). */
data class Bone(
    val id: String,
    val group: Group,
    val subgroup: String,
    val zh: String,
    val en: String,
    val factZh: String,
    val factEn: String,
    val boundsMin: Vec3,
    val boundsMax: Vec3,
) {
    /** Half the mesh's thickness across the bone axis (X / Z), metres. */
    val halfWidth: Float
        get() = maxOf(boundsMax.x - boundsMin.x, boundsMax.z - boundsMin.z) / 2f

    /** Radius of a sphere around the mesh centre that holds the whole mesh (carpals). */
    val radius: Float
        get() = (boundsMax - boundsMin).length / 2f

    val center: Vec3
        get() = (boundsMin + boundsMax) * 0.5f

    val isLong: Boolean
        get() = group != Group.CARPAL
}

/** The 27 bones in design order (`assets/bones.json`, built by `tools/make_bones_json.py`). */
class BoneCatalog(val bones: List<Bone>, val mnemonicZh: String, val mnemonicEn: String) {
    private val byId = bones.associateBy { it.id }

    operator fun get(id: String): Bone? = byId[id]

    fun of(group: Group): List<Bone> = bones.filter { it.group == group }

    companion object {
        fun parse(json: String): BoneCatalog {
            val root = JSONObject(json)
            val arr = root.getJSONArray("bones")
            val list =
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val b = o.getJSONObject("bounds")
                    fun v(key: String): Vec3 = b.getJSONArray(key).let { Vec3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
                    Bone(
                        id = o.getString("id"),
                        group = Group.of(o.getString("group")),
                        subgroup = o.optString("subgroup", ""),
                        zh = o.getString("zh"),
                        en = o.getString("en"),
                        factZh = o.getString("factZh"),
                        factEn = o.getString("factEn"),
                        boundsMin = v("min"),
                        boundsMax = v("max"),
                    )
                }
            val m = root.getJSONObject("mnemonic")
            // the English rhyme carries a Chinese gloss in brackets in the design package: keep the English part only
            return BoneCatalog(list, m.getString("zh"), m.getString("en").substringBefore('（').trim())
        }
    }
}
