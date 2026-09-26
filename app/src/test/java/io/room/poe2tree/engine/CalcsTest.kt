package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every Calcs-tab section, cell breakdown and sidebar breakdown of the fixtures can be produced. */
class CalcsTest {

    @Test
    fun allSectionsAndBreakdownsWork() {
        val api = EngineTestSupport.api
        val failures = ArrayList<String>()
        for ((name, xml) in EngineTestSupport.fixtures()) {
            api.call("loadBuild", mapOf("xml" to xml, "name" to name))
            api.call("recalc")
            val sections = api.callArray("calcSections")
            var keys = 0
            for (i in 0 until sections.length()) {
                val sec = sections.getJSONObject(i)
                val subs = sec.getJSONArray("subsections")
                for (j in 0 until subs.length()) {
                    val rows = subs.getJSONObject(j).getJSONArray("rows")
                    for (r in 0 until rows.length()) {
                        val cells = rows.getJSONObject(r).getJSONArray("cells")
                        for (c in 0 until cells.length()) {
                            val key = cells.getJSONObject(c).optString("key")
                            if (key.isEmpty()) continue
                            keys++
                            runCatching { api.callObject("calcBreakdown", mapOf("key" to key)) }
                                .onFailure { failures += "$name $key: ${it.message}" }
                        }
                    }
                }
            }
            val state = api.callObject("state")
            val rows = state.getJSONObject("sidebar").getJSONArray("rows")
            var sidebarBreakdowns = 0
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                if (!row.optBoolean("breakdown")) continue
                sidebarBreakdowns++
                val args = JSONObject().put("stat", row.getString("stat")).put("actor", row.getString("actor"))
                if (row.has("childStat")) args.put("childStat", row.getString("childStat"))
                runCatching { api.callObject("sidebarBreakdown", args) }
                    .onFailure { failures += "$name sidebar ${row.getString("stat")}: ${it.message}" }
            }
            println("$name: ${sections.length()} sections, $keys breakdown cells, $sidebarBreakdowns sidebar breakdowns")
        }
        // A sample, for a look at the data
        api.call("loadBuild", mapOf("xml" to EngineTestSupport.fixtures().getValue("deadeye_lightning_arrow"), "name" to "sample"))
        api.call("recalc")
        val life = api.callObject("sidebarBreakdown", mapOf("stat" to "Life", "actor" to "player"))
        println(life.toString(1).take(3000))
        println(api.callObject("calcsSelection").toString(1).take(1500))
        assertTrue(failures.take(30).joinToString("\n"), failures.isEmpty())
    }
}
