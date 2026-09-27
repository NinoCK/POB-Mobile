package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.AffixSlot
import io.room.poe2tree.engine.AffixValue
import io.room.poe2tree.engine.CraftControl
import io.room.poe2tree.engine.CraftModel
import io.room.poe2tree.engine.CraftRow
import io.room.poe2tree.engine.CraftState
import io.room.poe2tree.engine.DbItem
import io.room.poe2tree.engine.ModFamily
import io.room.poe2tree.engine.ModTier
import io.room.poe2tree.engine.RangeLine
import io.room.poe2tree.engine.TooltipLine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Longer dropdowns open a searchable list instead of a menu. */
private const val MENU_MAX_OPTIONS = 12

/** Colour of fractured modifier lines (PoB's colorCodes.FRACTURED). */
private val Fractured = Color(0xFFA29160)
private val Desecrated = Color(0xFF6BC9A7)

/**
 * Sends the user's input to the engine one change at a time. Text typed into PoB's edits is sent
 * before any other action, so a button always sees it, in the order PoB would.
 */
private class CraftActions(private val vm: TreeViewModel, private val scope: kotlinx.coroutines.CoroutineScope) {
    val pending: SnapshotStateMap<Pair<Boolean, String>, String> = mutableStateMapOf()
    private var last: Job? = null

    /** Runs [block] after the previous actions. */
    fun launch(block: suspend () -> Unit) {
        val previous = last
        last = scope.launch {
            previous?.join()
            flush()
            block()
        }
    }

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

/** A slot being filled: "prefix" / "suffix" and its 1-based index. */
private data class SlotRef(val slot: String, val index: Int)

/**
 * The item editor: PoB's item (its tooltip and editor controls) with the app's crafting screen for
 * its modifiers: rarity, name, item level, prefixes and suffixes from every source with their
 * tiers and rolls, other modifier lines, and the rolls of implicits and unique modifiers.
 */
@Composable
fun CraftEditor(vm: TreeViewModel, state: CraftState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val actions = remember { CraftActions(vm, scope) }
    var picking by remember { mutableStateOf<SlotRef?>(null) }
    var addingLine by remember { mutableStateOf(false) }
    // Typed text is sent shortly after the last key
    LaunchedEffect(actions.pending.toMap()) {
        if (actions.pending.isEmpty()) return@LaunchedEffect
        delay(400)
        actions.flushLater()
    }
    val model = state.model
    // PoB's tooltip: the item, then the stat changes of equipping it
    val (itemLines, compareLines) = remember(state.lines) { splitTooltip(state.lines.orEmpty()) }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.editing) "Edit item" else "New item",
                    color = PoeColors.GoldBright, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { actions.launch { vm.calc.craftCancel() } }) { Text("Close") }
            }
            if (itemLines.isNotEmpty()) Panel(null) { ItemTooltipLines(itemLines, model) }
            Rows(state.section("actions"), vm, actions)
            if (model != null) {
                ItemPanel(model, state, vm, actions)
                ModifiersPanel(model, state, vm, actions, onPick = { picking = it }, onAddLine = { addingLine = true })
            } else {
                Rows(state.section("variants") + state.section("properties") + state.section("modifiers"), vm, actions)
            }
            if (compareLines.isNotEmpty()) {
                Panel("Stat changes") {
                    for (line in compareLines) {
                        if (line.separator) HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(vertical = 5.dp))
                        else PobLabel(line.text ?: "", fontSize = 13.sp)
                    }
                }
            }
            model?.ranges?.takeIf { it.isNotEmpty() }?.let { RollsPanel(it, vm, actions) }
            state.section("enchant").takeIf { it.isNotEmpty() }?.let { rows ->
                Panel("Enchantments and corruption") { Rows(rows, vm, actions) }
            }
            state.section("sockets").takeIf { it.isNotEmpty() }?.let { rows ->
                Panel("Sockets") { Rows(rows, vm, actions) }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (vm.calc.busy > 0) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
    state.popup?.let { popup ->
        AlertDialog(
            onDismissRequest = {
                // PoB's popups close with their Cancel / Close button
                val cancel = popup.rows.flatMap { it.controls }.firstOrNull { it.kind == "button" && PobText.strip(it.label).trim() in setOf("Cancel", "Close") }
                if (cancel != null) actions.run(true, cancel, "click") else actions.launch { vm.calc.craftCancel() }
            },
            title = { Text(popup.title) },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (row in popup.rows) ControlRow(row.controls, popup = true, vm, actions)
                }
            },
            confirmButton = {},
        )
    }
    val slotRef = picking
    if (slotRef != null && model != null) {
        val current = (if (slotRef.slot == "prefix") model.prefixes else model.suffixes).getOrNull(slotRef.index - 1)
        ModifierPicker(vm, slotRef, current, model.itemLevel, onDismiss = { picking = null }) { tier ->
            picking = null
            actions.launch { vm.calc.craftSetAffix(slotRef.slot, slotRef.index, tier.modId, rollsFor(current, tier), current?.fractured ?: false) }
        }
    }
    if (addingLine) {
        PasteDialog(
            title = "Add modifier line",
            hint = "A modifier as it is written on items, e.g. \"+30 to maximum Life\" (one per line). Lines Path of Building does not understand are shown in red in the item.",
            onDismiss = { addingLine = false },
        ) { text ->
            addingLine = false
            actions.launch { vm.calc.craftAddLine(text) }
        }
    }
}

/** The rolls to keep when a slot changes tier: the same rolls if the new tier has as many values. */
private fun rollsFor(current: AffixSlot?, tier: ModTier): List<Double>? {
    if (current == null || current.empty) return null
    val count = tier.lines.sumOf { Regex("\\((-?[\\d.]+)-(-?[\\d.]+)\\)").findAll(it).count() }
    return current.values.map { it.roll }.takeIf { it.size == count && count > 0 }
}

/** Splits PoB's tooltip into the item and the stat changes of equipping / removing it. */
private fun splitTooltip(lines: List<TooltipLine>): Pair<List<TooltipLine>, List<TooltipLine>> {
    val start = lines.indexOfFirst { l ->
        val t = PobText.strip(l.text ?: "")
        t.endsWith("will give you:") || t.startsWith("Equipping this item") || t.startsWith("Removing this item")
    }
    if (start < 0) return lines to emptyList()
    val item = lines.subList(0, start).dropLastWhile { it.separator }
    return item to lines.subList(start, lines.size)
}

/** The lines of an item tooltip: the item's art and name lines centred, then the sections. */
@Composable
fun ItemTooltipLines(lines: List<TooltipLine>, model: CraftModel?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (model != null) ItemArt(model.title, model.baseName, model.rarity, Modifier.padding(bottom = 6.dp))
        val header = lines.takeWhile { !it.separator }
        for (line in header) PobLabel(line.text ?: "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
        for (line in lines.drop(header.size)) {
            if (line.separator) HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(vertical = 5.dp))
            else PobLabel(line.text ?: "", fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---- Item: rarity, name, item level, PoB's variants / quality / catalyst ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemPanel(model: CraftModel, state: CraftState, vm: TreeViewModel, actions: CraftActions) {
    Panel("Item") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (model.canSetRarity) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((rarity, label, color) in listOf(
                        Triple("NORMAL", "Normal", PoeColors.Normal),
                        Triple("MAGIC", "Magic", PoeColors.Magic),
                        Triple("RARE", "Rare", PoeColors.Rare),
                    )) {
                        FilterChip(
                            selected = model.rarity == rarity,
                            enabled = !(rarity == "RARE" && model.magicOnly),
                            onClick = { if (model.rarity != rarity) actions.launch { vm.calc.craftSetItem(rarity = rarity) } },
                            label = { Text(label, color = if (model.rarity == rarity) color else PoeColors.TextDim) },
                        )
                    }
                }
            }
            if (model.rarity == "RARE" && !model.unique) {
                CommitTextField(model.title ?: "", "Name", vm.calc.craftSession) { title ->
                    actions.launch { vm.calc.craftSetItem(title = title) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Item level", fontSize = 13.sp, color = PoeColors.TextDim)
                NumberInput(model.itemLevel?.toDouble(), null, Modifier.width(88.dp)) { v ->
                    actions.launch { vm.calc.craftSetItem(itemLevel = v?.toInt()?.coerceIn(0, 100) ?: 0) }
                }
                Text("Requires level ${model.requiredLevel}", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.weight(1f))
            }
            if (model.itemLevel == null && (model.crafted)) {
                Caption("Set the item level to see which tiers can roll on it.")
            }
            Rows(state.section("variants") + state.section("properties"), vm, actions)
        }
    }
}

/** A text field that commits on Done or when it loses focus. */
@Composable
private fun CommitTextField(value: String, label: String, session: Int, onCommit: (String) -> Unit) {
    var text by remember(session, value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        if (text != value) onCommit(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { s ->
            if (focused && !s.isFocused) commit()
            focused = s.isFocused
        },
    )
}

// ---- Modifiers ----

@Composable
private fun ModifiersPanel(
    model: CraftModel,
    state: CraftState,
    vm: TreeViewModel,
    actions: CraftActions,
    onPick: (SlotRef) -> Unit,
    onAddLine: () -> Unit,
) {
    when {
        model.unique -> {
            // Uniques: their modifiers are rolled below; PoB's "Add modifier" is not offered for them
        }
        model.crafted -> {
            for ((slot, list, limit) in listOf(Triple("prefix", model.prefixes, model.prefixLimit), Triple("suffix", model.suffixes, model.suffixLimit))) {
                val used = list.count { !it.empty }
                Panel("${if (slot == "prefix") "Prefixes" else "Suffixes"} ($used / $limit)") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (list.isEmpty()) Caption("This item can have no ${slot}es.")
                        for (s in list) AffixCard(s, slot, model.itemLevel, vm, actions, onPick = { onPick(SlotRef(slot, s.index)) })
                    }
                }
            }
            OtherLinesPanel(model, state, vm, actions, onAddLine, title = "Other modifier lines")
        }
        model.rarity == "NORMAL" -> Panel("Modifiers") {
            Caption(if (model.canSetRarity) "Normal items have no modifiers: make the item magic or rare to add some." else "This item has no modifiers to craft.")
            if (model.extra.isNotEmpty() || state.section("modifiers").isNotEmpty()) OtherLinesPanelContent(model, state, vm, actions, onAddLine)
        }
        else -> OtherLinesPanel(model, state, vm, actions, onAddLine, title = "Modifiers")
    }
}

@Composable
private fun OtherLinesPanel(model: CraftModel, state: CraftState, vm: TreeViewModel, actions: CraftActions, onAddLine: () -> Unit, title: String) {
    Panel(title) { OtherLinesPanelContent(model, state, vm, actions, onAddLine) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherLinesPanelContent(model: CraftModel, state: CraftState, vm: TreeViewModel, actions: CraftActions, onAddLine: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (model.convertible) {
            Caption("These modifiers are lines of text (the item was pasted or imported). Make them prefixes and suffixes to change their tiers and rolls or pick modifiers from the lists.")
            OutlinedButton(onClick = { actions.launch { vm.calc.craftConvert() } }) { Text("Edit as prefixes and suffixes") }
        }
        if (model.crafted && model.extra.isEmpty()) Caption("Lines added besides the prefixes and suffixes (custom text, or PoB's essence and desecrated lists) are listed here.")
        for (line in model.extra) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PobLabel(line.text, Modifier.weight(1f), fontSize = 13.sp)
                IconButton(onClick = { actions.launch { vm.calc.craftRemoveLine(line.index) } }) {
                    Icon(AppIcons.Close, "Remove", tint = PoeColors.TextDim, modifier = Modifier.size(18.dp))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = onAddLine) { Text("Add text line…", fontSize = 13.sp, color = PoeColors.Text) }
            for (row in state.section("modifiers")) for (c in row.controls) CraftControlView(c, false, vm, actions, Modifier)
        }
    }
}

/** A prefix / suffix: its modifier, tier, rolls and fractured state, or an empty slot. */
@Composable
private fun AffixCard(slot: AffixSlot, slotType: String, itemLevel: Int?, vm: TreeViewModel, actions: CraftActions, onPick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    if (slot.empty) {
        Row(
            Modifier.fillMaxWidth().clip(shape).border(1.dp, PoeColors.Outline.copy(alpha = 0.6f), shape).clickable(onClick = onPick).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Empty $slotType", color = PoeColors.TextDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Icon(AppIcons.Plus, null, tint = PoeColors.Gold, modifier = Modifier.size(18.dp))
            Text(" Choose", color = PoeColors.Gold, fontSize = 13.sp)
        }
        return
    }
    // Rolls follow the sliders at once; the engine gets them when a slider is released
    var rolls by remember(slot.modId, slot.values) { mutableStateOf(slot.values.map { it.roll }) }
    fun commit(newRolls: List<Double> = rolls, modId: String? = slot.modId, fractured: Boolean = slot.fractured) {
        actions.launch { vm.calc.craftSetAffix(slotType, slot.index, modId, newRolls.takeIf { it.isNotEmpty() }, fractured) }
    }
    var menu by remember { mutableStateOf(false) }
    var tierMenu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(PoeColors.Surface).border(1.dp, PoeColors.Outline.copy(alpha = 0.6f), shape).padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SourceBadge(slot.source, slot.sourceLabel)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = slot.tiers.size > 1) { tierMenu = true }.padding(vertical = 2.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        buildString {
                            if (slot.tiers.size > 1 && slot.tier != null) append("T${slot.tier} ")
                            append(slot.name ?: "")
                            if (slot.level > 1) append("  ·  ilvl ${slot.level}")
                        },
                        fontSize = 12.sp, color = PoeColors.GoldBright, maxLines = 1,
                    )
                    if (slot.tiers.size > 1) Icon(AppIcons.DropDown, null, tint = PoeColors.TextDim, modifier = Modifier.size(18.dp))
                }
                TierMenu(tierMenu, slot.tiers, slot.tier, onDismiss = { tierMenu = false }) { tier ->
                    tierMenu = false
                    commit(rollsFor(slot, tier) ?: emptyList(), modId = tier.modId)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(AppIcons.More, "More", tint = PoeColors.TextDim) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Change modifier…") }, onClick = { menu = false; onPick() })
                    if (slot.values.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Best rolls") }, onClick = { menu = false; commit(slot.values.map { 1.0 }) })
                        DropdownMenuItem(text = { Text("Worst rolls") }, onClick = { menu = false; commit(slot.values.map { 0.0 }) })
                    }
                    DropdownMenuItem(text = { Text(if (slot.fractured) "Not fractured" else "Fractured") }, onClick = { menu = false; commit(fractured = !slot.fractured) })
                    DropdownMenuItem(text = { Text("Remove", color = PoeColors.Negative) }, onClick = { menu = false; commit(emptyList(), modId = null, fractured = false) })
                }
            }
        }
        val lineColor = when {
            slot.fractured -> Fractured
            slot.source == "desecrated" -> Desecrated
            else -> PoeColors.Magic
        }
        for (line in slot.lines) PobLabel(line, default = lineColor, fontSize = 14.sp, modifier = Modifier.padding(end = 8.dp))
        if (slot.fractured) Caption("Fractured", color = Fractured)
        if (itemLevel != null && slot.level > itemLevel) Caption("Needs item level ${slot.level} (the item is $itemLevel)", color = PoeColors.Negative)
        slot.values.forEachIndexed { i, v ->
            ValueSlider(v, rolls.getOrElse(i) { v.roll }, onChange = { r -> rolls = rolls.toMutableList().also { it[i] = r } }, onDone = { commit() })
        }
    }
}

@Composable
private fun SourceBadge(source: String?, label: String?) {
    val color = when (source) {
        "essence" -> Color(0xFFB794F6)
        "desecrated" -> Desecrated
        "influence" -> Color(0xFF7FC3E0)
        "other", "any" -> Color(0xFFE0A060)
        else -> PoeColors.TextDim
    }
    Text(
        label ?: "Regular",
        fontSize = 10.sp,
        color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun TierMenu(open: Boolean, tiers: List<ModTier>, current: Int?, onDismiss: () -> Unit, onPick: (ModTier) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, modifier = Modifier.heightIn(max = 420.dp)) {
        for (t in tiers) {
            DropdownMenuItem(
                text = { TierText(t, selected = t.tier == current) },
                onClick = { onPick(t) },
            )
        }
    }
}

@Composable
private fun TierText(t: ModTier, selected: Boolean) {
    val dim = !t.available
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("T${t.tier} ", fontSize = 12.sp, color = if (dim) PoeColors.Outline else PoeColors.GoldBright, fontWeight = if (selected) FontWeight.Bold else null)
            Text(t.name, fontSize = 12.sp, color = if (dim) PoeColors.Outline else PoeColors.Text, fontWeight = if (selected) FontWeight.Bold else null)
            Text(if (t.level > 1) "  ilvl ${t.level}" else "", fontSize = 11.sp, color = PoeColors.TextDim)
        }
        for (line in t.lines) Text(line, fontSize = 12.sp, color = if (dim) PoeColors.Outline else PoeColors.Magic)
        if (dim) Text("Above the item level", fontSize = 10.sp, color = PoeColors.Outline)
    }
}

private fun formatValue(v: Double, decimals: Int): String {
    val p = 10.0.pow(decimals)
    val r = (v * p).roundToInt() / p
    return if (decimals <= 0) r.roundToInt().toString() else "%.${decimals}f".format(r).trimEnd('0').trimEnd('.')
}

/** A value's roll between its range's ends, snapping to the values the range can have. */
@Composable
private fun ValueSlider(v: AffixValue, roll: Double, onChange: (Double) -> Unit, onDone: () -> Unit) {
    if (v.min == v.max) return
    val scale = 10.0.pow(v.decimals)
    val count = (abs(v.max - v.min) * scale).roundToInt()
    val value = v.min + roll * (v.max - v.min)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        Text(formatValue(value, v.decimals), fontSize = 13.sp, color = PoeColors.GoldBright, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(48.dp))
        Slider(
            value = roll.toFloat(),
            onValueChange = { r ->
                // Snap to the range's steps (whole numbers, or its decimals)
                val snapped = if (count in 1..2000) (r * count).roundToInt().toDouble() / count else r.toDouble()
                onChange(snapped)
            },
            onValueChangeFinished = onDone,
            steps = if (count in 2..20) count - 1 else 0,
            modifier = Modifier.weight(1f).height(32.dp),
        )
        Text("${formatValue(v.min, v.decimals)}–${formatValue(v.max, v.decimals)}", fontSize = 11.sp, color = PoeColors.TextDim, modifier = Modifier.padding(start = 6.dp))
    }
}

// ---- Rolls of PoB's range lines ----

@Composable
private fun RollsPanel(ranges: List<RangeLine>, vm: TreeViewModel, actions: CraftActions) {
    Panel("Rolls") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (r in ranges) {
                var roll by remember(r.index, r.roll, r.text) { mutableFloatStateOf(r.roll.toFloat()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PobLabel(r.text, Modifier.weight(1f), fontSize = 13.sp)
                    val kind = when (r.kind) { "implicit" -> "implicit"; "enchant" -> "enchant"; "rune" -> "rune"; else -> null }
                    if (kind != null) Text(kind, fontSize = 10.sp, color = PoeColors.TextDim, modifier = Modifier.padding(start = 4.dp))
                }
                val first = r.values.firstOrNull()
                if (first != null) {
                    ValueSlider(
                        first.copy(roll = roll.toDouble()),
                        roll.toDouble(),
                        onChange = { roll = it.toFloat() },
                        onDone = { actions.launch { vm.calc.craftSetRange(r.index, roll.toDouble()) } },
                    )
                }
            }
        }
    }
}

// ---- The modifier picker ----

private val SOURCE_TABS = listOf(
    "all" to "All",
    "regular" to "Regular",
    "essence" to "Essence",
    "desecrated" to "Desecrated",
    "influence" to "Rune-influenced",
    "other" to "Other",
    "any" to "Any modifier",
)

/** Modifier tags worth showing (not the game's internal markers such as "unveiled_mod"). */
private fun visibleTags(tags: List<String>) = tags.filterNot { it.endsWith("_mod") }

private fun sourceHelp(source: String) = when (source) {
    "regular" -> "Modifiers this base rolls."
    "essence" -> "Modifiers of the essences for this item class; the tiers are the essences (Lesser to Perfect)."
    "desecrated" -> "Desecrated modifiers (Abyss) this base can have."
    "influence" -> "Modifiers of the rune influences of this item class."
    "other" -> "Modifiers of this item class this base does not roll (other bases, special sources)."
    "any" -> "Every modifier of the item's modifier table, whatever the base: not all of them can exist on this item."
    else -> "Every modifier the item can have, by source."
}

/** Picks a modifier and tier for a slot: the families by source, searchable, sortable by a statistic. */
@Composable
private fun ModifierPicker(
    vm: TreeViewModel,
    slotRef: SlotRef,
    current: AffixSlot?,
    itemLevel: Int?,
    onDismiss: () -> Unit,
    onPick: (ModTier) -> Unit,
) {
    var source by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<ModFamily, ModTier>?>(null) }
    val (families, loading) = rememberLoaded(slotRef) { vm.calc.craftPool(slotRef.slot, slotRef.index, any = false) }
    var anyFamilies by remember { mutableStateOf<List<ModFamily>?>(null) }
    LaunchedEffect(source) {
        if (source == "any" && anyFamilies == null) anyFamilies = vm.calc.craftPool(slotRef.slot, slotRef.index, any = true)?.filter { it.source == "any" }
    }
    // Sorting by a statistic: PoB's change of the statistic for each family's best tier
    val (stats, _) = rememberLoaded(Unit) { vm.calc.craftSortStats() }
    var sortStat by remember { mutableStateOf<Pair<String, String>?>(null) }
    val values = remember { mutableStateMapOf<String, Double>() }
    var sortProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val base = if (source == "any") anyFamilies.orEmpty() else families.orEmpty().filter { source == "all" || it.source == source }
    val listState = rememberLazyListState()
    LaunchedEffect(sortStat, source, families, anyFamilies) {
        val stat = sortStat ?: return@LaunchedEffect
        val ids = base.map { it.bestAvailable.modId }.distinct().filter { "${stat.second}|$it" !in values }
        var done = 0
        sortProgress = 0 to ids.size
        for (chunk in ids.chunked(4)) {
            val result = vm.calc.craftPoolValues(slotRef.slot, slotRef.index, stat.second, chunk, 0.5) ?: break
            for ((id, v) in result) values["${stat.second}|$id"] = v
            done += chunk.size
            sortProgress = done to ids.size
        }
        sortProgress = null
    }
    // A new list starts at its top (a sorted one once its values are calculated)
    val sorting = sortProgress != null
    LaunchedEffect(source, sortStat, sorting, query) {
        if (!sorting) listState.scrollToItem(0)
    }
    val shown = run {
        val q = query.trim().lowercase()
        val filtered = base.filter { f ->
            q.isEmpty() || f.label.lowercase().contains(q) || f.text.lowercase().contains(q) ||
                f.tiers.any { it.name.lowercase().contains(q) } || f.tags.any { it.lowercase().contains(q) }
        }
        val stat = sortStat
        if (stat == null || sorting) filtered
        else filtered.sortedByDescending { values["${stat.second}|${it.bestAvailable.modId}"] ?: Double.NEGATIVE_INFINITY }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(PoeColors.Background).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Choose ${slotRef.slot} ${slotRef.index}",
                    color = PoeColors.GoldBright, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            OutlinedTextField(
                query, { query = it }, singleLine = true,
                placeholder = { Text("Search modifiers, tiers or tags") },
                leadingIcon = { Icon(AppIcons.Search, null, tint = PoeColors.TextDim) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, label) in SOURCE_TABS) {
                    val count = when (id) {
                        "all" -> families?.size
                        "any" -> anyFamilies?.size
                        else -> families?.count { it.source == id }
                    }
                    if (id != "all" && id != "any" && count == 0) continue
                    FilterChip(selected = source == id, onClick = { source = id; expanded = null }, label = { Text(if (count != null) "$label $count" else label, fontSize = 12.sp) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(sourceHelp(source) + (itemLevel?.let { " Tiers above item level $it are greyed." } ?: ""), Modifier.weight(1f))
                SortMenu(stats.orEmpty(), sortStat) { sortStat = it }
            }
            sortProgress?.let { (done, total) ->
                if (total > 0) {
                    Caption("Calculating ${sortStat?.first ?: ""} for each modifier… $done / $total")
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
            }
            when {
                (families == null && loading) || (source == "any" && anyFamilies == null) -> Centered("Loading modifiers…", progress = true)
                shown.isEmpty() -> Centered("No modifiers found.")
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(shown, key = { _, f -> f.key }) { i, f ->
                        if (source == "all" && (sortStat == null || sorting) && (i == 0 || shown[i - 1].source != f.source)) {
                            Text(f.sourceLabel, color = PoeColors.Gold, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                        }
                        val stat = sortStat
                        FamilyRow(
                            f,
                            selected = current?.modId != null && f.tiers.any { it.modId == current.modId },
                            expanded = expanded == f.key,
                            sortValue = stat?.takeIf { !sorting }?.let { values["${it.second}|${f.bestAvailable.modId}"] },
                            onToggle = { expanded = if (expanded == f.key) null else f.key },
                            onPick = onPick,
                            onPreview = { tier -> preview = f to tier },
                        )
                        HorizontalDivider(color = PoeColors.Outline.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }
    preview?.let { (f, tier) ->
        val (lines, previewLoading) = rememberLoaded(tier.modId) { vm.calc.craftPreview(slotRef.slot, slotRef.index, tier.modId, null) }
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text((if (f.tiers.size > 1) "T${tier.tier} " else "") + tier.name, fontSize = 16.sp) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    for (line in tier.lines) PobLabel(line, default = PoeColors.Magic, fontSize = 14.sp)
                    Caption(buildString {
                        append(f.sourceLabel)
                        if (tier.level > 1) append(" · item level ${tier.level}")
                        visibleTags(f.tags).takeIf { it.isNotEmpty() }?.let { append(" · ${it.joinToString(", ")}") }
                    })
                    Spacer(Modifier.height(8.dp))
                    when {
                        lines == null -> Centered(if (previewLoading) "Calculating…" else "No details.", progress = previewLoading)
                        else -> for (line in lines) PobLabel(line, fontSize = 13.sp)
                    }
                    if (tier.lines.any { it.contains(Regex("\\(-?[\\d.]+-")) }) Caption("At the middle roll of the tier.")
                }
            },
            confirmButton = { TextButton(onClick = { preview = null; onPick(tier) }) { Text("Use this tier") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Back") } },
        )
    }
}

@Composable
private fun SortMenu(stats: List<Pair<String, String>>, selected: Pair<String, String>?, onSelect: (Pair<String, String>?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(selected?.let { "Sort: ${it.first}" } ?: "Sort", fontSize = 12.sp, maxLines = 1)
            Icon(AppIcons.DropDown, null, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(text = { Text("Default order") }, onClick = { open = false; onSelect(null) })
            // The usual ones first, then all of PoB's statistics
            val common = listOf("FullDPS", "CombinedDPS", "Life", "EnergyShield", "Mana", "TotalEHP", "Armour", "Evasion", "Spirit")
            for (stat in stats.filter { it.second in common }.sortedBy { common.indexOf(it.second) }) {
                DropdownMenuItem(text = { Text(stat.first) }, onClick = { open = false; onSelect(stat) })
            }
            DropdownMenuItem(text = { Text("More statistics…", color = PoeColors.Gold) }, onClick = { open = false; searching = true })
        }
    }
    if (searching) {
        var q by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { searching = false },
            title = { Text("Sort by") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        itemsIndexed(stats.filter { q.isBlank() || it.first.contains(q.trim(), ignoreCase = true) }, key = { _, s -> s.second }) { _, s ->
                            Text(s.first, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { searching = false; onSelect(s) }.padding(vertical = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { searching = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FamilyRow(
    f: ModFamily,
    selected: Boolean,
    expanded: Boolean,
    sortValue: Double?,
    onToggle: () -> Unit,
    onPick: (ModTier) -> Unit,
    onPreview: (ModTier) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { if (f.tiers.size == 1) onPreview(f.tiers[0]) else onToggle() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (f.source == "essence") Text(f.label, fontSize = 13.sp, color = Color(0xFFB794F6))
                Text(f.text, fontSize = 14.sp, color = PoeColors.Text, fontWeight = if (selected) FontWeight.Bold else null)
                val best = f.bestAvailable
                Caption(buildString {
                    if (f.tiers.size > 1) append("${f.tiers.size} tiers · best ") else append("")
                    append(best.name)
                    if (best.level > 1) append(" (ilvl ${best.level})")
                    f.influence?.let { append(" · ${it.replaceFirstChar { c -> c.uppercase() }}") }
                    val tags = visibleTags(f.tags)
                    if (f.source != "essence" && tags.isNotEmpty()) append(" · ${tags.take(4).joinToString(", ")}")
                })
            }
            if (sortValue != null) {
                Text(
                    (if (sortValue > 0) "+" else "") + formatValue(sortValue, if (abs(sortValue) < 10) 2 else if (abs(sortValue) < 1000) 1 else 0),
                    fontSize = 13.sp,
                    color = if (sortValue > 0) PoeColors.Positive else if (sortValue < 0) PoeColors.Negative else PoeColors.TextDim,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (f.tiers.size > 1) Icon(if (expanded) AppIcons.Up else AppIcons.Down, null, tint = PoeColors.TextDim)
        }
        if (expanded) {
            for (t in f.tiers) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(t) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { TierText(t, selected = false) }
                    TextButton(onClick = { onPreview(t) }) { Text("ⓘ", fontSize = 15.sp) }
                }
            }
        }
    }
}

// ---- PoB's controls ----

@Composable
private fun Rows(rows: List<CraftRow>, vm: TreeViewModel, actions: CraftActions) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) ControlRow(row.controls, popup = false, vm, actions)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlRow(row: List<CraftControl>, popup: Boolean, vm: TreeViewModel, actions: CraftActions) {
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
                            Row(Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                ItemIcon(item.title, item.base, item.rarity, 36.dp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(item.name.substringBefore(", "), color = if (kind == "RARE") PoeColors.Rare else PoeColors.Unique, fontSize = 14.sp)
                                    item.base?.let { Caption(it) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
