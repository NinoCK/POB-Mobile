package io.room.poe2tree.engine

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.room.poe2tree.tree.PassiveSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

sealed interface EngineStatus {
    data object Starting : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Stat changes for the selected node, for the tree state [treeSeq]. */
data class CompareResult(val nodeId: Int, val treeSeq: Int, val lines: List<String>)

/**
 * The open build in Path of Building's engine. The app's tree is authoritative for the passive
 * allocation and is pushed after every change (only the latest state is applied); everything else
 * (items, skills, configuration) lives in PoB, and its XML is saved after every change.
 * Must be used from the main thread; the engine work runs on its own thread.
 */
class CalcSession(
    private val engine: PobEngine,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        /** The build's PoB XML changed (items, skills, configuration...): save it. */
        fun onBuildXml(buildId: String, xml: String)
        fun onError(message: String)
    }

    var status by mutableStateOf<EngineStatus>(EngineStatus.Starting)
        private set
    /** Sidebar stats and main skill selection of the open build (null until it is calculated). */
    var state by mutableStateOf<EngineState?>(null)
        private set
    /** Number of engine operations running. */
    var busy by mutableIntStateOf(0)
        private set
    /** Incremented whenever the calculated build changed: screens reload their data. */
    var dataRevision by mutableIntStateOf(0)
        private set
    var compare by mutableStateOf<CompareResult?>(null)
        private set

    private data class OpenRequest(val buildId: String, val name: String, val xml: String?, val tree: PassiveSpec.Snapshot, val level: Int, val treeSeq: Int)
    private data class TreePush(val buildId: String, val tree: PassiveSpec.Snapshot, val seq: Int)

    /** Build loaded in the engine. */
    private var openBuildId: String? = null
    private var pendingOpen: OpenRequest? = null
    private val appliedTreeSeq = MutableStateFlow(-1)
    private val treePushes = MutableStateFlow<TreePush?>(null)
    private var compareJob: Job? = null
    private var saveJob: Job? = null

    fun start() {
        scope.launch {
            try {
                val ms = engine.start()
                Log.i(TAG, "engine started in $ms ms")
                status = EngineStatus.Ready
                pendingOpen?.let { doOpen(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "engine failed to start", e)
                status = EngineStatus.Failed(e.message ?: e.toString())
            }
        }
        scope.launch {
            treePushes.collect { push ->
                if (push == null || push.buildId != openBuildId || status != EngineStatus.Ready) return@collect
                if (push.seq <= appliedTreeSeq.value) return@collect
                val result = run { api ->
                    EngineJson.state(api.callObject("setTree", push.tree.toEngineJson()))
                }
                if (result != null && push.buildId == openBuildId && push.seq > appliedTreeSeq.value) {
                    applyState(result)
                    appliedTreeSeq.value = push.seq
                    // Keep the build's XML in step with the tree, so opening it later needs no recalculation
                    scheduleSave(TREE_SAVE_DELAY_MS)
                }
            }
        }
    }

    val ready get() = status == EngineStatus.Ready && openBuildId != null && state != null

    /** Opens a build: its PoB XML (null for a build without one), with the app's tree and level. */
    fun open(buildId: String, name: String, xml: String?, tree: PassiveSpec.Snapshot, level: Int, treeSeq: Int) {
        val request = OpenRequest(buildId, name, xml, tree, level, treeSeq)
        // A change of the previous build not saved yet
        val previous = openBuildId
        val unsaved = saveJob?.isActive == true
        saveJob?.cancel()
        compareJob?.cancel()
        openBuildId = null
        state = null
        compare = null
        if (status == EngineStatus.Ready) {
            scope.launch {
                if (unsaved && previous != null) saveNow(previous)
                doOpen(request)
            }
        } else {
            pendingOpen = request
        }
    }

    private suspend fun saveNow(buildId: String) {
        try {
            listener.onBuildXml(buildId, engine.call { api -> api.callString("saveXml") })
        } catch (e: Exception) {
            Log.w(TAG, "could not save build $buildId", e)
        }
    }

    private suspend fun doOpen(original: OpenRequest) {
        pendingOpen = null
        // Use the latest tree of this build if it changed while waiting
        val latest = treePushes.value
        val request = if (latest != null && latest.buildId == original.buildId && latest.seq > original.treeSeq)
            original.copy(tree = latest.tree, treeSeq = latest.seq) else original
        openBuildId = request.buildId
        appliedTreeSeq.value = -1
        val args = JSONObject().put("name", request.name).put("tree", request.tree.toEngineJson()).put("level", request.level)
        if (request.xml != null) args.put("xml", request.xml)
        var result = run(reportErrors = false) { api -> EngineJson.state(api.callObject("openBuild", args)) }
        if (result == null && request.xml != null) {
            // The saved build could not be loaded: continue with the tree alone
            listener.onError("The build's items and skills could not be loaded; only the passive tree is used.")
            args.remove("xml")
            result = run { api -> EngineJson.state(api.callObject("openBuild", args)) }
        }
        if (result == null || openBuildId != request.buildId) return
        if (result.droppedNodes.isNotEmpty()) Log.w(TAG, "PoB did not accept nodes ${result.droppedNodes}")
        applyState(result)
        appliedTreeSeq.value = maxOf(appliedTreeSeq.value, request.treeSeq)
        if (result.changed) scheduleSave(TREE_SAVE_DELAY_MS)
    }

    /** The app's tree changed. Only the latest state is sent. */
    fun pushTree(buildId: String, tree: PassiveSpec.Snapshot, seq: Int) {
        treePushes.value = TreePush(buildId, tree, seq)
    }

    /** Stat changes of allocating / removing [nodeId] (null clears). [path] is the app's planned path. */
    fun requestCompare(nodeId: Int?, path: IntArray?, allocMode: Int, treeSeq: Int) {
        compareJob?.cancel()
        if (nodeId == null || nodeId < 0) {
            compare = null
            return
        }
        if (compare?.nodeId != nodeId) compare = null
        compareJob = scope.launch {
            delay(120)
            appliedTreeSeq.first { it >= treeSeq }
            val args = JSONObject().put("id", nodeId).put("allocMode", allocMode)
            if (path != null) args.put("path", JSONArray().apply { path.forEach { put(it) } })
            val lines = run(reportErrors = false) { api -> EngineJson.nodeCompare(api.callObject("nodeCompare", args)).lines }
            if (lines != null) compare = CompareResult(nodeId, treeSeq, lines)
        }
    }

    // ---- Changes made in the engine ----

    fun select(args: Map<String, Any?>) = mutate { api -> EngineJson.state(api.callObject("select", JSONObject(args))) }

    fun setLevel(level: Int) = mutate(save = true) { api -> EngineJson.state(api.callObject("setLevel", mapOf("level" to level))) }

    suspend fun setConfig(idx: Int, value: Any?, showAll: Boolean): ConfigData? = mutateAndRead { api ->
        val args = JSONObject().put("idx", idx).put("value", value ?: JSONObject.NULL).put("showAll", showAll)
        ScreenJson.config(api.callObject("setConfig", args))
    }

    suspend fun resetConfig(idx: Int, showAll: Boolean): ConfigData? = mutateAndRead { api ->
        ScreenJson.config(api.callObject("resetConfig", mapOf("idx" to idx, "showAll" to showAll)))
    }

    suspend fun setConfigSet(id: Int, showAll: Boolean): ConfigData? = mutateAndRead { api ->
        ScreenJson.config(api.callObject("setConfigSet", mapOf("id" to id, "showAll" to showAll)))
    }

    suspend fun setCustomMods(blocks: List<CustomModBlock>): List<CustomModBlock>? = mutateAndRead { api ->
        val arr = JSONArray()
        for (b in blocks) arr.put(JSONObject().put("title", b.title).put("enabled", b.enabled).put("text", b.text))
        ScreenJson.customMods(api.callArray("setCustomMods", JSONObject().put("blocks", arr)))
    }

    suspend fun calcsSelect(args: Map<String, Any?>): CalcsSelection? = mutateAndRead(recalculates = args.keys != setOf("showMinion")) { api ->
        ScreenJson.calcsSelection(api.callObject("calcsSelect", JSONObject(args)))
    }.also { if (args.keys == setOf("showMinion")) dataRevision++ }

    suspend fun skillEdit(args: Map<String, Any?>): SkillsData? = mutateAndRead { api ->
        ScreenJson.skills(api.callObject("skillEdit", JSONObject(args)).getJSONObject("skills"))
    }

    /** Returns the new items and, for "add", the new item's id. */
    suspend fun itemEdit(args: Map<String, Any?>): Pair<ItemsData, Int?>? = mutateAndRead { api ->
        val result = api.callObject("itemEdit", JSONObject(args))
        ScreenJson.items(result.getJSONObject("items")) to (if (result.has("addedId")) result.getInt("addedId") else null)
    }

    // ---- Reading ----

    suspend fun calcSections(): List<CalcSection>? = read { api -> ScreenJson.calcSections(api.callArray("calcSections")) }
    suspend fun calcsSelection(): CalcsSelection? = read { api -> ScreenJson.calcsSelection(api.callObject("calcsSelection")) }
    suspend fun calcBreakdown(key: String): Breakdown? = read { api -> ScreenJson.breakdown(api.callObject("calcBreakdown", mapOf("key" to key))) }
    suspend fun sidebarBreakdown(row: SidebarRow): Breakdown? = read { api ->
        val args = JSONObject().put("stat", row.stat).put("actor", row.actor ?: "player")
        if (row.childStat != null) args.put("childStat", row.childStat)
        ScreenJson.breakdown(api.callObject("sidebarBreakdown", args))
    }
    suspend fun config(showAll: Boolean): ConfigData? = read { api -> ScreenJson.config(api.callObject("config", mapOf("showAll" to showAll))) }
    suspend fun skills(): SkillsData? = read { api -> ScreenJson.skills(api.callObject("skills")) }
    suspend fun gemSearch(query: String, support: Boolean?): List<GemChoice>? = read { api ->
        val args = JSONObject().put("query", query)
        if (support != null) args.put("support", support)
        ScreenJson.gemChoices(api.callArray("gemSearch", args))
    }
    suspend fun gemTooltip(group: Int, gem: Int): List<String>? = read { api -> api.callArray("gemTooltip", mapOf("group" to group, "gem" to gem)).strings() }
    suspend fun items(): ItemsData? = read { api -> ScreenJson.items(api.callObject("items")) }
    suspend fun itemTooltip(id: Int, slot: String?): ItemTooltip? = read { api ->
        ScreenJson.itemTooltip(api.callObject("itemTooltip", JSONObject().put("id", id).apply { if (slot != null) put("slot", slot) }))
    }

    /** The open build as PoB XML, with the current tree. Null if the engine is not ready. */
    suspend fun exportXml(treeSeq: Int): String? {
        if (!ready) return null
        appliedTreeSeq.first { it >= treeSeq }
        return run { api -> api.callString("saveXml") }
    }

    // ---- Plumbing ----

    private fun applyState(newState: EngineState) {
        state = newState
        dataRevision++
    }

    /** Runs an engine call for the open build; null (and the error reported) on failure. */
    private suspend fun <T> run(reportErrors: Boolean = true, block: (PobApi) -> T): T? {
        val buildId = openBuildId ?: return null
        busy++
        try {
            val t0 = System.nanoTime()
            var heapKb = 0L
            val result = engine.call { api -> block(api).also { heapKb = api.lua.memoryKb } }
            Log.d(TAG, "engine call ${(System.nanoTime() - t0) / 1_000_000} ms, Lua heap ${heapKb / 1024} MB")
            return if (buildId == openBuildId) result else null
        } catch (e: EngineException) {
            Log.w(TAG, "engine: ${e.message}")
            // PoB's message without the Lua traceback
            if (reportErrors) listener.onError(e.message?.substringBefore("stack traceback")?.trim()?.ifEmpty { null } ?: "Calculation error")
            return null
        } catch (e: LuaException) {
            Log.e(TAG, "lua error", e)
            if (reportErrors) listener.onError("Calculation error: ${e.message?.lineSequence()?.firstOrNull()}")
            return null
        } finally {
            busy--
        }
    }

    private suspend fun <T> read(block: (PobApi) -> T): T? = if (ready) run(block = block) else null

    private fun mutate(save: Boolean = true, block: (PobApi) -> EngineState) {
        if (!ready) return
        scope.launch {
            val result = run(block = block) ?: return@launch
            applyState(result)
            if (save) scheduleSave()
        }
    }

    /** A change of the build made in the engine: returns [block]'s result and refreshes the sidebar. */
    private suspend fun <T> mutateAndRead(recalculates: Boolean = true, block: (PobApi) -> T): T? {
        if (!ready) return null
        val (result, newState) = run { api ->
            val r = block(api)
            r to (if (recalculates) EngineJson.state(api.callObject("state")) else null)
        } ?: return null
        if (newState != null) {
            state = newState
            dataRevision++
            scheduleSave()
        }
        return result
    }

    /** Saves the build's XML shortly after the last change. */
    private fun scheduleSave(delayMs: Long = 400) {
        val buildId = openBuildId ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(delayMs)
            val xml = run { api -> api.callString("saveXml") } ?: return@launch
            if (buildId == openBuildId) listener.onBuildXml(buildId, xml)
        }
    }

    fun close() = engine.close()

    companion object {
        private const val TAG = "PoE2Calc"
        private const val TREE_SAVE_DELAY_MS = 1500L
    }
}
