package io.room.poe2tree.engine

import io.room.poe2tree.io.PobCode
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The app's tree (Kotlin) pushed into PoB must give the results PoB gets from the build's own XML. */
class TreeSyncTest {

    private val tree: PassiveTree by lazy {
        val dir = File(EngineTestSupport.assets, "tree")
        PassiveTree.load(File(dir, "tree.json").readText(), File(dir, "sprites.json").readText())
    }

    @Test
    fun pushedTreeMatchesXmlTree() {
        val api = EngineTestSupport.api
        for ((name, xml) in EngineTestSupport.fixtures()) {
            val expected = JSONObject(File(EngineTestSupport.fixtures, "$name.expected.json").readText())
            val imported = PobCode.importXml(xml, tree)
            // Normalise through the Kotlin spec, like the app does when it opens a build
            val spec = PassiveSpec(tree)
            spec.restore(imported.snapshot)
            val snapshot = spec.snapshot()
            assertEquals("$name: Kotlin kept every node", imported.snapshot.nodes.keys, snapshot.nodes.keys)
            // Load the build with an empty tree, then push the app's tree
            val state = EngineJson.state(
                api.callObject("openBuild", JSONObject().put("xml", xml).put("name", name).put("tree", snapshot.toEngineJson()))
            )
            assertTrue("$name: PoB dropped ${state.droppedNodes}", state.droppedNodes.isEmpty())
            // Same tree as the XML: no second calculation
            assertTrue("$name: tree should match its XML", !state.changed)
            val out = expected.getJSONObject("mainOutput")
            for (stat in listOf("CombinedDPS", "Life", "EnergyShield", "Mana", "Str", "Dex", "Int", "TotalEHP")) {
                if (!out.has(stat)) continue
                val actual = api.lua.exec("return string.format('%.17g', build.calcsTab.mainOutput.$stat)")
                assertEquals("$name.$stat", out.getString(stat).toDouble(), actual!!.toDouble(), 1e-9 * maxOf(1.0, out.getString(stat).toDouble()))
            }
            println("$name: tree sync OK (${snapshot.nodes.size} nodes, ${snapshot.attributes.size} attributes)")
        }
    }

    @Test
    fun changedTreeIsApplied() {
        val api = EngineTestSupport.api
        val xml = File(EngineTestSupport.fixtures, "stormweaver_spark.xml").readText()
        val spec = PassiveSpec(tree)
        spec.restore(PobCode.importXml(xml, tree).snapshot)
        val before = spec.snapshot()
        // Remove one allocated leaf notable
        val leaf = spec.allocatedIndices().first { i -> tree.nodes[i].type.name == "Notable" && spec.depends[i].size == 1 }
        spec.deallocNode(leaf)
        val after = spec.snapshot()
        assertTrue(after.nodes.size == before.nodes.size - 1)
        val state = EngineJson.state(api.callObject("openBuild", JSONObject().put("xml", xml).put("name", "changed").put("tree", after.toEngineJson())))
        assertTrue("a different tree must be applied", state.changed)
        val count = api.lua.exec("local n = 0 for _ in pairs(build.spec.allocNodes) do n = n + 1 end return tostring(n)")!!.toInt()
        assertEquals(after.nodes.size, count)
        // A different level too
        val leveled = EngineJson.state(api.callObject("openBuild", JSONObject().put("xml", xml).put("name", "level").put("tree", before.toEngineJson()).put("level", 50)))
        assertTrue(leveled.changed)
        assertEquals(50, leveled.level)
    }

    @Test
    fun nodeCompareListsStatChanges() {
        val api = EngineTestSupport.api
        val xml = File(EngineTestSupport.fixtures, "deadeye_lightning_arrow.xml").readText()
        api.call("loadBuild", mapOf("xml" to xml, "name" to "compare"))
        api.call("recalc")
        // An allocated notable: removing it changes stats
        val allocated = api.lua.exec(
            "for id, node in pairs(build.spec.allocNodes) do if node.type == 'Notable' then return tostring(id) end end"
        )!!.toInt()
        val removal = EngineJson.nodeCompare(api.callObject("nodeCompare", mapOf("id" to allocated)))
        println(removal.lines.joinToString("\n"))
        assertTrue(removal.lines.first().contains("Unallocating this node"))
        assertTrue(removal.count > 0)
        // An unallocated notable next to the tree
        val next = api.lua.exec(
            "local best for id, node in pairs(build.spec.nodes) do if not node.alloc and node.type == 'Notable' and not node.ascendancyName and node.path and #node.path > 0 and (not best or node.pathDist < best.pathDist) then best = node end end return tostring(best.id)"
        )!!.toInt()
        val add = EngineJson.nodeCompare(api.callObject("nodeCompare", mapOf("id" to next)))
        println(add.lines.joinToString("\n"))
        assertTrue(add.lines.first().contains("Allocating this node") || add.lines.first().contains("No changes"))
    }
}
