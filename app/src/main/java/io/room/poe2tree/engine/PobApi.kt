package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** An error reported by Path of Building or by the app's Lua API. */
class EngineException(message: String) : RuntimeException(message)

/**
 * Synchronous access to Path of Building through the app's Lua API (assets/engine/Api.lua).
 * Must only be used on the thread that owns [lua].
 */
class PobApi(val lua: LuaState) {

    /** Boots PoB and loads the API. [userPath] is a writable folder for PoB's settings. */
    fun boot(userPath: String) {
        lua.runFile("${ENGINE_PREFIX}Host.lua", "{ userPath = ${LuaState.luaString(userPath)} }")
        lua.runFile("${ENGINE_PREFIX}Api.lua")
        for (module in MODULES) {
            lua.exec("api.load(${LuaState.luaString(module)})", "=load:$module")
        }
    }

    /**
     * Calls api.<name>(args). Returns the decoded result: JSONObject, JSONArray, String, Number,
     * Boolean or null. Throws [EngineException] with PoB's error message.
     */
    fun call(name: String, args: Any? = null): Any? {
        val argsJson = when (args) {
            null -> ""
            is JSONObject, is JSONArray -> args.toString()
            is Map<*, *> -> JSONObject(args).toString()
            else -> throw IllegalArgumentException("arguments must be a JSON object")
        }
        val code = "return api.call(${LuaState.luaString(name)}, ${LuaState.luaString(argsJson)})"
        val text = lua.exec(code, "=api.$name") ?: throw EngineException("no response from api.$name")
        val response = JSONObject(text)
        if (!response.optBoolean("ok")) throw EngineException(response.optString("error", "unknown error"))
        return if (response.isNull("result")) null else response.get("result")
    }

    fun callObject(name: String, args: Any? = null): JSONObject =
        call(name, args) as? JSONObject ?: throw EngineException("api.$name did not return an object")

    fun callArray(name: String, args: Any? = null): JSONArray =
        call(name, args) as? JSONArray ?: throw EngineException("api.$name did not return a list")

    fun callString(name: String, args: Any? = null): String =
        call(name, args) as? String ?: throw EngineException("api.$name did not return text")

    companion object {
        /** Feature modules in assets/engine/api, loaded in this order. */
        val MODULES = listOf("Stats", "Tree", "Calcs", "Config", "Skills", "Items", "Power", "Craft")

        fun parse(text: String): Any? = JSONTokener(text).nextValue()
    }
}
