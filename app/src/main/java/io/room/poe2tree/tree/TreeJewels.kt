package io.room.poe2tree.tree

/** A jewel radius (PoB `data.jewelRadius`), in tree units. A ring when [inner] > 0. */
data class JewelRadius(val inner: Float, val outer: Float, val color: Int, val label: String) {
    val innerSq = inner * inner
    val outerSq = outer * outer
}

/** A jewel in a socket, with what it does to the tree (PoB `item.jewelData`). Indices are node indices. */
class SocketJewel(
    val socket: Int,
    val itemId: Int,
    val name: String,
    /** Unique name (its socket art, when the tree has it) and base type. */
    val title: String?,
    val baseName: String?,
    val rarity: String?,
    /** 1-based index into [TreeJewels.radii], 0 without a radius. */
    val radiusIndex: Int,
    /** The radius is a ring ("Variable", e.g. Controlled Metamorphosis). */
    val variable: Boolean,
    /** "kalguur", "abyss"...: passives in radius are conquered. */
    val conqueror: String?,
    /** Passives in radius can be allocated without being connected to the tree. */
    val leap: Boolean,
    /** Keystones whose radius allows allocation without a connection (From Nothing). */
    val fromNothing: IntArray,
    /** Another class's start the tree may be connected to (Split Personality), or -1. */
    val alternateStart: Int,
    /** Over the jewel's "Limited to" count: it does nothing. */
    val limitDisabled: Boolean,
)

/** An ascendancy passive allowing allocation near allocated keystones (PoB `AllocateFromNodeRadius`). */
class LeapSource(val node: Int, val from: String, val radiusIndex: Int, val to: Set<NodeType>)

/** A passive replaced by a conquering jewel. */
class ReplacedNode(val name: String, val icon: String?)

/**
 * Jewels and items acting on the passive tree, from Path of Building's calculation of the build:
 * radius art and effects for the tree view, and the pathing rules they change.
 */
class TreeJewels(
    val radii: List<JewelRadius>,
    val sockets: List<SocketJewel>,
    val leapSources: List<LeapSource>,
    /** Passives allocated by items. */
    val granted: Set<Int>,
    val replaced: Map<Int, ReplacedNode>,
) {
    val bySocket: Map<Int, SocketJewel> = sockets.associateBy { it.socket }
    private val grantedFlags = BooleanArray((granted.maxOrNull() ?: -1) + 1).also { a -> granted.forEach { a[it] = true } }

    fun isGranted(node: Int) = node < grantedFlags.size && grantedFlags[node]

    fun radius(index: Int): JewelRadius? = radii.getOrNull(index - 1)

    companion object {
        val EMPTY = TreeJewels(emptyList(), emptyList(), emptyList(), emptySet(), emptyMap())
    }
}

/**
 * Passives within each jewel radius of a socket or keystone, as PassiveTree.lua precalculates them
 * (`nodesInRadius`): by distance between node positions, excluding the centre and nodes without a
 * group. Cached per centre.
 */
class RadiusIndex(private val tree: PassiveTree) {
    private val cache = HashMap<Pair<Int, List<JewelRadius>>, Array<BooleanArray>>()

    /** For each radius (0-based), which nodes are inside it. */
    fun of(centre: Int, radii: List<JewelRadius>): Array<BooleanArray> = cache.getOrPut(centre to radii) {
        val c = tree.nodes[centre]
        val out = Array(radii.size) { BooleanArray(tree.nodes.size) }
        for (node in tree.nodes) {
            if (node.idx == centre || node.group <= 0 || node.type == NodeType.OnlyImage) continue
            val dx = node.x - c.x
            val dy = node.y - c.y
            val d = dx * dx + dy * dy
            for ((r, radius) in radii.withIndex()) {
                if (d <= radius.outerSq && radius.innerSq <= d) out[r][node.idx] = true
            }
        }
        out
    }

    fun contains(centre: Int, radii: List<JewelRadius>, radiusIndex: Int, node: Int): Boolean =
        radiusIndex in 1..radii.size && of(centre, radii)[radiusIndex - 1][node]
}

/** Reads the engine's `api.treeOverlay()` result for [tree]. */
fun parseTreeJewels(o: org.json.JSONObject, tree: PassiveTree): TreeJewels {
    fun idx(id: Int) = tree.node(id)?.idx ?: -1
    val radii = o.optJSONArray("radii")?.let { arr ->
        (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            JewelRadius(r.getDouble("inner").toFloat(), r.getDouble("outer").toFloat(), pobColour(r.optString("colour")), r.optString("label"))
        }
    }.orEmpty()
    val sockets = o.optJSONArray("sockets")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val s = arr.getJSONObject(i)
            val socket = idx(s.getInt("node")).takeIf { it >= 0 } ?: return@mapNotNull null
            SocketJewel(
                socket = socket,
                itemId = s.optInt("item"),
                name = s.optString("name"),
                title = s.optStringOrNull("title"),
                baseName = s.optStringOrNull("baseName"),
                rarity = s.optStringOrNull("rarity"),
                radiusIndex = s.optInt("radius", 0),
                variable = s.optBoolean("variable"),
                conqueror = s.optStringOrNull("conqueror"),
                leap = s.optBoolean("leap"),
                fromNothing = s.optJSONArray("fromNothing")?.toIntList()?.map { idx(it) }?.filter { it >= 0 }?.toIntArray() ?: IntArray(0),
                alternateStart = if (s.has("alternateStart")) idx(s.getInt("alternateStart")) else -1,
                limitDisabled = s.optBoolean("limitDisabled"),
            )
        }
    }.orEmpty()
    val leapSources = o.optJSONArray("leapSources")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val l = arr.getJSONObject(i)
            val node = idx(l.getInt("node")).takeIf { it >= 0 } ?: return@mapNotNull null
            val to = l.optJSONArray("to")?.toStringList().orEmpty().mapNotNull { t -> NodeType.entries.firstOrNull { it.name == t } }.toSet()
            LeapSource(node, l.optString("from"), l.optInt("radius"), to)
        }
    }.orEmpty()
    val granted = o.optJSONArray("granted")?.toIntList()?.map { idx(it) }?.filter { it >= 0 }?.toSet().orEmpty()
    val replaced = o.optJSONArray("nodes")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val r = arr.getJSONObject(i)
            val node = idx(r.getInt("id")).takeIf { it >= 0 } ?: return@mapNotNull null
            node to ReplacedNode(r.optString("name"), r.optStringOrNull("icon"))
        }.toMap()
    }.orEmpty()
    return TreeJewels(radii, sockets, leapSources, granted, replaced)
}

/** PoB colour code "^xRRGGBB" as an opaque ARGB colour (white if unreadable). */
fun pobColour(code: String): Int {
    val hex = code.removePrefix("^x").takeIf { it.length == 6 } ?: return -1
    return hex.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() } ?: -1
}
