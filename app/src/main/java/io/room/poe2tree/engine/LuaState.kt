package io.room.poe2tree.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The Lua files the engine runs: the app's own scripts ("engine/Host.lua") and the Path of Building
 * tree (paths relative to PoB's src folder, "Modules/Main.lua").
 */
fun interface EngineFiles {
    fun read(path: String): ByteArray?
}

/** Files from directories: [engineDir] for "engine/..." paths, [pobDir] for everything else. */
class DirectoryFiles(private val engineDir: File, private val pobDir: File) : EngineFiles {
    override fun read(path: String): ByteArray? {
        if (path.contains("..")) return null
        val f = if (path.startsWith(ENGINE_PREFIX)) File(engineDir, path.removePrefix(ENGINE_PREFIX)) else File(pobDir, path)
        return if (f.isFile) f.readBytes() else null
    }
}

internal const val ENGINE_PREFIX = "engine/"

/**
 * One LuaJIT state with the host services Path of Building needs. Not thread safe: all calls must
 * come from the same thread.
 */
class LuaState(private val files: EngineFiles, private val logger: (String) -> Unit) : AutoCloseable {

    private val host = object : LuaHost {
        override fun readFile(path: String): ByteArray? = files.read(path)
        override fun log(message: ByteArray) = logger(String(message, Charsets.UTF_8))
        override fun inflate(data: ByteArray): ByteArray? = runCatching { zlibInflate(data) }.getOrNull()
        override fun deflate(data: ByteArray): ByteArray? = zlibDeflate(data)
    }

    private var ptr: Long

    init {
        LuaNative.ensureLoaded()
        ptr = LuaNative.create(host)
        check(ptr != 0L) { "Could not create a Lua state" }
    }

    /** Runs [code] and returns its first result as a string (null for nil). */
    fun exec(code: String, chunkName: String = "=app"): String? {
        check(ptr != 0L) { "Lua state is closed" }
        return LuaNative.exec(ptr, code.toByteArray(Charsets.UTF_8), chunkName)?.toString(Charsets.UTF_8)
    }

    /** Runs a file of the engine or PoB tree with the given arguments passed as Lua literals. */
    fun runFile(path: String, argsLiteral: String = ""): String? =
        exec("local f = assert(__host_loadfile(${luaString(path)})); return f($argsLiteral)", "=run:$path")

    val memoryKb: Long get() = if (ptr != 0L) LuaNative.memoryKb(ptr) else 0

    override fun close() {
        if (ptr != 0L) {
            LuaNative.close(ptr)
            ptr = 0
        }
    }

    companion object {
        /** A Lua string literal for [s]. */
        fun luaString(s: String): String {
            val sb = StringBuilder(s.length + 2)
            sb.append('"')
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                when {
                    c == '"'.code -> sb.append("\\\"")
                    c == '\\'.code -> sb.append("\\\\")
                    c == '\n'.code -> sb.append("\\n")
                    // Control and non-ASCII bytes as 3-digit decimal escapes, keeping the literal ASCII
                    c < 0x20 || c >= 0x7F -> sb.append('\\').append(c.toString().padStart(3, '0'))
                    else -> sb.append(c.toChar())
                }
            }
            sb.append('"')
            return sb.toString()
        }

        fun zlibInflate(data: ByteArray): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream(data.size * 4)
                val buf = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                inflater.end()
            }
        }

        fun zlibDeflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.BEST_COMPRESSION)
            try {
                deflater.setInput(data)
                deflater.finish()
                val out = ByteArrayOutputStream(data.size / 2 + 64)
                val buf = ByteArray(64 * 1024)
                while (!deflater.finished()) {
                    out.write(buf, 0, deflater.deflate(buf))
                }
                return out.toByteArray()
            } finally {
                deflater.end()
            }
        }
    }
}
