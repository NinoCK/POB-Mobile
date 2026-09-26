package io.room.poe2tree.tree

/** Node categories, mirroring PoB's `node.type`. */
enum class NodeType(val label: String) {
    ClassStart("Class Start"),
    AscendClassStart("Ascendancy Start"),
    OnlyImage("Mastery"),
    Socket("Jewel Socket"),
    Keystone("Keystone"),
    Notable("Notable"),
    Normal("Passive");

    val isStart get() = this == ClassStart || this == AscendClassStart
}

/** Frame art names for the three display states of a node. */
data class Overlay(val alloc: String, val path: String, val unalloc: String) {
    fun forState(state: NodeState) = when (state) {
        NodeState.Alloc -> alloc
        NodeState.Path -> path
        NodeState.Unalloc -> unalloc
    }
}

enum class NodeState { Alloc, Path, Unalloc }

/** Half-extent draw sizes in tree units (PoB `GetNodeTargetSize`). 0 = not drawn. */
data class TargetSize(val base: Float, val overlay: Float, val effect: Float)

/** Alternative content of a node (attribute choice, or class / ascendancy specific replacement). */
data class NodeOption(
    val name: String?,
    val icon: String?,
    val stats: List<String>?,
    val overlay: Overlay?,
    val reminderText: List<String>?,
)

/** What is currently shown for a node, after switchable / attribute replacements. */
data class NodeView(
    val name: String,
    val icon: String?,
    val stats: List<String>,
    val overlay: Overlay?,
    val reminderText: List<String>,
)

class TreeNode(
    /** Dense index into the node arrays. */
    val idx: Int,
    /** PoB/GGG node hash. */
    val id: Int,
    val stringId: String,
    val name: String,
    val icon: String?,
    val stats: List<String>,
    val reminderText: List<String>,
    val flavourText: List<String>,
    val type: NodeType,
    val ascendancyName: String?,
    val classesStart: List<String>,
    val isAttribute: Boolean,
    val attributeOptions: List<NodeOption>,
    val isSwitchable: Boolean,
    val switchOptions: Map<String, NodeOption>,
    val isMultipleChoice: Boolean,
    val isMultipleChoiceOption: Boolean,
    val isFreeAllocate: Boolean,
    val isAscendancyStart: Boolean,
    val containJewelSocket: Boolean,
    val unlockConstraint: IntArray?,
    val unlockAscendancy: String?,
    val activeEffectImage: String?,
    val overlay: Overlay?,
    val connectionArt: String?,
    val recipe: List<String>,
    val group: Int,
    val orbit: Int,
    val orbitIndex: Int,
    val connections: List<Connection>,
) {
    var x = 0f
    var y = 0f
    var angle = 0.0
    var targetSize = TargetSize(0f, 0f, 0f)

    /** Hit radius in tree units (0 = not clickable). */
    var hitRadius = 0f

    /** Neighbour indices, both directions, excluding OnlyImage nodes. */
    var linked: IntArray = IntArray(0)

    /** Unlock constraint as dense indices. */
    var unlockIdx: IntArray? = null

    val isGlobal get() = type == NodeType.Keystone || type == NodeType.Socket || containJewelSocket

    val baseView = NodeView(name, icon, stats, overlay, reminderText)

    override fun toString() = "$name [$id]"
}

data class Connection(val id: Int, val orbit: Int)

data class Group(val x: Float, val y: Float, val orbits: List<Int>, val nodes: List<Int>)

data class Background(
    val image: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val activeWidth: Float = 0f,
    val activeHeight: Float = 0f,
    val bgWidth: Float = 0f,
    val bgHeight: Float = 0f,
)

class AscendancyInfo(
    /** 1-based index within the class (0 is "None"). */
    val index: Int,
    /** Display id / name, e.g. "Deadeye". */
    val id: String,
    val name: String,
    /** e.g. "Ranger1" - used in PoB build codes. */
    val internalId: String,
    val background: Background?,
    val replace: String?,
    val replaceBy: String?,
) {
    var startNodeIdx = -1
}

class ClassInfo(
    val integerId: Int,
    val name: String,
    val baseStr: Int,
    val baseDex: Int,
    val baseInt: Int,
    val background: Background?,
    val ascendancies: List<AscendancyInfo>,
) {
    var startNodeIdx = -1

    fun ascendancy(index: Int): AscendancyInfo? = ascendancies.firstOrNull { it.index == index }
}

/**
 * A textured connector between two nodes, in tree space. Straight connectors are a single quad;
 * curved ones are a ribbon of short quads following the orbit. Both use the line connector art,
 * whose cross-section matches PoB's orbit arcs.
 */
class Connector(
    val node1: Int,
    val node2: Int,
    val ascendancyName: String?,
    /** e.g. "Character", "CharacterAscendancy", "CharacterPlanned". */
    val art: String,
    /** Straight line: 4 vertices (x,y)*4 in tree space. Empty for arcs. */
    val verts: FloatArray,
    /** Straight line: texture coordinates (u,v)*4, u in art widths (repeats along the line). */
    val tex: FloatArray,
    /** Arc: (inner x, inner y, outer x, outer y) for each step along the arc. Null for lines. */
    val ribbon: FloatArray? = null,
    /** Arc: texture u (in art widths) at each step. */
    val ribbonU: FloatArray? = null,
) {
    var minX = 0f
    var minY = 0f
    var maxX = 0f
    var maxY = 0f

    val isArc get() = ribbon != null

    fun assetName(state: Int) = art + "LineConnector" + STATE_NAMES[state]

    companion object {
        val STATE_NAMES = arrayOf("Normal", "Intermediate", "Active")
    }
}

/** [width]/[height]: original art size (used for layout); [storedWidth]/[storedHeight]: size of the bundled file. */
data class SpriteInfo(val file: String, val width: Int, val height: Int, val storedWidth: Int, val storedHeight: Int)
