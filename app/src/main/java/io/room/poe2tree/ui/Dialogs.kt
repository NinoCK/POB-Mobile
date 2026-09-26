package io.room.poe2tree.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.io.PointSettings
import io.room.poe2tree.io.SavedBuild
import java.text.DateFormat
import java.util.Date

@Composable
fun ImportDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import build code") },
        text = {
            Column {
                Text("Paste a Path of Building 2 build code. It is imported as a new build, with its passive tree, items, skills and configuration.", fontSize = 13.sp, color = PoeColors.TextDim)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                    placeholder = { Text("eNrtW...") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = {
                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text != null) code = text
                }) { Text("Paste from clipboard") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(enabled = code.isNotBlank(), onClick = {
                val err = vm.importCode(code)
                if (err == null) onDismiss() else error = err
            }) { Text("Import") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ExportDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var exported by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { exported = vm.exportCode() }
    val code = exported ?: ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export build code") },
        text = {
            Column {
                Text(
                    if (vm.calc.ready) "Path of Building 2 code of the whole build (tree, items, skills, configuration). Paste it into PoB's Import/Export tab."
                    else "Path of Building 2 code containing this passive tree. Paste it into PoB's Import/Export tab.",
                    fontSize = 13.sp, color = PoeColors.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    code,
                    modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = PoeColors.Text,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = exported != null, onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PoB build code", code))
                vm.message = "Build code copied."
                onDismiss()
            }) { Text("Copy") }
        },
        dismissButton = {
            TextButton(enabled = exported != null, onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, code)
                    putExtra(Intent.EXTRA_SUBJECT, vm.buildName)
                }
                context.startActivity(Intent.createChooser(send, "Share build code"))
                onDismiss()
            }) { Text("Share") }
        },
    )
}

@Composable
fun SettingsDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val s = vm.settings
    var level by remember { mutableStateOf(s.level.toString()) }
    var quest by remember { mutableStateOf(s.questPoints.toString()) }
    var extra by remember { mutableStateOf(s.extraPoints.toString()) }
    var extraWs by remember { mutableStateOf(s.extraWeaponSetPoints.toString()) }
    var asc by remember { mutableStateOf(s.ascendancyPoints.toString()) }
    fun parsed() = PointSettings(
        level = level.toIntOrNull()?.coerceIn(1, 100) ?: s.level,
        questPoints = quest.toIntOrNull()?.coerceIn(0, 99) ?: s.questPoints,
        extraPoints = extra.toIntOrNull()?.coerceIn(0, 999) ?: s.extraPoints,
        extraWeaponSetPoints = extraWs.toIntOrNull()?.coerceIn(0, 999) ?: s.extraWeaponSetPoints,
        ascendancyPoints = asc.toIntOrNull()?.coerceIn(0, 99) ?: s.ascendancyPoints,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passive points") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Character level (1-100)", level) { level = it }
                NumberField("Quest weapon-set points (max ${PointSettings.MAX_QUEST_POINTS})", quest) { quest = it }
                NumberField("Extra passive points", extra) { extra = it }
                NumberField("Extra weapon-set points", extraWs) { extraWs = it }
                NumberField("Ascendancy points", asc) { asc = it }
                val p = parsed()
                Text(
                    "Main tree: ${p.normalMax} points  ·  Weapon sets: ${p.weaponSetMax} each  ·  Ascendancy: ${p.ascendancyPoints}",
                    fontSize = 13.sp, color = PoeColors.TextDim,
                )
                Text("Main tree = level - 1 + quest points + extra (as in Path of Building).", fontSize = 12.sp, color = PoeColors.TextDim)
                TextButton(onClick = {
                    level = "100"; quest = PointSettings.MAX_QUEST_POINTS.toString(); extra = "0"; extraWs = "0"; asc = "8"
                }) { Text("Reset to endgame defaults") }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.updateSettings(parsed()); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SummaryDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val summary = remember(vm.revision) { vm.summary() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allocated stats") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                item {
                    val (s, d, i) = summary.attributes
                    Text("Attributes from tree: ", color = PoeColors.TextDim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Text("+$s Str", color = PoeColors.Str, fontWeight = FontWeight.Bold)
                        Text("+$d Dex", color = PoeColors.Dex, fontWeight = FontWeight.Bold)
                        Text("+$i Int", color = PoeColors.Int, fontWeight = FontWeight.Bold)
                    }
                    Text("Text totals only - no calculations are applied.", color = PoeColors.TextDim, fontSize = 11.sp)
                }
                if (summary.keystones.isNotEmpty()) {
                    item { SectionHeader("Keystones") }
                    items(summary.keystones) { Text(it, color = PoeColors.Keystone, fontSize = 14.sp) }
                }
                if (summary.ascendancy.isNotEmpty()) {
                    item { SectionHeader("Ascendancy") }
                    items(summary.ascendancy) { SummaryRow(it.text, it.count, PoeColors.Ascendancy) }
                }
                item { SectionHeader("Passive stats") }
                if (summary.stats.isEmpty()) item { Text("Nothing allocated yet.", color = PoeColors.TextDim) }
                items(summary.stats) { SummaryRow(it.text, it.count, PoeColors.Magic) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        Spacer(Modifier.height(10.dp))
        Text(text, color = PoeColors.Gold, fontWeight = FontWeight.SemiBold)
        HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
private fun SummaryRow(text: String, count: Int, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(text, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (count > 1) Text("×$count", color = PoeColors.TextDim, fontSize = 12.sp)
    }
}

@Composable
fun ClassChangeDialog(vm: TreeViewModel) {
    val pending = vm.pendingClassChange ?: return
    val cls = vm.tree.classById(pending.classId) ?: return
    AlertDialog(
        onDismissRequest = { vm.cancelClassChange() },
        title = { Text("Change class to ${cls.name}?") },
        text = {
            Text("Your tree is not connected to the ${cls.name} starting area. Changing class will remove the disconnected passives, unless you connect a path to it.")
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { vm.confirmClassChange(connectPath = true) }) { Text("Connect path") }
                TextButton(onClick = { vm.confirmClassChange(connectPath = false) }) { Text("Reset tree") }
                TextButton(onClick = { vm.cancelClassChange() }) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NewBuildDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("New build") }
    var classId by remember { mutableStateOf(vm.spec.classId) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New build") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, singleLine = true, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Class: ", color = PoeColors.TextDim)
                    Column {
                        OutlinedButton(onClick = { expanded = true }) {
                            Text(vm.tree.classById(classId)?.name ?: "")
                            Icon(AppIcons.DropDown, null)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            for (c in vm.tree.classes) {
                                DropdownMenuItem(text = { Text(c.name) }, onClick = { classId = c.integerId; expanded = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.createBuild(name, classId); onDismiss() }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BuildsDialog(vm: TreeViewModel, onDismiss: () -> Unit, onNew: () -> Unit) {
    var builds by remember { mutableStateOf(vm.listBuilds()) }
    var renaming by remember { mutableStateOf<SavedBuild?>(null) }
    var deleting by remember { mutableStateOf<SavedBuild?>(null) }
    val refresh = { builds = vm.listBuilds() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Builds") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(builds, key = { it.id }) { b ->
                    BuildRow(
                        vm, b,
                        current = b.id == vm.buildId,
                        onOpen = { vm.openBuild(b.id); onDismiss() },
                        onRename = { renaming = b },
                        onDuplicate = { vm.duplicateBuild(b.id); onDismiss() },
                        onDelete = { deleting = b },
                    )
                    HorizontalDivider(color = PoeColors.Outline.copy(alpha = 0.5f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onNew() }) { Text("New build") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    renaming?.let { b ->
        NameDialog("Rename build", if (b.id == vm.buildId) vm.buildName else b.name, "Rename",
            onConfirm = { vm.renameBuild(b.id, it); refresh() }, onDismiss = { renaming = null })
    }
    deleting?.let { b ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete build?") },
            text = { Text("\"${b.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteBuild(b.id); deleting = null; refresh() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BuildRow(
    vm: TreeViewModel,
    b: SavedBuild,
    current: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                (if (current) vm.buildName else b.name) + if (current) "  (open)" else "",
                color = if (current) PoeColors.GoldBright else PoeColors.Text,
                fontWeight = FontWeight.SemiBold,
            )
            Text(vm.buildSummary(if (current) b.copy(snapshot = vm.spec.snapshot(), settings = vm.settings) else b), color = PoeColors.TextDim, fontSize = 12.sp)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(b.updatedAt)), color = PoeColors.TextDim, fontSize = 11.sp)
        }
        Column {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "Build actions") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}
