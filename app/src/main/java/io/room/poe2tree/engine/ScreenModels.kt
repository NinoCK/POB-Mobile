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

data class ItemInfo(
    val id: Int, val name: String, val title: String?, val base: String?, val type: String?, val rarity: String?, val equipped: List<String>,
    /** The rune (or soul core, talisman, ...) in each of the item's sockets, "" for an empty one. */
    val sockets: List<String> = emptyList(),
)
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

/** A control of PoB's item editor or of one of its popups (see engine/api/Craft.lua). */
data class CraftControl(
    val name: String,
    /** "dropdown", "edit", "slider", "button", "check", "label" or "list" */
    val kind: String,
    val enabled: Boolean,
    val label: String,
    val options: List<String>,
    val selected: Int,
    val text: String,
    val prompt: String?,
    val numeric: Boolean,
    val multiline: Boolean,
    val value: Float,
    val steps: Int,
    val state: Boolean,
    /** Dropdown options have details (e.g. an affix's tiers). */
    val detail: Boolean,
)

/** A row of PoB's controls, and the part of the crafting screen it belongs to ("actions", "variants", "properties", "enchant", "sockets", "modifiers" or "popup"). */
data class CraftRow(val section: String, val controls: List<CraftControl>)

data class CraftPopup(val title: String, val rows: List<CraftRow>)

/** PoB's item editor: the item being crafted or edited (null when none), its controls and modifiers. */
data class CraftState(
    val lines: List<TooltipLine>?,
    val editing: Boolean,
    val rows: List<CraftRow>,
    val popup: CraftPopup?,
    val model: CraftModel?,
) {
    val open get() = lines != null || popup != null
    fun section(name: String) = rows.filter { it.section == name }
}

/** A tier of a modifier family (1 = best), with its lines as PoB lists them ("+(200-214) to maximum Life"). */
data class ModTier(val modId: String, val tier: Int, val level: Int, val name: String, val lines: List<String>, val available: Boolean)

/** A rolled value of a crafted modifier: the range and the roll (0..1) giving [value]. */
data class AffixValue(val line: Int, val min: Double, val max: Double, val decimals: Int, val roll: Double, val value: Double)

/** A prefix or suffix slot of a crafted item (empty when [modId] is null). */
data class AffixSlot(
    val index: Int,
    val type: String,
    val modId: String?,
    val source: String?,
    val sourceLabel: String?,
    /** The family's text over all tiers, or the essence's name */
    val label: String?,
    val name: String?,
    val tier: Int?,
    val tiers: List<ModTier>,
    val level: Int,
    val lines: List<String>,
    val values: List<AffixValue>,
    val fractured: Boolean,
) {
    val empty get() = modId == null
}

/** A modifier family that can fill a slot: the tiers of one modifier from one source. */
data class ModFamily(
    val key: String,
    val source: String,
    val sourceLabel: String,
    val label: String,
    val text: String,
    val influence: String?,
    val tags: List<String>,
    val tiers: List<ModTier>,
) {
    /** The best tier the item level allows (the best one without an item level). */
    val bestAvailable get() = tiers.firstOrNull { it.available } ?: tiers.first()
}

/** A modifier line outside the crafted affixes (custom lines, or all lines of an item that is not crafted). */
data class ExtraLine(val index: Int, val text: String, val custom: Boolean)

/** One of PoB's range lines (implicits, uniques' modifiers...): one roll for its values. */
data class RangeLine(val index: Int, val text: String, val roll: Double, val values: List<AffixValue>, val kind: String)

/** The crafting model of the item in the editor (engine/api/CraftMods.lua). */
data class CraftModel(
    val rarity: String,
    val title: String?,
    val baseName: String,
    val itemLevel: Int?,
    val crafted: Boolean,
    val unique: Boolean,
    val canSetRarity: Boolean,
    val magicOnly: Boolean,
    val requiredLevel: Int,
    val prefixLimit: Int,
    val suffixLimit: Int,
    val prefixes: List<AffixSlot>,
    val suffixes: List<AffixSlot>,
    val extra: List<ExtraLine>,
    val ranges: List<RangeLine>,
    val convertible: Boolean,
)

/** A unique or rare template from PoB's item databases. */
data class DbItem(val name: String, val title: String?, val base: String?, val type: String?, val rarity: String?)

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
                title = it.optStringOrNull("title"),
                base = it.optStringOrNull("base"),
                type = it.optStringOrNull("type"),
                rarity = it.optStringOrNull("rarity"),
                equipped = it.optJSONArray("equipped")?.strings().orEmpty(),
                sockets = it.optJSONArray("sockets")?.strings().orEmpty(),
            )
        },
    )

    private fun craftRows(arr: JSONArray?): List<CraftRow> = arr?.let { rows ->
        (0 until rows.length()).map { r ->
            val o = rows.getJSONObject(r)
            val row = o.optJSONArray("controls") ?: JSONArray()
            CraftRow(o.optString("section"), (0 until row.length()).map { i ->
                val c = row.getJSONObject(i)
                CraftControl(
                    name = c.optString("name"),
                    kind = c.optString("kind"),
                    enabled = c.optBoolean("enabled", true),
                    label = c.optString("label"),
                    options = c.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty(),
                    selected = c.optInt("selected", 0),
                    text = c.optString("text"),
                    prompt = c.optStringOrNull("prompt"),
                    numeric = c.optBoolean("numeric"),
                    multiline = c.optBoolean("multiline"),
                    value = c.optDouble("value", 0.0).toFloat(),
                    steps = c.optInt("steps", 0),
                    state = c.optBoolean("state"),
                    detail = c.optBoolean("detail"),
                )
            })
        }
    }.orEmpty()

    fun craftState(o: JSONObject): CraftState {
        val item = o.optJSONObject("item")
        val popup = o.optJSONObject("popup")
        return CraftState(
            lines = item?.let { it.objects("lines").map { l -> TooltipLine(l.optStringOrNull("text"), l.optBoolean("separator")) } },
            editing = item?.optBoolean("editing") ?: false,
            rows = craftRows(o.optJSONArray("rows")),
            popup = popup?.let { CraftPopup(it.optString("title"), craftRows(it.optJSONArray("rows"))) },
            model = o.optJSONObject("model")?.let { craftModel(it) },
        )
    }

    private fun modTier(t: JSONObject) = ModTier(
        modId = t.optString("modId"),
        tier = t.optInt("tier", 1),
        level = t.optInt("level"),
        name = t.optString("name"),
        lines = t.optJSONArray("lines")?.strings().orEmpty(),
        available = t.optBoolean("available", true),
    )

    private fun affixValue(v: JSONObject) = AffixValue(
        line = v.optInt("line", 1),
        min = v.optDouble("min", 0.0),
        max = v.optDouble("max", 0.0),
        decimals = v.optInt("decimals"),
        roll = v.optDouble("roll", 0.5),
        value = v.optDouble("value", 0.0),
    )

    private fun affixSlot(s: JSONObject) = AffixSlot(
        index = s.optInt("index", 1),
        type = s.optString("type"),
        modId = s.optStringOrNull("modId"),
        source = s.optStringOrNull("source"),
        sourceLabel = s.optStringOrNull("sourceLabel"),
        label = s.optStringOrNull("label"),
        name = s.optStringOrNull("name"),
        tier = s.intOrNull("tier"),
        tiers = s.objects("tiers").map { modTier(it) },
        level = s.optInt("level"),
        lines = s.optJSONArray("lines")?.strings().orEmpty(),
        values = s.objects("values").map { affixValue(it) },
        fractured = s.optBoolean("fractured"),
    )

    fun craftModel(o: JSONObject) = CraftModel(
        rarity = o.optString("rarity"),
        title = o.optStringOrNull("title"),
        baseName = o.optString("baseName"),
        itemLevel = o.intOrNull("itemLevel"),
        crafted = o.optBoolean("crafted"),
        unique = o.optBoolean("unique"),
        canSetRarity = o.optBoolean("canSetRarity"),
        magicOnly = o.optBoolean("magicOnly"),
        requiredLevel = o.optInt("requiredLevel"),
        prefixLimit = o.optInt("prefixLimit"),
        suffixLimit = o.optInt("suffixLimit"),
        prefixes = o.objects("prefixes").map { affixSlot(it) },
        suffixes = o.objects("suffixes").map { affixSlot(it) },
        extra = o.objects("extra").map { ExtraLine(it.optInt("index"), it.optString("text"), it.optBoolean("custom")) },
        ranges = o.objects("ranges").map { r ->
            val roll = r.optDouble("roll", 0.5)
            RangeLine(
                index = r.optInt("index"),
                text = r.optString("text"),
                roll = roll,
                values = r.objects("values").map { v ->
                    val min = v.optDouble("min", 0.0)
                    val max = v.optDouble("max", 0.0)
                    AffixValue(1, min, max, v.optInt("decimals"), roll, min + roll * (max - min))
                },
                kind = r.optString("kind"),
            )
        },
        convertible = o.optBoolean("convertible"),
    )

    fun modFamilies(arr: JSONArray): List<ModFamily> = (0 until arr.length()).map { i ->
        val f = arr.getJSONObject(i)
        ModFamily(
            key = f.optString("key"),
            source = f.optString("source"),
            sourceLabel = f.optString("sourceLabel"),
            label = f.optString("label"),
            text = f.optString("text"),
            influence = f.optStringOrNull("influence"),
            tags = f.optJSONArray("tags")?.strings().orEmpty(),
            tiers = f.objects("tiers").map { modTier(it) },
        )
    }

    fun dbItems(arr: JSONArray) = (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let {
            DbItem(it.optString("name"), it.optStringOrNull("title"), it.optStringOrNull("base"), it.optStringOrNull("type"), it.optStringOrNull("rarity"))
        }
    }

    fun itemTooltip(o: JSONObject) = ItemTooltip(
        title = o.optString("title"),
        rarity = o.optStringOrNull("rarity"),
        lines = o.objects("lines").map { l -> TooltipLine(l.optStringOrNull("text"), l.optBoolean("separator")) },
        raw = o.optString("raw"),
    )
}
