package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.Dp
import io.room.poe2tree.engine.CalcRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.Breakdown
import io.room.poe2tree.engine.CalcCell
import io.room.poe2tree.engine.CalcSection
import io.room.poe2tree.engine.CalcSubsection
import io.room.poe2tree.engine.CalcsSelection
import kotlinx.coroutines.launch

/** The Calcs screen: PoB's Calcs tab. */
@Composable
fun CalcsScreen(vm: TreeViewModel, modifier: Modifier = Modifier) {
    EngineGate(vm, modifier) {
        val calc = vm.calc
        val scope = rememberCoroutineScope()
        val (sections, loading) = rememberLoaded(calc.dataRevision) { calc.calcSections() }
        val (loadedSelection, _) = rememberLoaded(calc.dataRevision) { calc.calcsSelection() }
        var selection by remember(loadedSelection) { mutableStateOf(loadedSelection) }
        // Subsections the user opened or closed (keyed by section id and label)
        val collapsed = remember { mutableStateMapOf<String, Boolean>() }
        var open by remember { mutableStateOf<Pair<String, String>?>(null) }
        var breakdown by remember { mutableStateOf<Breakdown?>(null) }
        var breakdownLoading by remember { mutableStateOf(false) }

        LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            selection?.let { sel ->
                item(key = "selection") {
                    CalcsSelectionPanel(sel) { args -> scope.launch { calc.calcsSelect(args)?.let { selection = it } } }
                }
            }
            if (sections == null) {
                item { Centered(if (loading) "Calculating…" else "No calculations.", progress = loading) }
            } else {
                items(sections, key = { it.id }) { section ->
                    CalcSectionPanel(
                        section,
                        isCollapsed = { sub -> collapsed["${section.id}/${sub.label}"] ?: sub.collapsed },
                        onToggle = { sub -> collapsed["${section.id}/${sub.label}"] = !(collapsed["${section.id}/${sub.label}"] ?: sub.collapsed) },
                        onCell = { title, cell ->
                            val key = cell.key ?: return@CalcSectionPanel
                            open = title to key
                            breakdown = null
                            breakdownLoading = true
                            scope.launch {
                                breakdown = calc.calcBreakdown(key)
                                breakdownLoading = false
                            }
                        },
                    )
                }
            }
        }
        open?.let { (title, _) ->
            BreakdownDialog(
                title = breakdown?.title?.let { PobText.strip(it) } ?: title,
                breakdown = breakdown,
                loading = breakdownLoading,
                onShowNode = { id -> open = null; vm.showNodeById(id) },
                onDismiss = { open = null },
            )
        }
    }
}

@Composable
private fun CalcsSelectionPanel(sel: CalcsSelection, onSelect: (Map<String, Any?>) -> Unit) {
    Panel("Skill for the calculations") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sel.groups.isNotEmpty()) {
                ChoiceField(sel.groups.map { it.ifBlank { "<No active skills>" } }, sel.socketGroup, caption = "Skill group") { onSelect(mapOf("socketGroup" to it)) }
            }
            if (sel.activeSkills.size > 1) ChoiceField(sel.activeSkills, sel.mainActiveSkill, caption = "Active skill") { onSelect(mapOf("mainActiveSkill" to it)) }
            if (sel.statSets.size > 1) ChoiceField(sel.statSets, sel.statSet, caption = "Stat set") { onSelect(mapOf("statSet" to it)) }
            if (sel.parts.size > 1) ChoiceField(sel.parts, sel.skillPart, caption = "Skill part") { onSelect(mapOf("skillPart" to it)) }
            if (sel.stageCount != null || sel.hasMineCount) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sel.stageCount != null) Column(Modifier.weight(1f)) {
                        Caption("Stages")
                        NumberInput(sel.stageCount.toDouble(), null) { v -> onSelect(mapOf("stageCount" to (v?.toInt() ?: 1))) }
                    }
                    if (sel.hasMineCount) Column(Modifier.weight(1f)) {
                        Caption("Active mines")
                        NumberInput(sel.mineCount?.toDouble(), null) { v -> onSelect(mapOf("mineCount" to (v?.toInt() ?: -1))) }
                    }
                }
            }
            if (sel.minionSkills.isNotEmpty()) ChoiceField(sel.minionSkills, sel.minionSkill, caption = "Minion skill") { onSelect(mapOf("minionSkill" to it)) }
            if (sel.hasMinion) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onSelect(mapOf("showMinion" to !sel.showMinion)) }) {
                    Checkbox(checked = sel.showMinion, onCheckedChange = { onSelect(mapOf("showMinion" to it)) })
                    Text("Show minion stats", fontSize = 14.sp)
                }
            }
            val modeIndex = sel.buffModes.indexOfFirst { it.mode == sel.buffMode }.let { if (it < 0) sel.buffModes.size else it + 1 }
            if (sel.buffModes.isNotEmpty()) {
                ChoiceField(sel.buffModes.map { it.label }, modeIndex, caption = "Calculation mode") { onSelect(mapOf("buffMode" to sel.buffModes[it - 1].mode)) }
            }
            sel.buffList?.let { StatLine("Aura and buff skills", "^7$it", fontSize = 13) }
            sel.combatList?.let { StatLine("Combat buffs", "^7$it", fontSize = 13) }
            sel.curseList?.let { StatLine("Curses and debuffs", "^7$it", fontSize = 13) }
        }
    }
}

@Composable
private fun CalcSectionPanel(
    section: CalcSection,
    isCollapsed: (CalcSubsection) -> Boolean,
    onToggle: (CalcSubsection) -> Unit,
    onCell: (String, CalcCell) -> Unit,
) {
    val colour = PobText.color(section.colour) ?: PoeColors.Gold
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PoeColors.SurfaceHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        section.subsections.forEachIndexed { i, sub ->
            val closed = isCollapsed(sub)
            if (i > 0) HorizontalDivider(color = colour.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 4.dp))
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(sub) }.padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (closed) "▸ " else "▾ ", color = colour, fontSize = 13.sp)
                Text(sub.label, color = colour, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                sub.extra?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(8.dp))
                    PobLabel(it, fontSize = 13.sp, maxLines = 1)
                }
            }
            if (!closed) {
                val columns = sub.rows.maxOfOrNull { it.cells.size } ?: 1
                if (columns > 2) {
                    // Per damage type columns: fixed widths, scrolling sideways on narrow screens
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        for (row in sub.rows) CalcRowView(row, columns, onCell, labelWidth = 118.dp, cellWidth = 92.dp)
                    }
                } else {
                    for (row in sub.rows) CalcRowView(row, columns, onCell, labelWidth = null, cellWidth = null)
                }
            }
        }
    }
}

/**
 * One row of a subsection: label and cells (with fixed [labelWidth] / [cellWidth], or sharing the
 * width). A row with a single cell in a multi-column subsection spans all columns, as in PoB.
 */
@Composable
private fun CalcRowView(row: CalcRow, columns: Int, onCell: (String, CalcCell) -> Unit, labelWidth: Dp?, cellWidth: Dp?) {
    val cells = row.cells.ifEmpty { listOf(CalcCell("", null)) }
    fun Modifier.label() = if (labelWidth != null) width(labelWidth) else fillMaxWidth(0.34f)
    fun RowScope.cellModifier(span: Int): Modifier =
        if (cellWidth != null) Modifier.width(cellWidth * span) else Modifier.weight(span.toFloat() / cells.size)
    if (row.label == null) {
        // Column headings
        Row(Modifier.padding(top = 2.dp)) {
            Spacer(Modifier.label())
            for (cell in cells) PobLabel(cell.text, cellModifier(1).padding(horizontal = 3.dp), fontSize = 11.sp, default = PoeColors.TextDim)
        }
        return
    }
    Row(Modifier.padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        PobLabel(
            (row.labelColor ?: "^7") + row.label + ":",
            Modifier.label().padding(end = 6.dp),
            fontSize = 12.sp,
            textAlign = TextAlign.End,
        )
        val span = if (cells.size == 1) columns else 1
        for (cell in cells) {
            PobLabel(
                "^7" + cell.text,
                cellModifier(span)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable(enabled = cell.key != null) { onCell(row.label, cell) }
                    .background(if (cell.key != null) PoeColors.Surface.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 3.dp),
                fontSize = 12.sp,
            )
        }
    }
}
