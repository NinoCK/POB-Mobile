package io.room.poe2tree.engine

import io.room.poe2tree.io.PobCode
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Character import (engine/api/Import.lua): a character in the Path of Exile API's format
 * (GET /character/poe2/<name>) imported with PoB's ImportTab, then read back like the app does.
 */
class CharacterImportTest {
    private val api get() = EngineTestSupport.api

    private val tree: PassiveTree by lazy {
        val dir = File(EngineTestSupport.assets, "tree")
        PassiveTree.load(File(dir, "tree.json").readText(), File(dir, "sprites.json").readText())
    }

    /** The Stormweaver fixture's passives. */
    private val hashes: List<Int> by lazy {
        val xml = File(EngineTestSupport.fixtures, "stormweaver_spark.xml").readText()
        PobCode.importXml(xml, tree).snapshot.nodes.keys.filter { id -> tree.node(id)?.type?.isStart == false }
    }

    private fun property(name: String, value: String) =
        JSONObject().put("name", name).put("values", JSONArray().put(JSONArray().put(value).put(0)))

    private fun item(id: String, slot: String, name: String, base: String, frameType: Int, mods: List<String>, vararg properties: JSONObject) =
        JSONObject()
            .put("id", id).put("frameType", frameType).put("name", name).put("typeLine", base).put("baseType", base)
            .put("inventoryId", slot).put("x", 0).put("y", 0).put("ilvl", 80).put("identified", true)
            .put("properties", JSONArray().apply { properties.forEach { put(it) } })
            .put("explicitMods", JSONArray(mods))

    private fun gem(name: String, support: Boolean, level: Int, socketed: List<JSONObject> = emptyList()) = JSONObject()
        .put("typeLine", name).put("baseType", name).put("support", support)
        .put("properties", JSONArray().put(property("Level", level.toString())).put(property("[Quality]", "+20%")))
        .apply { if (socketed.isNotEmpty()) put("socketedItems", JSONArray(socketed)) }

    /** A level 88 Stormweaver (the API gives the internal ascendancy id as the class). */
    private fun character(jewelX: Int?): JSONObject {
        val equipment = JSONArray()
            .put(item("w1", "Weapon", "Tempest Branch", "Voltaic Staff", 2,
                listOf("+3 to Level of all Spell Skills", "80% increased Spell Damage", "25% increased Cast Speed"), property("[Quality]", "+20%")))
            .put(item("b1", "BodyArmour", "Storm Shroud", "Silk Robe", 2,
                listOf("+120 to maximum Energy Shield", "80% increased Energy Shield", "+40% to Lightning Resistance")))
            .put(item("r1", "Ring", "Blood Loop", "Ruby Ring", 2, listOf("+40 to maximum Life", "+30% to Lightning Resistance")))
        val jewels = JSONArray()
        if (jewelX != null) {
            jewels.put(item("j1", "PassiveJewels", "Storm Eye", "Sapphire", 2, listOf("15% increased Lightning Damage")).put("x", jewelX))
        }
        val skills = JSONArray().put(gem("Spark", false, 20, listOf(gem("Lightning Penetration", true, 1))))
        val passives = JSONObject()
            .put("hashes", JSONArray(hashes))
            .put("specialisations", JSONObject())
            // An attribute passive set to Intelligence
            .put("skill_overrides", JSONObject().put("34058", JSONObject().put("name", "Intelligence").put("icon", "").put("stats", JSONArray().put("+5 to Intelligence"))))
            .put("jewel_data", JSONObject())
            .put("quest_stats", JSONArray().put("+30 to [Spirit|Spirit]"))
        return JSONObject()
            .put("id", "abc").put("name", "SparkTest").put("realm", "poe2").put("class", "Sorceress1")
            .put("league", "Standard").put("level", 88).put("experience", 1)
            .put("equipment", equipment).put("jewels", jewels).put("skills", skills).put("passives", passives)
    }

    private fun importInto(character: JSONObject, vararg options: Pair<String, Any>): JSONObject {
        val args = JSONObject().put("character", character)
        for ((k, v) in options) args.put(k, v)
        return api.callObject("importCharacter", args)
    }

    @Test
    fun importsCharacterIntoNewBuild() {
        EngineTestSupport.newBuild()
        // A jewel socket among the passives, as its index in PoB's jewel slot list (the API's "x")
        val socketIds = api.lua.exec("local t = { } for i, id in ipairs(build.latestTree.jewelSlots) do t[#t + 1] = id end return table.concat(t, ',')")!!
            .split(',').map { it.toInt() }
        val socketX = socketIds.indexOfFirst { it in hashes }.takeIf { it >= 0 }
        val result = importInto(character(socketX))
        val xml = result.getString("xml")

        // The tree, as the app reads it from the build's XML
        val imported = PobCode.importXml(xml, tree)
        val asc = tree.classById(imported.snapshot.classId)?.ascendancy(imported.snapshot.ascendClassId)
        assertEquals("Stormweaver", asc?.name)
        assertEquals(88, imported.level)
        assertTrue("missing passives", imported.snapshot.nodes.keys.containsAll(hashes))
        assertEquals(3, imported.snapshot.attributes[34058])
        // Kotlin's pathing keeps the whole tree
        val spec = PassiveSpec(tree)
        spec.restore(imported.snapshot)
        assertEquals(imported.snapshot.nodes.keys, spec.snapshot().nodes.keys)

        // Items and skills
        val items = api.callObject("items")
        val slots = items.getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        fun slotItem(name: String) = slots.firstOrNull { it.getString("name") == name }?.optString("item")
        assertTrue(slotItem("Weapon 1").orEmpty().contains("Tempest Branch"))
        assertTrue(slotItem("Body Armour").orEmpty().contains("Storm Shroud"))
        assertTrue(slotItem("Ring 1").orEmpty().contains("Blood Loop"))
        if (socketX != null) {
            val socket = socketIds[socketX]
            val jewel = api.lua.exec("local id = build.spec.jewels[$socket] return id and build.itemsTab.items[id] and build.itemsTab.items[id].title or ''")
            assertEquals("Storm Eye", jewel)
        }
        val groups = api.callObject("skills").getJSONArray("groups")
        assertEquals(1, groups.length())
        val gems = groups.getJSONObject(0).getJSONArray("gems")
        assertEquals("Spark", gems.getJSONObject(0).getString("name"))
        assertEquals(20, gems.getJSONObject(0).getInt("level"))
        assertEquals(20, gems.getJSONObject(0).getInt("quality"))
        assertEquals("Lightning Penetration", gems.getJSONObject(1).getString("name"))

        // Calculated, with the quest reward found from the character's quest stats
        val dps = api.lua.exec("return tostring(build.calcsTab.mainOutput.CombinedDPS)")!!.toDouble()
        assertTrue("DPS $dps", dps > 0)
        assertNotNull(EngineJson.state(result.getJSONObject("state")))
        println("imported: ${imported.snapshot.nodes.size} passives, DPS $dps, jewel socket $socketX")
    }

    @Test
    fun reimportReplacesEquipmentAndKeepsOptions() {
        EngineTestSupport.loadFixture("stormweaver_spark")
        api.call("recalc")
        val before = api.callObject("items").getJSONArray("slots").let { a -> (0 until a.length()).count { a.getJSONObject(it).has("item") } }
        // Items only, PoB's defaults (delete equipment and skills)
        val character = character(null)
        importInto(character, "tree" to false)
        val slots = api.callObject("items").getJSONArray("slots").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val equipped = slots.filter { it.has("item") && !it.getString("name").startsWith("Jewel") }.map { it.getString("name") }.toSet()
        assertEquals(setOf("Weapon 1", "Body Armour", "Ring 1"), equipped)
        assertTrue(before > equipped.size)
        // Without deleting the equipment, other items stay
        EngineTestSupport.loadFixture("stormweaver_spark")
        api.call("recalc")
        importInto(character, "tree" to false, "clearItems" to false, "clearSkills" to false)
        val kept = api.callObject("items").getJSONArray("slots").let { a -> (0 until a.length()).count { a.getJSONObject(it).has("item") } }
        assertEquals(before, kept)
        assertTrue(api.callObject("skills").getJSONArray("groups").length() > 1)
    }

    @Test
    fun rejectsIncompleteCharacter() {
        EngineTestSupport.newBuild()
        val error = runCatching { importInto(JSONObject().put("name", "x")) }.exceptionOrNull()
        assertTrue(error is EngineException && error.message!!.contains("incomplete"))
    }
}
