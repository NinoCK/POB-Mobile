package io.room.poe2tree.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.tree.NodeType
import io.room.poe2tree.tree.NodeView

/** Portrait: bottom panel over the tree with the selected node's details and allocation buttons. */
@Composable
fun NodePanel(vm: TreeViewModel, modifier: Modifier = Modifier) {
    vm.revision // recompose on allocation changes
    val idx = vm.selected
    if (idx < 0) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        key(idx) {
            NodeDetails(vm, idx, Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp), statsMaxHeight = 190.dp, actionsFirst = false)
        }
    }
}

/** Landscape: the selected node's details inside the side panel, scrolling as a whole. */
@Composable
fun NodeSidebarDetails(vm: TreeViewModel, modifier: Modifier = Modifier) {
    vm.revision
    val idx = vm.selected
    if (idx < 0) {
        Column(modifier.padding(16.dp)) {
            Text("No node selected", color = PoeColors.Text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a node on the tree to see its details. Tap it again to allocate or remove it.",
                color = PoeColors.TextDim, fontSize = 13.sp,
            )
        }
        return
    }
    key(idx) {
        NodeDetails(
            vm, idx,
            modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
            statsMaxHeight = null,
            // The side panel is short in landscape: keep the buttons right under the name
            actionsFirst = true,
        )
    }
}

/**
 * Name, stats and actions of a node. [statsMaxHeight] limits (and scrolls) the stats block;
 * [actionsFirst] places the allocation buttons above the stats.
 */
@Composable
private fun NodeDetails(vm: TreeViewModel, idx: Int, modifier: Modifier, statsMaxHeight: Dp?, actionsFirst: Boolean) {
    vm.revision // the spec is not snapshot state: subscribe to its changes here
    val tree = vm.tree
    val spec = vm.spec
    val node = tree.nodes[idx]
    val view = spec.views[idx]
    val alloc = spec.alloc[idx]

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    view.name,
                    color = nameColor(node.type, node.ascendancyName != null),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                )
                Text(typeLine(vm, idx), color = PoeColors.TextDim, fontSize = 12.sp)
            }
            IconButton(onClick = { vm.clearSelection() }) {
                Icon(AppIcons.Close, contentDescription = "Close", tint = PoeColors.TextDim)
            }
        }
        if (actionsFirst) {
            Spacer(Modifier.height(4.dp))
            Actions(vm, idx, alloc)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp, horizontal = 0.dp), color = PoeColors.Outline)

        Column(
            (if (statsMaxHeight != null) Modifier.heightIn(max = statsMaxHeight).verticalScroll(rememberScrollState()) else Modifier)
                .padding(end = 8.dp)
        ) {
            // PoB's text when it differs from the tree's: stats changed by jewels in radius, conquered
            // passives, "effect of small passives" modifiers, or the socketed jewel
            val pobText = vm.calc.compare?.takeIf { it.nodeId == node.id }?.info?.let { pobNodeText(it, view) }
            if (pobText != null) {
                for (line in pobText) {
                    if (PobText.strip(line).isBlank()) Spacer(Modifier.height(6.dp))
                    else PobLabel(line, fontSize = 15.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            } else {
                if (view.stats.isEmpty() && !node.isMultipleChoice) {
                    Text("No stats", color = PoeColors.TextDim, fontStyle = FontStyle.Italic, fontSize = 14.sp)
                }
                for (line in view.stats) {
                    Text(line, color = PoeColors.Magic, fontSize = 15.sp, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
            if (node.isMultipleChoice) {
                Text("Choose one of the connected options.", color = PoeColors.Tip, fontSize = 13.sp)
            }
            if (pobText == null) for (line in view.reminderText) {
                Text(line, color = PoeColors.TextDim, fontStyle = FontStyle.Italic, fontSize = 13.sp)
            }
            if (node.unlockIdx != null) {
                val names = node.unlockIdx!!.joinToString(", ") { spec.views[it].name }
                Text("Unlocked by: $names", color = PoeColors.Tip, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (node.recipe.isNotEmpty()) {
                Text("Anoint: " + node.recipe.joinToString(", "), color = PoeColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (pobText == null) for (line in node.flavourText) {
                Text(line, color = PoeColors.Unique, fontStyle = FontStyle.Italic, fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.padding(top = 4.dp))
            }
            if (vm.spec.canAllocateUnconnected(idx) && !alloc) {
                Text("Can be allocated without pathing to it", color = PoeColors.Tip, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!node.type.isStart) NodeCompare(vm, node.id)
        }
        if (!actionsFirst) {
            Spacer(Modifier.height(10.dp))
            Actions(vm, idx, alloc)
        }
    }
}

@Composable
private fun Actions(vm: TreeViewModel, idx: Int, alloc: Boolean) {
    vm.revision
    val spec = vm.spec
    val node = vm.tree.nodes[idx]
    if (node.type.isStart) {
        Text("Starting point", color = PoeColors.TextDim, fontSize = 13.sp)
        return
    }
    val customPath = vm.hasCustomRoute && vm.selected == idx
    val reachable = spec.path[idx] != null || customPath
    val otherAscendancy = !alloc && node.ascendancyName != null && spec.currentAscendancy.let { a ->
        a == null || node.ascendancyName != (a.replace ?: a.id)
    }
    Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (otherAscendancy) {
            Button(onClick = { vm.allocate(idx, null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Switch to ${node.ascendancyName}")
            }
            Hint("Changes your ascendancy and allocates this node.")
            return@Column
        }
        if (node.isAttribute) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = if (alloc) spec.attributeOverride[idx] else 0
                AttributeButton("Str", PoeColors.Str, current == 1, Modifier.weight(1f)) { vm.switchAttribute(idx, 1) }
                AttributeButton("Dex", PoeColors.Dex, current == 2, Modifier.weight(1f)) { vm.switchAttribute(idx, 2) }
                AttributeButton("Int", PoeColors.Int, current == 3, Modifier.weight(1f)) { vm.switchAttribute(idx, 3) }
            }
        }
        when {
            alloc -> {
                val count = vm.removalCount(idx)
                val blocked = spec.isGlobalDeallocationBlocked(idx)
                OutlinedButton(
                    onClick = { vm.deallocate(idx) },
                    enabled = !blocked,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A80)),
                ) {
                    Text(if (count > 1) "Remove ($count nodes)" else "Remove")
                }
                if (blocked) Hint("Switch to the main tree to remove keystones and jewel sockets.")
            }
            !reachable -> Hint(if (node.unlockIdx != null) "Locked" else "Not reachable from your tree")
            else -> {
                val blocked = spec.isGlobalAllocationBlocked(idx)
                if (!node.isAttribute) {
                    val cost = vm.allocationCost(idx)
                    Button(
                        onClick = { vm.allocate(idx, null) },
                        enabled = !blocked,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                cost == 0 -> "Allocate"
                                else -> "Allocate ($cost ${if (cost == 1) "point" else "points"})"
                            }
                        )
                    }
                } else {
                    val cost = vm.allocationCost(idx)
                    Hint("Choose an attribute to allocate ($cost ${if (cost == 1) "point" else "points"})")
                }
                if (blocked) Hint(
                    if (spec.currentAllocMode > 0) "Keystones and jewel sockets use main tree points only."
                    else "The path goes through weapon set nodes."
                )
                if (spec.currentAllocMode > 0 && node.ascendancyName == null && !node.isGlobal) {
                    Hint("Allocating with weapon set ${spec.currentAllocMode} points")
                }
                if (customPath) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Using the path you picked", color = PoeColors.Tip, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.useShortestPath() }) { Text("Use shortest path") }
                    }
                }
            }
        }
        Text(
            if (alloc) "Tip: tap a selected node again to remove it."
            else "Tip: tap nodes one after another to pick the path, then tap the last one again to allocate.",
            color = PoeColors.TextDim, fontSize = 11.sp,
        )
    }
}

@Composable
private fun AttributeButton(label: String, color: Color, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (active) {
        Button(onClick = onClick, modifier = modifier, colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.85f), contentColor = Color.Black)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, colors = ButtonDefaults.outlinedButtonColors(contentColor = color)) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * PoB's text for a node (name, then sections separated by blank lines), without the name, or null
 * when its stats are the tree's own. For a socket holding a jewel, the jewel's tooltip.
 */
private fun pobNodeText(info: List<String>, view: NodeView): List<String>? {
    val lines = info.dropWhile { PobText.strip(it).isBlank() }
    if (lines.isEmpty()) return null
    val name = PobText.strip(lines.first()).trim()
    val body = if (name == view.name) lines.drop(1).dropWhile { PobText.strip(it).isBlank() } else lines
    val stats = body.takeWhile { PobText.strip(it).isNotBlank() }.map { PobText.strip(it).trim() }
    return if (name == view.name && stats == view.stats) null else body
}

@Composable
private fun Hint(text: String) {
    Text(text, color = PoeColors.TextDim, fontSize = 13.sp)
}

private fun nameColor(type: NodeType, ascendancy: Boolean) = when {
    type == NodeType.Keystone -> PoeColors.Keystone
    ascendancy -> PoeColors.Ascendancy
    type == NodeType.Notable -> PoeColors.Notable
    type == NodeType.Socket -> PoeColors.Positive
    else -> PoeColors.Text
}

private fun typeLine(vm: TreeViewModel, idx: Int): String {
    val node = vm.tree.nodes[idx]
    val spec = vm.spec
    val parts = ArrayList<String>()
    parts += when {
        node.isAttribute -> "Attribute"
        node.isMultipleChoiceOption -> "Choice"
        node.type == NodeType.Socket || node.containJewelSocket -> "Jewel Socket"
        else -> node.type.label
    }
    node.ascendancyName?.let { parts += it }
    if (spec.alloc[idx]) {
        parts += when (spec.allocMode[idx]) {
            1 -> "Weapon set 1"
            2 -> "Weapon set 2"
            else -> "Allocated"
        }
    }
    if (node.isFreeAllocate) parts += "Free"
    return parts.joinToString(" · ")
}

/** Stat changes from allocating / removing the node, computed by Path of Building. */
@Composable
private fun NodeCompare(vm: TreeViewModel, nodeId: Int) {
    val calc = vm.calc
    if (calc.status != io.room.poe2tree.engine.EngineStatus.Ready) return
    val result = calc.compare?.takeIf { it.nodeId == nodeId }
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(bottom = 4.dp))
        if (result == null) {
            Text("Calculating stat changes…", color = PoeColors.TextDim, fontSize = 12.sp)
        } else {
            for (line in result.lines) {
                val header = PobText.strip(line).endsWith(":")
                PobLabel(line, fontSize = if (header) 13.sp else 14.sp, fontWeight = if (header) FontWeight.SemiBold else null)
            }
        }
    }
}
