package io.room.poe2tree.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PoB's node power (heat map, power report), built in steps. */
class PowerTest {
    private val api get() = EngineTestSupport.api

    private fun build(stat: Int, maxDepth: Int?): org.json.JSONObject {
        api.call("powerStart", if (maxDepth != null) mapOf("stat" to stat, "maxDepth" to maxDepth) else mapOf("stat" to stat))
        var steps = 0
        val t0 = System.nanoTime()
        while (true) {
            val step = api.callObject("powerStep", mapOf("ms" to 150))
            steps++
            if (step.getBoolean("done")) {
                println("stat $stat depth $maxDepth: $steps steps, ${(System.nanoTime() - t0) / 1_000_000} ms")
                return step.getJSONObject("result")
            }
            assertTrue(step.getInt("progress") in 0..100)
        }
    }

    @Test
    fun heatMapAndReport() {
        EngineTestSupport.loadFixture("deadeye_lightning_arrow")
        api.call("recalc")
        val stats = api.callArray("powerStats")
        assertEquals("Offence/Defence", stats.getJSONObject(0).getString("label"))
        val combined = build(1, 5)
        assertTrue(!combined.getBoolean("single"))
        val nodes = combined.getJSONArray("nodes")
        assertTrue(nodes.length() > 50)
        assertTrue(combined.getJSONObject("max").getDouble("offence") > 0)
        // Life as a single statistic, with the report
        val lifeIndex = (0 until stats.length()).first { stats.getJSONObject(it).optString("stat") == "Life" } + 1
        val life = build(lifeIndex, 5)
        assertTrue(life.getBoolean("single"))
        val report = life.getJSONArray("report")
        assertTrue(report.length() > 10)
        val top = report.getJSONObject(0)
        println("top life node: ${top.getString("name")} ${top.getString("powerStr")} (path ${top.getString("pathPowerStr")}, ${top.getInt("pathDist")} points)")
        assertTrue(top.getDouble("power") > 0)
        // The same values as PoB's own tooltip calculation for that node
        val id = top.getInt("id")
        val direct = api.lua.exec(
            """
            local node = build.spec.nodes[$id]
            local calcFunc, calcBase = build.calcsTab:GetMiscCalculator()
            local output = node.alloc and calcFunc({ removeNodes = { [node] = true } }) or calcFunc({ addNodes = { [node] = true } })
            return tostring(output.Life - calcBase.Life)
            """.trimIndent()
        )!!.toDouble()
        assertEquals(direct, top.getDouble("power"), 1e-6)
    }
}
