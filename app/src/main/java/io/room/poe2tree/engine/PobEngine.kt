package io.room.poe2tree.engine

import android.content.res.AssetManager
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Engine files from the APK: "engine/..." from assets/engine, PoB's tree from assets/pob. */
class AssetFiles(private val assets: AssetManager) : EngineFiles {
    override fun read(path: String): ByteArray? {
        if (path.contains("..")) return null
        val assetPath = if (path.startsWith(ENGINE_PREFIX)) path else "pob/$path"
        return try {
            assets.open(assetPath, AssetManager.ACCESS_STREAMING).use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }
}

/**
 * Path of Building's calculation engine: PoB's Lua program running headless in LuaJIT on a
 * dedicated thread. All calls go through [call], in order.
 */
class PobEngine(private val files: EngineFiles, private val userDir: File, private val logger: (String) -> Unit) {

    // Deep Lua call chains (PoB's calc code) need more C stack than the default thread stack
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(null, r, "pob-engine", 16L * 1024 * 1024) }
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()

    private var api: PobApi? = null

    /** Boots PoB and loads the app's API. Returns the time taken in milliseconds. */
    suspend fun start(): Long = withContext(dispatcher) {
        val t0 = System.nanoTime()
        if (api == null) {
            val lua = LuaState(files, logger)
            try {
                userDir.mkdirs()
                PobApi(lua).also { it.boot(userDir.absolutePath.replace('\\', '/').trimEnd('/') + "/") }.let { api = it }
            } catch (e: Throwable) {
                lua.close()
                throw e
            }
        }
        (System.nanoTime() - t0) / 1_000_000
    }

    /** Runs [block] on the engine thread. */
    suspend fun <T> call(block: (PobApi) -> T): T = withContext(dispatcher) {
        block(api ?: throw EngineException("The calculation engine is not running"))
    }

    fun close() {
        executor.execute {
            api?.lua?.close()
            api = null
        }
        executor.shutdown()
    }
}
