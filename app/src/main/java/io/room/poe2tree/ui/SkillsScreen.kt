package io.room.poe2tree.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.GemChoice
import io.room.poe2tree.engine.GemInfo
import io.room.poe2tree.engine.SkillGroup
import io.room.poe2tree.engine.SkillsData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Skills screen: PoB's Skills tab. Group and gem numbers are 1-based, as in PoB. */
@Composable
fun SkillsScreen(vm: TreeViewModel, modifier: Modifier = Modifier) {
    EngineGate(vm, modifier) {
        val calc = vm.calc
        val scope = rememberCoroutineScope()
        val (loaded, loading) = rememberLoaded(calc.dataRevision) { calc.skills() }
        var data by remember(loaded) { mutableStateOf(loaded) }
        var addingTo by remember { mutableStateOf<Int?>(null) }
        var editingGem by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var pasting by remember { mutableStateOf(false) }
        var labelling by remember { mutableStateOf<Int?>(null) }

        fun edit(args: Map<String, Any?>) {
            scope.launch { calc.skillEdit(args)?.let { data = it } }
        }

        val current = data
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "top") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (current != null && current.skillSets.size > 1) {
                        ChoiceField(current.skillSets.map { it.title }, current.skillSets.indexOfFirst { it.id == current.activeSkillSetId } + 1, caption = "Skill set") { i ->
                            edit(mapOf("op" to "skillSet", "value" to current.skillSets[i - 1].id))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { edit(mapOf("op" to "newGroup")) }) { Text("New group") }
                        OutlinedButton(onClick = { pasting = true }) { Text("Paste group") }
                    }
                }
            }
            if (current == null) {
                item { Centered(if (loading) "Loading…" else "No skills.", progress = loading) }
            } else {
                if (current.groups.isEmpty()) item { Caption("No skill groups yet.") }
                itemsIndexed(current.groups) { i, group ->
                    val index = i + 1
                    SkillGroupPanel(
                        group, index,
                        onEdit = { args -> edit(args + ("group" to index)) },
                        onAddGem = { addingTo = index },
                        onGem = { gem -> editingGem = index to gem },
                        onLabel = { labelling = index },
                    )
                }
            }
        }

        addingTo?.let { group ->
            GemSearchDialog(vm, onDismiss = { addingTo = null }) { gem ->
                addingTo = null
                edit(mapOf("op" to "setGem", "group" to group, "value" to gem.gemId))
            }
        }
        editingGem?.let { (group, gemIndex) ->
            val gem = current?.groups?.getOrNull(group - 1)?.gems?.getOrNull(gemIndex - 1)
            if (gem == null) editingGem = null else GemDialog(vm, group, gemIndex, gem, onDismiss = { editingGem = null }) { args ->
                edit(args + mapOf("group" to group, "gem" to gemIndex))
            }
        }
        if (pasting) {
            PasteDialog(
                title = "Paste skill group",
                hint = "One gem per line, as Path of Building copies them: \"Fireball 20/0  1\" (name level/quality count).",
                onDismiss = { pasting = false },
            ) { text ->
                pasting = false
                edit(mapOf("op" to "pasteGroup", "value" to text))
            }
        }
        labelling?.let { group ->
            NameDialog("Group label", current?.groups?.getOrNull(group - 1)?.label ?: "", "Save", onConfirm = { label ->
                edit(mapOf("op" to "groupLabel", "group" to group, "value" to label))
            }, onDismiss = { labelling = null })
        }
    }
}

@Composable
private fun SkillGroupPanel(
    group: SkillGroup,
    index: Int,
    onEdit: (Map<String, Any?>) -> Unit,
    onAddGem: () -> Unit,
    onGem: (Int) -> Unit,
    onLabel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Panel(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (group.isMain) Text("★ ", color = PoeColors.GoldBright, fontSize = 15.sp)
                    PobLabel(
                        group.displayLabel.ifBlank { "<No active skills>" },
                        default = if (group.enabled) PoeColors.Text else PoeColors.TextDim,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    )
                }
                val tags = buildList {
                    add(if (group.weaponSet == "Both") "Both weapon sets" else "Weapon ${group.weaponSet}")
                    if (group.includeInFullDPS) add("Full DPS")
                    if (!group.enabled) add("Disabled")
                    group.sourceName?.let { add("From $it") } ?: group.source?.let { add(it) }
                }
                Caption(tags.joinToString(" · "))
            }
            Checkbox(checked = group.enabled, onCheckedChange = { onEdit(mapOf("op" to "groupEnabled", "value" to it)) })
            Column {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "Group actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!group.isMain) DropdownMenuItem(text = { Text("Set as main skill") }, onClick = { menu = false; onEdit(mapOf("op" to "setMain")) })
                    DropdownMenuItem(
                        text = { Text(if (group.includeInFullDPS) "Remove from Full DPS" else "Include in Full DPS") },
                        onClick = { menu = false; onEdit(mapOf("op" to "groupFullDPS", "value" to !group.includeInFullDPS)) },
                    )
                    if (!group.weaponSetLocked) {
                        for ((label, sets) in listOf("Both weapon sets" to (true to true), "Weapon set 1 only" to (true to false), "Weapon set 2 only" to (false to true))) {
                            if (group.set1 != sets.first || group.set2 != sets.second) {
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menu = false
                                    onEdit(mapOf("op" to "groupWeaponSets", "set1" to sets.first, "set2" to sets.second))
                                })
                            }
                        }
                    }
                    DropdownMenuItem(text = { Text("Label…") }, onClick = { menu = false; onLabel() })
                    if (group.deletable) DropdownMenuItem(text = { Text("Delete group") }, onClick = { menu = false; onEdit(mapOf("op" to "deleteGroup")) })
                }
            }
        }
        for (skill in group.activeSkills.filter { it.disabled }) {
            Caption("${skill.name}: ${skill.disableReason ?: "disabled"}", color = PoeColors.Negative)
        }
        HorizontalDivider(color = PoeColors.Outline.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
        group.gems.forEachIndexed { g, gem -> GemRow(gem) { onGem(g + 1) } }
        TextButton(onClick = onAddGem) { Text("+ Add gem") }
    }
}

@Composable
private fun GemRow(gem: GemInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            PobLabel(
                gem.colour + gem.name,
                default = PoeColors.Text,
                fontSize = 14.sp,
                fontWeight = if (gem.support) FontWeight.Normal else FontWeight.SemiBold,
            )
            val notes = buildList {
                if (!gem.active && gem.reason != null) add(gem.reason)
                if (gem.errMsg != null) add(gem.errMsg)
                if (gem.count > 1) add("×${gem.count}")
                if (gem.corrupted) add("Corrupted")
            }
            if (notes.isNotEmpty()) Caption(notes.joinToString(" · "), color = if (gem.errMsg != null) PoeColors.Negative else PoeColors.TextDim)
        }
        val level = gem.effectiveLevel?.takeIf { it != gem.level }?.let { "${gem.level} (+${it - gem.level})" } ?: gem.level.toString()
        Text(
            "$level / ${gem.quality}%",
            fontSize = 13.sp,
            color = if (gem.enabled) PoeColors.Text else PoeColors.TextDim,
            fontStyle = if (gem.enabled) FontStyle.Normal else FontStyle.Italic,
        )
        if (!gem.enabled) Text("  off", fontSize = 12.sp, color = PoeColors.TextDim)
    }
}

@Composable
private fun GemDialog(vm: TreeViewModel, group: Int, gemIndex: Int, gem: GemInfo, onDismiss: () -> Unit, onEdit: (Map<String, Any?>) -> Unit) {
    val (tooltip, _) = rememberLoaded(group, gemIndex, vm.calc.dataRevision) { vm.calc.gemTooltip(group, gemIndex) }
    var level by remember(gem.level) { mutableStateOf(gem.level.toString()) }
    var quality by remember(gem.quality) { mutableStateOf(gem.quality.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { PobLabel(gem.colour + gem.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!gem.locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(level, { level = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Level") }, singleLine = true)
                        OutlinedTextField(quality, { quality = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f), label = { Text("Quality") }, singleLine = true)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onEdit(mapOf("op" to "gemEnabled", "value" to !gem.enabled)) }) {
                        Checkbox(checked = gem.enabled, onCheckedChange = { onEdit(mapOf("op" to "gemEnabled", "value" to it)) })
                        Text("Enabled")
                    }
                } else {
                    Caption("Granted by an item or passive: it cannot be changed here.")
                }
                if (tooltip != null) {
                    HorizontalDivider(color = PoeColors.Outline)
                    for (line in tooltip) PobLabel(line.trim(), fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                level.toIntOrNull()?.takeIf { it != gem.level }?.let { onEdit(mapOf("op" to "gemLevel", "value" to it)) }
                quality.toIntOrNull()?.takeIf { it != gem.quality }?.let { onEdit(mapOf("op" to "gemQuality", "value" to it)) }
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = {
            if (!gem.locked) TextButton(onClick = { onEdit(mapOf("op" to "removeGem")); onDismiss() }) { Text("Remove", color = PoeColors.Negative) }
        },
    )
}

@Composable
private fun GemSearchDialog(vm: TreeViewModel, onDismiss: () -> Unit, onPick: (GemChoice) -> Unit) {
    var query by remember { mutableStateOf("") }
    var support by remember { mutableStateOf<Boolean?>(null) }
    var results by remember { mutableStateOf<List<GemChoice>>(emptyList()) }
    LaunchedEffect(query, support) {
        delay(150)
        results = vm.calc.gemSearch(query, support).orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add gem") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Gem name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    for ((label, value) in listOf("All" to null, "Skills" to false, "Supports" to true)) {
                        Text(
                            label,
                            color = if (support == value) PoeColors.GoldBright else PoeColors.TextDim,
                            fontWeight = if (support == value) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clickable { support = value }.padding(4.dp),
                        )
                    }
                }
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(results, key = { it.gemId }) { gem ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(gem) }.padding(vertical = 6.dp)) {
                            PobLabel(gem.colour + gem.name, fontSize = 15.sp)
                            gem.tags?.let { Caption(it) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A dialog to paste text (with a button for the clipboard). */
@Composable
fun PasteDialog(title: String, hint: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Caption(hint)
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    text, { text = it },
                    Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp),
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { text = it }
                }) { Text("Paste from clipboard") }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
