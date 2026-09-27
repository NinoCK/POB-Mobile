package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The crafting model (engine/api/CraftMods.lua): modifier pools, tiers, rolls and item properties. */
class CraftModsTest {
    private val api get() = EngineTestSupport.api

    @Before
    fun load() {
        EngineTestSupport.loadFixture("warrior_boneshatter_weaponsets")
        api.call("recalc")
    }

    private fun rows(o: JSONObject): List<JSONObject> {
        val arr = o.optJSONArray("rows") ?: return emptyList()
        return (0 until arr.length()).flatMap { r -> arr.getJSONObject(r).getJSONArray("controls").let { row -> (0 until row.length()).map { row.getJSONObject(it) } } }
    }

    private fun action(target: String, name: String, op: String, value: Any? = null): JSONObject =
        api.callObject("craftAction", JSONObject().put("target", target).put("name", name).put("op", op).put("value", value ?: JSONObject.NULL))

    private fun lines(state: JSONObject) = state.getJSONObject("item").getJSONArray("lines").let { arr ->
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("text").takeIf { t -> t.isNotEmpty() } }.map { strip(it) }
    }

    /** The item's own lines, without PoB's stat comparison. */
    private fun itemLines(state: JSONObject) = lines(state).takeWhile { !it.contains("will give you") }

    private fun strip(s: String) = s.replace(Regex("\\^(x[0-9A-Fa-f]{6}|[0-9])"), "")

    private fun craft(type: String, rarity: String, base: String? = null): JSONObject {
        var state = api.callObject("craftNew")
        val types = rows(state.getJSONObject("popup")).first { it.getString("name") == "type" }.getJSONArray("options")
        action("popup", "type", "select", (0 until types.length()).first { types.getString(it) == type } + 1)
        state = api.callObject("craftState")
        if (base != null) {
            val bases = rows(state.getJSONObject("popup")).first { it.getString("name") == "base" }.getJSONArray("options")
            action("popup", "base", "select", (0 until bases.length()).first { strip(bases.getString(it)) == base } + 1)
        }
        val rarities = rows(state.getJSONObject("popup")).first { it.getString("name") == "rarity" }.getJSONArray("options")
        action("popup", "rarity", "select", (0 until rarities.length()).first { rarities.getString(it).contains(rarity) } + 1)
        return action("popup", "save", "click")
    }

    private fun families(slot: String, index: Int, any: Boolean = false): List<JSONObject> {
        val arr = api.callObject("craftPool", JSONObject().put("slot", slot).put("index", index).put("any", any)).getJSONArray("families")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun JSONObject.tiers() = getJSONArray("tiers").let { t -> (0 until t.length()).map { t.getJSONObject(it) } }

    private fun setAffix(slot: String, index: Int, modId: String?, vararg rolls: Double, fractured: Boolean = false): JSONObject {
        val args = JSONObject().put("slot", slot).put("index", index).put("modId", modId ?: "None").put("fractured", fractured)
        if (rolls.isNotEmpty()) args.put("ranges", JSONArray().apply { rolls.forEach { put(it) } })
        return api.callObject("craftSetAffix", args)
    }

    private fun slot(state: JSONObject, slot: String, index: Int) =
        state.getJSONObject("model").getJSONArray(if (slot == "prefix") "prefixes" else "suffixes").getJSONObject(index - 1)

    @Test
    fun poolsHaveEverySource() {
        val state = craft("Body Armour", "Rare", "Golden Mantle")
        val model = state.getJSONObject("model")
        assertTrue(model.getBoolean("crafted"))
        assertEquals(3, model.getInt("prefixLimit"))
        assertEquals(3, model.getInt("suffixLimit"))
        val prefixes = families("prefix", 1)
        val suffixes = families("suffix", 1)
        val prefixSources = prefixes.map { it.getString("source") }.toSet()
        val suffixSources = suffixes.map { it.getString("source") }.toSet()
        println("prefix sources ${prefixes.groupingBy { it.getString("source") }.eachCount()}, suffix sources ${suffixes.groupingBy { it.getString("source") }.eachCount()}")
        assertTrue("regular" in prefixSources && "essence" in prefixSources)
        assertTrue("regular" in suffixSources && "desecrated" in suffixSources && "essence" in suffixSources)
        // Every family is of the slot's type, with tiers best first
        for (f in prefixes + suffixes) {
            val levels = f.tiers().map { it.getInt("level") }
            assertEquals("tiers of ${f.getString("label")}", levels.sortedDescending(), levels)
        }
        val life = prefixes.first { it.getString("source") == "regular" && it.getString("text") == "+(10-214) to maximum Life" }
        assertEquals(13, life.tiers().size)
        assertEquals("Prime", life.tiers()[0].getString("name"))
        // "Any modifier" lists the whole table
        val any = families("prefix", 1, any = true).filter { it.getString("source") == "any" }
        assertTrue(any.size > prefixes.size)
    }

    @Test
    fun affixesTiersRollsAndFractured() {
        craft("Body Armour", "Rare", "Golden Mantle")
        val life = families("prefix", 1).first { it.getString("text") == "+(10-214) to maximum Life" }
        var state = setAffix("prefix", 1, life.tiers()[0].getString("modId"), 1.0)
        assertTrue(itemLines(state).contains("+214 to maximum Life"))
        var s = slot(state, "prefix", 1)
        assertEquals(1, s.getInt("tier"))
        assertEquals("regular", s.getString("source"))
        assertEquals(214.0, s.getJSONArray("values").getJSONObject(0).getDouble("value"), 0.0)

        // A hybrid with its own roll for each value, fractured
        val hybrid = families("prefix", 2).first { it.getString("text") == "+(9-170) to Armour / +(6-161) to Evasion Rating" }
        state = setAffix("prefix", 2, hybrid.tiers()[0].getString("modId"), 0.0, 1.0, fractured = true)
        assertTrue(itemLines(state).containsAll(listOf("+150 to Armour", "+161 to Evasion Rating")))
        s = slot(state, "prefix", 2)
        assertTrue(s.getBoolean("fractured"))
        assertEquals(listOf(150.0, 161.0), (0 until 2).map { s.getJSONArray("values").getJSONObject(it).getDouble("value") })

        // The same group cannot be in two slots
        assertFalse(families("prefix", 3).any { f -> f.tiers().any { it.getString("modId") == life.tiers()[0].getString("modId") } })

        // Another tier keeps the slot; removing empties it
        state = setAffix("prefix", 1, life.tiers()[4].getString("modId"), 0.0)
        assertTrue(itemLines(state).contains("+120 to maximum Life"))
        state = setAffix("prefix", 1, null)
        assertNull(slot(state, "prefix", 1).optString("modId").ifEmpty { null })
        assertFalse(itemLines(state).any { it.contains("maximum Life") })
    }

    @Test
    fun desecratedAndEssenceAffixesAreKeptInTheBuild() {
        craft("Body Armour", "Rare", "Golden Mantle")
        val desecrated = families("suffix", 1).first { it.getString("source") == "desecrated" }
        val desecratedId = desecrated.tiers()[0].getString("modId")
        var state = setAffix("suffix", 1, desecratedId, 1.0)
        assertEquals("desecrated", slot(state, "suffix", 1).getString("source"))
        val essence = families("suffix", 2).first { it.getString("source") == "essence" && it.tiers().any { t -> t.getString("name").startsWith("Perfect") } }
        val essenceTier = essence.tiers().first { it.getString("name").startsWith("Perfect") }
        state = setAffix("suffix", 2, essenceTier.getString("modId"), 1.0)
        assertEquals(essenceTier.getString("name"), slot(state, "suffix", 2).getString("name"))
        val expected = itemLines(state).filter { it.contains("reduced") || it.contains(" and ") }
        assertTrue(expected.isNotEmpty())

        // Saved in the build, reloaded, edited again: the slots are the same
        state = action("editor", "addDisplayItem", "click")
        assertTrue(state.optBoolean("added"))
        val items = api.callObject("items").getJSONArray("items")
        val id = items.getJSONObject(items.length() - 1).getInt("id")
        api.call("loadBuild", mapOf("xml" to api.callString("saveXml"), "name" to "reload"))
        state = api.callObject("craftEdit", JSONObject().put("id", id))
        assertEquals(desecratedId, slot(state, "suffix", 1).getString("modId"))
        assertEquals(essenceTier.getString("modId"), slot(state, "suffix", 2).getString("modId"))
        assertTrue(itemLines(state).containsAll(expected))
        api.callObject("craftCancel")
    }

    @Test
    fun rarityNameAndItemLevel() {
        craft("Body Armour", "Rare", "Golden Mantle")
        val life = families("prefix", 1).first { it.getString("text") == "+(10-214) to maximum Life" }
        val res = families("suffix", 1).first { it.getString("text") == "+(6-45)% to Fire Resistance" }
        setAffix("prefix", 1, life.tiers()[0].getString("modId"), 1.0)
        setAffix("prefix", 2, families("prefix", 2).first { it.getString("source") == "regular" }.tiers()[0].getString("modId"))
        setAffix("suffix", 1, res.tiers()[0].getString("modId"), 1.0)

        var state = api.callObject("craftSetItem", JSONObject().put("rarity", "MAGIC"))
        var model = state.getJSONObject("model")
        assertEquals("MAGIC", model.getString("rarity"))
        assertEquals(1, model.getInt("prefixLimit"))
        assertEquals(1, model.getInt("suffixLimit"))
        assertTrue(itemLines(state)[0].startsWith("Prime Golden Mantle"))

        state = api.callObject("craftSetItem", JSONObject().put("rarity", "RARE").put("title", "Doom Shell").put("itemLevel", 50))
        model = state.getJSONObject("model")
        assertEquals("Doom Shell", itemLines(state)[0])
        assertEquals(50, model.getInt("itemLevel"))
        assertTrue(itemLines(state).contains("+214 to maximum Life"))
        val tiers = slot(state, "prefix", 1).tiers()
        assertTrue(tiers.filter { it.getInt("level") > 50 }.none { it.getBoolean("available") })
        assertTrue(tiers.filter { it.getInt("level") <= 50 }.all { it.getBoolean("available") })

        state = api.callObject("craftSetItem", JSONObject().put("rarity", "NORMAL"))
        assertFalse(state.getJSONObject("model").getBoolean("crafted"))
        assertFalse(itemLines(state).any { it.contains("maximum Life") })
        api.callObject("craftCancel")
    }

    @Test
    fun previewAndStatSorting() {
        craft("Body Armour", "Rare", "Golden Mantle")
        val life = families("prefix", 1).first { it.getString("text") == "+(10-214) to maximum Life" }
        val preview = api.callArray("craftPreview", JSONObject().put("slot", "prefix").put("index", 1).put("modId", life.tiers()[0].getString("modId")).put("range", 1.0))
        assertTrue((0 until preview.length()).any { strip(preview.getString(it)).contains("Life") })
        val stats = api.callArray("craftSortStats")
        assertTrue((0 until stats.length()).any { stats.getJSONObject(it).getString("stat") == "Life" })
        val ids = JSONArray().put(life.tiers()[0].getString("modId")).put(families("prefix", 1).first { it.getString("text").contains("to Armour") }.tiers()[0].getString("modId"))
        val values = api.callObject("craftPoolValues", JSONObject().put("slot", "prefix").put("index", 1).put("stat", "Life").put("modIds", ids).put("range", 0.5))
        assertTrue(values.getDouble(life.tiers()[0].getString("modId")) > 100)
        api.callObject("craftCancel")
    }

    @Test
    fun convertPastedItemIntoAffixes() {
        val text = """
            Item Class: Rings
            Rarity: Rare
            Havoc Loop
            Ruby Ring
            --------
            Requirements:
            Level: 50
            --------
            Item Level: 80
            --------
            +25% to Fire Resistance (implicit)
            --------
            +87 to maximum Life
            +32% to Cold Resistance
            +18 to Strength
            +12% to Chaos Resistance
            Adds 5 to 9 Physical Damage to Attacks
            Totally made up modifier line
        """.trimIndent()
        var state = api.callObject("craftFromText", JSONObject().put("text", text))
        assertTrue(state.getJSONObject("model").getBoolean("convertible"))
        val before = itemLines(state)
        state = api.callObject("craftConvert")
        val model = state.getJSONObject("model")
        assertTrue(model.getBoolean("crafted"))
        val prefixes = (0 until model.getJSONArray("prefixes").length()).map { model.getJSONArray("prefixes").getJSONObject(it) }.filter { it.has("modId") }
        val suffixes = (0 until model.getJSONArray("suffixes").length()).map { model.getJSONArray("suffixes").getJSONObject(it) }.filter { it.has("modId") }
        assertEquals(2, prefixes.size)
        assertEquals(3, suffixes.size)
        // The unknown line stays as a custom line; the item's text is the same
        assertEquals(1, model.getJSONArray("extra").length())
        for (line in listOf("+87 to maximum Life", "+32% to Cold Resistance", "+18 to Strength", "+12% to Chaos Resistance", "Adds 5 to 9 Physical Damage to Attacks", "+25% to Fire Resistance")) {
            assertTrue("$line in $before", before.contains(line))
            assertTrue("$line after converting", itemLines(state).contains(line))
        }
        // The catalyst keeps the app's affixes
        state = action("editor", "displayItemCatalyst", "select", 2)
        assertEquals(2, (0 until state.getJSONObject("model").getJSONArray("prefixes").length()).count { state.getJSONObject("model").getJSONArray("prefixes").getJSONObject(it).has("modId") })
        assertTrue(itemLines(state).any { it.matches(Regex("\\+1\\d\\d to maximum Life")) })
        api.callObject("craftCancel")
    }

    @Test
    fun uniqueRollsAndOtherItemKinds() {
        val belt = api.callArray("itemDB", JSONObject().put("kind", "UNIQUE").put("query", "belt")).getJSONObject(0).getString("name")
        var state = api.callObject("craftFromDB", JSONObject().put("kind", "UNIQUE").put("name", belt))
        val ranges = state.getJSONObject("model").getJSONArray("ranges")
        assertTrue(ranges.length() > 0)
        state = api.callObject("craftSetRange", JSONObject().put("index", 1).put("range", 1.0))
        assertEquals(1.0, state.getJSONObject("model").getJSONArray("ranges").getJSONObject(0).getDouble("roll"), 1e-9)
        api.callObject("craftCancel")

        // Magic flask, rare jewel, a weapon: slots and pools
        for ((type, rarity, prefixLimit) in listOf(Triple("Flask: Life", "Magic", 1), Triple("Jewel", "Rare", 2), Triple("One Hand Mace", "Rare", 3), Triple("Charm", "Magic", 1))) {
            state = craft(type, rarity)
            val model = state.getJSONObject("model")
            assertEquals(type, prefixLimit, model.getInt("prefixLimit"))
            val fams = families("prefix", 1)
            assertTrue("$type prefixes", fams.isNotEmpty())
            state = setAffix("prefix", 1, fams[0].tiers()[0].getString("modId"), 1.0)
            assertNotNull(slot(state, "prefix", 1).optString("modId"))
            api.callObject("craftCancel")
        }
    }
}
