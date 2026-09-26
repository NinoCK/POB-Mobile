package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.Breakdown
import io.room.poe2tree.engine.EngineStatus
import io.room.poe2tree.engine.Sidebar
import io.room.poe2tree.engine.SidebarRow
import io.room.poe2tree.engine.SkillSelection
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Shows [content] once the calculation engine has the build; until then its status.
 */
@Composable
fun EngineGate(vm: TreeViewModel, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val calc = vm.calc
    when (val status = calc.status) {
        EngineStatus.Starting -> Centered("Starting Path of Building…", modifier, progress = true)
        is EngineStatus.Failed -> Column(modifier.padding(20.dp)) {
            Text("The calculation engine could not start.", color = PoeColors.Negative, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(status.message, color = PoeColors.TextDim, fontSize = 12.sp)
        }
        EngineStatus.Ready -> if (calc.state == null) Centered("Calculating…", modifier, progress = true) else content()
    }
}

/** Which part of the Stats screen to show (landscape splits it between the side panel and the content). */
enum class StatsPart { All, Selection, List }

/** The Stats screen: Path of Building's sidebar (main skill, stats, warnings). */
@Composable
fun StatsScreen(vm: TreeViewModel, modifier: Modifier = Modifier, part: StatsPart = StatsPart.All) {
    EngineGate(vm, modifier) {
        val state = vm.calc.state ?: return@EngineGate
        if (part == StatsPart.Selection) {
            Column(modifier.verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkillSelectionPanel(vm, state.selection)
                LevelLine(vm)
                WarningsPanel(state.sidebar.warnings)
            }
            return@EngineGate
        }
        if (part == StatsPart.List) {
            Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                StatsList(vm, state.sidebar)
            }
            return@EngineGate
        }
        BoxWithConstraints(modifier) {
            if (maxWidth > 640.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.42f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkillSelectionPanel(vm, state.selection)
                        LevelLine(vm)
                        WarningsPanel(state.sidebar.warnings)
                    }
                    Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                        StatsList(vm, state.sidebar)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkillSelectionPanel(vm, state.selection)
                    LevelLine(vm)
                    WarningsPanel(state.sidebar.warnings)
                    StatsList(vm, state.sidebar)
                }
            }
        }
    }
}

/** Landscape side panel on the other screens: main skill and stats, like PoB's sidebar. */
@Composable
fun SidebarStats(vm: TreeViewModel, modifier: Modifier = Modifier, showSelection: Boolean = true) {
    EngineGate(vm, modifier) {
        val state = vm.calc.state ?: return@EngineGate
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSelection) SkillSelectionPanel(vm, state.selection, compact = true)
            WarningsPanel(state.sidebar.warnings)
            StatsList(vm, state.sidebar, fontSize = 13)
        }
    }
}

@Composable
private fun LevelLine(vm: TreeViewModel) {
    Caption("Character level ${vm.settings.level} (change it in Passive points)")
}

/** Main skill selection (PoB's sidebar dropdowns). */
@Composable
fun SkillSelectionPanel(vm: TreeViewModel, sel: SkillSelection, compact: Boolean = false) {
    val calc = vm.calc
    Panel(if (compact) null else "Main skill") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sel.groups.isEmpty()) {
                Caption("No skills in this build. Import a build with skills, or add them in the Skills screen.")
            } else {
                val groupLabels = sel.groups.map { g ->
                    buildString {
                        append(g.label.ifBlank { "<No active skills>" })
                        if (!g.enabled) append(" ^x808080(Disabled)")
                    }
                }
                ChoiceField(groupLabels, sel.mainSocketGroup, caption = if (compact) "Main skill" else "Skill group") { calc.select(mapOf("mainSocketGroup" to it)) }
                if (sel.activeSkills.size > 1) {
                    ChoiceField(sel.activeSkills, sel.mainActiveSkill, caption = "Active skill") { calc.select(mapOf("mainActiveSkill" to it)) }
                }
                if (sel.statSets.size > 1) {
                    ChoiceField(sel.statSets, sel.statSet, caption = "Stat set") { calc.select(mapOf("statSet" to it)) }
                }
                if (sel.parts.size > 1) {
                    ChoiceField(sel.parts, sel.skillPart, caption = "Skill part") { calc.select(mapOf("skillPart" to it)) }
                }
                if (sel.stageCount != null || sel.hasMineCount) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sel.stageCount != null) {
                            Column(Modifier.weight(1f)) {
                                Caption("Stages")
                                NumberInput(sel.stageCount.toDouble(), null) { v -> calc.select(mapOf("stageCount" to (v?.toInt() ?: 1))) }
                            }
                        }
                        if (sel.hasMineCount) {
                            Column(Modifier.weight(1f)) {
                                Caption("Active mines")
                                NumberInput(sel.mineCount?.toDouble(), null) { v -> calc.select(mapOf("mineCount" to (v?.toInt() ?: -1))) }
                            }
                        }
                    }
                }
                if (sel.minions.isNotEmpty()) {
                    val index = sel.minions.indexOfFirst { m ->
                        (m.minionId != null && m.minionId == sel.minionId) || (m.itemSetId != null && m.itemSetId == sel.minionItemSetId)
                    }.let { if (it < 0) 1 else it + 1 }
                    ChoiceField(sel.minions.map { it.label }, index, caption = "Minion") { i ->
                        val m = sel.minions[i - 1]
                        calc.select(
                            if (m.itemSetId != null) mapOf("minionItemSetId" to m.itemSetId, "label" to m.label)
                            else mapOf("minionId" to m.minionId, "label" to m.label)
                        )
                    }
                }
                if (sel.minionSkills.isNotEmpty()) {
                    ChoiceField(sel.minionSkills, sel.minionSkill, caption = "Minion skill") { calc.select(mapOf("minionSkill" to it)) }
                }
                if (sel.minionSkillStatSets.size > 1) {
                    ChoiceField(sel.minionSkillStatSets, sel.minionSkillStatSet, caption = "Minion skill stat set") { calc.select(mapOf("minionSkillStatSet" to it)) }
                }
            }
            WeaponSetToggle(sel.useSecondWeaponSet) { second -> calc.select(mapOf("useSecondWeaponSet" to second)) }
        }
    }
}

/** Weapon set I / II (PoB's Items tab buttons). */
@Composable
fun WeaponSetToggle(second: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Caption("Weapon set", Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))) {
            for ((i, label) in listOf("I", "II").withIndex()) {
                val active = (i == 1) == second
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) Color.Black else PoeColors.TextDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(if (active) PoeColors.Gold else Color.Transparent)
                        .clickable { if (!active) onChange(i == 1) }
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
fun WarningsPanel(warnings: List<String>) {
    if (warnings.isEmpty()) return
    var open by remember { mutableStateOf(true) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PoeColors.Negative.copy(alpha = 0.12f))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            if (warnings.size == 1) "1 Warning" else "${warnings.size} Warnings",
            color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
        )
        if (open) for (w in warnings) PobLabel(w, default = Color(0xFFFFB0B0), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** PoB's stat list. Tapping a stat with a breakdown shows it. */
@Composable
fun StatsList(vm: TreeViewModel, sidebar: Sidebar, fontSize: Int = 14) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<SidebarRow?>(null) }
    var breakdown by remember { mutableStateOf<Breakdown?>(null) }
    var loading by remember { mutableStateOf(false) }
    Column {
        var lastWasSeparator = true
        for (row in sidebar.rows) {
            when (row.type) {
                SidebarRow.Type.Separator -> {
                    if (!lastWasSeparator) Spacer(Modifier.height(8.dp))
                    lastWasSeparator = true
                    continue
                }
                SidebarRow.Type.Header -> PobLabel(row.text, fontWeight = FontWeight.Bold, fontSize = (fontSize + 1).sp, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                SidebarRow.Type.Info -> PobLabel(row.text, fontSize = (fontSize - 1).sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                SidebarRow.Type.Stat -> Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = row.hasBreakdown) {
                            open = row
                            breakdown = null
                            loading = true
                            scope.launch {
                                breakdown = vm.calc.sidebarBreakdown(row)
                                loading = false
                            }
                        }
                        .padding(vertical = 1.dp),
                ) {
                    PobLabel(row.label + ":", Modifier.weight(0.55f), fontSize = fontSize.sp, textAlign = TextAlign.End)
                    Spacer(Modifier.width(6.dp))
                    PobLabel(row.value, Modifier.weight(0.45f), fontSize = fontSize.sp)
                }
            }
            lastWasSeparator = false
        }
    }
    open?.let { row ->
        BreakdownDialog(
            title = PobText.strip(row.label),
            breakdown = breakdown,
            loading = loading,
            onShowNode = { id -> open = null; vm.showNodeById(id) },
            onDismiss = { open = null },
        )
    }
}

/** A few key numbers above the tree; tap to open the Stats screen. */
@Composable
fun KeyStatsBar(vm: TreeViewModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val calc = vm.calc
    val state = calc.state
    Row(
        modifier
            .fillMaxWidth()
            .background(PoeColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            calc.status is EngineStatus.Failed -> Caption("Calculations unavailable")
            state == null -> Caption("Loading calculations…")
            else -> {
                val sb = state.sidebar
                val dps = dpsStat(sb)
                if (dps != null) KeyStat(dps.first, dps.second, PoeColors.GoldBright)
                sb.raw("Life")?.takeIf { it > 0 }?.let { KeyStat("Life", it, PoeColors.Str) }
                sb.raw("EnergyShield")?.takeIf { it > 0 }?.let { KeyStat("ES", it, Color(0xFF88FFFF)) }
                sb.raw("TotalEHP")?.takeIf { it > 0 }?.let { KeyStat("EHP", it, PoeColors.Text) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (calc.busy > 0) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        if (state != null && state.sidebar.warnings.isNotEmpty()) {
            Text("⚠ ${state.sidebar.warnings.size}", color = Color(0xFFFF6B6B), fontSize = 12.sp)
        }
    }
}

private fun dpsStat(sb: Sidebar): Pair<String, Double>? {
    for (actor in listOf("player", "minion")) {
        for ((stat, label) in listOf("FullDPS" to "Full DPS", "CombinedDPS" to "DPS", "TotalDPS" to "DPS", "TotalDot" to "DoT DPS")) {
            val v = sb.raw(stat, actor)
            if (v != null && v > 0) return (if (actor == "minion") "Minion $label" else label) to v
        }
    }
    return null
}

@Composable
private fun KeyStat(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontSize = 11.sp, color = PoeColors.TextDim)
        Text(compactNumber(value), fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** 1234 -> "1,234", 123456 -> "123.5k", 12345678 -> "12.3M". */
fun compactNumber(v: Double): String = when {
    v.isInfinite() -> "∞"
    v >= 1e9 -> String.format(Locale.US, "%.2fB", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.2fM", v / 1e6)
    v >= 1e5 -> String.format(Locale.US, "%.1fk", v / 1e3)
    v >= 100 -> String.format(Locale.US, "%,.0f", v)
    else -> String.format(Locale.US, "%.1f", v)
}
