package io.room.poe2tree.engine

import java.io.File

/**
 * One Path of Building engine shared by the unit tests (booting takes a couple of seconds).
 * Runs the desktop build of the native library (native/host/win-x64/poblua.dll, from
 * tools/build_luajit.py --host) on the APK's engine assets (tools/build_pob_assets.py).
 */
object EngineTestSupport {
    val project: File = listOf(File("."), File("..")).map { it.canonicalFile }.first { File(it, "native").isDirectory }
    val assets = File(project, "app/src/main/assets")
    val fixtures = File(project, "app/src/test/resources/builds")

    val api: PobApi by lazy {
        System.setProperty("poblua.path", File(project, "native/host/win-x64/poblua.dll").absolutePath)
        val files = DirectoryFiles(File(assets, "engine"), File(assets, "pob"))
        val lua = LuaState(files) { msg ->
            if (!msg.startsWith("missing node") && !msg.endsWith("not found...")) println("[lua] $msg")
        }
        val user = File(project, "app/build/tmp/pob-user").apply { mkdirs() }
        PobApi(lua).also { it.boot(user.invariantSeparatorsPath + "/") }
    }

    /** Fixture builds: name -> PoB XML. */
    fun fixtures(): Map<String, String> = fixtures.listFiles { f -> f.extension == "xml" }.orEmpty()
        .sortedBy { it.name }
        .associate { it.nameWithoutExtension to it.readText() }

    fun loadFixture(name: String) {
        api.call("loadBuild", mapOf("xml" to File(fixtures, "$name.xml").readText(), "name" to name))
    }

    fun newBuild() {
        api.call("loadBuild", mapOf("name" to "Test"))
    }
}
