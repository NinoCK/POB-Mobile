package io.room.poe2tree.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The app's engine (LuaJIT built from source, assets/pob) must compute what the desktop program
 * computes: compares each fixture with <name>.expected.json from tools/pob_reference.py.
 */
class FixtureParityTest {

    @Test
    fun fixturesMatchDesktopPathOfBuilding() {
        val api = EngineTestSupport.api
        val fixtures = EngineTestSupport.fixtures()
        assertTrue("no fixtures", fixtures.isNotEmpty())
        val problems = ArrayList<String>()
        for ((name, xml) in fixtures) {
            val expectedFile = File(EngineTestSupport.fixtures, "$name.expected.json")
            if (!expectedFile.exists()) continue
            val expected = JSONObject(expectedFile.readText())
            api.call("loadBuild", mapOf("xml" to xml, "name" to name))
            api.call("recalc")
            val actual = JSONObject(
                api.lua.exec(
                    """
                    local out = build.calcsTab.mainOutput
                    local flat = { }
                    local function put(prefix, t)
                        for k, v in pairs(t) do
                            local tv = type(v)
                            if tv == "number" then flat[prefix .. tostring(k)] = string.format("%.17g", v)
                            elseif tv == "boolean" or tv == "string" then flat[prefix .. tostring(k)] = tostring(v) end
                        end
                    end
                    put("", out)
                    if out.Minion then put("Minion.", out.Minion) end
                    return api.encode({ mainOutput = flat, state = api.state() })
                    """.trimIndent()
                )!!
            )
            compareOutputs(name, expected.getJSONObject("mainOutput"), actual.getJSONObject("mainOutput"), problems)
            compareJson("$name.state", expected.get("state"), actual.get("state"), problems)
            println("$name: ${if (problems.isEmpty()) "OK" else "${problems.size} differences so far"}")
        }
        assertTrue(problems.take(40).joinToString("\n"), problems.isEmpty())
    }

    private fun compareOutputs(name: String, expected: JSONObject, actual: JSONObject, problems: MutableList<String>) {
        for (key in expected.keys()) {
            val e = expected.getString(key)
            val a = actual.optString(key, "<missing>")
            if (e == a) continue
            val ed = e.toDoubleOrNull()
            val ad = a.toDoubleOrNull()
            if (ed != null && ad != null && abs(ed - ad) <= 1e-9 * max(abs(ed), abs(ad))) continue
            problems += "$name.mainOutput.$key: expected $e, got $a"
        }
        for (key in actual.keys()) {
            if (!expected.has(key)) problems += "$name.mainOutput.$key: unexpected ${actual.getString(key)}"
        }
    }

    private fun compareJson(path: String, expected: Any?, actual: Any?, problems: MutableList<String>) {
        when {
            expected is JSONObject && actual is JSONObject -> {
                for (key in expected.keys().asSequence().toSet() + actual.keys().asSequence().toSet()) compareJson("$path.$key", expected.opt(key), actual.opt(key), problems)
            }
            expected is JSONArray && actual is JSONArray -> {
                if (expected.length() != actual.length()) problems += "$path: expected ${expected.length()} items, got ${actual.length()}"
                for (i in 0 until minOf(expected.length(), actual.length())) compareJson("$path[$i]", expected.get(i), actual.get(i), problems)
            }
            expected is Number && actual is Number -> {
                val e = expected.toDouble()
                val a = actual.toDouble()
                if (e != a && abs(e - a) > 1e-9 * max(abs(e), abs(a))) problems += "$path: expected $e, got $a"
            }
            expected != actual && expected.toString() != actual.toString() -> problems += "$path: expected $expected, got $actual"
        }
    }
}
