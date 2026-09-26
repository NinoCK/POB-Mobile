package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.EngineStatus

/** PoB's depth limits for the heat map ("Limit of Node distance to search"). */
private val DEPTHS = listOf<Int?>(null, 5, 10, 15)
private fun depthLabel(d: Int?) = d?.let { "$it points" } ?: "All"

/** Heat map settings: statistic, depth, and PoB's power report. */
@Composable
fun HeatMapDialog(vm: TreeViewModel, onReport: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadPowerStats() }
    val stats = vm.powerStats
    val ready = vm.calc.status == EngineStatus.Ready
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heat map") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show node power", modifier = Modifier.weight(1f))
                    Switch(checked = vm.heatMap, enabled = ready, onCheckedChange = { vm.setHeatMap(it) })
                }
                ChoiceField(
                    options = stats.map { it.label }.ifEmpty { listOf("Offence/Defence") },
                    selected = vm.heatStat,
                    caption = "Power of",
                    enabled = ready,
                ) { vm.setHeatMap(true, stat = it) }
                ChoiceField(
                    options = DEPTHS.map { depthLabel(it) },
                    selected = DEPTHS.indexOf(vm.heatDepth) + 1,
                    caption = "Passives up to (fewer is faster)",
                    enabled = ready,
                ) { vm.setHeatMap(true, depth = DEPTHS[it - 1]) }
                Text(
                    if (vm.heatStat == 1) "Red: offence (damage), blue: defence, for allocating each passive. Brighter is more."
                    else "Brighter red: more ${stats.getOrNull(vm.heatStat - 1)?.label ?: ""} for allocating each passive.",
                    fontSize = 13.sp, color = PoeColors.TextDim,
                )
                val progress = vm.calc.powerProgress
                if (progress >= 0) Text("Calculating… $progress%", fontSize = 13.sp, color = PoeColors.Tip)
            }
        },
        confirmButton = {
            val report = vm.calc.power?.takeIf { it.single && vm.heatMap }
            Row {
                if (report != null) TextButton(onClick = onReport) { Text("Power report") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

/** Shown on the tree while the heat map is on: its statistic and progress. */
@Composable
fun HeatMapChip(vm: TreeViewModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!vm.heatMap) return
    val label = vm.powerStats.getOrNull(vm.heatStat - 1)?.label ?: "Offence/Defence"
    val progress = vm.calc.powerProgress
    Text(
        "Heat map: $label" + if (progress >= 0) " · $progress%" else "",
        color = Color.White,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(0xCC4A1010), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** PoB's power report: passives by their power for the heat map's statistic; tap one to show it. */
@Composable
fun PowerReportDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val result = vm.calc.power
    val label = vm.powerStats.getOrNull(vm.heatStat - 1)?.label ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Power report: $label") },
        text = {
            if (result == null || result.report.isEmpty()) {
                Text("No report for this statistic.", color = PoeColors.TextDim)
            } else {
                Column {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                        Text("Passive", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.weight(1f))
                        Text("Power", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.padding(start = 8.dp))
                        Text("Per point", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.padding(start = 12.dp))
                    }
                    HorizontalDivider(color = PoeColors.Outline)
                    LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(result.report, key = { it.id }) { e ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onDismiss()
                                        vm.showNodeById(e.id)
                                    }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        e.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = when (e.type) {
                                            "Keystone" -> PoeColors.Keystone
                                            "Notable" -> PoeColors.Notable
                                            else -> PoeColors.Text
                                        },
                                        fontWeight = if (e.type == "Normal") null else FontWeight.SemiBold,
                                    )
                                    Text(
                                        if (e.allocated) "Allocated (removing it)" else "${e.pathDist} ${if (e.pathDist == 1) "point" else "points"} away",
                                        fontSize = 11.sp, color = PoeColors.TextDim,
                                    )
                                }
                                PobLabel(e.powerStr, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
                                PobLabel(e.pathPowerStr, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Per point: the path's power divided by its points.", fontSize = 11.sp, color = PoeColors.TextDim)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
