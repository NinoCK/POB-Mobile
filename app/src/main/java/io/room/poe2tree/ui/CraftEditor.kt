package io.room.poe2tree.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.CraftControl
import io.room.poe2tree.engine.CraftState
import io.room.poe2tree.engine.DbItem
import io.room.poe2tree.engine.TooltipLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Longer dropdowns open a searchable list instead of a menu. */
private const val MENU_MAX_OPTIONS = 12

/**
 * Sends the user's input to PoB's controls. Text typed into edits is sent before any other action,
 * so a button always sees it, in the order PoB would.
 */
private class CraftActions(private val vm: TreeViewModel, private val scope: kotlinx.coroutines.CoroutineScope) {
    val pending: SnapshotStateMap<Pair<Boolean, String>, String> = mutableStateMapOf()
    private var last: Job? = null

    fun run(popup: Boolean, c: CraftControl, op: String, value: Any? = null) {
        val previous = last
        last = scope.launch {
            previous?.join()
            if (op != "text") flush()
            vm.calc.craftAction(popup, c.name, op, value)
            if (c.name == "addDisplayItem" && op == "click" && vm.calc.craft == null) vm.message = "Item saved in the build."
        }
    }

    /** Text typed: sent after a pause, or before the next action. */
    fun type(popup: Boolean, c: CraftControl, text: String) {
        pending[popup to c.name] = text
    }

    suspend fun flush() {
        val edits = pending.toMap()
        pending.clear()
        for ((key, text) in edits) vm.calc.craftAction(key.first, key.second, "text", text)
    }

    fun flushLater() {
        val previous = last
        last = scope.launch {
            previous?.join()
            flush()
        }
    }
}

/** PoB's item editor: the item being crafted or edited, with the controls PoB shows for it. */
@Composable
fun CraftEditor(vm: TreeViewModel, state: CraftState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val actions = remember { CraftActions(vm, scope) }
    // Typed text is sent shortly after the last key
    LaunchedEffect(actions.pending.toMap()) {
        if (actions.pending.isEmpty()) return@LaunchedEffect
        delay(400)
        actions.flushLater()
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.editing) "Edit item" else "New item",
                color = PoeColors.GoldBright, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { vm.calc.craftCancel() } }) { Text("Close") }
        }
        state.lines?.let { lines ->
            Panel(null) { ItemTooltipLines(lines) }
        }
        for (row in state.rows) CraftRow(row, popup = false, vm, actions)
    }
    state.popup?.let { popup ->
        AlertDialog(
            onDismissRequest = {
                // PoB's popups close with their Cancel / Close button
                val cancel = popup.rows.flatten().firstOrNull { it.kind == "button" && PobText.strip(it.label).trim() in setOf("Cancel", "Close") }
                if (cancel != null) actions.run(true, cancel, "click") else scope.launch { vm.calc.craftCancel() }
            },
            title = { Text(popup.title) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in popup.rows) CraftRow(row, popup = true, vm, actions)
                }
            },
            confirmButton = {},
        )
    }
}

/** The lines of an item tooltip: name lines centred, then the sections. */
@Composable
fun ItemTooltipLines(lines: List<TooltipLine>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val header = lines.takeWhile { !it.separator }
        for (line in header) PobLabel(line.text ?: "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
        for (line in lines.drop(header.size)) {
            if (line.separator) HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(vertical = 5.dp))
            else PobLabel(line.text ?: "", fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CraftRow(row: List<CraftControl>, popup: Boolean, vm: TreeViewModel, actions: CraftActions) {
    // A slider with its text (roll of a modifier line): the text above, the slider below
    if (row.size == 2 && row.any { it.kind == "slider" } && row.any { it.kind == "label" }) {
        Column {
            for (c in row.sortedBy { if (it.kind == "label") 0 else 1 }) CraftControlView(c, popup, vm, actions, Modifier.fillMaxWidth())
        }
        return
    }
    // Rows of buttons wrap; other rows put their label before the input
    if (row.all { it.kind == "button" || it.kind == "check" }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (c in row) CraftControlView(c, popup, vm, actions, Modifier)
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (c in row) {
            val wide = c.kind == "dropdown" || c.kind == "list" || c.kind == "slider" || (c.kind == "edit" && !c.numeric)
            CraftControlView(c, popup, vm, actions, if (wide) Modifier.weight(1f) else Modifier)
        }
    }
}

@Composable
private fun CraftControlView(c: CraftControl, popup: Boolean, vm: TreeViewModel, actions: CraftActions, modifier: Modifier) {
    when (c.kind) {
        "label" -> PobLabel(c.label, modifier, default = PoeColors.TextDim, fontSize = 13.sp)
        "button" -> OutlinedButton(onClick = { actions.run(popup, c, "click") }, enabled = c.enabled, modifier = modifier) {
            PobLabel(c.label, fontSize = 13.sp, maxLines = 1)
        }
        "check" -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = c.state, enabled = c.enabled, onCheckedChange = { actions.run(popup, c, "check", it) })
            PobLabel(c.label, fontSize = 13.sp)
        }
        "dropdown", "list" -> CraftDropdown(c, popup, vm, actions, modifier)
        "edit" -> CraftEdit(c, popup, vm, actions, modifier)
        "slider" -> CraftSlider(c, popup, actions, modifier)
    }
}

@Composable
private fun CraftDropdown(c: CraftControl, popup: Boolean, vm: TreeViewModel, actions: CraftActions, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    var detailFor by remember { mutableStateOf<Int?>(null) }
    val label = c.options.getOrNull(c.selected - 1) ?: ""
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))
                    .clickable(enabled = c.enabled && c.options.isNotEmpty()) { open = true }
                    .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PobLabel(label.ifEmpty { "Choose…" }, Modifier.weight(1f), default = if (c.enabled) PoeColors.Text else PoeColors.TextDim, fontSize = 13.sp)
                Icon(AppIcons.DropDown, null, tint = PoeColors.TextDim)
            }
            if (open && c.options.size <= MENU_MAX_OPTIONS) {
                DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                    c.options.forEachIndexed { i, o ->
                        DropdownMenuItem(text = { PobLabel(o, fontSize = 14.sp) }, onClick = {
                            open = false
                            actions.run(popup, c, "select", i + 1)
                        })
                    }
                }
            }
        }
        if (c.detail && c.selected >= 1) {
            TextButton(onClick = { detailFor = c.selected }, modifier = Modifier.width(44.dp)) { Text("ⓘ", fontSize = 16.sp) }
        }
    }
    if (open && c.options.size > MENU_MAX_OPTIONS) {
        OptionPicker(c, onDismiss = { open = false }, onDetail = if (c.detail) { i -> detailFor = i } else null) { i ->
            open = false
            actions.run(popup, c, "select", i)
        }
    }
    detailFor?.let { index ->
        val (lines, loading) = rememberLoaded(c.name, index, c.options) { vm.calc.craftDetail(popup, c.name, index) }
        AlertDialog(
            onDismissRequest = { detailFor = null },
            title = { PobLabel(c.options.getOrNull(index - 1) ?: "", fontSize = 16.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (lines == null) Centered(if (loading) "Loading…" else "No details.", progress = loading)
                    else for (line in lines) {
                        if (line.isBlank()) Spacer(Modifier.height(6.dp)) else PobLabel(line, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detailFor = null }) { Text("Close") } },
        )
    }
}

/** A long list of options with a search field. [onPick] and [onDetail] get the 1-based index. */
@Composable
private fun OptionPicker(c: CraftControl, onDismiss: () -> Unit, onDetail: ((Int) -> Unit)?, onPick: (Int) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query, c.options) {
        val q = query.trim().lowercase()
        c.options.withIndex().filter { q.isEmpty() || PobText.strip(it.value).lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    itemsIndexed(shown, key = { _, v -> v.index }) { _, v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(v.index + 1) }.padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PobLabel(
                                v.value, Modifier.weight(1f), fontSize = 14.sp,
                                fontWeight = if (v.index + 1 == c.selected) FontWeight.Bold else null,
                            )
                            if (onDetail != null) {
                                Text("ⓘ", fontSize = 15.sp, color = PoeColors.TextDim, modifier = Modifier.clickable { onDetail(v.index + 1) }.padding(horizontal = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CraftEdit(c: CraftControl, popup: Boolean, vm: TreeViewModel, actions: CraftActions, modifier: Modifier) {
    // The field keeps what is typed; PoB's text is taken when another item is edited
    var text by remember(c.name, vm.calc.craftSession) { mutableStateOf(c.text) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            actions.type(popup, c, it)
        },
        enabled = c.enabled,
        singleLine = !c.multiline,
        placeholder = c.prompt?.let { p -> { Text(p) } },
        keyboardOptions = if (c.numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        textStyle = if (c.multiline) androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        else androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        modifier = if (c.multiline) modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp)
        else if (c.numeric) modifier.width(96.dp) else modifier,
    )
}

@Composable
private fun CraftSlider(c: CraftControl, popup: Boolean, actions: CraftActions, modifier: Modifier) {
    var value by remember(c.name, c.value) { mutableFloatStateOf(c.value) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { actions.run(popup, c, "value", value.toDouble()) },
            enabled = c.enabled,
            modifier = Modifier.weight(1f),
        )
        Text("${(value * 100).toInt()}%", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

/** PoB's unique items or rare templates, searchable; [onPick] starts editing one. */
@Composable
fun ItemDbDialog(vm: TreeViewModel, kind: String, onDismiss: () -> Unit, onPick: (DbItem) -> Unit) {
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<DbItem>?>(null) }
    LaunchedEffect(query) {
        delay(if (items == null) 0 else 250)
        items = vm.calc.itemDB(kind, query) ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == "RARE") "Rare templates" else "Uniques") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search name, base or type") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val list = items
                if (list == null) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                    Caption("Loading Path of Building's item database…")
                } else if (list.isEmpty()) {
                    Caption("No items found.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 460.dp)) {
                        itemsIndexed(list, key = { _, it -> it.name }) { i, item ->
                            if (i == 0 || list[i - 1].type != item.type) {
                                Text(item.type ?: "", color = PoeColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                            }
                            Column(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 6.dp)) {
                                Text(item.name.substringBefore(", "), color = if (kind == "RARE") PoeColors.Rare else PoeColors.Unique, fontSize = 14.sp)
                                item.base?.let { Caption(it) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
