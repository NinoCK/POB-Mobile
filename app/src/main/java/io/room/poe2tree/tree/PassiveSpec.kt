package io.room.poe2tree.tree

/**
 * Allocation state of the passive tree, with PoB's pathing and dependency rules.
 * Port of PoB's PassiveSpec.lua, without masteries and calculations. Jewels that change the rules
 * (allocation without a connection, another class's start) come from the engine: [jewels].
 *
 * Allocation modes: 0 = normal points, 1 = weapon set 1, 2 = weapon set 2.
 */
class PassiveSpec(val tree: PassiveTree) {
    private val nodes = tree.nodes
    private val n = nodes.size

    val alloc = BooleanArray(n)
    val allocMode = IntArray(n)

    /** Chosen attribute for attribute nodes: 0 = none, 1 = Str, 2 = Dex, 3 = Int. */
    val attributeOverride = IntArray(n)

    var classId: Int = tree.classes.first().integerId
        private set
    /** 1-based ascendancy index within the class, 0 = none. */
    var ascendClassId: Int = 0
        private set

    /** Current allocation mode used for new allocations. */
    var currentAllocMode: Int = 0

    /** Last chosen attribute (1..3) used for attribute nodes on a path. */
    var attributeIndex: Int = 1

    /** Jewels and items acting on the tree, from Path of Building (see [setJewels]). */
    var jewels: TreeJewels = TreeJewels.EMPTY
        private set
    /**
     * False while the open build's jewels are not known yet: unconnected nodes are then kept, as
     * they may be allocated through a jewel (PoB keeps them).
     */
    var jewelsKnown = true
        private set
    val radiusIndex = RadiusIndex(tree)
    private val keystones = nodes.filter { it.type == NodeType.Keystone && it.group > 0 }.map { it.idx }

    // ---- Derived state (rebuilt by [rebuild]) ----
    val depends: Array<ArrayList<Int>> = Array(n) { ArrayList() }
    val connectedToStart = BooleanArray(n)
    val pathDist = IntArray(n)
    /** Path from the node back towards the tree (node first, allocated root excluded). */
    val path: Array<IntArray?> = arrayOfNulls(n)
    val pathRoot = IntArray(n) { -1 }
    val views: Array<NodeView> = Array(n) { nodes[it].baseView }
    /**
     * Sockets and keystones that let each node be allocated without being connected to the tree
     * (PoB `intuitiveLeapLikesAffecting`).
     */
    val leapAffecting: Array<IntArray> = Array(n) { NONE }
    /** Class starts of other classes the tree may be connected to (jewels). */
    private val altStart = BooleanArray(n)

    private val visitedFlag = BooleanArray(n)

    val currentClass: ClassInfo get() = tree.classById(classId)!!
    val currentAscendancy: AscendancyInfo? get() = currentClass.ascendancy(ascendClassId)

    init {
        selectClass(classId)
    }

    /** Sets the jewels of the open build ([known] = false while they are not known yet) and rebuilds. */
    fun setJewels(value: TreeJewels, known: Boolean) {
        jewels = value
        jewelsKnown = known
        rebuild()
    }

    fun canAllocateUnconnected(node: Int) = leapAffecting[node].isNotEmpty()

    // ---------------------------------------------------------------------------------------
    // Class / ascendancy
    // ---------------------------------------------------------------------------------------

    fun selectClass(newClassId: Int) {
        tree.classById(classId)?.startNodeIdx?.takeIf { it >= 0 }?.let { deallocRaw(it) }
        resetAscendClass()
        classId = newClassId
        val start = currentClass.startNodeIdx
        if (start >= 0) {
            alloc[start] = true
            allocMode[start] = 0
        }
        ascendClassId = 0
        selectAscendClass(0)
    }

    private fun resetAscendClass() {
        val asc = tree.classById(classId)?.ascendancy(ascendClassId) ?: return
        if (asc.startNodeIdx >= 0) deallocRaw(asc.startNodeIdx)
    }

    fun selectAscendClass(newAscendClassId: Int) {
        resetAscendClass()
        ascendClassId = newAscendClassId
        val asc = currentAscendancy
        if (asc != null && asc.startNodeIdx >= 0) {
            alloc[asc.startNodeIdx] = true
            allocMode[asc.startNodeIdx] = 0
        }
        rebuild()
    }

    /** Is the given class's start node connected to the current tree without passing through ascendancies. */
    fun isClassConnected(targetClassId: Int): Boolean {
        val start = tree.classById(targetClassId)?.startNodeIdx ?: return false
        if (start < 0) return false
        for (other in nodes[start].linked) {
            if (alloc[other]) {
                visitedFlag[other] = true
                val visited = ArrayList<Int>()
                val found = findStartFromNode(other, visited, noAscend = true, mode = allocMode[other])
                for (v in visited) visitedFlag[v] = false
                visitedFlag[other] = false
                if (found) return true
            }
        }
        return false
    }

    /** Allocate the shortest main-tree path connecting the tree to another class's start. */
    fun connectToClass(targetClassId: Int): Boolean {
        val target = tree.classById(targetClassId)?.startNodeIdx ?: return false
        if (target < 0) return false
        fun isMain(i: Int) = nodes[i].ascendancyName == null && !nodes[i].type.isStart
        val prev = IntArray(n) { -1 }
        val seen = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        queue += target
        seen[target] = true
        var found = -1
        while (queue.isNotEmpty() && found < 0) {
            val node = queue.removeFirst()
            if (node != target && alloc[node] && connectedToStart[node] && !nodes[node].type.isStart) {
                found = node
                break
            }
            for (l in nodes[node].linked) {
                if (isMain(l) && !seen[l]) {
                    seen[l] = true
                    prev[l] = node
                    queue += l
                }
            }
        }
        if (found < 0) return false
        var cur = found
        while (cur >= 0 && cur != target) {
            if (!alloc[cur]) {
                alloc[cur] = true
                allocMode[cur] = 0
                if (nodes[cur].isAttribute) setAttribute(cur, attributeIndex)
            }
            cur = prev[cur]
        }
        rebuild()
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Allocation
    // ---------------------------------------------------------------------------------------

    fun resetNodes() {
        for (i in 0 until n) {
            if (!nodes[i].type.isStart) {
                alloc[i] = false
                allocMode[i] = 0
                attributeOverride[i] = 0
            }
        }
    }

    private fun canPathThroughAllocMode(mode: Int, node: Int): Boolean {
        val nodeMode = allocMode[node]
        return nodeMode == 0 || (mode > 0 && nodeMode == mode)
    }

    private fun unlockMet(node: Int): Boolean {
        val c = nodes[node].unlockIdx ?: return true
        return c.all { alloc[it] }
    }

    /** Would allocating this global node (keystone / jewel socket) be refused in the current mode. */
    fun isGlobalAllocationBlocked(node: Int): Boolean {
        val t = nodes[node]
        if (!t.isGlobal || alloc[node] || path[node] == null) return false
        return currentAllocMode > 0 || (leapAffecting[node].isEmpty() && isConnectedToWeaponSetNodes(node))
    }

    fun isGlobalDeallocationBlocked(node: Int): Boolean {
        val t = nodes[node]
        if (!t.isGlobal || !alloc[node]) return false
        return allocMode[node] == 0 && currentAllocMode > 0
    }

    fun isConnectedToWeaponSetNodes(node: Int): Boolean {
        val p = path[node]
        if (p != null && p.size > 1) {
            for (i in 1 until p.size) if (alloc[p[i]] && allocMode[p[i]] > 0) return true
        }
        for (l in nodes[node].linked) if (alloc[l] && allocMode[l] > 0) return true
        return false
    }

    /** Shortest path to [target] using only roots/allocated nodes compatible with [mode]. */
    fun getAllocationPath(target: Int, mode: Int, allocatedOnly: Boolean = false, skipRoot: Int = -1): IntArray? {
        val prev = IntArray(n) { -1 }
        val seen = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        for (i in 0 until n) {
            if (alloc[i] && i != skipRoot && canPathThroughAllocMode(if (allocatedOnly) 0 else mode, i)) {
                seen[i] = true
                queue += i
            }
        }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == target) {
                val out = ArrayList<Int>()
                var cur = target
                while (cur >= 0 && prev[cur] >= 0) {
                    out += cur
                    cur = prev[cur]
                }
                return out.toIntArray()
            }
            if (!unlockMet(node)) continue
            val nodeT = nodes[node]
            for (other in nodeT.linked) {
                val otherT = nodes[other]
                val canPath = unlockMet(other)
                val otherMode = allocMode[other]
                val canVisit = if (allocatedOnly) {
                    alloc[other] && canPathThroughAllocMode(mode, other) && (otherMode > 0 || !otherT.type.isStart)
                } else {
                    !alloc[other] || canPathThroughAllocMode(mode, other)
                }
                if (canPath && canVisit && !seen[other] && !otherT.type.isStart &&
                    (nodeT.ascendancyName == otherT.ascendancyName || (prev[node] < 0 && otherT.ascendancyName == null))
                ) {
                    seen[other] = true
                    prev[other] = node
                    queue += other
                }
            }
        }
        return null
    }

    /** The path that allocating [node] would actually allocate, including weapon-set promotion. */
    fun effectiveAllocationPath(node: Int): IntArray? {
        val base = path[node] ?: return null
        // Allocated on its own through a jewel (PoB AllocNode)
        if (leapAffecting[node].isNotEmpty()) return intArrayOf(node)
        val root = pathRoot[node]
        val rootMode = if (root >= 0) allocMode[root] else 0
        if (rootMode == 0) return base
        if (currentAllocMode == 0 && root >= 0 && alloc[root]) {
            // Normal allocation through a weapon-set branch promotes that branch back to normal
            val out = base.toMutableList()
            val rootPath = getAllocationPath(root, rootMode, allocatedOnly = true, skipRoot = root) ?: intArrayOf(root)
            for (p in rootPath) if (p !in out) out += p
            return out.toIntArray()
        }
        if (currentAllocMode > 0 && rootMode != currentAllocMode) {
            return getAllocationPath(node, currentAllocMode)
        }
        return base
    }

    /** Nodes that would be highlighted as the path preview for [node]. */
    fun previewPath(node: Int): IntArray? = if (alloc[node]) path[node] else effectiveAllocationPath(node)

    fun allocNode(node: Int) {
        if (path[node] == null) return
        if (leapAffecting[node].isNotEmpty()) {
            // Allocated without its path (PoB AllocNode)
            val t = nodes[node]
            alloc[node] = true
            allocMode[node] = if (t.ascendancyName != null || t.isGlobal) 0 else currentAllocMode
            if (t.isAttribute) setAttribute(node, attributeIndex)
            rebuild()
            return
        }
        val p = effectiveAllocationPath(node) ?: return
        allocPath(p, node)
        rebuild()
    }

    /**
     * Allocates exactly the nodes of [route], ordered from the tree outwards, ending at the target.
     * Like PoB's AllocNode with a traced path (Shift + hover).
     */
    fun allocRoute(route: IntArray) {
        if (route.isEmpty()) return
        allocPath(route, route.last())
        rebuild()
    }

    private fun allocPath(p: IntArray, node: Int) {
        val target = nodes[node]
        for (pn in p) {
            val pt = nodes[pn]
            alloc[pn] = true
            allocMode[pn] = if (target.ascendancyName != null || pt.isGlobal) 0 else currentAllocMode
            if (pt.isAttribute) setAttribute(pn, attributeIndex)
        }
        if (target.isMultipleChoiceOption) {
            val parent = target.linked.firstOrNull { nodes[it].isMultipleChoice && alloc[it] && it != node }
            if (parent != null) {
                for (opt in nodes[parent].linked) {
                    if (nodes[opt].isMultipleChoiceOption && alloc[opt] && opt != node) deallocRaw(opt)
                }
            }
        }
    }

    /**
     * Shortest chain of unallocated nodes from [from] to [to] (excluding [from], including [to]) that
     * avoids the nodes marked in [exclude]. Uses the allocation path rules: no start nodes, no
     * crossing between ascendancies, locked nodes are impassable. Null if [to] cannot be reached.
     */
    fun legPath(from: Int, to: Int, exclude: BooleanArray): IntArray? {
        if (from == to) return IntArray(0)
        val prev = IntArray(n) { -1 }
        val seen = BooleanArray(n)
        val queue = ArrayDeque<Int>()
        seen[from] = true
        queue += from
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeT = nodes[node]
            for (other in nodeT.linked) {
                if (seen[other] || alloc[other] || exclude[other]) continue
                val otherT = nodes[other]
                if (otherT.type.isStart || otherT.ascendancyName != nodeT.ascendancyName || !unlockMet(other)) continue
                seen[other] = true
                prev[other] = node
                if (other == to) {
                    val out = ArrayList<Int>()
                    var cur = to
                    while (cur != from) {
                        out += cur
                        cur = prev[cur]
                    }
                    out.reverse()
                    return out.toIntArray()
                }
                queue += other
            }
        }
        return null
    }

    fun deallocNode(node: Int) {
        for (dep in depends[node].toList()) {
            if (nodes[dep].isAttribute) attributeOverride[dep] = 0
            deallocRaw(dep)
        }
        rebuild()
    }

    /** Change the attribute of an attribute node (1 = Str, 2 = Dex, 3 = Int) without reallocating. */
    fun switchAttribute(node: Int, attribute: Int) {
        attributeIndex = attribute
        setAttribute(node, attribute)
        rebuild()
    }

    private fun setAttribute(node: Int, attribute: Int) {
        if (nodes[node].isAttribute) attributeOverride[node] = attribute
    }

    private fun deallocRaw(i: Int) {
        alloc[i] = false
        allocMode[i] = 0
    }

    // ---------------------------------------------------------------------------------------
    // Counting
    // ---------------------------------------------------------------------------------------

    data class Counts(val used: Int, val ascUsed: Int, val ws1: Int, val ws2: Int) {
        /** Points taken from the main pool (weapon-set nodes allocated in both sets are paid once). */
        val normal get() = used - minOf(ws1, ws2)
    }

    fun counts(): Counts {
        var used = 0; var asc = 0; var ws1 = 0; var ws2 = 0
        for (i in 0 until n) {
            if (!alloc[i]) continue
            val t = nodes[i]
            if (!t.type.isStart && !t.isFreeAllocate) {
                if (t.ascendancyName != null) {
                    if (!t.isMultipleChoiceOption) asc++
                } else used++
            }
            when (allocMode[i]) {
                1 -> ws1++
                2 -> ws2++
            }
        }
        return Counts(used, asc, ws1, ws2)
    }

    fun allocatedIndices(): List<Int> = (0 until n).filter { alloc[it] }

    // ---------------------------------------------------------------------------------------
    // Rebuild dependencies and paths (PoB BuildAllDependsAndPaths)
    // ---------------------------------------------------------------------------------------

    fun rebuild() {
        val cls = currentClass
        val asc = currentAscendancy
        val j = jewels
        val radii = j.radii

        // Other classes' starts the tree can connect to, through jewels in allocated sockets
        altStart.fill(false)
        val altStarts = ArrayList<Int>()
        for (sj in j.sockets) {
            if (alloc[sj.socket] && sj.alternateStart >= 0 && !altStart[sj.alternateStart]) {
                altStart[sj.alternateStart] = true
                altStarts += sj.alternateStart
            }
        }
        // Allocation near allocated keystones, from allocated ascendancy passives
        val leapMaps = j.leapSources.filter { alloc[it.node] }

        val leapList = ArrayList<Int>()
        for (i in 0 until n) {
            val t = nodes[i]
            depends[i].clear()
            // Class / ascendancy specific replacements
            var view = t.baseView
            if (t.isSwitchable) {
                val opt = t.switchOptions[cls.name] ?: asc?.let { t.switchOptions[it.id] }
                if (opt != null) view = view.with(opt)
            }
            if (t.isAttribute && attributeOverride[i] in 1..t.attributeOptions.size) {
                view = view.with(t.attributeOptions[attributeOverride[i] - 1])
            }
            j.replaced[i]?.let { r -> view = view.copy(name = r.name, icon = r.icon ?: view.icon) }
            views[i] = view
            if (alloc[i]) depends[i] += i

            // Jewels allowing allocation without a connection (PoB intuitiveLeapLikesAffecting)
            leapList.clear()
            if (t.type != NodeType.ClassStart && t.type != NodeType.Socket && t.ascendancyName == null) {
                for (sj in j.sockets) {
                    if (sj.radiusIndex <= 0 || !alloc[sj.socket] || sj.limitDisabled) continue
                    if (sj.leap && radiusIndex.contains(sj.socket, radii, sj.radiusIndex, i)) leapList += sj.socket
                    for (k in sj.fromNothing) {
                        if (radiusIndex.contains(k, radii, sj.radiusIndex, i)) leapList += sj.socket
                    }
                }
                for (m in leapMaps) {
                    if (t.type !in m.to || m.from != "Keystone") continue
                    for (k in keystones) {
                        if (alloc[k] && radiusIndex.contains(k, radii, m.radiusIndex, i)) leapList += k
                    }
                }
            }
            leapAffecting[i] = if (leapList.isEmpty()) NONE else leapList.toIntArray()
        }
        for (i in 0 until n) {
            val c = nodes[i].unlockIdx ?: continue
            for (cid in c) depends[cid] += i
        }

        // Dependencies and orphan pruning (PoB BuildAllDependsAndPaths)
        val visited = ArrayList<Int>()
        // Nodes allocated through jewels that may depend on a node, and allocated nodes in the radius
        // of a jewel socket or keystone that may depend on a node
        val potentialDeps = HashMap<Int, ArrayList<Int>>()
        val intuitiveLeaps = HashMap<Int, ArrayList<Int>>()
        for (i in 0 until n) {
            if (!alloc[i]) continue
            val t = nodes[i]
            visitedFlag[i] = true
            connectedToStart[i] = false
            var anyStartFound = t.type.isStart || t.isFreeAllocate
            if (t.isFreeAllocate) connectedToStart[i] = true
            for (other in t.linked) {
                if ((alloc[other] || altStart[other]) && canPathThroughAllocMode(allocMode[i], other) && other !in depends[i]) {
                    if (nodes[other].type.isStart) {
                        anyStartFound = true
                        connectedToStart[i] = true
                    } else if (findStartFromNode(other, visited, noAscend = false, mode = allocMode[i])) {
                        anyStartFound = true
                        connectedToStart[i] = true
                        for (v in visited) visitedFlag[v] = false
                        visited.clear()
                    } else {
                        // Everything visited is only connected through this node
                        for (v in visited) {
                            if (leapAffecting[v].isNotEmpty()) {
                                potentialDeps.getOrPut(v) { ArrayList() } += i
                            } else {
                                depends[i] += v
                            }
                            // Nodes in the radius of a keystone allowing allocation may depend on it
                            for (m in leapMaps) {
                                if (t.type !in m.to || m.from != nodes[v].type.pobName) continue
                                for (a in 0 until n) {
                                    if (alloc[a] && radiusIndex.contains(v, radii, m.radiusIndex, a)) intuitiveLeaps.getOrPut(i) { ArrayList() } += a
                                }
                            }
                            val inRadius = nodesInLeapRadius(v)
                            if (inRadius.isNotEmpty()) intuitiveLeaps.getOrPut(i) { ArrayList() } += inRadius
                            visitedFlag[v] = false
                        }
                        visited.clear()
                    }
                }
            }
            visitedFlag[i] = false
            if (!anyStartFound && jewelsKnown) {
                // An orphan: prune it and what depends on it, except nodes a jewel keeps allocated
                for (dep in depends[i].toList()) {
                    var prune = true
                    for (sj in j.sockets) {
                        if (!alloc[sj.socket] || sj.radiusIndex <= 0) continue
                        val kept = (sj.leap && radiusIndex.contains(sj.socket, radii, sj.radiusIndex, dep)) ||
                            sj.fromNothing.any { k -> radiusIndex.contains(k, radii, sj.radiusIndex, dep) }
                        if (kept) {
                            prune = false
                            intuitiveLeaps.getOrPut(sj.socket) { ArrayList() } += dep
                            break
                        }
                    }
                    for (m in j.leapSources) {
                        if (!alloc[m.node] || nodes[dep].type !in m.to || m.from != "Keystone") continue
                        for (k in keystones) {
                            if (radiusIndex.contains(k, radii, m.radiusIndex, dep)) {
                                prune = false
                                intuitiveLeaps.getOrPut(k) { ArrayList() } += dep
                            }
                        }
                    }
                    if (prune) deallocRaw(dep)
                }
            }
        }
        for (i in 0 until n) if (!alloc[i]) connectedToStart[i] = false

        // Nodes allocated through jewels depend on the nodes connecting every jewel they rely on
        for ((v, deps) in potentialDeps) {
            val providers = leapAffecting[v]
            for (d in deps.distinct()) {
                if (providers.all { it in depends[d] } && v !in depends[d]) depends[d] += v
            }
        }
        for ((k, deps) in intuitiveLeaps) {
            for (dep in deps.distinct()) {
                if (connectedToStart[dep]) continue
                if (leapAffecting[dep].all { it in depends[k] } && dep !in depends[k]) depends[k] += dep
            }
        }

        // Paths (multi-source 0-1 BFS from the allocated nodes connected to the tree)
        for (i in 0 until n) {
            pathDist[i] = if (alloc[i] && leapAffecting[i].isEmpty()) 0 else 1000
            path[i] = null
            pathRoot[i] = -1
        }
        buildNodePathsToRootNodes((0 until n).filter { alloc[it] && (leapAffecting[it].isEmpty() || connectedToStart[it]) })
        if (altStarts.isNotEmpty()) buildNodePathsToRootNodes(altStarts)
    }

    /** Allocated nodes a jewel in [socket] allows to be allocated without a connection (PoB NodesInIntuitiveLeapLikeRadius). */
    private fun nodesInLeapRadius(socket: Int): List<Int> {
        val sj = jewels.bySocket[socket] ?: return emptyList()
        if (sj.radiusIndex <= 0) return emptyList()
        val out = ArrayList<Int>()
        val radii = jewels.radii
        for (a in 0 until n) {
            if (!alloc[a]) continue
            if (sj.leap && radiusIndex.contains(socket, radii, sj.radiusIndex, a)) out += a
            for (k in sj.fromNothing) if (radiusIndex.contains(k, radii, sj.radiusIndex, a)) out += a
        }
        return out
    }

    private fun findStartFromNode(node: Int, visited: ArrayList<Int>, noAscend: Boolean, mode: Int): Boolean {
        visitedFlag[node] = true
        visited += node
        val t = nodes[node]
        val nodeAsc = t.ascendancyName
        for (other in t.linked) {
            val startIndex = visited.size
            if ((alloc[other] || altStart[other]) && canPathThroughAllocMode(mode, other)) {
                val otherT = nodes[other]
                if (otherT.type.isStart || (!visitedFlag[other] && findStartFromNode(other, visited, noAscend, mode))) {
                    if (nodeAsc != null && otherT.ascendancyName == null) {
                        // Pathing out of an ascendancy: un-visit the outside nodes
                        while (visited.size > startIndex) visitedFlag[visited.removeAt(visited.size - 1)] = false
                    } else if (!noAscend || otherT.type != NodeType.AscendClassStart) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun buildNodePathsToRootNodes(roots: List<Int>) {
        val q = ArrayDeque<Int>()
        for (r in roots) {
            pathDist[r] = 0
            path[r] = IntArray(0)
            pathRoot[r] = r
            q.addLast(r)
        }
        while (q.isNotEmpty()) {
            val node = q.removeFirst()
            if (!unlockMet(node)) continue
            val t = nodes[node]
            val nodeDist = pathDist[node]
            val nodePath = path[node] ?: IntArray(0)
            val nodeRoot = pathRoot[node]
            for (other in t.linked) {
                val otherT = nodes[other]
                val weight = if (alloc[other]) 0 else 1
                val distVia = nodeDist + weight
                val otherDist = pathDist[other]
                val otherRoot = pathRoot[other]
                val preferNormalRoot = distVia == otherDist && nodeRoot >= 0 && allocMode[nodeRoot] == 0 &&
                    otherRoot >= 0 && allocMode[otherRoot] != 0
                if ((distVia < otherDist || preferNormalRoot) &&
                    !otherT.type.isStart &&
                    (!alloc[other] || canPathThroughAllocMode(allocMode[node], other)) &&
                    (t.ascendancyName == otherT.ascendancyName || (nodeDist == 0 && otherT.ascendancyName == null)) &&
                    unlockMet(other)
                ) {
                    if (weight == 0) q.addFirst(other) else q.addLast(other)
                    pathDist[other] = distVia
                    val np = IntArray(nodePath.size + 1)
                    np[0] = other
                    System.arraycopy(nodePath, 0, np, 1, nodePath.size)
                    path[other] = np
                    pathRoot[other] = nodeRoot
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Snapshots (undo, import, save)
    // ---------------------------------------------------------------------------------------

    data class Snapshot(
        val classId: Int,
        val ascendClassId: Int,
        /** node id -> alloc mode */
        val nodes: Map<Int, Int>,
        /** node id -> attribute (1..3) */
        val attributes: Map<Int, Int>,
    )

    fun snapshot(): Snapshot {
        val ns = LinkedHashMap<Int, Int>()
        val attrs = LinkedHashMap<Int, Int>()
        for (i in 0 until n) {
            if (alloc[i]) {
                ns[nodes[i].id] = allocMode[i]
                if (attributeOverride[i] != 0) attrs[nodes[i].id] = attributeOverride[i]
            }
        }
        return Snapshot(classId, ascendClassId, ns, attrs)
    }

    /** Load a spec from a node list (PoB ImportFromNodeList). Unknown node ids are ignored. */
    fun restore(s: Snapshot) {
        resetNodes()
        val cls = tree.classById(s.classId) ?: tree.classes.first()
        selectClassQuiet(cls.integerId)
        ascendClassId = 0
        val asc = cls.ascendancy(s.ascendClassId)
        if (asc != null) {
            ascendClassId = asc.index
            if (asc.startNodeIdx >= 0) alloc[asc.startNodeIdx] = true
        }
        for ((id, mode) in s.nodes) {
            val node = tree.node(id) ?: continue
            alloc[node.idx] = true
            allocMode[node.idx] = mode
        }
        for ((id, attr) in s.attributes) {
            val node = tree.node(id) ?: continue
            if (node.isAttribute) attributeOverride[node.idx] = attr
        }
        rebuild()
    }

    private fun selectClassQuiet(newClassId: Int) {
        for (c in tree.classes) {
            if (c.startNodeIdx >= 0) deallocRaw(c.startNodeIdx)
            for (a in c.ascendancies) if (a.startNodeIdx >= 0) deallocRaw(a.startNodeIdx)
        }
        classId = newClassId
        val start = currentClass.startNodeIdx
        if (start >= 0) alloc[start] = true
    }
}

private val NONE = IntArray(0)

/** PoB's `node.type` string. */
private val NodeType.pobName
    get() = when (this) {
        NodeType.ClassStart -> "ClassStart"
        NodeType.AscendClassStart -> "AscendClassStart"
        NodeType.OnlyImage -> "OnlyImage"
        NodeType.Socket -> "Socket"
        NodeType.Keystone -> "Keystone"
        NodeType.Notable -> "Notable"
        NodeType.Normal -> "Normal"
    }

private fun NodeView.with(o: NodeOption) = NodeView(
    name = o.name ?: name,
    icon = o.icon ?: icon,
    stats = o.stats ?: stats,
    overlay = o.overlay ?: overlay,
    reminderText = o.reminderText ?: reminderText,
)
