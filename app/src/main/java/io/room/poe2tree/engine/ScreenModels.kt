package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject

// Data of the Calcs, Config, Skills and Items screens (assets/engine/api/*.lua).

// ---- Calcs ----

data class CalcCell(val text: String, val key: String?)
data class CalcRow(val label: String?, val labelColor: String?, val cells: List<CalcCell>)
data class CalcSubsection(val label: String, val collapsed: Boolean, val extra: String?, val rows: List<CalcRow>)
data class CalcSection(val id: String, val colour: String?, val subsections: List<CalcSubsection>)

data class BuffMode(val label: String, val mode: String)

/** The Calcs tab's own skill selection and calculation mode. */
data class CalcsSelection(
    val socketGroup: Int,
    val groups: List<String>,
    val mainActiveSkill: Int,
    val activeSkills: List<String>,
    val statSets: List<String>,
    val statSet: Int,
    val parts: List<String>,
    val skillPart: Int,
    val stageCount: Int?,
    val hasMineCount: Boolean,
    val mineCount: Int?,
    val minionSkills: List<String>,
    val minionSkill: Int,
    val buffMode: String,
    val buffModes: List<BuffMode>,
    val showMinion: Boolean,
    val hasMinion: Boolean,
    val buffList: String?,
    val combatList: String?,
    val curseList: String?,
)

data class BreakdownColumn(val label: String, val right: Boolean)
data class BreakdownRow(val cells: List<String>, val nodeId: Int?, val sourceTotals: List<String>)

sealed interface BreakdownSection {
    data class Lines(val lines: List<String>) : BreakdownSection
    data class Table(val label: String?, val footer: String?, val columns: List<BreakdownColumn>, val rows: List<BreakdownRow>) : BreakdownSection
}

data class Breakdown(val title: String?, val value: String?, val sections: List<BreakdownSection>)

// ---- Config ----

data class ConfigRow(
    val idx: Int,
    val variable: String?,
    /** check, count, countAllowZero, integer, float, list, text or label (a sub-heading). */
    val type: String,
    val label: String,
    val checked: Boolean,
    val choices: List<String>,
    val selected: Int,
    val number: Double?,
    val placeholder: Double?,
    val text: String,
    val modified: Boolean,
    val invalid: Boolean,
    val enabled: Boolean,
    val tooltip: String?,
)

data class ConfigSection(val name: String, val rows: List<ConfigRow>)
data class ConfigSetInfo(val id: Int, val title: String, val active: Boolean)
data class CustomModLine(val text: String, val supported: Boolean)
data class CustomModBlock(val title: String, val enabled: Boolean, val text: String, val lines: List<CustomModLine>)
data class ConfigData(val sections: List<ConfigSection>, val sets: List<ConfigSetInfo>, val customMods: List<CustomModBlock>)

// ---- Skills ----

data class GemInfo(
    val name: String,
    val level: Int,
    val quality: Int,
    val effectiveLevel: Int?,
    val effectiveQuality: Int?,
    val enabled: Boolean,
    val count: Int,
    val corrupted: Boolean,
    val corruptLevel: Int,
    val colour: String,
    val support: Boolean,
    val errMsg: String?,
    val active: Boolean,
    val reason: String?,
    val locked: Boolean,
)

data class ActiveSkillInfo(val name: String, val disabled: Boolean, val disableReason: String?)

data class SkillGroup(
    val label: String,
    val displayLabel: String,
    val enabled: Boolean,
    val includeInFullDPS: Boolean,
    val isMain: Boolean,
    val source: String?,
    val sourceName: String?,
    val deletable: Boolean,
    val set1: Boolean,
    val set2: Boolean,
    val weaponSet: String,
    val weaponSetLocked: Boolean,
    val activeSkills: List<ActiveSkillInfo>,
    val gems: List<GemInfo>,
)

data class SkillSetInfo(val id: Int, val title: String)
data class SkillsData(val mainSocketGroup: Int, val activeSkillSetId: Int, val skillSets: List<SkillSetInfo>, val groups: List<SkillGroup>)
data class GemChoice(val gemId: String, val name: String, val support: Boolean, val colour: String, val tags: String?)

// ---- Items ----

data class ItemSlot(
    val name: String,
    val label: String,
    val itemId: Int,
    val item: String?,
    val nodeId: Int?,
    val canActivate: Boolean,
    val active: Boolean,
    val candidates: List<Int>,
)

data class ItemInfo(val id: Int, val name: String, val base: String?, val type: String?, val rarity: String?, val equipped: List<String>)
data class ItemSetInfo(val id: Int, val title: String)

data class ItemsData(
    val activeItemSetId: Int,
    val useSecondWeaponSet: Boolean,
    val itemSets: List<ItemSetInfo>,
    val slots: List<ItemSlot>,
    val items: List<ItemInfo>,
) {
    fun item(id: Int) = items.firstOrNull { it.id == id }
}

data class TooltipLine(val text: String?, val separator: Boolean)
data class ItemTooltip(val title: String, val rarity: String?, val lines: List<TooltipLine>, val raw: String)

object ScreenJson {

    private fun JSONObject.intOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONObject.objects(key: String): List<JSONObject> =
        optJSONArray(key)?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()

    fun calcSections(arr: JSONArray): List<CalcSection> = (0 until arr.length()).map { i ->
        val s = arr.getJSONObject(i)
        CalcSection(
            id = s.optString("id"),
            colour = s.optStringOrNull("colour"),
            subsections = s.objects("subsections").map { sub ->
                CalcSubsection(
                    label = sub.optString("label"),
                    collapsed = sub.optBoolean("collapsed"),
                    extra = sub.optStringOrNull("extra"),
                    rows = sub.objects("rows").map { r ->
                        CalcRow(
                            label = r.optStringOrNull("label"),
                            labelColor = r.optStringOrNull("labelColor"),
                            cells = r.objects("cells").map { c -> CalcCell(c.optString("text"), c.optStringOrNull("key")) },
                        )
                    },
                )
            },
        )
    }

    fun calcsSelection(o: JSONObject) = CalcsSelection(
        socketGroup = o.optInt("socketGroup", 1),
        groups = o.objects("groups").map { it.optString("label") },
        mainActiveSkill = o.optInt("mainActiveSkill", 1),
        activeSkills = o.optJSONArray("activeSkills")?.strings().orEmpty(),
        statSets = o.optJSONArray("statSets")?.strings().orEmpty(),
        statSet = o.optInt("statSet", 1),
        parts = o.optJSONArray("parts")?.strings().orEmpty(),
        skillPart = o.optInt("skillPart", 1),
        stageCount = o.intOrNull("stageCount"),
        hasMineCount = o.optBoolean("hasMineCount"),
        mineCount = o.intOrNull("mineCount"),
        minionSkills = o.optJSONArray("minionSkills")?.strings().orEmpty(),
        minionSkill = o.optInt("minionSkill", 1),
        buffMode = o.optString("buffMode", "EFFECTIVE"),
        buffModes = o.objects("buffModes").map { BuffMode(it.optString("label"), it.optString("mode")) },
        showMinion = o.optBoolean("showMinion"),
        hasMinion = o.optBoolean("hasMinion"),
        buffList = o.optStringOrNull("buffList")?.takeIf { it.isNotBlank() },
        combatList = o.optStringOrNull("combatList")?.takeIf { it.isNotBlank() },
        curseList = o.optStringOrNull("curseList")?.takeIf { it.isNotBlank() },
    )

    fun breakdown(o: JSONObject) = Breakdown(
        title = o.optStringOrNull("title"),
        value = o.optStringOrNull("value"),
        sections = o.objects("sections").mapNotNull { s ->
            when (s.optString("type")) {
                "text" -> BreakdownSection.Lines(s.optJSONArray("lines")?.strings().orEmpty())
                "table" -> BreakdownSection.Table(
                    label = s.optStringOrNull("label"),
                    footer = s.optStringOrNull("footer"),
                    columns = s.objects("columns").map { BreakdownColumn(it.optString("label"), it.optBoolean("right")) },
                    rows = s.objects("rows").map { r ->
                        BreakdownRow(
                            cells = r.optJSONArray("cells")?.strings().orEmpty(),
                            nodeId = r.intOrNull("nodeId"),
                            sourceTotals = r.optJSONArray("sourceTotals")?.strings().orEmpty(),
                        )
                    },
                )
                else -> null
            }
        },
    )

    fun config(o: JSONObject) = ConfigData(
        sections = o.objects("sections").map { s ->
            ConfigSection(
                name = s.optString("name"),
                rows = s.objects("rows").map { r ->
                    ConfigRow(
                        idx = r.optInt("idx"),
                        variable = r.optStringOrNull("var"),
                        type = r.optString("type", "label"),
                        label = r.optString("label"),
                        checked = r.optBoolean("value"),
                        choices = r.optJSONArray("choices")?.strings().orEmpty(),
                        selected = r.optInt("selected", 1),
                        number = r.optDoubleOrNull("number"),
                        placeholder = r.optDoubleOrNull("placeholder"),
                        text = r.optString("text"),
                        modified = r.optBoolean("modified"),
                        invalid = r.optBoolean("invalid"),
                        enabled = r.optBoolean("enabled", true),
                        tooltip = r.optStringOrNull("tooltip"),
                    )
                },
            )
        },
        sets = o.objects("sets").map { ConfigSetInfo(it.optInt("id"), it.optString("title"), it.optBoolean("active")) },
        customMods = customMods(o.optJSONArray("customMods") ?: JSONArray()),
    )

    fun customMods(arr: JSONArray): List<CustomModBlock> = (0 until arr.length()).map { i ->
        val b = arr.getJSONObject(i)
        CustomModBlock(
            title = b.optString("title"),
            enabled = b.optBoolean("enabled", true),
            text = b.optString("text"),
            lines = b.objects("lines").map { CustomModLine(it.optString("text"), it.optBoolean("supported", true)) },
        )
    }

    fun skills(o: JSONObject) = SkillsData(
        mainSocketGroup = o.optInt("mainSocketGroup", 1),
        activeSkillSetId = o.optInt("activeSkillSetId", 1),
        skillSets = o.objects("skillSets").map { SkillSetInfo(it.optInt("id"), it.optString("title")) },
        groups = o.objects("groups").map { g ->
            SkillGroup(
                label = g.optString("label"),
                displayLabel = g.optString("displayLabel"),
                enabled = g.optBoolean("enabled"),
                includeInFullDPS = g.optBoolean("includeInFullDPS"),
                isMain = g.optBoolean("isMain"),
                source = g.optStringOrNull("source"),
                sourceName = g.optStringOrNull("sourceName"),
                deletable = g.optBoolean("deletable", true),
                set1 = g.optBoolean("set1", true),
                set2 = g.optBoolean("set2", true),
                weaponSet = g.optString("weaponSet"),
                weaponSetLocked = g.optBoolean("weaponSetLocked"),
                activeSkills = g.objects("activeSkills").map {
                    ActiveSkillInfo(it.optString("name"), it.optBoolean("disabled"), it.optStringOrNull("disableReason"))
                },
                gems = g.objects("gems").map { gem ->
                    GemInfo(
                        name = gem.optString("name"),
                        level = gem.optInt("level", 1),
                        quality = gem.optInt("quality"),
                        effectiveLevel = gem.intOrNull("effectiveLevel"),
                        effectiveQuality = gem.intOrNull("effectiveQuality"),
                        enabled = gem.optBoolean("enabled", true),
                        count = gem.optInt("count", 1),
                        corrupted = gem.optBoolean("corrupted"),
                        corruptLevel = gem.optInt("corruptLevel"),
                        colour = gem.optString("colour"),
                        support = gem.optBoolean("support"),
                        errMsg = gem.optStringOrNull("errMsg"),
                        active = gem.optString("status") == "active",
                        reason = gem.optStringOrNull("reason"),
                        locked = gem.optBoolean("locked"),
                    )
                },
            )
        },
    )

    fun gemChoices(arr: JSONArray): List<GemChoice> = (0 until arr.length()).map { i ->
        val g = arr.getJSONObject(i)
        GemChoice(g.optString("gemId"), g.optString("name"), g.optBoolean("support"), g.optString("colour"), g.optStringOrNull("tags"))
    }

    fun items(o: JSONObject) = ItemsData(
        activeItemSetId = o.optInt("activeItemSetId", 1),
        useSecondWeaponSet = o.optBoolean("useSecondWeaponSet"),
        itemSets = o.objects("itemSets").map { ItemSetInfo(it.optInt("id"), it.optString("title")) },
        slots = o.objects("slots").map { s ->
            ItemSlot(
                name = s.optString("name"),
                label = s.optString("label"),
                itemId = s.optInt("itemId"),
                item = s.optStringOrNull("item"),
                nodeId = s.intOrNull("nodeId"),
                canActivate = s.optBoolean("canActivate"),
                active = s.optBoolean("active"),
                candidates = s.optJSONArray("candidates")?.ints().orEmpty(),
            )
        },
        items = o.objects("items").map { it ->
            ItemInfo(
                id = it.optInt("id"),
                name = it.optString("name"),
                base = it.optStringOrNull("base"),
                type = it.optStringOrNull("type"),
                rarity = it.optStringOrNull("rarity"),
                equipped = it.optJSONArray("equipped")?.strings().orEmpty(),
            )
        },
    )

    fun itemTooltip(o: JSONObject) = ItemTooltip(
        title = o.optString("title"),
        rarity = o.optStringOrNull("rarity"),
        lines = o.objects("lines").map { l -> TooltipLine(l.optStringOrNull("text"), l.optBoolean("separator")) },
        raw = o.optString("raw"),
    )
}
