package io.room.poe2tree.tree

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable passive tree data: nodes, positions, classes and connector geometry.
 * Port of PoB's PassiveTree.lua (without modifier parsing).
 */
class PassiveTree(
    val nodes: List<TreeNode>,
    val groups: List<Group?>,
    val classes: List<ClassInfo>,
    val connectors: List<Connector>,
    val sprites: Map<String, SpriteInfo>,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val treeVersion: String,
) {
    val nodeById: Map<Int, TreeNode> = nodes.associateBy { it.id }

    /** Largest dimension used to fit the tree on screen (PoB `tree.size`). */
    val size = min(maxX - minX, maxY - minY) * 1.1f

    /** Bounds of what is actually drawn (nodes and ascendancy art); PoB's min/max include empty margins. */
    val contentBounds: FloatArray = run {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (n in nodes) {
            if (n.group <= 0) continue
            x0 = min(x0, n.x); x1 = max(x1, n.x)
            y0 = min(y0, n.y); y1 = max(y1, n.y)
        }
        for (c in classes) for (a in c.ascendancies) {
            val b = a.background ?: continue
            x0 = min(x0, b.x - b.width); x1 = max(x1, b.x + b.width)
            y0 = min(y0, b.y - b.height); y1 = max(y1, b.y + b.height)
        }
        if (x0 > x1) floatArrayOf(minX, minY, maxX, maxY) else floatArrayOf(x0, y0, x1, y1)
    }

    fun node(id: Int) = nodeById[id]

    fun classById(integerId: Int) = classes.firstOrNull { it.integerId == integerId }

    fun classByName(name: String) = classes.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Every ascendancy with its class, keyed by ascendancy id (display name). */
    val ascendancyMap: Map<String, Pair<ClassInfo, AscendancyInfo>> =
        classes.flatMap { c -> c.ascendancies.map { a -> a.id to (c to a) } }.toMap()

    val ascendancyByInternalId: Map<String, Pair<ClassInfo, AscendancyInfo>> =
        classes.flatMap { c -> c.ascendancies.map { a -> a.internalId to (c to a) } }.toMap()

    companion object {
        /** Angular step of arc ribbons (3 degrees keeps the largest orbits smooth when zoomed in). */
        private const val ARC_STEP = PI / 60

        fun load(treeJson: String, spritesJson: String): PassiveTree {
            val sprites = parseSprites(JSONObject(spritesJson))
            return Loader(JSONObject(treeJson), sprites).build()
        }

        fun parseSprites(obj: JSONObject): Map<String, SpriteInfo> {
            val out = HashMap<String, SpriteInfo>(obj.length() * 2)
            for (key in obj.keys()) {
                val s = obj.getJSONObject(key)
                out[key] = SpriteInfo(s.getString("f"), s.getInt("w"), s.getInt("h"), s.optInt("sw", s.getInt("w")), s.optInt("sh", s.getInt("h")))
            }
            return out
        }
    }

    private class Loader(val root: JSONObject, val sprites: Map<String, SpriteInfo>) {
        val constants: JSONObject = root.getJSONObject("constants")
        val orbitRadii: FloatArray = constants.getJSONArray("orbitRadii").toFloatArray()
        val orbitAngles: List<DoubleArray> = constants.getJSONArray("orbitAnglesByOrbit").let { arr ->
            (0 until arr.length()).map { i -> arr.getJSONArray(i).toDoubleArray() }
        }
        val defaultOverlays: Map<String, Overlay> = root.getJSONObject("nodeOverlay").let { o ->
            o.keys().asSequence().associateWith { parseOverlay(o.getJSONObject(it))!! }
        }
        val connectionArtDefault: String = root.optJSONObject("connectionArt")?.optString("default", "Character") ?: "Character"
        val connectionArtAscendancy: String = root.optJSONObject("connectionArt")?.optString("ascendancy", "CharacterAscendancy") ?: "CharacterAscendancy"

        fun build(): PassiveTree {
            val groups = parseGroups(root.getJSONArray("groups"))
            val nodes = parseNodes(root.getJSONObject("nodes"))
            val byId = nodes.associateBy { it.id }

            // Positions and sizes
            for (node in nodes) {
                val group = groups.getOrNull(node.group - 1)
                if (group != null) {
                    node.angle = orbitAngles.getOrNull(node.orbit)?.getOrNull(node.orbitIndex) ?: 0.0
                    val radius = orbitRadii.getOrElse(node.orbit) { 0f }
                    node.x = group.x + (sin(node.angle) * radius).toFloat()
                    node.y = group.y - (cos(node.angle) * radius).toFloat()
                }
                node.targetSize = targetSize(node)
                node.hitRadius = if (node.type == NodeType.ClassStart || node.type == NodeType.OnlyImage || node.isAscendancyStart) 0f
                else if (node.targetSize.overlay > 0) node.targetSize.overlay else node.targetSize.base
            }

            // Links (both directions) and connectors
            val linked = Array(nodes.size) { LinkedHashSet<Int>() }
            val connectors = ArrayList<Connector>()
            for (node in nodes) {
                for (conn in node.connections) {
                    val other = byId[conn.id] ?: continue
                    if (node.type == NodeType.OnlyImage || other.type == NodeType.OnlyImage) continue
                    if (node.id == other.id) continue
                    linked[node.idx].add(other.idx)
                    linked[other.idx].add(node.idx)
                    if (node.ascendancyName != other.ascendancyName) continue
                    if (node.classesStart.isNotEmpty() || other.classesStart.isNotEmpty()) continue
                    connectors += buildConnector(node, other, conn, groups)
                }
            }
            for (node in nodes) {
                node.linked = linked[node.idx].toIntArray()
                node.unlockIdx = node.unlockConstraint?.toList()?.mapNotNull { byId[it]?.idx }?.toIntArray()
            }

            val classes = parseClasses(root.getJSONArray("classes"))
            for (node in nodes) {
                if (node.type == NodeType.ClassStart) {
                    for (className in node.classesStart) {
                        classes.firstOrNull { it.name == className }?.startNodeIdx = node.idx
                    }
                } else if (node.isAscendancyStart) {
                    for (c in classes) for (a in c.ascendancies) {
                        if (a.id == node.ascendancyName || (node.isSwitchable && node.switchOptions.containsKey(a.id))) {
                            a.startNodeIdx = node.idx
                        }
                    }
                }
            }

            return PassiveTree(
                nodes = nodes,
                groups = groups,
                classes = classes,
                connectors = connectors,
                sprites = sprites,
                minX = root.getDouble("min_x").toFloat(),
                minY = root.getDouble("min_y").toFloat(),
                maxX = root.getDouble("max_x").toFloat(),
                maxY = root.getDouble("max_y").toFloat(),
                treeVersion = root.optString("pobTreeVersion", "0_5"),
            )
        }

        fun parseGroups(arr: JSONArray): List<Group?> = (0 until arr.length()).map { i ->
            val g = arr.optJSONObject(i) ?: return@map null
            Group(
                x = g.getDouble("x").toFloat(),
                y = g.getDouble("y").toFloat(),
                orbits = g.optJSONArray("orbits")?.toIntList() ?: emptyList(),
                nodes = g.optJSONArray("nodes")?.toIntList() ?: emptyList(),
            )
        }

        fun parseNodes(obj: JSONObject): List<TreeNode> {
            val ids = obj.keys().asSequence().map { it.toInt() }.sorted().toList()
            return ids.mapIndexed { idx, id ->
                val n = obj.getJSONObject(id.toString())
                val classesStart = n.optJSONArray("classesStart")?.toStringList() ?: emptyList()
                val isAscStart = n.optBoolean("isAscendancyStart")
                val type = when {
                    classesStart.isNotEmpty() -> NodeType.ClassStart
                    isAscStart -> NodeType.AscendClassStart
                    n.optBoolean("isOnlyImage") -> NodeType.OnlyImage
                    n.optBoolean("isJewelSocket") -> NodeType.Socket
                    n.optBoolean("isKeystone") -> NodeType.Keystone
                    n.optBoolean("isNotable") -> NodeType.Notable
                    else -> NodeType.Normal
                }
                val isAttribute = n.optBoolean("isAttribute")
                val isSwitchable = n.optBoolean("isSwitchable")
                val options = n.opt("options")
                val attributeOptions = if (isAttribute && options is JSONArray) {
                    (0 until options.length()).map { parseOption(options.getJSONObject(it)) }
                } else emptyList()
                val switchOptions = if (isSwitchable && options is JSONObject) {
                    options.keys().asSequence().associateWith { parseOption(options.getJSONObject(it)) }
                } else emptyMap()
                val unlock = n.optJSONObject("unlockConstraint")
                val nodeOverlay = n.optJSONObject("nodeOverlay")?.let { parseOverlay(it) }
                val overlay = nodeOverlay ?: defaultOverlays[type.overlayKey()]
                TreeNode(
                    idx = idx,
                    id = id,
                    stringId = n.optString("stringId", ""),
                    name = n.optString("name", ""),
                    icon = n.optStringOrNull("icon"),
                    stats = n.optJSONArray("stats")?.toStatLines() ?: emptyList(),
                    reminderText = n.optJSONArray("reminderText")?.toStringList() ?: emptyList(),
                    // Some flavour texts contain an escaped "\n" instead of a line break
                    flavourText = n.opt("flavourText").toStringListLoose()
                        .flatMap { it.replace("\\n", "\n").split('\n') }
                        .map { it.trim() }
                        .filter { it.isNotEmpty() },
                    type = type,
                    ascendancyName = n.optStringOrNull("ascendancyName"),
                    classesStart = classesStart,
                    isAttribute = isAttribute,
                    attributeOptions = attributeOptions,
                    isSwitchable = isSwitchable,
                    switchOptions = switchOptions,
                    isMultipleChoice = n.optBoolean("isMultipleChoice"),
                    isMultipleChoiceOption = n.optBoolean("isMultipleChoiceOption"),
                    isFreeAllocate = n.optBoolean("isFreeAllocate"),
                    isAscendancyStart = isAscStart,
                    containJewelSocket = n.optBoolean("containJewelSocket"),
                    unlockConstraint = unlock?.optJSONArray("nodes")?.toIntList()?.toIntArray(),
                    unlockAscendancy = unlock?.optStringOrNull("ascendancy"),
                    activeEffectImage = n.optStringOrNull("activeEffectImage"),
                    overlay = overlay,
                    connectionArt = n.optStringOrNull("connectionArt"),
                    recipe = n.optJSONArray("recipe")?.toStringList() ?: emptyList(),
                    group = n.optInt("group", 0),
                    orbit = n.optInt("orbit", 0),
                    orbitIndex = n.optInt("orbitIndex", 0),
                    connections = n.optJSONArray("connections")?.let { arr ->
                        (0 until arr.length()).map {
                            val c = arr.getJSONObject(it)
                            Connection(c.getInt("id"), c.optInt("orbit", 0))
                        }
                    } ?: emptyList(),
                )
            }
        }

        fun NodeType.overlayKey() = when (this) {
            NodeType.Keystone -> "Keystone"
            NodeType.Notable -> "Notable"
            NodeType.Socket -> "Socket"
            NodeType.Normal -> "Normal"
            else -> ""
        }

        fun parseOption(o: JSONObject) = NodeOption(
            name = o.optStringOrNull("name"),
            icon = o.optStringOrNull("icon"),
            stats = o.optJSONArray("stats")?.toStatLines(),
            overlay = o.optJSONObject("nodeOverlay")?.let { parseOverlay(it) },
            reminderText = o.optJSONArray("reminderText")?.toStringList(),
        )

        fun parseOverlay(o: JSONObject): Overlay? {
            val alloc = o.optStringOrNull("alloc") ?: return null
            return Overlay(alloc, o.optString("path", alloc), o.optString("unalloc", alloc))
        }

        fun parseClasses(arr: JSONArray): List<ClassInfo> = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val ascArr = c.optJSONArray("ascendancies") ?: JSONArray()
            ClassInfo(
                integerId = c.getInt("integerId"),
                name = c.getString("name"),
                baseStr = c.optInt("base_str"),
                baseDex = c.optInt("base_dex"),
                baseInt = c.optInt("base_int"),
                background = c.optJSONObject("background")?.let { parseBackground(it) },
                ascendancies = (0 until ascArr.length()).map { j ->
                    val a = ascArr.getJSONObject(j)
                    AscendancyInfo(
                        index = j + 1,
                        id = a.getString("id"),
                        name = a.optString("name", a.getString("id")),
                        internalId = a.optString("internalId", ""),
                        background = a.optJSONObject("background")?.let { parseBackground(it) },
                        replace = a.optStringOrNull("replace"),
                        replaceBy = a.optStringOrNull("replaceBy"),
                    )
                },
            )
        }.sortedBy { it.integerId }

        fun parseBackground(b: JSONObject) = Background(
            image = b.getString("image"),
            x = b.optDouble("x", 0.0).toFloat(),
            y = b.optDouble("y", 0.0).toFloat(),
            width = b.optDouble("width", 0.0).toFloat(),
            height = b.optDouble("height", 0.0).toFloat(),
            activeWidth = b.optJSONObject("active")?.optDouble("width", 0.0)?.toFloat() ?: 0f,
            activeHeight = b.optJSONObject("active")?.optDouble("height", 0.0)?.toFloat() ?: 0f,
            bgWidth = b.optJSONObject("bg")?.optDouble("width", 0.0)?.toFloat() ?: 0f,
            bgHeight = b.optJSONObject("bg")?.optDouble("height", 0.0)?.toFloat() ?: 0f,
        )

        /** PoB `GetNodeTargetSize`, scaleImage = 1. Values are half-extents. */
        fun targetSize(node: TreeNode): TargetSize = when {
            node.isAscendancyStart -> TargetSize(0f, 50f, 0f)
            node.type == NodeType.Normal && node.ascendancyName != null -> TargetSize(37f, 80f, 0f)
            node.containJewelSocket -> TargetSize(80f, 80f, 0f)
            node.ascendancyName != null -> TargetSize(54f, 100f, 0f)
            node.type == NodeType.Notable -> TargetSize(54f, 80f, 380f)
            node.type == NodeType.OnlyImage -> TargetSize(380f, 0f, 0f)
            node.type == NodeType.Keystone -> TargetSize(82f, 120f, 380f)
            node.type == NodeType.Normal -> TargetSize(37f, 54f, 0f)
            node.type == NodeType.Socket -> TargetSize(76f, 76f, 0f)
            node.type == NodeType.ClassStart -> TargetSize(37f, 1f, 0f)
            else -> TargetSize(0f, 0f, 0f)
        }

        // ---- Connector geometry (PoB BuildConnector) ----

        fun spriteWidth(name: String) = sprites[name]?.width?.toFloat() ?: 0f
        fun spriteHeight(name: String) = sprites[name]?.height?.toFloat() ?: 0f

        fun buildConnector(node1In: TreeNode, node2In: TreeNode, connection: Connection, groups: List<Group?>): List<Connector> {
            var node1 = node1In
            var node2 = node2In
            val art = node1.connectionArt ?: node2.connectionArt
                ?: if (node1.ascendancyName != null) connectionArtAscendancy else connectionArtDefault

            val orbit = abs(connection.orbit)
            if (connection.orbit != 0 && orbit < orbitRadii.size) {
                // Curved connection between groups: arc of the given orbit radius through both nodes
                val r = orbitRadii[orbit]
                val dx = node2.x - node1.x
                val dy = node2.y - node1.y
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < r * 2) {
                    val perp = sqrt(r * r - dist * dist / 4) * (if (connection.orbit > 0) 1 else -1)
                    val cx = node1.x + dx / 2 + perp * (dy / dist)
                    val cy = node1.y + dy / 2 - perp * (dx / dist)
                    var angle1 = atan2((node1.y - cy).toDouble(), (node1.x - cx).toDouble())
                    var angle2 = atan2((node2.y - cy).toDouble(), (node2.x - cx).toDouble())
                    if (angle1 > angle2) angle1 = angle2.also { angle2 = angle1 }
                    var arcAngle = angle2 - angle1
                    if (arcAngle >= PI) {
                        angle1 = angle2.also { angle2 = angle1 }
                        arcAngle = PI * 2 - arcAngle
                    }
                    // atan2 angle -> PoB orbit angle (x = sin, y = -cos)
                    angle1 += PI / 2
                    if (arcAngle <= PI) {
                        return listOf(arcRibbon(node1In, node2In, art, cx, cy, r, angle1, arcAngle))
                    }
                }
            } else if (node1.group == node2.group && node1.orbit == node2.orbit && connection.orbit == 0) {
                // Neighbours on the same orbit of a group: arc around the group centre
                if (node1.angle > node2.angle) node1 = node2.also { node2 = node1 }
                var arcAngle = node2.angle - node1.angle
                if (arcAngle >= PI) {
                    node1 = node2.also { node2 = node1 }
                    arcAngle = PI * 2 - arcAngle
                }
                if (arcAngle <= PI) {
                    val g = groups[node1.group - 1]!!
                    return listOf(arcRibbon(node1In, node2In, art, g.x, g.y, orbitRadii[node1.orbit], node1.angle, arcAngle))
                }
            }

            // Straight line
            val lineArt = art + "LineConnectorNormal"
            val artW = spriteWidth(lineArt).takeIf { it > 0 } ?: 1f
            val artH = spriteHeight(lineArt).takeIf { it > 0 } ?: 1f
            val vX = node2.x - node1.x
            val vY = node2.y - node1.y
            val dist = sqrt(vX * vX + vY * vY)
            if (dist <= 0f) return emptyList()
            val scale = artH * 0.5f / dist
            val nX = vX * scale
            val nY = vY * scale
            val endS = dist / artW
            val v = floatArrayOf(
                node1.x - nY, node1.y + nX,
                node1.x + nY, node1.y - nX,
                node2.x + nY, node2.y - nX,
                node2.x - nY, node2.y + nX,
            )
            val tex = floatArrayOf(0f, 1f, 0f, 0f, endS, 0f, endS, 1f)
            return listOf(Connector(node1In.idx, node2In.idx, node1In.ascendancyName, art, v, tex).withBounds())
        }

        /**
         * An arc of radius [r] around ([cx], [cy]) from PoB orbit angle [start] (x = sin, y = -cos),
         * sweeping [sweep] radians, as a ribbon as wide as the line connector art.
         */
        fun arcRibbon(n1: TreeNode, n2: TreeNode, art: String, cx: Float, cy: Float, r: Float, start: Double, sweep: Double): Connector {
            val lineArt = art + "LineConnectorNormal"
            val artW = spriteWidth(lineArt).takeIf { it > 0 } ?: 1f
            val halfWidth = (spriteHeight(lineArt).takeIf { it > 0 } ?: 1f) * 0.5f
            val steps = max(1, ceil(sweep / ARC_STEP).toInt())
            val ribbon = FloatArray((steps + 1) * 4)
            val u = FloatArray(steps + 1)
            for (k in 0..steps) {
                val a = start + sweep * k / steps
                val dirX = sin(a).toFloat()
                val dirY = (-cos(a)).toFloat()
                val px = cx + dirX * r
                val py = cy + dirY * r
                ribbon[k * 4] = px - dirX * halfWidth
                ribbon[k * 4 + 1] = py - dirY * halfWidth
                ribbon[k * 4 + 2] = px + dirX * halfWidth
                ribbon[k * 4 + 3] = py + dirY * halfWidth
                u[k] = (r * sweep * k / steps).toFloat() / artW
            }
            return Connector(n1.idx, n2.idx, n1.ascendancyName, art, FloatArray(0), FloatArray(0), ribbon, u).withBounds()
        }

        fun Connector.withBounds(): Connector {
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            val points = ribbon ?: verts
            for (i in points.indices step 2) {
                x0 = min(x0, points[i]); x1 = max(x1, points[i])
                y0 = min(y0, points[i + 1]); y1 = max(y1, points[i + 1])
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1
            return this
        }
    }
}

// ---- JSON helpers ----

internal fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

internal fun JSONArray.toIntList(): List<Int> = (0 until length()).map { getInt(it) }
internal fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }
internal fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }
internal fun JSONArray.toDoubleArray(): DoubleArray = DoubleArray(length()) { getDouble(it) }

/** Stat lines, splitting any embedded newlines like PoB does. */
internal fun JSONArray.toStatLines(): List<String> =
    toStringList().flatMap { line -> line.split('\n').map { it.trim() }.filter { it.isNotEmpty() } }

internal fun Any?.toStringListLoose(): List<String> = when (this) {
    is JSONArray -> toStringList()
    is String -> if (isEmpty()) emptyList() else listOf(this)
    else -> emptyList()
}
