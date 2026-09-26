package io.room.poe2tree.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillsItemsTest {
    private val api get() = EngineTestSupport.api
    private fun out(stat: String) = api.lua.exec("return tostring(build.calcsTab.mainOutput.$stat)")!!.toDouble()

    @Test
    fun skillsListingAndEdits() {
        EngineTestSupport.loadFixture("stormweaver_spark")
        api.call("recalc")
        val skills = api.callObject("skills")
        val groups = skills.getJSONArray("groups")
        for (i in 0 until groups.length()) {
            val g = groups.getJSONObject(i)
            val gems = g.getJSONArray("gems")
            println("${i + 1}. ${g.getString("displayLabel")} [${g.getString("weaponSet")}] main=${g.getBoolean("isMain")} " +
                (0 until gems.length()).joinToString { gems.getJSONObject(it).let { gem -> "${gem.getString("name")} ${gem.get("level")}/${gem.get("quality")} ${gem.getString("status")}" } })
        }
        val dps = out("CombinedDPS")
        // Disable the Lightning Penetration support (gem 2 of group 1)
        val afterDisable = api.callObject("skillEdit", mapOf("op" to "gemEnabled", "group" to 1, "gem" to 2, "value" to false))
        val gem = afterDisable.getJSONObject("skills").getJSONArray("groups").getJSONObject(0).getJSONArray("gems").getJSONObject(1)
        assertEquals(false, gem.getBoolean("enabled"))
        assertEquals("Disabled", gem.getString("reason"))
        assertNotEquals(dps, out("CombinedDPS"), 0.001)
        api.callObject("skillEdit", mapOf("op" to "gemEnabled", "group" to 1, "gem" to 2, "value" to true))
        assertEquals(dps, out("CombinedDPS"), 1e-6)

        // Gem level
        api.callObject("skillEdit", mapOf("op" to "gemLevel", "group" to 1, "gem" to 1, "value" to 10))
        assertTrue(out("CombinedDPS") < dps)
        api.callObject("skillEdit", mapOf("op" to "gemLevel", "group" to 1, "gem" to 1, "value" to 20))
        assertEquals(dps, out("CombinedDPS"), 1e-6)

        // Search and add a support gem
        val found = api.callArray("gemSearch", mapOf("query" to "Controlled Destruction", "support" to true))
        assertTrue(found.length() > 0)
        val gemId = found.getJSONObject(0).getString("gemId")
        val added = api.callObject("skillEdit", mapOf("op" to "setGem", "group" to 1, "value" to gemId))
        val gems = added.getJSONObject("skills").getJSONArray("groups").getJSONObject(0).getJSONArray("gems")
        assertEquals("Controlled Destruction", gems.getJSONObject(gems.length() - 1).getString("name"))
        println("with Controlled Destruction: ${out("CombinedDPS")} (was $dps)")
        api.callObject("skillEdit", mapOf("op" to "removeGem", "group" to 1, "gem" to gems.length()))

        // Main skill switch and paste
        val main2 = api.callObject("skillEdit", mapOf("op" to "setMain", "group" to 2))
        assertEquals(2, main2.getJSONObject("state").getJSONObject("selection").getInt("mainSocketGroup"))
        val pasted = api.callObject("skillEdit", mapOf("op" to "pasteGroup", "value" to "Fireball 20/0  1\nFire Penetration I 1/0  1"))
        assertTrue(pasted.getJSONObject("skills").getJSONArray("groups").let { arr -> (0 until arr.length()).any { arr.getJSONObject(it).getString("displayLabel") == "Fireball" } })
        println(api.callArray("gemTooltip", mapOf("group" to 1, "gem" to 1)).toString().take(600))
    }

    @Test
    fun itemsListingTooltipsAndEdits() {
        EngineTestSupport.loadFixture("warrior_boneshatter_weaponsets")
        api.call("recalc")
        val items = api.callObject("items")
        val slots = items.getJSONArray("slots")
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            println("${s.getString("label")}: ${s.optString("item", "-")} (${s.getJSONArray("candidates").length()} candidates)")
        }
        val weaponSlot = (0 until slots.length()).map { slots.getJSONObject(it) }.first { it.getString("name") == "Weapon 1" }
        val weaponId = weaponSlot.getInt("itemId")
        val tooltip = api.callObject("itemTooltip", mapOf("id" to weaponId, "slot" to "Weapon 1"))
        println(tooltip.getJSONArray("lines").let { arr -> (0 until arr.length()).joinToString("\n") { arr.getJSONObject(it).optString("text", "----") } })

        val life = out("Life")
        val add = api.callObject("itemEdit", mapOf("op" to "add", "text" to "Rarity: RARE\nTest Band\nRuby Ring\n+50 to maximum Life", "equip" to false))
        val id = add.getInt("addedId")
        assertEquals(life, out("Life"), 1e-9)
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Ring 1", "id" to id))
        assertTrue(out("Life") != life)
        api.callObject("itemEdit", mapOf("op" to "delete", "id" to id))
        println("life $life after delete ${out("Life")}")

        // Unequip the weapon: damage drops
        val dps = out("CombinedDPS")
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Weapon 1", "id" to 0))
        assertTrue(out("CombinedDPS") < dps)
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Weapon 1", "id" to weaponId))
        assertEquals(dps, out("CombinedDPS"), 1e-6)

        val bad = runCatching { api.callObject("itemEdit", mapOf("op" to "add", "text" to "hello")) }.exceptionOrNull()
        assertTrue(bad is EngineException)
    }
}
