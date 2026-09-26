package io.room.poe2tree

import io.room.poe2tree.io.PobCode
import io.room.poe2tree.tree.NodeType
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import io.room.poe2tree.tree.TreeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.hypot

class TreeLogicTest {

    companion object {
        lateinit var tree: PassiveTree

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = listOf(File("src/main/assets/tree"), File("app/src/main/assets/tree")).first { it.exists() }
            tree = PassiveTree.load(File(dir, "tree.json").readText(), File(dir, "sprites.json").readText())
        }
    }

    private fun newSpec(className: String = "Ranger"): PassiveSpec {
        val spec = PassiveSpec(tree)
        spec.selectClass(tree.classByName(className)!!.integerId)
        return spec
    }

    @Test
    fun treeLoads() {
        assertEquals(4914, tree.nodes.size)
        assertEquals(8, tree.classes.size)
        for (c in tree.classes) {
            assertTrue("${c.name} has a start node", c.startNodeIdx >= 0)
            for (a in c.ascendancies) assertTrue("${a.id} has a start node", a.startNodeIdx >= 0)
        }
        assertTrue(tree.connectors.size > 5000)
    }

    @Test
    fun connectorArtExists() {
        val missing = tree.connectors.flatMap { c -> (0..2).map { c.assetName(it) } }.filter { it !in tree.sprites }.toSet()
        assertTrue("missing connector art: $missing", missing.isEmpty())
    }

    @Test
    fun nodeArtExists() {
        val missing = HashSet<String>()
        for (n in tree.nodes) {
            if (n.type == NodeType.ClassStart || n.type == NodeType.OnlyImage) continue
            if (n.type != NodeType.AscendClassStart && n.type != NodeType.Socket && !n.containJewelSocket) {
                n.icon?.let { if (it !in tree.sprites) missing += it }
            }
            n.overlay?.let { o -> listOf(o.alloc, o.path, o.unalloc).forEach { if (it !in tree.sprites) missing += it } }
        }
        assertTrue("missing node art: $missing", missing.isEmpty())
    }

    @Test
    fun arcRibbonsStartAndEndOnTheirNodes() {
        val arcs = tree.connectors.filter { it.isArc }
        assertTrue("arcs: ${arcs.size}", arcs.size > 1000)
        for (c in arcs) {
            val rib = c.ribbon!!
            val last = rib.size - 4
            val sx = (rib[0] + rib[2]) / 2
            val sy = (rib[1] + rib[3]) / 2
            val ex = (rib[last] + rib[last + 2]) / 2
            val ey = (rib[last + 1] + rib[last + 3]) / 2
            val a = tree.nodes[c.node1]
            val b = tree.nodes[c.node2]
            val miss = minOf(
                hypot(sx - a.x, sy - a.y) + hypot(ex - b.x, ey - b.y),
                hypot(sx - b.x, sy - b.y) + hypot(ex - a.x, ey - a.y),
            )
            assertTrue("arc ${a.id}-${b.id} misses its nodes by $miss", miss < 2f)
            assertEquals(c.ribbonU!!.size * 4, rib.size)
        }
    }

    @Test
    fun routeThroughChosenNodesIsAllocatedExactly() {
        val spec = newSpec()
        // Find a detour: target T next to W, where going through W is longer than T's shortest path
        var found: Pair<Int, Int>? = null
        for (w in tree.nodes) {
            if (spec.alloc[w.idx] || w.ascendancyName != null || w.type.isStart || spec.path[w.idx] == null) continue
            val wPath = spec.path[w.idx]!!
            for (t in w.linked) {
                val tn = tree.nodes[t]
                if (spec.alloc[t] || tn.ascendancyName != null || tn.type.isStart || spec.path[t] == null) continue
                if (t in wPath) continue
                if (spec.pathDist[t] <= spec.pathDist[w.idx]) {
                    found = w.idx to t
                    break
                }
            }
            if (found != null && spec.pathDist[found.first] >= 2) break
        }
        val (w, t) = found!!
        val toW = spec.effectiveAllocationPath(w)!!.reversedArray()
        val exclude = BooleanArray(tree.nodes.size).also { arr -> toW.forEach { arr[it] = true } }
        val leg = spec.legPath(w, t, exclude)!!
        assertEquals(listOf(t), leg.toList())
        val route = toW + leg
        assertTrue("detour is longer than the shortest path", route.size > spec.pathDist[t])
        spec.allocRoute(route)
        for (i in route) assertTrue("${tree.nodes[i]} allocated", spec.alloc[i])
        assertEquals(route.size, spec.counts().used)
    }

    @Test
    fun legPathStaysOutOfAscendanciesAndStarts() {
        val spec = newSpec()
        val main = tree.nodes.first { it.ascendancyName == null && !it.type.isStart && spec.path[it.idx] != null }
        val asc = tree.nodes.first { it.ascendancyName != null && !it.type.isStart }
        assertNull(spec.legPath(main.idx, asc.idx, BooleanArray(tree.nodes.size)))
    }

    @Test
    fun flavourTextHasNoEscapedNewlines() {
        val bad = tree.nodes.filter { n -> n.flavourText.any { it.contains("\\n") } }
        assertTrue("escaped newlines in: $bad", bad.isEmpty())
        val vaalPact = tree.nodes.first { it.name == "Vaal Pact" }
        assertEquals(2, vaalPact.flavourText.size)
    }

    @Test
    fun positionsAreWithinBounds() {
        for (n in tree.nodes) {
            assertTrue(n.x >= tree.minX - 1 && n.x <= tree.maxX + 1)
            assertTrue(n.y >= tree.minY - 1 && n.y <= tree.maxY + 1)
        }
    }

    @Test
    fun startNodeIsAllocatedAndNeighboursHavePaths() {
        val spec = newSpec()
        val start = spec.currentClass.startNodeIdx
        assertTrue(spec.alloc[start])
        for (l in tree.nodes[start].linked) {
            // The class start also links to its ascendancy start nodes, which are never pathable
            if (tree.nodes[l].type.isStart || tree.nodes[l].ascendancyName != null) continue
            assertNotNull("neighbour ${tree.nodes[l]} has a path", spec.path[l])
            assertEquals(1, spec.path[l]!!.size)
        }
    }

    /** Some notable reachable from the Ranger start, a few steps away. */
    private fun farNode(spec: PassiveSpec, minDist: Int = 6): Int =
        tree.nodes.first { n ->
            n.type == NodeType.Notable && n.ascendancyName == null && !n.isAttribute &&
                (spec.path[n.idx]?.size ?: 0) >= minDist && n.unlockIdx == null
        }.idx

    @Test
    fun allocatePathAndDeallocateDependents() {
        val spec = newSpec()
        val target = farNode(spec)
        val path = spec.path[target]!!.toList()
        spec.allocNode(target)
        for (p in path) assertTrue("${tree.nodes[p]} allocated", spec.alloc[p])
        assertEquals(path.size, spec.counts().used)
        assertTrue(spec.connectedToStart[target])

        // Removing the first node after the start removes everything behind it
        val first = path.last()
        assertTrue(spec.depends[first].containsAll(path))
        spec.deallocNode(first)
        for (p in path) assertFalse(spec.alloc[p])
        assertEquals(0, spec.counts().used)
    }

    @Test
    fun weaponSetAllocationAndPromotion() {
        val spec = newSpec()
        val target = tree.nodes.first { n ->
            n.type == NodeType.Normal && n.ascendancyName == null && !n.isAttribute && (spec.path[n.idx]?.size ?: 0) == 3
        }.idx
        val path = spec.path[target]!!.toList()
        spec.currentAllocMode = 1
        spec.allocNode(target)
        for (p in path) assertEquals(1, spec.allocMode[p])
        val c = spec.counts()
        assertEquals(3, c.ws1)
        assertEquals(0, c.ws2)
        assertEquals(3, c.normal) // ws points come from the main pool when not paired

        // Weapon set 2 cannot path through weapon set 1 nodes
        spec.currentAllocMode = 2
        val next = tree.nodes[target].linked.firstOrNull { !spec.alloc[it] && tree.nodes[it].ascendancyName == null && !tree.nodes[it].isGlobal }
        if (next != null) {
            val p2 = spec.effectiveAllocationPath(next)
            if (p2 != null) for (p in p2) assertTrue(spec.allocMode[p] != 1 || !spec.alloc[p])
        }

        // Normal allocation through the weapon-set branch promotes it back to normal
        spec.currentAllocMode = 0
        if (next != null && spec.path[next] != null) {
            spec.allocNode(next)
            for (p in path) assertEquals(0, spec.allocMode[p])
        }
    }

    @Test
    fun globalNodesBlockedInWeaponSetMode() {
        val spec = newSpec()
        spec.currentAllocMode = 1
        val keystone = tree.nodes.firstOrNull { it.type == NodeType.Keystone && spec.path[it.idx] != null }
        if (keystone != null) assertTrue(spec.isGlobalAllocationBlocked(keystone.idx))
    }

    @Test
    fun attributeNodesTakeChosenAttribute() {
        val spec = newSpec()
        val attr = tree.nodes.first { it.isAttribute && spec.path[it.idx] != null }
        spec.attributeIndex = 2
        spec.allocNode(attr.idx)
        assertEquals(2, spec.attributeOverride[attr.idx])
        assertEquals("Dexterity", spec.views[attr.idx].name)
        spec.switchAttribute(attr.idx, 3)
        assertEquals("Intelligence", spec.views[attr.idx].name)
    }

    @Test
    fun ascendancyAllocation() {
        val spec = newSpec()
        val cls = spec.currentClass
        val asc = cls.ascendancies.first()
        spec.selectAscendClass(asc.index)
        assertTrue(spec.alloc[asc.startNodeIdx])
        val ascNode = tree.nodes.first { it.ascendancyName == asc.id && !it.type.isStart && spec.path[it.idx] != null }
        spec.allocNode(ascNode.idx)
        assertTrue(spec.counts().ascUsed > 0)
        assertEquals(0, spec.counts().used)
        // Switching ascendancy prunes the old ascendancy's nodes
        val other = cls.ascendancies[1]
        spec.selectAscendClass(other.index)
        assertFalse(spec.alloc[ascNode.idx])
        assertEquals(0, spec.counts().ascUsed)
    }

    @Test
    fun switchableNodesUseClassOptions() {
        val witchNode = tree.nodes.first { it.isSwitchable && it.switchOptions.containsKey("Witch") }
        val spec = newSpec("Witch")
        assertEquals(witchNode.switchOptions["Witch"]!!.name, spec.views[witchNode.idx].name)
        val sorc = newSpec("Sorceress")
        assertEquals(witchNode.name, sorc.views[witchNode.idx].name)
    }

    @Test
    fun classSwitchKeepsSharedStart() {
        // Ranger and Huntress share a start node, so the tree survives the switch
        val spec = newSpec("Ranger")
        val target = farNode(spec)
        spec.allocNode(target)
        val used = spec.counts().used
        val huntress = tree.classByName("Huntress")!!.integerId
        assertTrue(spec.isClassConnected(huntress))
        spec.selectClass(huntress)
        assertEquals(used, spec.counts().used)

        // Witch starts elsewhere: switching prunes the tree
        val witch = tree.classByName("Witch")!!.integerId
        assertFalse(spec.isClassConnected(witch))
        spec.selectClass(witch)
        assertEquals(0, spec.counts().used)
    }

    @Test
    fun connectToClassBuildsPath() {
        val spec = newSpec("Ranger")
        spec.allocNode(farNode(spec, 3))
        val witch = tree.classByName("Witch")!!.integerId
        assertTrue(spec.connectToClass(witch))
        assertTrue(spec.isClassConnected(witch))
    }

    @Test
    fun snapshotRestoreRoundTrip() {
        val spec = newSpec()
        spec.allocNode(farNode(spec))
        spec.currentAllocMode = 2
        spec.allocNode(tree.nodes.first { it.type == NodeType.Normal && it.ascendancyName == null && !spec.alloc[it.idx] && spec.path[it.idx]?.size == 1 }.idx)
        val snap = spec.snapshot()
        val other = PassiveSpec(tree)
        other.restore(snap)
        assertEquals(snap, other.snapshot())
    }

    @Test
    fun pobCodeRoundTrip() {
        val spec = newSpec("Mercenary")
        spec.selectAscendClass(2)
        spec.allocNode(farNode(spec))
        val attr = tree.nodes.firstOrNull { it.isAttribute && spec.path[it.idx] != null }
        if (attr != null) {
            spec.attributeIndex = 3
            spec.allocNode(attr.idx)
        }
        spec.currentAllocMode = 1
        spec.allocNode(tree.nodes.first { it.type == NodeType.Normal && it.ascendancyName == null && !spec.alloc[it.idx] && spec.path[it.idx]?.size == 1 }.idx)

        val code = PobCode.export(spec, "Test build", 90)
        val result = PobCode.import(code, tree)
        assertEquals(spec.snapshot(), result.snapshot)
        assertEquals("Test build", result.title)
        assertEquals(90, result.level)
        assertTrue(result.warnings.isEmpty())

        val xml = PobCode.decodeXml(code)
        assertTrue(xml.contains("<PathOfBuilding2>"))
        assertTrue(xml.contains("ascendancyInternalId=\"Mercenary2\""))
    }

    @Test
    fun importPobSavedXml() {
        // Shaped like PoB's PassiveSpec:Save output
        val start = tree.classByName("Witch")!!
        val spec = PassiveSpec(tree)
        spec.selectClass(start.integerId)
        val n = farNode(spec, 4)
        val ids = (spec.path[n]!!.map { tree.nodes[it].id } + tree.nodes[start.startNodeIdx].id).sorted()
        val ws = tree.nodes[spec.path[n]!!.first()].id
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <PathOfBuilding2>
              <Build level="75" targetVersion="0_1" className="Witch" ascendClassName="Infernalist" mainSocketGroup="1"/>
              <Tree activeSpec="1">
                <Spec title="Leveling" treeVersion="0_4" classId="1" ascendClassId="1" classInternalId="1" ascendancyInternalId="Witch1" secondaryAscendClassId="0" nodes="${ids.joinToString(",")}" masteryEffects="">
                  <URL>https://www.pathofexile.com/passive-skill-tree/AAAABgE</URL>
                  <WeaponSet1 nodes="$ws"/>
                  <Sockets/>
                  <Overrides><AttributeOverride strNodes="" dexNodes="" intNodes=""/></Overrides>
                </Spec>
              </Tree>
              <Items/>
            </PathOfBuilding2>
        """.trimIndent()
        val result = PobCode.importXml(xml, tree)
        assertEquals(start.integerId, result.snapshot.classId)
        assertEquals(1, result.snapshot.ascendClassId)
        assertEquals(1, result.snapshot.nodes[ws])
        assertEquals(1, result.warnings.size) // tree version conversion notice
        val restored = PassiveSpec(tree)
        restored.restore(result.snapshot)
        assertTrue(restored.alloc[n])
        assertEquals("Infernalist", restored.currentAscendancy!!.id)
    }

    @Test
    fun rejectsGarbage() {
        try {
            PobCode.import("not a code!!", tree)
            throw AssertionError("expected failure")
        } catch (e: PobCode.ImportException) {
            // expected
        }
    }

    @Test
    fun summarySumsStats() {
        val spec = newSpec()
        spec.allocNode(farNode(spec, 8))
        val summary = TreeText.summarize(spec)
        assertTrue(summary.stats.isNotEmpty())
    }

    @Test
    fun searchFindsKeystones() {
        val spec = newSpec()
        val ks = tree.nodes.first { it.type == NodeType.Keystone }
        val words = TreeText.searchWords("\"${ks.name}\"")
        assertTrue(TreeText.matches(spec, ks.idx, words))
        assertNull(null)
    }

    @Test
    fun allocationIsFastEnough() {
        val spec = newSpec()
        val t0 = System.nanoTime()
        repeat(20) {
            spec.allocNode(farNode(spec, 3))
        }
        val ms = (System.nanoTime() - t0) / 1e6
        println("20 allocations: %.1f ms, used=%d".format(ms, spec.counts().used))
        assertTrue(ms < 5000)
    }
}
