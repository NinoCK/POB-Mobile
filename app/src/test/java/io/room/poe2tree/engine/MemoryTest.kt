package io.room.poe2tree.engine

import io.room.poe2tree.io.PobCode
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Repeated changes must not grow the Lua heap: PoB's undo states used to keep every generation of
 * the tree's attribute overrides (and of items / skills / configuration) alive.
 * The heap is left to the incremental collector during the loop, as in the app; a full collection
 * before and after measures the live data.
 */
class MemoryTest {
    private val api get() = EngineTestSupport.api

    private fun liveMb(): Double =
        api.lua.exec("collectgarbage('collect') collectgarbage('collect') return tostring(collectgarbage('count') / 1024)")!!.toDouble()

    private fun heapMb(): Double = api.lua.exec("return tostring(collectgarbage('count') / 1024)")!!.toDouble()

    @Test
    fun treePushesDoNotLeak() {
        val dir = File(EngineTestSupport.assets, "tree")
        val tree = PassiveTree.load(File(dir, "tree.json").readText(), File(dir, "sprites.json").readText())
        val xml = File(EngineTestSupport.fixtures, "deadeye_lightning_arrow.xml").readText()
        val spec = PassiveSpec(tree)
        spec.restore(PobCode.importXml(xml, tree).snapshot)
        val less = spec.snapshot()
        // A long path with attribute nodes on it, like allocating a far keystone in the app
        val keystone = tree.nodes.first { it.name == "Chaos Inoculation" }.idx
        spec.attributeIndex = 2
        spec.allocNode(keystone)
        val full = spec.snapshot()
        assertTrue(full.nodes.size > less.nodes.size + 5)
        api.callObject("openBuild", JSONObject().put("xml", xml).put("name", "mem").put("tree", full.toEngineJson()))
        val start = liveMb()
        var peak = 0.0
        for (i in 1..120) {
            api.callObject("setTree", (if (i % 2 == 0) full else less).toEngineJson())
            peak = maxOf(peak, heapMb())
        }
        val end = liveMb()
        println("tree pushes: live %.1f -> %.1f MB, peak %.1f MB".format(start, end, peak))
        assertTrue("live heap grew from $start to $end MB", end < start + 3)
        // The incremental collector keeps up (pause 200: about twice the live data)
        assertTrue("heap peaked at $peak MB", peak < start * 3)
    }

    /**
     * Attribute node overrides are deep copies of the shared tree's nodes (PoB's
     * PassiveSpec:SwitchAttributeNode). PoB's copyTableSafe wrote the fields of copied class proxies
     * into the original objects, linking the shared tree to every new copy (fixed in Host.lua).
     */
    @Test
    fun attributeSwitchesLeaveTheTreeUnchanged() {
        EngineTestSupport.loadFixture("deadeye_lightning_arrow")
        api.lua.exec(
            """
            function __treeAttributeTables()
                local seen, n, stack = { }, 0, { }
                for _, node in pairs(build.spec.tree.nodes) do
                    if node.isAttribute then stack[#stack + 1] = node end
                end
                while #stack > 0 do
                    local t = table.remove(stack)
                    if not seen[t] then
                        seen[t] = true
                        n = n + 1
                        for k, v in next, t do
                            if type(k) == "table" then stack[#stack + 1] = k end
                            if type(v) == "table" then stack[#stack + 1] = v end
                        end
                    end
                end
                return tostring(n)
            end
            """.trimIndent(), "treeAttributeTables"
        )
        val switch = "for id in pairs(build.spec.hashOverrides) do build.spec:SwitchAttributeNode(id, 1) build.spec:SwitchAttributeNode(id, 3) end"
        api.lua.exec(switch)
        val start = api.lua.exec("return __treeAttributeTables()")!!.toInt()
        repeat(10) { api.lua.exec(switch) }
        val end = api.lua.exec("return __treeAttributeTables()")!!.toInt()
        assertTrue("the tree's attribute nodes grew from $start to $end tables", end == start)
    }

    @Test
    fun editsDoNotLeak() {
        EngineTestSupport.loadFixture("warrior_boneshatter_weaponsets")
        api.call("recalc")
        val config = api.callObject("config")
        val idx = (0 until config.getJSONArray("sections").length()).asSequence()
            .flatMap { i -> config.getJSONArray("sections").getJSONObject(i).getJSONArray("rows").let { r -> (0 until r.length()).map { r.getJSONObject(it) } } }
            .first { it.optString("type") == "check" }.getInt("idx")
        val weapon = api.callObject("items").getJSONArray("slots").getJSONObject(0).getInt("itemId")
        fun edits(count: Int) {
            for (i in 1..count) {
                api.callObject("setConfig", mapOf("idx" to idx, "value" to (i % 2 == 0)))
                api.callObject("skillEdit", mapOf("op" to "gemLevel", "group" to 1, "gem" to 1, "value" to if (i % 2 == 0) 20 else 19))
                api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Weapon 1", "id" to if (i % 2 == 0) weapon else 0))
                api.callObject("select", mapOf("useSecondWeaponSet" to (i % 2 == 0)))
            }
        }
        val start = liveMb()
        // The tabs' undo histories fill up to PoB's limit (100 states), then stop growing
        edits(110)
        val full = liveMb()
        edits(30)
        val end = liveMb()
        println("edits: live %.1f MB, with full undo histories %.1f MB, after more edits %.1f MB".format(start, full, end))
        assertTrue("undo histories take ${full - start} MB", full < start + 10)
        assertTrue("live heap grew from $full to $end MB", end < full + 3)
    }
}
