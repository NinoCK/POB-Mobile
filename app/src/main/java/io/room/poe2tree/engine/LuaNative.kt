package io.room.poe2tree.engine

/** A Lua error, with the Lua traceback in the message. */
class LuaException(message: String) : RuntimeException(message)

/**
 * Services the Lua side reaches through the `__host_*` globals (see native/pob_jni.c).
 * Called on the thread that runs the Lua state.
 */
interface LuaHost {
    /** Contents of a file of the Path of Building tree ("Modules/Main.lua"), or null if missing. */
    fun readFile(path: String): ByteArray?
    fun log(message: ByteArray)
    /** zlib-wrapped deflate data, as Path of Building's Inflate / Deflate use. */
    fun inflate(data: ByteArray): ByteArray?
    fun deflate(data: ByteArray): ByteArray?
}

/** JNI bindings of libpoblua (LuaJIT + native/pob_jni.c). A state must only be used from one thread. */
internal object LuaNative {
    @Volatile private var loaded = false

    /** Loads the native library; unit tests load the desktop build with [System.load] first. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                if (System.getProperty("poblua.path") != null) System.load(System.getProperty("poblua.path")!!)
                else System.loadLibrary("poblua")
                loaded = true
            }
        }
    }

    @JvmStatic external fun create(host: LuaHost): Long
    /** Runs [code]; returns its first result as a string's bytes (null for nil). Throws [LuaException]. */
    @JvmStatic external fun exec(state: Long, code: ByteArray, chunkName: String): ByteArray?
    @JvmStatic external fun memoryKb(state: Long): Long
    @JvmStatic external fun close(state: Long)
}
