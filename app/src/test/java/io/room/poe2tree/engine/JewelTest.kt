package io.room.poe2tree.engine

import io.room.poe2tree.tree.NodeType
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import io.room.poe2tree.tree.TreeJewels
import io.room.poe2tree.tree.parseTreeJewels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jewels acting on the tree: the engine's description of them (radius, conquered passives, PoB's
 * node text), and the app's pathing with them (allocation without a connection, another class's
 * start), which must match PoB's.
 */
class JewelTest {
    private val api get() = EngineTestSupport.api

    private val tree: PassiveTree by lazy {
        val dir = File(EngineTestSupport.assets, "tree")
        PassiveTree.load(File(dir, "tree.json").readText(), File(dir, "sprites.json").readText())
    }

    private val ranger get() = tree.classes.first { it.name == "Ranger" }

    /** A new Ranger build with the jewel socket nearest to the start allocated. */
    private fun setup(): Pair<PassiveSpec, Int> {
        EngineTestSupport.newBuild()
        val spec = PassiveSpec(tree)
        spec.selectClass(ranger.integerId)
        val socket = tree.nodes.filter { it.type == NodeType.Socket && it.name == "Jewel Socket" && spec.path[it.idx] != null }
            .minBy { spec.pathDist[it.idx] }.idx
        spec.allocNode(socket)
        push(spec)
        return spec to socket
    }

    private fun push(spec: PassiveSpec): EngineState = EngineJson.state(api.callObject("setTree", spec.snapshot().toEngineJson()))

    private fun socketJewel(text: String, socket: Int): Int {
        val id = api.callObject("itemEdit", mapOf("op" to "add", "text" to text.trimIndent())).getInt("addedId")
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Jewel ${tree.nodes[socket].id}", "id" to id))
        return id
    }

    private fun overlay(): TreeJewels = parseTreeJewels(api.callObject("state").getJSONObject("overlay"), tree)

    private fun info(node: Int): List<String> =
        EngineJson.nodeCompare(api.callObject("nodeCompare", mapOf("id" to tree.nodes[node].id))).info

    private fun pobLeap(node: Int) = api.lua.exec("return tostring(#build.spec.nodes[${tree.nodes[node].id}].intuitiveLeapLikesAffecting)")!!.toInt()

    private fun pobDepends(of: Int, node: Int) = api.lua.exec(
        "return tostring(isValueInArray(build.spec.nodes[${tree.nodes[of].id}].depends, build.spec.nodes[${tree.nodes[node].id}]) ~= nil)"
    ) == "true"

    @Test
    fun timelessJewelConquersPassives() {
        val (spec, socket) = setup()
        socketJewel(
            """
            Rarity: UNIQUE
            Undying Hate
            Timeless Jewel
            Radius: Very Large
            Glorifying the defilement of 5000 souls in tribute to Amanamu
            Passives in radius are Conquered by the Abyssals
            Historic
            """, socket,
        )
        val jewels = overlay()
        val sj = jewels.bySocket[socket]
        assertNotNull(sj)
        assertEquals("abyss", sj!!.conqueror)
        assertEquals(4, sj.radiusIndex)
        assertTrue("conquered passives", jewels.replaced.isNotEmpty())
        spec.setJewels(jewels, known = true)
        val (replacedNode, replacement) = jewels.replaced.entries.first()
        assertEquals(replacement.name, spec.views[replacedNode].name)
        assertTrue(spec.radiusIndex.contains(socket, jewels.radii, 4, replacedNode))
        println("conquered: ${jewels.replaced.size} passives, e.g. ${tree.nodes[replacedNode].name} -> ${replacement.name} (${replacement.icon})")
        println("socket text: ${info(socket).take(6)}")
        assertTrue(info(socket).any { it.contains("Undying Hate") })
        assertTrue(info(replacedNode).first().contains(replacement.name))
    }

    @Test
    fun controlledMetamorphosisAllowsUnconnectedPassives() {
        val (spec, socket) = setup()
        socketJewel(
            """
            Rarity: UNIQUE
            Controlled Metamorphosis
            Diamond
            Radius: Variable
            Only affects Passives in Medium Ring
            Passives in Radius can be Allocated without being connected to your tree
            -10% to all Elemental Resistances
            """, socket,
        )
        val jewels = overlay()
        val sj = jewels.bySocket[socket]!!
        assertTrue(sj.leap && sj.variable)
        assertEquals(8, sj.radiusIndex)
        spec.setJewels(jewels, known = true)
        // A passive in the ring, away from the tree
        val target = tree.nodes.first { n ->
            (n.type == NodeType.Normal || n.type == NodeType.Notable) && !spec.alloc[n.idx] && spec.pathDist[n.idx] > 2 &&
                spec.canAllocateUnconnected(n.idx)
        }.idx
        assertTrue(spec.radiusIndex.contains(socket, jewels.radii, 8, target))
        val before = spec.counts().used
        spec.allocNode(target)
        assertEquals("only the passive is allocated", before + 1, spec.counts().used)
        val state = push(spec)
        assertTrue("PoB dropped ${state.droppedNodes}", state.droppedNodes.isEmpty())
        assertTrue("PoB allocates it through the jewel", pobLeap(target) > 0)
        // Removing the socket removes it, in both
        assertTrue(target in spec.depends[socket])
        assertTrue(pobDepends(socket, target))

        // Without the jewel it is no longer connected: both remove it
        api.callObject("itemEdit", mapOf("op" to "equip", "slot" to "Jewel ${tree.nodes[socket].id}", "id" to 0))
        val pobState = push(spec)
        assertEquals(listOf(tree.nodes[target].id), pobState.droppedNodes)
        spec.setJewels(overlay(), known = true)
        assertFalse(spec.alloc[target])
    }

    @Test
    fun fromNothingAllowsPassivesNearAKeystone() {
        val (spec, socket) = setup()
        socketJewel(
            """
            Rarity: UNIQUE
            From Nothing
            Diamond
            Radius: Small
            Passives in radius of Chaos Inoculation can be Allocated without being connected to your tree
            Corrupted
            """, socket,
        )
        val jewels = overlay()
        val sj = jewels.bySocket[socket]!!
        val keystone = tree.nodes.first { it.name == "Chaos Inoculation" }.idx
        assertEquals(listOf(keystone), sj.fromNothing.toList())
        spec.setJewels(jewels, known = true)
        val target = tree.nodes.first { n -> !spec.alloc[n.idx] && n.type != NodeType.Keystone && spec.canAllocateUnconnected(n.idx) }.idx
        assertTrue(spec.radiusIndex.contains(keystone, jewels.radii, sj.radiusIndex, target))
        spec.allocNode(target)
        val state = push(spec)
        assertTrue("PoB dropped ${state.droppedNodes}", state.droppedNodes.isEmpty())
        assertTrue(pobLeap(target) > 0)
    }

    @Test
    fun splitPersonalityAddsAClassStart() {
        val (spec, socket) = setup()
        socketJewel(
            """
            Rarity: UNIQUE
            Split Personality
            Ruby
            Can Allocate Passive Skills from the Warrior's starting point
            Corrupted
            """, socket,
        )
        val jewels = overlay()
        val warriorStart = tree.classes.first { it.name == "Warrior" }.startNodeIdx
        assertEquals(warriorStart, jewels.bySocket[socket]!!.alternateStart)
        assertTrue(spec.path[tree.nodes[warriorStart].linked.first { !tree.nodes[it].type.isStart }] == null ||
            spec.pathDist[tree.nodes[warriorStart].linked.first { !tree.nodes[it].type.isStart }] > 1)
        spec.setJewels(jewels, known = true)
        val next = tree.nodes[warriorStart].linked.first { !tree.nodes[it].type.isStart && tree.nodes[it].ascendancyName == null }
        assertEquals(1, spec.pathDist[next])
        spec.allocNode(next)
        assertTrue(spec.alloc[next])
        val state = push(spec)
        assertTrue("PoB dropped ${state.droppedNodes}", state.droppedNodes.isEmpty())
        // Connected through the other start, not through the socket (as in PoB)...
        assertFalse(next in spec.depends[socket])
        assertFalse(pobDepends(socket, next))
        // ...until the socket is removed: then it is no longer connected, and removed
        spec.deallocNode(socket)
        assertFalse(spec.alloc[next])
        assertTrue(push(spec).droppedNodes.isEmpty())
    }

    @Test
    fun timeLostJewelChangesPassivesInRadius() {
        val (spec, socket) = setup()
        socketJewel(
            """
            Rarity: RARE
            Test Jewel
            Time-Lost Ruby
            Radius: Small
            Small Passive Skills in Radius also grant 20% increased Accuracy Rating
            """, socket,
        )
        val jewels = overlay()
        val sj = jewels.bySocket[socket]!!
        assertEquals(1, sj.radiusIndex)
        spec.setJewels(jewels, known = true)
        // An allocated small passive in the radius
        val small = spec.allocatedIndices().firstOrNull { i ->
            tree.nodes[i].type == NodeType.Normal && !tree.nodes[i].isAttribute && spec.radiusIndex.contains(socket, jewels.radii, 1, i)
        } ?: tree.nodes.first { n -> n.type == NodeType.Normal && !n.isAttribute && spec.radiusIndex.contains(socket, jewels.radii, 1, n.idx) }.idx
        val lines = info(small)
        println("${tree.nodes[small].name}: $lines")
        assertTrue(lines.any { it.contains("Accuracy Rating") })
    }

    @Test
    fun overlayListsNothingWithoutJewels() {
        setup()
        val jewels = overlay()
        assertTrue(jewels.sockets.isEmpty() && jewels.replaced.isEmpty())
        assertEquals(12, jewels.radii.size)
        assertTrue(jewels.leapSources.isNotEmpty())
        println("leap sources: ${jewels.leapSources.map { tree.nodes[it.node].name to it.to }}")
        println(JSONObject(api.callObject("state").getJSONObject("overlay").toString()).toString().take(400))
    }
}
