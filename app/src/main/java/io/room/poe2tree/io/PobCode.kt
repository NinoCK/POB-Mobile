package io.room.poe2tree.io

import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Path of Building 2 build codes: base64url(zlib(XML)).
 * Only the passive tree part is read and written.
 */
object PobCode {

    /** Legacy numeric class ids used by old tree versions (PoB `legacyClassIdMap`). */
    private val legacyClassIdMap = mapOf(
        "0_1" to mapOf(0 to 2, 1 to 6, 2 to 9, 3 to 1, 4 to 7, 5 to 10),
        "0_2" to mapOf(0 to 2, 1 to 8, 2 to 6, 3 to 9, 4 to 1, 5 to 7, 6 to 10),
        "0_3" to mapOf(0 to 2, 1 to 8, 2 to 6, 3 to 9, 4 to 1, 5 to 7, 6 to 10),
    )

    class ImportResult(
        val snapshot: PassiveSpec.Snapshot,
        val title: String?,
        val level: Int?,
        val treeVersion: String?,
        val warnings: List<String>,
    )

    class ImportException(message: String) : Exception(message)

    fun decodeXml(code: String): String {
        val cleaned = code
            .filterNot { it.isWhitespace() }
            .replace('-', '+').replace('_', '/')
            .trimEnd('=')
        if (cleaned.isEmpty()) throw ImportException("Build code is empty")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val bytes = try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw ImportException("Not a valid build code (base64)")
        }
        val inflater = Inflater()
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, count)
            }
        } catch (e: Exception) {
            throw ImportException("Not a valid build code (compression)")
        } finally {
            inflater.end()
        }
        if (out.size() == 0) throw ImportException("Not a valid build code (empty)")
        return out.toString(Charsets.UTF_8.name())
    }

    fun encodeXml(xml: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(xml.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray()).replace('+', '-').replace('/', '_')
    }

    fun import(code: String, tree: PassiveTree): ImportResult = importXml(decodeXml(code), tree)

    fun importXml(xml: String, tree: PassiveTree): ImportResult {
        val doc = try {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        } catch (e: Exception) {
            throw ImportException("Build code contains invalid XML")
        }
        val root = doc.documentElement
        if (root.tagName == "PathOfBuilding") {
            throw ImportException("This is a Path of Exile 1 build code. Only PoE2 codes are supported.")
        }
        if (root.tagName != "PathOfBuilding2") throw ImportException("Not a Path of Building 2 build code")
        val warnings = ArrayList<String>()

        val build = root.children("Build").firstOrNull()
        val level = build?.getAttribute("level")?.toIntOrNull()
        val buildClassName = build?.getAttribute("className")?.takeIf { it.isNotEmpty() }
        val buildAscName = build?.getAttribute("ascendClassName")?.takeIf { it.isNotEmpty() }

        val treeEl = root.children("Tree").firstOrNull()
        val specs = treeEl?.children("Spec") ?: emptyList()
        if (specs.isEmpty()) throw ImportException("Build code has no passive tree")
        val activeIndex = (treeEl?.getAttribute("activeSpec")?.toIntOrNull() ?: 1).coerceIn(1, specs.size)
        val spec = specs[activeIndex - 1]

        val treeVersion = spec.getAttribute("treeVersion").takeIf { it.isNotEmpty() }
        if (treeVersion != null && treeVersion != tree.treeVersion) {
            warnings += "Build uses tree version ${treeVersion.replace('_', '.')}; converted to ${tree.treeVersion.replace('_', '.')}. Some nodes may have changed."
        }

        // Class
        var classId = -1
        spec.getAttribute("classInternalId").toIntOrNull()?.let { if (tree.classById(it) != null) classId = it }
        if (classId < 0) {
            spec.getAttribute("classId").toIntOrNull()?.let { raw ->
                val mapped = legacyClassIdMap[treeVersion ?: "0_1"]?.get(raw) ?: raw
                if (tree.classById(mapped) != null) classId = mapped
            }
        }
        if (classId < 0 && buildClassName != null) {
            tree.classByName(buildClassName)?.let { classId = it.integerId }
        }
        if (classId < 0) throw ImportException("Build code has an unknown class")
        val cls = tree.classById(classId)!!

        // Ascendancy
        var ascId = 0
        if (spec.hasAttribute("ascendancyInternalId")) {
            val internal = spec.getAttribute("ascendancyInternalId")
            ascId = if (internal.isEmpty()) 0 else tree.ascendancyByInternalId[internal]?.second?.index ?: 0
        } else {
            val raw = spec.getAttribute("ascendClassId").toIntOrNull()
            if (raw != null && cls.ascendancy(raw) != null) ascId = raw
            else if (buildAscName != null) ascId = cls.ascendancies.firstOrNull { it.id == buildAscName }?.index ?: 0
        }

        // Weapon sets
        val weaponSets = HashMap<Int, Int>()
        for (child in spec.childElements()) {
            val m = Regex("^WeaponSet(\\d)$").find(child.tagName) ?: continue
            val set = m.groupValues[1].toInt()
            for (id in child.getAttribute("nodes").idList()) weaponSets[id] = set
        }

        // Attribute overrides
        val attrs = LinkedHashMap<Int, Int>()
        for (overrides in spec.children("Overrides")) {
            for (o in overrides.children("AttributeOverride")) {
                o.getAttribute("strNodes").idList().forEach { attrs[it] = 1 }
                o.getAttribute("dexNodes").idList().forEach { attrs[it] = 2 }
                o.getAttribute("intNodes").idList().forEach { attrs[it] = 3 }
            }
        }

        val nodeIds = spec.getAttribute("nodes").idList()
        if (nodeIds.isEmpty() && spec.children("URL").isNotEmpty()) {
            warnings += "Legacy tree URL format is not supported; only the class was imported."
        }
        val missing = nodeIds.count { tree.node(it) == null }
        if (missing > 0) warnings += "$missing node(s) do not exist in this tree version and were skipped."

        val nodes = LinkedHashMap<Int, Int>()
        for (id in nodeIds) nodes[id] = weaponSets[id] ?: 0

        return ImportResult(
            snapshot = PassiveSpec.Snapshot(classId, ascId, nodes, attrs),
            title = spec.getAttribute("title").takeIf { it.isNotEmpty() },
            level = level,
            treeVersion = treeVersion,
            warnings = warnings,
        )
    }

    /** Minimal PoB2 build containing only the passive tree. */
    fun exportXml(spec: PassiveSpec, title: String, level: Int): String {
        val tree = spec.tree
        val snap = spec.snapshot()
        val cls = tree.classById(snap.classId)!!
        val asc = cls.ascendancy(snap.ascendClassId)

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = doc.createElement("PathOfBuilding2")
        doc.appendChild(root)

        val build = doc.createElement("Build")
        build.setAttribute("level", level.toString())
        build.setAttribute("targetVersion", "0_1")
        build.setAttribute("className", cls.name)
        build.setAttribute("ascendClassName", asc?.id ?: "None")
        build.setAttribute("mainSocketGroup", "1")
        build.setAttribute("viewMode", "TREE")
        build.setAttribute("characterLevelAutoMode", "false")
        root.appendChild(build)

        val treeEl = doc.createElement("Tree")
        treeEl.setAttribute("activeSpec", "1")
        root.appendChild(treeEl)

        val sortedIds = snap.nodes.keys.sorted()
        val specEl = doc.createElement("Spec")
        specEl.setAttribute("title", title)
        specEl.setAttribute("treeVersion", tree.treeVersion)
        specEl.setAttribute("classId", cls.integerId.toString())
        specEl.setAttribute("ascendClassId", (asc?.index ?: 0).toString())
        specEl.setAttribute("classInternalId", cls.integerId.toString())
        specEl.setAttribute("ascendancyInternalId", asc?.internalId ?: "")
        specEl.setAttribute("secondaryAscendClassId", "0")
        specEl.setAttribute("nodes", sortedIds.joinToString(","))
        specEl.setAttribute("masteryEffects", "")
        treeEl.appendChild(specEl)

        for (set in 1..2) {
            val ids = sortedIds.filter { snap.nodes[it] == set }
            if (ids.isNotEmpty()) {
                val ws = doc.createElement("WeaponSet$set")
                ws.setAttribute("nodes", ids.joinToString(","))
                specEl.appendChild(ws)
            }
        }
        specEl.appendChild(doc.createElement("Sockets"))
        val overrides = doc.createElement("Overrides")
        val attr = doc.createElement("AttributeOverride")
        val attrIds = snap.attributes.keys.sorted()
        attr.setAttribute("strNodes", attrIds.filter { snap.attributes[it] == 1 }.joinToString(","))
        attr.setAttribute("dexNodes", attrIds.filter { snap.attributes[it] == 2 }.joinToString(","))
        attr.setAttribute("intNodes", attrIds.filter { snap.attributes[it] == 3 }.joinToString(","))
        overrides.appendChild(attr)
        specEl.appendChild(overrides)

        val writer = StringWriter()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    fun export(spec: PassiveSpec, title: String, level: Int): String = encodeXml(exportXml(spec, title, level))

    // ---- DOM helpers ----

    private fun Element.childElements(): List<Element> {
        val out = ArrayList<Element>()
        val list = childNodes
        for (i in 0 until list.length) {
            val node = list.item(i)
            if (node is Element) out += node
        }
        return out
    }

    private fun Element.children(tag: String) = childElements().filter { it.tagName == tag }

    private fun String.idList(): List<Int> = Regex("\\d+").findAll(this).map { it.value.toInt() }.toList()
}
