package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.ConfigData
import io.room.poe2tree.engine.ConfigRow
import io.room.poe2tree.engine.CustomModBlock
import kotlinx.coroutines.launch

/** The Config screen: PoB's Configuration tab. */
@Composable
fun ConfigScreen(vm: TreeViewModel, modifier: Modifier = Modifier) {
    EngineGate(vm, modifier) {
        val calc = vm.calc
        val scope = rememberCoroutineScope()
        var showAll by remember { mutableStateOf(false) }
        val (loaded, loading) = rememberLoaded(calc.dataRevision, showAll) { calc.config(showAll) }
        var data by remember(loaded) { mutableStateOf(loaded) }
        var tooltip by remember { mutableStateOf<ConfigRow?>(null) }
        var editingMods by remember { mutableStateOf(false) }

        fun apply(result: ConfigData?) {
            if (result != null) data = result
        }

        val current = data
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "top") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show all configurations", fontSize = 14.sp)
                        Caption("Also options that do not apply to this build")
                    }
                    Switch(checked = showAll, onCheckedChange = { showAll = it })
                }
            }
            if (current == null) {
                item { Centered(if (loading) "Loading…" else "No options.", progress = loading) }
            } else {
                if (current.sets.size > 1) {
                    item(key = "sets") {
                        ChoiceField(current.sets.map { it.title }, current.sets.indexOfFirst { it.active } + 1, caption = "Configuration set") { i ->
                            scope.launch { apply(calc.setConfigSet(current.sets[i - 1].id, showAll)) }
                        }
                    }
                }
                items(current.sections, key = { it.name }) { section ->
                    Panel(section.name) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in section.rows) {
                                ConfigRowView(
                                    row,
                                    onInfo = { tooltip = row },
                                    onChange = { value -> scope.launch { apply(calc.setConfig(row.idx, value, showAll)) } },
                                    onReset = { scope.launch { apply(calc.resetConfig(row.idx, showAll)) } },
                                )
                            }
                        }
                    }
                }
                item(key = "custom") {
                    Panel("Custom Modifiers") {
                        val blocks = current.customMods
                        val lines = blocks.filter { it.enabled }.flatMap { it.lines }.filter { it.text.isNotBlank() }
                        if (lines.isEmpty()) Caption("None. Add modifier lines (as they appear on items) that PoB should apply.")
                        for (line in lines) {
                            PobLabel(line.text, default = if (line.supported) PoeColors.Magic else Color(0xFFF05050), fontSize = 13.sp)
                        }
                        Spacer(Modifier.size(6.dp))
                        OutlinedButton(onClick = { editingMods = true }) { Text("Edit custom modifiers") }
                    }
                }
            }
        }
        tooltip?.let { row ->
            AlertDialog(
                onDismissRequest = { tooltip = null },
                title = { PobLabel(row.label, fontWeight = FontWeight.SemiBold) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { PobLabel(row.tooltip ?: "", fontSize = 14.sp) } },
                confirmButton = { TextButton(onClick = { tooltip = null }) { Text("OK") } },
            )
        }
        if (editingMods && current != null) {
            CustomModsDialog(current.customMods, onDismiss = { editingMods = false }) { blocks ->
                scope.launch {
                    val saved = calc.setCustomMods(blocks)
                    if (saved != null) data = data?.copy(customMods = saved)
                }
                editingMods = false
            }
        }
    }
}

@Composable
private fun ConfigRowView(row: ConfigRow, onInfo: () -> Unit, onChange: (Any?) -> Unit, onReset: () -> Unit) {
    if (row.type == "label") {
        PobLabel(row.label, default = PoeColors.GoldBright, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        return
    }
    val labelColor = if (row.invalid) PoeColors.Negative else PoeColors.Text
    val highlight = if (row.modified) PoeColors.Gold.copy(alpha = 0.10f) else Color.Transparent
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(highlight)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(enabled = row.tooltip != null, onClick = onInfo),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PobLabel(row.label, default = labelColor, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                if (row.tooltip != null) Text("  ⓘ", fontSize = 12.sp, color = PoeColors.TextDim)
            }
            when (row.type) {
                "check" -> Checkbox(checked = row.checked, enabled = row.enabled, onCheckedChange = { onChange(it) })
                "list" -> ChoiceField(row.choices, row.selected, Modifier.width(170.dp), enabled = row.enabled) { onChange(it) }
                "text" -> Unit
                else -> NumberInput(
                    row.number, row.placeholder, Modifier.width(110.dp),
                    decimals = row.type == "float",
                    allowNegative = row.type == "integer" || row.type == "countAllowZero",
                    enabled = row.enabled,
                ) { onChange(it) }
            }
        }
        if (row.type == "text") {
            var text by remember(row.text) { mutableStateOf(row.text) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            if (text != row.text) TextButton(onClick = { onChange(text) }) { Text("Apply") }
        }
        if (row.invalid) Caption("This option does not apply to the build any more, but is still set.", color = PoeColors.Negative)
        if (row.modified) {
            Text("Reset", fontSize = 11.sp, color = PoeColors.TextDim, modifier = Modifier.clickable(onClick = onReset).padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun CustomModsDialog(blocks: List<CustomModBlock>, onDismiss: () -> Unit, onSave: (List<CustomModBlock>) -> Unit) {
    var edited by remember { mutableStateOf(blocks.ifEmpty { listOf(CustomModBlock("Default", true, "", emptyList())) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom modifiers") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Caption("One modifier per line, as written on items or passives (e.g. \"+50 to maximum Life\"). Lines shown in red after saving are not understood by Path of Building.")
                edited.forEachIndexed { i, block ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(block.title.ifBlank { "Default" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("Enabled", fontSize = 12.sp, color = PoeColors.TextDim)
                            Switch(checked = block.enabled, onCheckedChange = { on ->
                                edited = edited.toMutableList().also { it[i] = block.copy(enabled = on) }
                            })
                        }
                        OutlinedTextField(
                            value = block.text,
                            onValueChange = { t -> edited = edited.toMutableList().also { it[i] = block.copy(text = t) } },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                            textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                        for (line in block.lines) {
                            if (!line.supported && line.text.isNotBlank()) {
                                Text("Not supported: ${PobText.strip(line.text)}", color = Color(0xFFF05050), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edited) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
