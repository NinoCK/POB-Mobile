package io.room.poe2tree.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.ItemSlot
import io.room.poe2tree.engine.ItemsData
import kotlinx.coroutines.launch

/** The Items screen: PoB's Items tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemsScreen(vm: TreeViewModel, modifier: Modifier = Modifier) {
    EngineGate(vm, modifier) {
        val calc = vm.calc
        val scope = rememberCoroutineScope()
        // PoB's item editor while an item is crafted or edited
        calc.craft?.let { craft ->
            CraftEditor(vm, craft, modifier)
            return@EngineGate
        }
        var database by remember { mutableStateOf<String?>(null) }
        val (loaded, loading) = rememberLoaded(calc.dataRevision) { calc.items() }
        var data by remember(loaded) { mutableStateOf(loaded) }
        /** (item id, slot name) of the open tooltip. */
        var viewing by remember { mutableStateOf<Pair<Int, String?>?>(null) }
        var choosingFor by remember { mutableStateOf<ItemSlot?>(null) }
        var adding by remember { mutableStateOf(false) }

        fun edit(args: Map<String, Any?>, after: (Int?) -> Unit = {}) {
            scope.launch {
                calc.itemEdit(args)?.let { (items, added) ->
                    data = items
                    after(added)
                }
            }
        }

        val current = data
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (current == null) {
                item { Centered(if (loading) "Loading…" else "No items.", progress = loading) }
                return@LazyColumn
            }
            item(key = "top") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (current.itemSets.size > 1) {
                        ChoiceField(current.itemSets.map { it.title }, current.itemSets.indexOfFirst { it.id == current.activeItemSetId } + 1, caption = "Item set") { i ->
                            edit(mapOf("op" to "itemSet", "id" to current.itemSets[i - 1].id))
                        }
                    }
                    WeaponSetToggle(current.useSecondWeaponSet) { second -> edit(mapOf("op" to "weaponSet", "value" to second)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { scope.launch { calc.craftNew() } }) { Text("Craft item…") }
                        OutlinedButton(onClick = { database = "UNIQUE" }) { Text("Uniques…") }
                        OutlinedButton(onClick = { database = "RARE" }) { Text("Rare templates…") }
                        OutlinedButton(onClick = { adding = true }) { Text("Add from text") }
                    }
                }
            }
            item(key = "slots") {
                Panel("Equipped") {
                    for (slot in current.slots) {
                        SlotRow(
                            slot,
                            onActivate = { on -> edit(mapOf("op" to "activate", "slot" to slot.name, "value" to on)) },
                            onClick = {
                                if (slot.itemId != 0) viewing = slot.itemId to slot.name
                                else choosingFor = slot
                            },
                        )
                    }
                }
            }
            item(key = "all") {
                Panel("All items (${current.items.size})") {
                    if (current.items.isEmpty()) Caption("No items in this build.")
                    for (item in current.items) {
                        Row(
                            Modifier.fillMaxWidth().clickable { viewing = item.id to null }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                PobLabel(item.name, fontSize = 14.sp)
                                if (item.equipped.isNotEmpty()) Caption("Equipped: " + item.equipped.joinToString(", "))
                            }
                        }
                    }
                }
            }
        }

        viewing?.let { (id, slotName) ->
            val slot = current?.slots?.firstOrNull { it.name == slotName }
            ItemDialog(
                vm, id, slotName,
                canChange = slot != null && slot.candidates.size > 1,
                onDismiss = { viewing = null },
                onChange = { viewing = null; choosingFor = slot },
                onUnequip = slot?.let { { viewing = null; edit(mapOf("op" to "equip", "slot" to it.name, "id" to 0)) } },
                onDelete = if (slot == null) { { viewing = null; edit(mapOf("op" to "delete", "id" to id)) } } else null,
                onEdit = { viewing = null; scope.launch { calc.craftEdit(id) } },
            )
        }
        database?.let { kind ->
            ItemDbDialog(vm, kind, onDismiss = { database = null }) { item ->
                database = null
                scope.launch { calc.craftFromDB(kind, item.name) }
            }
        }
        choosingFor?.let { slot ->
            val items = current
            AlertDialog(
                onDismissRequest = { choosingFor = null },
                title = { Text(slot.label) },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                        if (slot.candidates.isEmpty()) Caption("No item of this build fits this slot. Add one with \"Add item from text\".")
                        for (id in slot.candidates) {
                            val item = items?.item(id) ?: continue
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    choosingFor = null
                                    edit(mapOf("op" to "equip", "slot" to slot.name, "id" to id))
                                }.padding(vertical = 8.dp),
                            ) {
                                PobLabel(item.name + if (id == slot.itemId) " ^x808080(equipped)" else "", fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choosingFor = null }) { Text("Close") } },
                dismissButton = if (slot.itemId != 0) {
                    { TextButton(onClick = { choosingFor = null; edit(mapOf("op" to "equip", "slot" to slot.name, "id" to 0)) }) { Text("Unequip") } }
                } else null,
            )
        }
        if (adding) {
            PasteDialog(
                title = "Add item",
                hint = "Paste an item copied in Path of Exile 2 (Ctrl+C on the item) or from Path of Building. It is equipped in a free slot when there is one.",
                onDismiss = { adding = false },
            ) { text ->
                adding = false
                edit(mapOf("op" to "add", "text" to text, "equip" to true)) { added ->
                    if (added != null) vm.message = "Item added."
                }
            }
        }
    }
}

@Composable
private fun SlotRow(slot: ItemSlot, onActivate: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slot.label, fontSize = 13.sp, color = PoeColors.TextDim, modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
        Text("  ", fontSize = 13.sp)
        if (slot.item != null) PobLabel(slot.item, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2)
        else Text("Empty", Modifier.weight(1f), fontSize = 13.sp, color = PoeColors.Outline)
        if (slot.canActivate && slot.itemId != 0) {
            Checkbox(checked = slot.active, onCheckedChange = onActivate)
        }
    }
}

@Composable
private fun ItemDialog(
    vm: TreeViewModel,
    id: Int,
    slotName: String?,
    canChange: Boolean,
    onDismiss: () -> Unit,
    onChange: () -> Unit,
    onUnequip: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onEdit: () -> Unit,
) {
    val (tooltip, loading) = rememberLoaded(id, slotName, vm.calc.dataRevision) { vm.calc.itemTooltip(id, slotName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (tooltip != null) {
                // The first lines are the name (and base) in the rarity colour
                val header = tooltip.lines.takeWhile { !it.separator }.mapNotNull { it.text }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    for (line in header) PobLabel(line, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, textAlign = TextAlign.Center)
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                when {
                    tooltip == null -> Centered(if (loading) "Loading…" else "Item not found.", progress = loading)
                    else -> {
                        val body = tooltip.lines.dropWhile { !it.separator }
                        for (line in body) {
                            if (line.separator) HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(vertical = 5.dp))
                            else PobLabel(line.text ?: "", fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("Edit") }
                if (canChange) TextButton(onClick = onChange) { Text("Change") }
                if (onUnequip != null) TextButton(onClick = onUnequip) { Text("Unequip") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = PoeColors.Negative) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Suppress("unused")
private fun ItemsData.slot(name: String) = slots.firstOrNull { it.name == name }
