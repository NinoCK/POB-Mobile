package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crafting and editing items with PoB's item editor, driven through its controls. */
class CraftTest {
    private val api get() = EngineTestSupport.api

    private fun controls(rows: JSONArray): List<JSONObject> =
        (0 until rows.length()).flatMap { r -> rows.getJSONArray(r).let { row -> (0 until row.length()).map { row.getJSONObject(it) } } }

    private fun editor(state: JSONObject) = controls(state.getJSONArray("rows"))
    private fun popup(state: JSONObject) = controls(state.getJSONObject("popup").getJSONArray("rows"))
    private fun find(list: List<JSONObject>, name: String) = list.firstOrNull { it.getString("name") == name }

    private fun action(target: String, name: String, op: String, value: Any? = null): JSONObject =
        api.callObject("craftAction", JSONObject().put("target", target).put("name", name).put("op", op).put("value", value ?: JSONObject.NULL))

    private fun itemLines(state: JSONObject) = state.getJSONObject("item").getJSONArray("lines").let { arr ->
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optString("text").takeIf { t -> t.isNotEmpty() } }
    }

    private fun describe(state: JSONObject): String = buildString {
        if (state.has("popup")) append("popup ${state.getJSONObject("popup").getString("title")}: ${popup(state).map { it.getString("name") + ":" + it.getString("kind") }}\n")
        append("editor: ${editor(state).map { it.getString("name") + ":" + it.getString("kind") }}")
    }

    @Test
    fun craftRareAddModifierAndUnique() {
        EngineTestSupport.loadFixture("warrior_boneshatter_weaponsets")
        api.call("recalc")
        val itemsBefore = api.callObject("items").getJSONArray("items").length()

        // Craft item: rare amulet
        var state = api.callObject("craftNew")
        println(describe(state))
        val type = find(popup(state), "type")!!
        val amulet = (0 until type.getJSONArray("options").length()).first { type.getJSONArray("options").getString(it) == "Amulet" } + 1
        action("popup", "type", "select", amulet)
        val rarity = find(popup(api.callObject("craftState")), "rarity")!!
        val rare = (0 until rarity.getJSONArray("options").length()).first { rarity.getJSONArray("options").getString(it).contains("Rare") } + 1
        action("popup", "rarity", "select", rare)
        action("popup", "title", "text", "Test Amulet")
        state = action("popup", "save", "click")
        assertFalse(state.has("popup"))
        println(describe(state))
        println(itemLines(state))
        assertTrue(itemLines(state).any { it.contains("Test Amulet") })

        // First affix: pick an option, then roll it to the top of its range
        val affix = find(editor(state), "displayItemAffix1")!!
        assertEquals("dropdown", affix.getString("kind"))
        assertTrue(affix.getJSONArray("options").length() > 2)
        println("affix 1: ${affix.getJSONArray("options").length()} options, e.g. ${affix.getJSONArray("options").getString(1)}")
        val detail = api.callArray("craftDetail", JSONObject().put("target", "editor").put("name", "displayItemAffix1").put("index", 2))
        println("detail: $detail")
        state = action("editor", "displayItemAffix1", "select", 2)
        val withAffix = itemLines(state)
        println(withAffix)
        val range = find(editor(state), "displayItemAffixRange1")
        if (range != null) {
            state = action("editor", "displayItemAffixRange1", "value", 1.0)
            println("rolled high: ${itemLines(state)}")
        }

        // Add modifier: a custom line
        state = action("editor", "displayItemAddCustom", "click")
        println(describe(state))
        val source = find(popup(state), "source")!!
        val custom = (0 until source.getJSONArray("options").length()).firstOrNull { source.getJSONArray("options").getString(it).contains("Custom") }
        if (custom != null) {
            action("popup", "source", "select", custom + 1)
            action("popup", "custom", "text", "+25 to maximum Life")
        } else {
            action("popup", "modSelect", "select", 1)
        }
        state = action("popup", "save", "click")
        assertFalse(state.has("popup"))
        println("after add modifier: ${itemLines(state)}")
        val customLabel = find(editor(state), "displayItemCustomModifier1")
        if (customLabel != null) {
            println("custom modifier label: ${customLabel.getString("label")}")
            assertFalse(customLabel.getString("label") == "...")
        }

        // Add to build
        state = action("editor", "addDisplayItem", "click")
        assertTrue(state.optBoolean("added"))
        assertFalse(state.has("item"))
        assertEquals(itemsBefore + 1, api.callObject("items").getJSONArray("items").length())

        // A unique from PoB's database
        val uniques = api.callArray("itemDB", JSONObject().put("kind", "UNIQUE").put("query", "amulet"))
        assertTrue(uniques.length() > 0)
        val unique = uniques.getJSONObject(0).getString("name")
        state = api.callObject("craftFromDB", JSONObject().put("kind", "UNIQUE").put("name", unique))
        println("unique $unique: ${describe(state)}")
        assertTrue(itemLines(state).first().contains(unique.substringBefore(",")))
        assertFalse("desktop tips are left out", itemLines(state).any { it.contains("Tip: ") })
        state = action("editor", "addDisplayItem", "click")
        assertTrue(state.optBoolean("added"))
        assertEquals(itemsBefore + 2, api.callObject("items").getJSONArray("items").length())

        // Editing an item of the build: saved under the same id
        val items = api.callObject("items").getJSONArray("items")
        val id = items.getJSONObject(0).getInt("id")
        state = api.callObject("craftEdit", JSONObject().put("id", id))
        assertTrue(state.getJSONObject("item").getBoolean("editing"))
        assertEquals("Save", find(editor(state), "addDisplayItem")!!.getString("label"))
        state = action("editor", "addDisplayItem", "click")
        assertTrue(state.optBoolean("added"))
        assertEquals(itemsBefore + 2, api.callObject("items").getJSONArray("items").length())

        // Rare templates
        val rares = api.callArray("itemDB", JSONObject().put("kind", "RARE").put("query", ""))
        assertTrue(rares.length() > 10)
        api.callObject("craftFromDB", JSONObject().put("kind", "RARE").put("name", rares.getJSONObject(0).getString("name")))
        state = api.callObject("craftCancel")
        assertFalse(state.has("item"))
        assertNotNull(state)
    }
}
