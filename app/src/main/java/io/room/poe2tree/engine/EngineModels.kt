package io.room.poe2tree.engine

import io.room.poe2tree.tree.PassiveSpec
import org.json.JSONArray
import org.json.JSONObject

// Data exchanged with the Lua API (assets/engine). Text keeps Path of Building's colour codes
// (^0-^9, ^xRRGGBB); ui/PobText renders them.

/** The app's tree as the Lua API expects it (api.setTree). */
fun PassiveSpec.Snapshot.toEngineJson(): JSONObject {
    val nodeArr = JSONArray()
    for ((id, mode) in nodes) nodeArr.put(JSONObject().put("id", id).put("ws", mode))
    val attrArr = JSONArray()
    for ((id, attr) in attributes) attrArr.put(JSONObject().put("id", id).put("attr", attr))
    return JSONObject()
        .put("classId", classId)
        .put("ascendClassId", ascendClassId)
        .put("nodes", nodeArr)
        .put("attributes", attrArr)
}

/** One line of the build sidebar (Build.lua AddDisplayStatList). */
data class SidebarRow(
    val type: Type,
    /** Label with colour codes, without the trailing colon (stat rows). */
    val label: String = "",
    /** Formatted value with colour codes (stat rows). */
    val value: String = "",
    /** Text of header and info rows. */
    val text: String = "",
    /** Output key, e.g. "Life", "TotalDPS" (stat rows). */
    val stat: String? = null,
    /** Key inside the output table [stat], for a few rows. */
    val childStat: String? = null,
    /** "player" or "minion". */
    val actor: String? = null,
    val raw: Double? = null,
    val warn: Boolean = false,
    /** PoB shows a breakdown for this stat. */
    val hasBreakdown: Boolean = false,
) {
    enum class Type { Stat, Separator, Header, Info }
}

data class Sidebar(val rows: List<SidebarRow>, val warnings: List<String>) {
    /** Value of a player stat, if shown. */
    fun raw(stat: String, actor: String = "player"): Double? =
        rows.firstOrNull { it.type == SidebarRow.Type.Stat && it.stat == stat && it.actor == actor }?.raw

    fun row(stat: String, actor: String = "player"): SidebarRow? =
        rows.firstOrNull { it.type == SidebarRow.Type.Stat && it.stat == stat && it.actor == actor }
}

data class SocketGroupChoice(
    val label: String,
    val enabled: Boolean,
    val includeInFullDPS: Boolean,
    val weaponSet: String,
    val source: String?,
)

data class MinionChoice(val label: String, val minionId: String?, val itemSetId: Int?)

/** The main skill selection of the sidebar (Build.lua RefreshSkillSelectControls). */
data class SkillSelection(
    val mainSocketGroup: Int,
    val groups: List<SocketGroupChoice>,
    val mainActiveSkill: Int,
    val activeSkills: List<String>,
    val statSets: List<String>,
    val statSet: Int,
    val parts: List<String>,
    val skillPart: Int,
    val stageCount: Int?,
    val hasMineCount: Boolean,
    val mineCount: Int?,
    val minions: List<MinionChoice>,
    val minionId: String?,
    val minionItemSetId: Int?,
    val minionSkills: List<String>,
    val minionSkill: Int,
    val minionSkillStatSets: List<String>,
    val minionSkillStatSet: Int,
    val useSecondWeaponSet: Boolean,
)

/** What the sidebar shows after each change. */
data class EngineState(
    val sidebar: Sidebar,
    val selection: SkillSelection,
    val level: Int,
    val levelAuto: Boolean,
    /** Tree nodes PoB did not accept (not connected under its rules). */
    val droppedNodes: List<Int>,
    /** Opening the build changed it (tree or level differed from its XML). */
    val changed: Boolean = false,
    /** What PoB's tabs can undo / redo, by tab ("skills", "items", "config", "calcs"). */
    val undo: Map<String, UndoFlags> = emptyMap(),
    /** Jewels and items acting on the tree (api.treeOverlay), read with parseTreeJewels. */
    val overlay: JSONObject? = null,
)

data class UndoFlags(val undo: Boolean, val redo: Boolean)

/** Stat changes shown for a node (PassiveTreeView tooltip). */
data class NodeCompare(
    val lines: List<String>,
    val count: Int,
    /** PoB's text for the node (name, stats as changed by jewels...; "" separates sections). */
    val info: List<String> = emptyList(),
)

object EngineJson {

    fun state(o: JSONObject): EngineState {
        val tree = o.optJSONObject("tree")
        return EngineState(
            sidebar = sidebar(o.getJSONObject("sidebar")),
            selection = selection(o.getJSONObject("selection")),
            level = o.optInt("level", 1),
            levelAuto = o.optBoolean("levelAuto"),
            droppedNodes = tree?.optJSONArray("dropped")?.ints().orEmpty(),
            changed = o.optBoolean("changed"),
            overlay = o.optJSONObject("overlay"),
            undo = o.optJSONObject("undo")?.let { u ->
                u.keys().asSequence().associateWith { k -> u.getJSONObject(k).let { UndoFlags(it.optBoolean("undo"), it.optBoolean("redo")) } }
            }.orEmpty(),
        )
    }

    fun sidebar(o: JSONObject): Sidebar {
        val rows = ArrayList<SidebarRow>()
        val arr = o.optJSONArray("rows") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val type = when (r.optString("type")) {
                "stat" -> SidebarRow.Type.Stat
                "header" -> SidebarRow.Type.Header
                "info" -> SidebarRow.Type.Info
                else -> SidebarRow.Type.Separator
            }
            rows += SidebarRow(
                type = type,
                label = r.optString("label"),
                value = r.optString("value"),
                text = r.optString("text"),
                stat = r.optStringOrNull("stat"),
                childStat = r.optStringOrNull("childStat"),
                actor = r.optStringOrNull("actor"),
                raw = r.optDoubleOrNull("raw"),
                warn = r.optBoolean("warn"),
                hasBreakdown = r.optBoolean("breakdown"),
            )
        }
        return Sidebar(rows, o.optJSONArray("warnings")?.strings().orEmpty())
    }

    fun selection(o: JSONObject): SkillSelection {
        val groups = ArrayList<SocketGroupChoice>()
        o.optJSONArray("groups")?.let { arr ->
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                groups += SocketGroupChoice(
                    label = g.optString("label"),
                    enabled = g.optBoolean("enabled"),
                    includeInFullDPS = g.optBoolean("includeInFullDPS"),
                    weaponSet = g.optString("weaponSet"),
                    source = g.optStringOrNull("source"),
                )
            }
        }
        val minions = ArrayList<MinionChoice>()
        o.optJSONArray("minions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                minions += MinionChoice(
                    label = m.optString("label"),
                    minionId = m.optStringOrNull("minionId"),
                    itemSetId = if (m.has("itemSetId")) m.optInt("itemSetId") else null,
                )
            }
        }
        return SkillSelection(
            mainSocketGroup = o.optInt("mainSocketGroup", 1),
            groups = groups,
            mainActiveSkill = o.optInt("mainActiveSkill", 1),
            activeSkills = o.optJSONArray("activeSkills")?.strings().orEmpty(),
            statSets = o.optJSONArray("statSets")?.strings().orEmpty(),
            statSet = o.optInt("statSet", 1),
            parts = o.optJSONArray("parts")?.strings().orEmpty(),
            skillPart = o.optInt("skillPart", 1),
            stageCount = if (o.has("stageCount")) o.optInt("stageCount") else null,
            hasMineCount = o.optBoolean("hasMineCount"),
            mineCount = if (o.has("mineCount")) o.optInt("mineCount") else null,
            minions = minions,
            minionId = o.optStringOrNull("minionId"),
            minionItemSetId = if (o.has("minionItemSetId")) o.optInt("minionItemSetId") else null,
            minionSkills = o.optJSONArray("minionSkills")?.strings().orEmpty(),
            minionSkill = o.optInt("minionSkill", 1),
            minionSkillStatSets = o.optJSONArray("minionSkillStatSets")?.strings().orEmpty(),
            minionSkillStatSet = o.optInt("minionSkillStatSet", 1),
            useSecondWeaponSet = o.optBoolean("useSecondWeaponSet"),
        )
    }

    fun nodeCompare(o: JSONObject) = NodeCompare(
        o.optJSONArray("lines")?.strings().orEmpty(), o.optInt("count"), o.optJSONArray("info")?.strings().orEmpty(),
    )
}

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

internal fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

internal fun JSONArray.ints(): List<Int> = (0 until length()).map { optInt(it) }

/** A statistic for the node power heat map (PoB TreeTab.powerStatList; the first is offence / defence). */
data class PowerStat(val label: String, val stat: String?)

/** Power of a node: [single] for a single statistic, [offence] / [defence] for the combined map. */
data class NodePower(val single: Double, val offence: Double, val defence: Double)

/** A line of PoB's power report. */
data class PowerReportEntry(
    val id: Int,
    val name: String,
    val type: String,
    val power: Double,
    val powerStr: String,
    val pathPowerStr: String,
    val pathDist: Int,
    val allocated: Boolean,
)

/** PoB's node powers (CalcsTab:PowerBuilder) for one statistic, with the maxima used for colours. */
data class PowerResult(
    val single: Boolean,
    val maxSingle: Double,
    val maxOffence: Double,
    val maxDefence: Double,
    /** By node id. */
    val nodes: Map<Int, NodePower>,
    val report: List<PowerReportEntry>,
) {
    companion object {
        fun parse(o: JSONObject): PowerResult {
            val max = o.optJSONObject("max") ?: JSONObject()
            val nodes = HashMap<Int, NodePower>()
            o.optJSONArray("nodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val n = arr.getJSONObject(i)
                    nodes[n.getInt("id")] = NodePower(n.optDouble("single", 0.0), n.optDouble("offence", 0.0), n.optDouble("defence", 0.0))
                }
            }
            val report = o.optJSONArray("report")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    PowerReportEntry(
                        r.getInt("id"), r.optString("name"), r.optString("type"), r.optDouble("power", 0.0),
                        r.optString("powerStr"), r.optString("pathPowerStr"), r.optInt("pathDist"), r.optBoolean("allocated"),
                    )
                }
            }.orEmpty()
            return PowerResult(
                o.optBoolean("single"), max.optDouble("singleStat", 0.0), max.optDouble("offence", 0.0), max.optDouble("defence", 0.0),
                nodes, report,
            )
        }
    }
}
