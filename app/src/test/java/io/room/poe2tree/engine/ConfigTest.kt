package io.room.poe2tree.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTest {

    private fun findRow(config: JSONObject, varName: String): JSONObject? {
        val sections = config.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val rows = sections.getJSONObject(i).getJSONArray("rows")
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                if (row.optString("var") == varName) return row
            }
        }
        return null
    }

    @Test
    fun listAndChangeOptions() {
        val api = EngineTestSupport.api
        EngineTestSupport.loadFixture("deadeye_lightning_arrow")
        api.call("recalc")
        val config = api.callObject("config")
        val sections = config.getJSONArray("sections")
        var rows = 0
        for (i in 0 until sections.length()) {
            val s = sections.getJSONObject(i)
            rows += s.getJSONArray("rows").length()
            println("${s.getString("name")}: ${s.getJSONArray("rows").length()} rows")
        }
        assertTrue(rows > 20)
        val all = api.callObject("config", mapOf("showAll" to true))
        var allRows = 0
        for (i in 0 until all.getJSONArray("sections").length()) allRows += all.getJSONArray("sections").getJSONObject(i).getJSONArray("rows").length()
        assertTrue(allRows > rows)

        // The fixture sets "Is the enemy Shocked?"; turning it off lowers damage
        val shocked = findRow(config, "conditionEnemyShocked")!!
        assertEquals(true, shocked.getBoolean("value"))
        val dpsBefore = api.lua.exec("return tostring(build.calcsTab.mainOutput.CombinedDPS)")!!.toDouble()
        val after = api.callObject("setConfig", mapOf("idx" to shocked.getInt("idx"), "value" to false))
        assertEquals(false, findRow(after, "conditionEnemyShocked")!!.getBoolean("value"))
        val dpsAfter = api.lua.exec("return tostring(build.calcsTab.mainOutput.CombinedDPS)")!!.toDouble()
        assertTrue("$dpsBefore -> $dpsAfter", dpsAfter < dpsBefore)

        // List option: boss type by index
        val boss = findRow(after, "enemyIsBoss")!!
        println("enemyIsBoss: ${boss.getJSONArray("choices")} selected ${boss.getInt("selected")}")
        val changed = api.callObject("setConfig", mapOf("idx" to boss.getInt("idx"), "value" to 1))
        assertEquals(1, findRow(changed, "enemyIsBoss")!!.getInt("selected"))

        // Number option with placeholder: enemy level
        val level = findRow(changed, "enemyLevel")!!
        println("enemyLevel placeholder ${level.opt("placeholder")}")
        val set = api.callObject("setConfig", mapOf("idx" to level.getInt("idx"), "value" to 70))
        assertEquals(70, findRow(set, "enemyLevel")!!.getInt("number"))
        val cleared = api.callObject("setConfig", JSONObject().put("idx", level.getInt("idx")).put("value", JSONObject.NULL))
        assertTrue(!findRow(cleared, "enemyLevel")!!.has("number"))

        // Custom modifiers
        val mods = api.callArray("setCustomMods", JSONObject().put("blocks", org.json.JSONArray().put(
            JSONObject().put("title", "Test").put("enabled", true).put("text", "+100 to maximum Life\nnot a real modifier")
        )))
        val lines = mods.getJSONObject(0).getJSONArray("lines")
        assertEquals(true, lines.getJSONObject(0).getBoolean("supported"))
        assertEquals(false, lines.getJSONObject(1).getBoolean("supported"))
        val life = api.lua.exec("return tostring(build.calcsTab.mainOutput.Life)")!!.toDouble()
        assertNotEquals(1790.0, life)
        println("life with custom mod: $life")
    }
}
