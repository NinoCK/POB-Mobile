package io.room.poe2tree.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PoB's undo / redo of the Skills, Items, Configuration and Calcs tabs. */
class UndoTest {
    private val api get() = EngineTestSupport.api
    private fun out(stat: String) = api.lua.exec("return tostring(build.calcsTab.mainOutput.$stat)")!!.toDouble()
    private fun flags(state: JSONObject, tab: String) = state.getJSONObject("undo").getJSONObject(tab)
    private fun undo(tab: String, redo: Boolean = false) = api.callObject("undo", mapOf("tab" to tab, "redo" to redo))

    @Test
    fun skillsUndoAndRedo() {
        EngineTestSupport.loadFixture("stormweaver_spark")
        api.call("recalc")
        assertFalse(flags(api.callObject("state"), "skills").getBoolean("undo"))
        val dps = out("CombinedDPS")
        api.callObject("skillEdit", mapOf("op" to "gemLevel", "group" to 1, "gem" to 1, "value" to 10))
        val lowered = out("CombinedDPS")
        assertTrue(lowered < dps)
        api.callObject("skillEdit", mapOf("op" to "gemEnabled", "group" to 1, "gem" to 2, "value" to false))
        val disabled = out("CombinedDPS")

        var state = undo("skills")
        assertEquals(lowered, out("CombinedDPS"), 1e-6)
        assertTrue(flags(state, "skills").getBoolean("redo"))
        state = undo("skills")
        assertEquals(dps, out("CombinedDPS"), 1e-6)
        assertFalse(flags(state, "skills").getBoolean("undo"))
        undo("skills", redo = true)
        undo("skills", redo = true)
        assertEquals(disabled, out("CombinedDPS"), 1e-6)
        assertFalse(flags(api.callObject("state"), "skills").getBoolean("redo"))

        // A new change clears the redo history
        undo("skills")
        state = api.callObject("skillEdit", mapOf("op" to "gemQuality", "group" to 1, "gem" to 1, "value" to 5)).getJSONObject("state")
        assertFalse(flags(state, "skills").getBoolean("redo"))
    }

    @Test
    fun newBuildFirstChangeCanBeUndone() {
        EngineTestSupport.newBuild()
        api.call("recalc")
        val added = api.callObject("itemEdit", mapOf("op" to "add", "text" to "Rarity: RARE\nTest Ring\nIron Ring\n+30 to maximum Life", "equip" to true))
        assertTrue(flags(added.getJSONObject("state"), "items").getBoolean("undo"))
        undo("items")
        assertEquals(0, api.callObject("items").getJSONArray("items").length())
    }

    @Test
    fun itemsConfigAndCalcsUndo() {
        EngineTestSupport.loadFixture("warrior_boneshatter_weaponsets")
        api.call("recalc")
        val dps = out("CombinedDPS")
        val weapon = api.callObject("items").getJSONArray("slots").getJSONObject(0)
        val slot = weapon.getString("name")
        val id = weapon.getInt("itemId")
        assertNotEquals(0, id)
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to slot, "id" to 0))
        assertNotEquals(dps, out("CombinedDPS"), 0.001)
        undo("items")
        assertEquals(id, api.callObject("items").getJSONArray("slots").getJSONObject(0).getInt("itemId"))
        assertEquals(dps, out("CombinedDPS"), 1e-6)
        undo("items", redo = true)
        assertEquals(0, api.callObject("items").getJSONArray("slots").getJSONObject(0).getInt("itemId"))
        undo("items")

        // Deleting an item and undoing it brings it back
        val count = api.callObject("items").getJSONArray("items").length()
        api.callObject("itemEdit", mapOf("op" to "delete", "id" to id))
        assertEquals(count - 1, api.callObject("items").getJSONArray("items").length())
        undo("items")
        assertEquals(count, api.callObject("items").getJSONArray("items").length())
        assertEquals(dps, out("CombinedDPS"), 1e-6)

        // Configuration
        val config = api.callObject("config")
        val row = (0 until config.getJSONArray("sections").length()).asSequence()
            .flatMap { i -> config.getJSONArray("sections").getJSONObject(i).getJSONArray("rows").let { r -> (0 until r.length()).map { r.getJSONObject(it) } } }
            .first { it.optString("type") == "check" && !it.optBoolean("value") }
        api.callObject("setConfig", mapOf("idx" to row.getInt("idx"), "value" to true))
        val state = undo("config")
        assertFalse(flags(state, "config").getBoolean("undo"))
        assertTrue(flags(state, "config").getBoolean("redo"))
        val restored = api.callObject("config").getJSONArray("sections").let { s ->
            (0 until s.length()).asSequence().flatMap { i -> s.getJSONObject(i).getJSONArray("rows").let { r -> (0 until r.length()).map { r.getJSONObject(it) } } }
                .first { it.optInt("idx", -1) == row.getInt("idx") }
        }
        assertFalse(restored.optBoolean("value"))
        assertEquals(dps, out("CombinedDPS"), 1e-6)

        // Calcs tab: calculation mode
        val mode = api.callObject("calcsSelection").getString("buffMode")
        val other = api.callObject("calcsSelection").getJSONArray("buffModes").let { m ->
            (0 until m.length()).map { m.getJSONObject(it).getString("mode") }.first { it != mode }
        }
        api.callObject("calcsSelect", mapOf("buffMode" to other))
        assertEquals(other, api.callObject("calcsSelection").getString("buffMode"))
        undo("calcs")
        assertEquals(mode, api.callObject("calcsSelection").getString("buffMode"))
    }
}
