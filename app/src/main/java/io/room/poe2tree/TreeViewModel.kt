package io.room.poe2tree

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.room.poe2tree.io.BuildStore
import io.room.poe2tree.io.PobCode
import io.room.poe2tree.io.PointSettings
import io.room.poe2tree.io.SavedBuild
import io.room.poe2tree.tree.NodeType
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import io.room.poe2tree.tree.TreeText
import io.room.poe2tree.ui.Camera
import io.room.poe2tree.ui.RenderState
import io.room.poe2tree.ui.SpriteCache
import io.room.poe2tree.ui.TreeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

sealed interface LoadState {
    data object Loading : LoadState
    data class Failed(val message: String) : LoadState
    data object Ready : LoadState
}

private class Loaded(val tree: PassiveTree, val spec: PassiveSpec, val lastBuild: SavedBuild?, val sprites: SpriteCache)

/** A class change that needs confirmation because the tree is not connected to the new class. */
data class PendingClassChange(
    val classId: Int,
    val ascendClassId: Int,
    /** Node to allocate after switching (when triggered by tapping another class's ascendancy node). */
    val allocateAfter: Int,
)

class TreeViewModel(app: Application) : AndroidViewModel(app) {

    var loadState by mutableStateOf<LoadState>(LoadState.Loading)
        private set

    lateinit var tree: PassiveTree
        private set
    lateinit var spec: PassiveSpec
        private set
    lateinit var renderer: TreeRenderer
        private set
    private lateinit var sprites: SpriteCache
    private val store = BuildStore(app.filesDir)

    /** Incremented on every change of the allocation state. */
    var revision by mutableIntStateOf(0)
        private set
    /** Incremented when sprites finish loading, to trigger a redraw. */
    var spriteRevision by mutableIntStateOf(0)
        private set

    var selected by mutableIntStateOf(-1)
        private set
    var allocMode by mutableIntStateOf(0)
        private set

    var buildId by mutableStateOf("")
        private set
    var buildName by mutableStateOf("")
        private set
    var settings by mutableStateOf(PointSettings())
        private set
    private var buildUpdatedAt = 0L

    var message by mutableStateOf<String?>(null)
    var pendingClassChange by mutableStateOf<PendingClassChange?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    private val undoStack = ArrayDeque<PassiveSpec.Snapshot>()
    private val redoStack = ArrayDeque<PassiveSpec.Snapshot>()

    // ---- Camera ----
    var camera by mutableStateOf(Camera(0f, 0f, 0.05f))
        private set
    private var viewWidth = 0
    private var viewHeight = 0
    private var cameraInitialised = false
    private var density = 1f

    // ---- Search ----
    var searchQuery by mutableStateOf("")
        private set
    var searchResults by mutableStateOf<List<Int>>(emptyList())
        private set
    private var searchMatches: BooleanArray? = null
    private var searchCursor = -1

    // ---- Derived selection state, cached per (selected, revision) ----
    private var cacheKey = Long.MIN_VALUE
    private var cachedPath: BooleanArray? = null
    private var cachedDep: BooleanArray? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val spriteRedrawPosted = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) {
                    val assets = getApplication<Application>().assets
                    val t0 = SystemClock.elapsedRealtime()
                    val treeJson = assets.open("tree/tree.json").bufferedReader().use { it.readText() }
                    val spritesJson = assets.open("tree/sprites.json").bufferedReader().use { it.readText() }
                    val t1 = SystemClock.elapsedRealtime()
                    val t = PassiveTree.load(treeJson, spritesJson)
                    val t2 = SystemClock.elapsedRealtime()
                    val s = PassiveSpec(t)
                    val t3 = SystemClock.elapsedRealtime()
                    val last = store.lastBuildId?.let { store.load(it) } ?: store.list().firstOrNull()
                    val cache = SpriteCache(assets, t.sprites) {
                        if (spriteRedrawPosted.compareAndSet(false, true)) {
                            mainHandler.post {
                                spriteRedrawPosted.set(false)
                                spriteRevision++
                            }
                        }
                    }
                    cache.loadAtlases()
                    Log.i(TAG, "read ${t1 - t0} ms, parse tree ${t2 - t1} ms, spec ${t3 - t2} ms, builds+atlases ${SystemClock.elapsedRealtime() - t3} ms")
                    Loaded(t, s, last, cache)
                }
                tree = loaded.tree
                spec = loaded.spec
                sprites = loaded.sprites
                // Connector art is needed at every zoom level; with batching, node art comes from the atlases
                val connectorArt = tree.connectors.flatMap { c -> (0..2).map { c.assetName(it) } }.toSet()
                sprites.preload(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) connectorArt
                    else tree.sprites.keys // per-sprite drawing: load everything small up front
                )
                renderer = TreeRenderer(tree, sprites)
                val last = loaded.lastBuild
                val t4 = SystemClock.elapsedRealtime()
                if (last != null) applyBuild(last) else createBuild("My build", DEFAULT_CLASS)
                Log.i(TAG, "apply build ${SystemClock.elapsedRealtime() - t4} ms")
                loadState = LoadState.Ready
            } catch (e: Exception) {
                loadState = LoadState.Failed(e.message ?: e.toString())
            }
        }
    }

    override fun onCleared() {
        if (::sprites.isInitialized) sprites.shutdown()
    }

    // =======================================================================================
    // Camera
    // =======================================================================================

    fun onViewport(width: Int, height: Int, density: Float) {
        this.density = density
        if (width == viewWidth && height == viewHeight) return
        // Keep the tree point at the centre of the view in place (rotation, layout changes)
        val hadView = viewWidth > 0 && viewHeight > 0
        val centreX = camera.toTreeX(viewWidth / 2f)
        val centreY = camera.toTreeY(viewHeight / 2f)
        viewWidth = width
        viewHeight = height
        if (!cameraInitialised && width > 0 && height > 0 && loadState == LoadState.Ready) {
            cameraInitialised = true
            focusOnStart()
        } else if (hadView && width > 0 && height > 0) {
            val s = camera.scale.coerceIn(minScale, maxScale)
            camera = clampCamera(Camera(width / 2f - centreX * s, height / 2f - centreY * s, s))
        } else {
            camera = clampCamera(camera)
        }
    }

    /** Centre of the tree view, in view pixels. */
    fun viewCentre() = Offset(viewWidth / 2f, viewHeight / 2f)

    val canZoomIn by derivedStateOf { camera.scale < maxScale * 0.99f }
    val canZoomOut by derivedStateOf { camera.scale > minScale * 1.01f }

    /** Scale at which the whole drawn tree fits the view. */
    private val fitScale: Float
        get() {
            val b = tree.contentBounds
            return min(viewWidth / (b[2] - b[0]), viewHeight / (b[3] - b[1])) * 0.95f
        }
    private val minScale get() = fitScale * 0.8f
    /** Largest zoom: a small passive icon (74 tree units) is about 40dp wide. */
    private val maxScale get() = 0.55f * density

    fun transform(centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoom: Float) {
        val c = camera
        val newScale = (c.scale * zoom).coerceIn(minScale, maxScale)
        val factor = newScale / c.scale
        val ox = centroidX - (centroidX - c.offsetX) * factor + panX
        val oy = centroidY - (centroidY - c.offsetY) * factor + panY
        camera = clampCamera(Camera(ox, oy, newScale))
    }

    private fun clampCamera(c: Camera): Camera {
        if (viewWidth == 0 || !::tree.isInitialized) return c
        // Keep the screen centre over the tree
        val b = tree.contentBounds
        val cx = c.toTreeX(viewWidth / 2f).coerceIn(b[0], b[2])
        val cy = c.toTreeY(viewHeight / 2f).coerceIn(b[1], b[3])
        return Camera(viewWidth / 2f - cx * c.scale, viewHeight / 2f - cy * c.scale, c.scale)
    }

    /** Centre the view on a tree position. [anchorY] is the vertical screen fraction to place it at. */
    fun focusOn(x: Float, y: Float, scale: Float = max(camera.scale, 0.18f), anchorY: Float = 0.5f) {
        val s = scale.coerceIn(minScale, maxScale)
        camera = clampCamera(Camera(viewWidth / 2f - x * s, viewHeight * anchorY - y * s, s))
    }

    fun focusOnStart() {
        val start = spec.currentClass.startNodeIdx
        if (start >= 0) {
            val n = tree.nodes[start]
            // Detailed view around the class start
            focusOn(n.x, n.y, max(TreeRenderer.DETAIL_SCALE * 2.2f, fitScale * 4f))
        } else {
            focusOn(0f, 0f, fitScale)
        }
    }

    fun zoomOutFull() {
        val b = tree.contentBounds
        focusOn((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f, fitScale)
    }

    // =======================================================================================
    // Rendering state
    // =======================================================================================

    fun renderState(): RenderState {
        val key = (selected.toLong() shl 40) xor (revision.toLong() shl 16) xor routeRevision.toLong()
        if (key != cacheKey) {
            cacheKey = key
            cachedPath = null
            cachedDep = null
            val sel = selected
            if (sel >= 0) {
                val p = route ?: spec.previewPath(sel)
                if (p != null) cachedPath = BooleanArray(tree.nodes.size).also { arr -> p.forEach { arr[it] = true } }
                if (spec.alloc[sel]) {
                    cachedDep = BooleanArray(tree.nodes.size).also { arr -> spec.depends[sel].forEach { arr[it] = true } }
                }
            }
        }
        return RenderState(camera, selected, cachedPath, cachedDep, searchMatches)
    }

    // =======================================================================================
    // Taps and allocation
    // =======================================================================================

    /**
     * Handles a tap on the tree view. Returns a zoom factor to animate around the tap point, or null:
     * - zoomed out too far to pick single nodes: a tap zooms in towards the tapped area;
     * - a double tap on empty space zooms in.
     */
    fun onTap(sx: Float, sy: Float): Float? {
        if (camera.scale < TreeRenderer.DETAIL_SCALE) {
            lastEmptyTapTime = 0L
            return (TreeRenderer.DETAIL_SCALE * 2.5f) / camera.scale
        }
        val tx = camera.toTreeX(sx)
        val ty = camera.toTreeY(sy)
        val touchSlop = 18f * density / camera.scale
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (node in tree.nodes) {
            if (node.hitRadius <= 0f) continue
            val c = node.unlockIdx
            if (c != null && !c.all { spec.alloc[it] }) continue
            val d = hypot(node.x - tx, node.y - ty)
            val reach = max(node.hitRadius, touchSlop)
            if (d <= reach && d - node.hitRadius < bestDist) {
                bestDist = d - node.hitRadius
                best = node.idx
            }
        }
        if (best < 0) {
            selected = -1
            setRoute(null)
            val now = SystemClock.uptimeMillis()
            val double = now - lastEmptyTapTime < DOUBLE_TAP_MS &&
                hypot(sx - lastEmptyTapX, sy - lastEmptyTapY) < 48f * density
            lastEmptyTapTime = if (double) 0L else now
            lastEmptyTapX = sx
            lastEmptyTapY = sy
            return if (double) DOUBLE_TAP_ZOOM else null
        }
        lastEmptyTapTime = 0L
        if (best == selected) {
            primaryAction(best)
        } else if (!extendRoute(best)) {
            setRoute(null)
            selected = best
        }
        return null
    }

    // ---- Planned path ----

    /**
     * Path chosen by tapping nodes one after another, ordered from the tree outwards and ending at
     * the selected node. Null means the default shortest path is used.
     */
    private var route: IntArray? = null
    private var routeRevision by mutableIntStateOf(0)

    /** True when the planned path of the selected node was chosen by the user (not the shortest). */
    var hasCustomRoute by mutableStateOf(false)
        private set

    private fun setRoute(r: IntArray?) {
        if (r == null && route == null) return
        route = r
        hasCustomRoute = r != null
        routeRevision++
    }

    /** Back to the shortest path for the selected node. */
    fun useShortestPath() = setRoute(null)

    /** The path that allocating the selected node would take, tree side first. */
    private fun plannedPath(sel: Int): IntArray? = route ?: spec.effectiveAllocationPath(sel)?.reversedArray()

    /**
     * Tapping another unallocated node while one is selected continues the planned path through it,
     * like PoB's Shift path tracing, when that node is closer to the end of the planned path than
     * to the allocated tree. Tapping a node already on the path cuts the path back to it.
     */
    private fun extendRoute(target: Int): Boolean {
        val sel = selected
        if (sel < 0 || spec.alloc[sel] || spec.alloc[target] || tree.nodes[target].type.isStart) return false
        val current = plannedPath(sel) ?: return false
        val onPath = current.indexOf(target)
        if (onPath >= 0) {
            setRoute(current.copyOf(onPath + 1))
            selected = target
            return true
        }
        val exclude = BooleanArray(tree.nodes.size).also { arr -> current.forEach { arr[it] = true } }
        val leg = spec.legPath(sel, target, exclude) ?: return false
        val fromTree = if (spec.path[target] != null) spec.pathDist[target] else Int.MAX_VALUE
        if (leg.size >= fromTree) return false
        setRoute(current + leg)
        selected = target
        return true
    }

    private var lastEmptyTapTime = 0L
    private var lastEmptyTapX = 0f
    private var lastEmptyTapY = 0f

    fun select(idx: Int) {
        setRoute(null)
        selected = idx
    }

    fun clearSelection() {
        setRoute(null)
        selected = -1
    }

    /** Second tap on a node: allocate or deallocate it like a click in PoB. */
    private fun primaryAction(idx: Int) {
        if (spec.alloc[idx]) deallocate(idx) else allocate(idx, null)
    }

    fun changeAllocMode(mode: Int) {
        spec.currentAllocMode = mode
        allocMode = mode
        setRoute(null)
        revision++
    }

    /** Allocate [idx] and its path. [attribute] (1..3) sets the choice for attribute nodes. */
    fun allocate(idx: Int, attribute: Int?) {
        val node = tree.nodes[idx]
        if (spec.alloc[idx]) return
        if (node.type.isStart) return

        // Nodes of another ascendancy switch ascendancy (and class if needed), like PoB
        val ascName = node.ascendancyName
        if (ascName != null) {
            val current = spec.currentAscendancy
            if (current == null || (ascName != (current.replace ?: current.id))) {
                val (cls, asc) = tree.ascendancyMap[ascName] ?: return
                if (cls.integerId == spec.classId) {
                    commit { spec.selectAscendClass(asc.index) }
                } else if (spec.counts().used == 0 || spec.isClassConnected(cls.integerId)) {
                    commit {
                        spec.selectClass(cls.integerId)
                        spec.selectAscendClass(asc.index)
                    }
                } else {
                    pendingClassChange = PendingClassChange(cls.integerId, asc.index, idx)
                    return
                }
            }
        }

        val customRoute = route?.takeIf { idx == selected && it.isNotEmpty() && it.last() == idx }
        if (customRoute == null && spec.path[idx] == null) {
            message = if (node.unlockIdx != null) "This node is locked." else "This node cannot be reached from your tree."
            return
        }
        if (spec.isGlobalAllocationBlocked(idx)) {
            message = if (spec.currentAllocMode > 0)
                "Keystones and jewel sockets can only be allocated with main tree points."
            else "Cannot allocate: the path goes through weapon set nodes. Deallocate them first."
            return
        }
        if (attribute != null) spec.attributeIndex = attribute
        commit(checkCaps = true) {
            if (customRoute != null) spec.allocRoute(customRoute) else spec.allocNode(idx)
        }
    }

    fun deallocate(idx: Int) {
        if (!spec.alloc[idx] || tree.nodes[idx].type.isStart) return
        if (spec.isGlobalDeallocationBlocked(idx)) {
            message = "Switch to the main tree to deallocate keystones and jewel sockets."
            return
        }
        commit { spec.deallocNode(idx) }
    }

    fun switchAttribute(idx: Int, attribute: Int) {
        if (!spec.alloc[idx]) {
            allocate(idx, attribute)
            return
        }
        commit { spec.switchAttribute(idx, attribute) }
    }

    /** Points that allocating the node would cost along its planned path (0 for promotions / free nodes). */
    fun allocationCost(idx: Int): Int {
        val p = (if (idx == selected) plannedPath(idx) else spec.effectiveAllocationPath(idx)) ?: return 0
        return p.count { !spec.alloc[it] && !tree.nodes[it].isFreeAllocate && !tree.nodes[it].isMultipleChoiceOption }
    }

    fun removalCount(idx: Int): Int = spec.depends[idx].count { !tree.nodes[it].type.isStart }

    // =======================================================================================
    // Class / ascendancy
    // =======================================================================================

    fun selectClass(classId: Int) {
        if (classId == spec.classId) return
        if (spec.counts().used == 0 || spec.isClassConnected(classId)) {
            commit { spec.selectClass(classId) }
            focusOnStart()
        } else {
            pendingClassChange = PendingClassChange(classId, 0, -1)
        }
    }

    fun selectAscendancy(index: Int) {
        if (index == spec.ascendClassId) return
        commit { spec.selectAscendClass(index) }
    }

    /** Resolve a pending class change: reset the tree, or connect the tree to the new class. */
    fun confirmClassChange(connectPath: Boolean) {
        val p = pendingClassChange ?: return
        pendingClassChange = null
        commit(checkCaps = connectPath) {
            if (connectPath) spec.connectToClass(p.classId)
            spec.selectClass(p.classId)
            if (p.ascendClassId > 0) spec.selectAscendClass(p.ascendClassId)
            if (p.allocateAfter >= 0 && spec.path[p.allocateAfter] != null) spec.allocNode(p.allocateAfter)
        }
        focusOnStart()
    }

    fun cancelClassChange() {
        pendingClassChange = null
    }

    fun resetTree() {
        commit {
            spec.resetNodes()
            spec.selectAscendClass(spec.ascendClassId)
        }
    }

    // =======================================================================================
    // Commit / undo / point caps
    // =======================================================================================

    private fun capViolation(before: PassiveSpec.Counts, after: PassiveSpec.Counts): String? {
        val s = settings
        return when {
            after.normal > s.normalMax && after.normal > before.normal ->
                "Not enough passive points: needs ${after.normal - before.normal}, ${max(0, s.normalMax - before.normal)} left."
            after.ws1 > s.weaponSetMax && after.ws1 > before.ws1 ->
                "Not enough weapon set 1 points (${s.weaponSetMax} max)."
            after.ws2 > s.weaponSetMax && after.ws2 > before.ws2 ->
                "Not enough weapon set 2 points (${s.weaponSetMax} max)."
            after.ascUsed > s.ascendancyPoints && after.ascUsed > before.ascUsed ->
                "Not enough ascendancy points (${s.ascendancyPoints} max)."
            else -> null
        }
    }

    private inline fun commit(checkCaps: Boolean = false, block: () -> Unit) {
        val before = spec.snapshot()
        val beforeCounts = spec.counts()
        block()
        if (checkCaps) {
            val violation = capViolation(beforeCounts, spec.counts())
            if (violation != null) {
                spec.restore(before)
                message = violation
                revision++
                return
            }
        }
        setRoute(null)
        val after = spec.snapshot()
        if (after != before) {
            undoStack.addLast(before)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            redoStack.clear()
            updateUndoFlags()
            save()
        }
        revision++
        refreshSearch()
    }

    fun undo() {
        val s = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(spec.snapshot())
        spec.restore(s)
        afterHistoryChange()
    }

    fun redo() {
        val s = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(spec.snapshot())
        spec.restore(s)
        afterHistoryChange()
    }

    private fun afterHistoryChange() {
        setRoute(null)
        updateUndoFlags()
        revision++
        refreshSearch()
        save()
    }

    private fun updateUndoFlags() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }

    // =======================================================================================
    // Builds
    // =======================================================================================

    fun listBuilds(): List<SavedBuild> = store.list()

    private fun currentBuild() = SavedBuild(buildId, buildName, buildUpdatedAt, spec.snapshot(), settings)

    /**
     * Saves the open build. Every edit saves straight away, so the saves made before switching
     * to another build pass [changed] = false to keep the build's "last updated" time.
     */
    private fun save(changed: Boolean = true) {
        if (buildId.isEmpty()) return
        if (changed) buildUpdatedAt = System.currentTimeMillis()
        val build = currentBuild()
        store.lastBuildId = build.id
        viewModelScope.launch(Dispatchers.IO) { store.save(build) }
    }

    private fun applyBuild(build: SavedBuild) {
        buildId = build.id
        buildName = build.name
        buildUpdatedAt = build.updatedAt
        settings = build.settings
        spec.currentAllocMode = 0
        allocMode = 0
        spec.restore(build.snapshot)
        undoStack.clear()
        redoStack.clear()
        updateUndoFlags()
        setRoute(null)
        selected = -1
        store.lastBuildId = build.id
        revision++
        refreshSearch()
        if (cameraInitialised) focusOnStart()
    }

    fun createBuild(name: String, classId: Int) {
        if (buildId.isNotEmpty()) save(changed = false)
        val s = PassiveSpec(tree)
        s.selectClass(tree.classById(classId)?.integerId ?: tree.classes.first().integerId)
        val build = SavedBuild(BuildStore.newId(), name.ifBlank { "New build" }, System.currentTimeMillis(), s.snapshot(), PointSettings())
        store.save(build)
        applyBuild(build)
    }

    fun openBuild(id: String) {
        if (id == buildId) return
        save(changed = false)
        store.load(id)?.let { applyBuild(it) }
    }

    fun renameBuild(id: String, name: String) {
        val clean = name.trim().ifEmpty { return }
        if (id == buildId) {
            buildName = clean
            save()
        } else {
            store.load(id)?.let { store.save(it.copy(name = clean)) }
        }
    }

    fun duplicateBuild(id: String) {
        save(changed = false)
        val source = if (id == buildId) currentBuild() else store.load(id) ?: return
        val copy = source.copy(id = BuildStore.newId(), name = source.name + " (copy)", updatedAt = System.currentTimeMillis())
        store.save(copy)
        applyBuild(copy)
    }

    fun deleteBuild(id: String) {
        store.delete(id)
        if (id == buildId) {
            buildId = ""
            val next = store.list().firstOrNull()
            if (next != null) applyBuild(next) else createBuild("My build", DEFAULT_CLASS)
        }
    }

    fun updateSettings(newSettings: PointSettings) {
        settings = newSettings
        save()
        revision++
    }

    fun buildSummary(build: SavedBuild): String {
        val cls = tree.classById(build.snapshot.classId)
        val asc = cls?.ascendancy(build.snapshot.ascendClassId)
        val points = build.snapshot.nodes.keys.count { id ->
            val n = tree.node(id) ?: return@count false
            !n.type.isStart && n.ascendancyName == null && !n.isFreeAllocate
        }
        return listOfNotNull(asc?.name ?: cls?.name, "$points points", "Lv ${build.settings.level}").joinToString(" · ")
    }

    // =======================================================================================
    // Import / export
    // =======================================================================================

    /** Imports a PoB2 build code as a new build. Returns an error message, or null on success. */
    fun importCode(code: String): String? {
        val result = try {
            PobCode.import(code, tree)
        } catch (e: PobCode.ImportException) {
            return e.message
        } catch (e: Exception) {
            return "Could not read build code: ${e.message}"
        }
        save(changed = false)
        val base = PointSettings()
        val build = SavedBuild(
            id = BuildStore.newId(),
            name = result.title?.takeIf { it != "Default" } ?: "Imported build",
            updatedAt = System.currentTimeMillis(),
            snapshot = result.snapshot,
            settings = base.copy(level = (result.level ?: base.level).coerceIn(1, 100)),
        )
        store.save(build)
        applyBuild(build)
        // Store the normalized state (unknown nodes dropped)
        save()
        val c = spec.counts()
        val warnings = result.warnings.toMutableList()
        if (c.normal > settings.normalMax) warnings += "This build uses more passive points (${c.normal}) than the current limit (${settings.normalMax})."
        message = if (warnings.isEmpty()) "Build imported." else warnings.joinToString("\n")
        return null
    }

    fun exportCode(): String = PobCode.export(spec, buildName, settings.level)

    // =======================================================================================
    // Search
    // =======================================================================================

    fun updateSearch(query: String) {
        searchQuery = query
        searchCursor = -1
        refreshSearch()
    }

    private fun refreshSearch() {
        val words = TreeText.searchWords(searchQuery)
        if (words.isEmpty()) {
            searchMatches = null
            searchResults = emptyList()
            return
        }
        val matches = BooleanArray(tree.nodes.size)
        val results = ArrayList<Int>()
        for (node in tree.nodes) {
            if (TreeText.matches(spec, node.idx, words)) {
                if (node.unlockIdx != null && !node.unlockIdx!!.all { spec.alloc[it] }) continue
                matches[node.idx] = true
                results += node.idx
            }
        }
        searchMatches = matches
        // Notables and keystones first, then by distance from the class start
        val start = spec.currentClass.startNodeIdx.takeIf { it >= 0 }?.let { tree.nodes[it] }
        searchResults = results.sortedWith(
            compareBy<Int> {
                when (tree.nodes[it].type) {
                    NodeType.Keystone -> 0
                    NodeType.Notable -> 1
                    NodeType.Socket -> 2
                    else -> 3
                }
            }.thenBy { i -> start?.let { s -> hypot(tree.nodes[i].x - s.x, tree.nodes[i].y - s.y) } ?: 0f }
        )
    }

    /** Selects and centres the next search result. */
    fun nextSearchResult(direction: Int = 1) {
        if (searchResults.isEmpty()) return
        searchCursor = (searchCursor + direction).mod(searchResults.size)
        showNode(searchResults[searchCursor])
    }

    fun showNode(idx: Int) {
        setRoute(null)
        selected = idx
        val n = tree.nodes[idx]
        // Keep the node above the info panel
        focusOn(n.x, n.y, max(camera.scale, 0.2f), anchorY = 0.3f)
    }

    val searchPosition get() = if (searchCursor >= 0) searchCursor + 1 else 0

    fun summary(): TreeText.Summary = TreeText.summarize(spec)

    companion object {
        private const val TAG = "PoE2Tree"
        private const val MAX_UNDO = 100
        private const val DOUBLE_TAP_MS = 350L
        private const val DOUBLE_TAP_ZOOM = 2f
        /** PoB defaults to the Dexterity class (Ranger). */
        const val DEFAULT_CLASS = 2
    }
}
