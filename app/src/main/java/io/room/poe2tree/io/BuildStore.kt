package io.room.poe2tree.io

import io.room.poe2tree.tree.PassiveSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Point limits. Mirrors PoB's estimate:
 *  - main pool   = (level - 1) + quest points + extra points
 *  - weapon sets = quest points + extra weapon-set points (each)
 *  - ascendancy  = ascendancy points
 * PoE2 quests give 24 weapon-set passive points which PoB also counts in the main pool.
 */
data class PointSettings(
    val level: Int = 100,
    val questPoints: Int = MAX_QUEST_POINTS,
    val extraPoints: Int = 0,
    val extraWeaponSetPoints: Int = 0,
    val ascendancyPoints: Int = 8,
) {
    val normalMax get() = (level - 1) + questPoints + extraPoints
    val weaponSetMax get() = questPoints + extraWeaponSetPoints

    fun toJson() = JSONObject()
        .put("level", level)
        .put("questPoints", questPoints)
        .put("extraPoints", extraPoints)
        .put("extraWeaponSetPoints", extraWeaponSetPoints)
        .put("ascendancyPoints", ascendancyPoints)

    companion object {
        const val MAX_QUEST_POINTS = 24

        fun fromJson(o: JSONObject?) = if (o == null) PointSettings() else PointSettings(
            level = o.optInt("level", 100).coerceIn(1, 100),
            questPoints = o.optInt("questPoints", MAX_QUEST_POINTS).coerceIn(0, 99),
            extraPoints = o.optInt("extraPoints", 0).coerceIn(0, 999),
            extraWeaponSetPoints = o.optInt("extraWeaponSetPoints", 0).coerceIn(0, 999),
            ascendancyPoints = o.optInt("ascendancyPoints", 8).coerceIn(0, 99),
        )
    }
}

data class SavedBuild(
    val id: String,
    val name: String,
    val updatedAt: Long,
    val snapshot: PassiveSpec.Snapshot,
    val settings: PointSettings,
) {
    fun toJson(): JSONObject {
        val nodes = JSONArray()
        for ((id, mode) in snapshot.nodes) nodes.put(JSONArray().put(id).put(mode))
        val attrs = JSONObject()
        for ((id, a) in snapshot.attributes) attrs.put(id.toString(), a)
        return JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("name", name)
            .put("updatedAt", updatedAt)
            .put("classId", snapshot.classId)
            .put("ascendClassId", snapshot.ascendClassId)
            .put("nodes", nodes)
            .put("attributes", attrs)
            .put("settings", settings.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): SavedBuild {
            val nodes = LinkedHashMap<Int, Int>()
            val arr = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                nodes[pair.getInt(0)] = pair.optInt(1, 0)
            }
            val attrs = LinkedHashMap<Int, Int>()
            o.optJSONObject("attributes")?.let { a -> for (k in a.keys()) attrs[k.toInt()] = a.getInt(k) }
            return SavedBuild(
                id = o.getString("id"),
                name = o.optString("name", "Build"),
                updatedAt = o.optLong("updatedAt", 0),
                snapshot = PassiveSpec.Snapshot(o.optInt("classId", 2), o.optInt("ascendClassId", 0), nodes, attrs),
                settings = PointSettings.fromJson(o.optJSONObject("settings")),
            )
        }
    }
}

/**
 * Stores each build as a JSON file in the app's private storage: the passive tree and point settings,
 * plus the build's Path of Building XML (items, skills, configuration) in a separate file.
 */
class BuildStore(baseDir: File) {
    private val dir = File(baseDir, "builds").apply { mkdirs() }
    private val stateFile = File(baseDir, "state.json")

    fun list(): List<SavedBuild> = dir.listFiles { f -> f.extension == "json" }
        .orEmpty()
        .mapNotNull { f -> runCatching { SavedBuild.fromJson(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    fun load(id: String): SavedBuild? = fileFor(id).takeIf { it.exists() }
        ?.let { runCatching { SavedBuild.fromJson(JSONObject(it.readText())) }.getOrNull() }

    fun save(build: SavedBuild) = writeAtomic(fileFor(build.id), build.toJson().toString())

    fun exists(id: String) = fileFor(id).exists()

    fun delete(id: String) {
        fileFor(id).delete()
        xmlFor(id).delete()
    }

    /** The build's Path of Building XML, or null if it has none (a tree-only build). */
    fun loadXml(id: String): String? = xmlFor(id).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun saveXml(id: String, xml: String) = writeAtomic(xmlFor(id), xml)

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    var lastBuildId: String?
        get() = runCatching { JSONObject(stateFile.readText()).optString("lastBuildId").takeIf { it.isNotEmpty() } }.getOrNull()
        set(value) {
            stateFile.writeText(JSONObject().put("lastBuildId", value ?: "").toString())
        }

    private fun fileFor(id: String) = File(dir, "$id.json")
    private fun xmlFor(id: String) = File(dir, "$id.pob.xml")

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
