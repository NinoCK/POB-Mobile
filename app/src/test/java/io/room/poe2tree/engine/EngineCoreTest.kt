package io.room.poe2tree.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineCoreTest {
    private val api get() = EngineTestSupport.api

    @Test
    fun newBuildComputesDefaults() {
        EngineTestSupport.newBuild()
        val life = api.lua.exec("return tostring(build.calcsTab.mainOutput.Life)")
        assertEquals("65", life)
    }

    @Test
    fun saveAndReloadXml() {
        EngineTestSupport.newBuild()
        val xml = api.callString("saveXml")
        assertTrue(xml.contains("<PathOfBuilding2>"))
        api.call("loadBuild", mapOf("xml" to xml, "name" to "Reloaded"))
        assertEquals("65", api.lua.exec("return tostring(build.calcsTab.mainOutput.Life)"))
    }

    @Test
    fun errorsAreReported() {
        val e = runCatching { api.call("loadBuild", mapOf("xml" to "<nope/>")) }.exceptionOrNull()
        assertTrue(e is EngineException)
    }
}
