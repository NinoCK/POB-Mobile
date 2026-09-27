package io.room.poe2tree.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.room.poe2tree.TreeViewModel
import io.room.poe2tree.engine.ItemInfo
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
                fun openSlot(slot: ItemSlot) {
                    if (slot.itemId != 0) viewing = slot.itemId to slot.name
                    else choosingFor = slot
                }
                fun activate(slot: ItemSlot, on: Boolean) = edit(mapOf("op" to "activate", "slot" to slot.name, "value" to on))
                Panel("Equipped") {
                    // The equipment where the game's inventory has it; the other slots (jewel
                    // sockets, a third ring, ...) listed below
                    val (placed, others) = current.slots.partition { dollPlace(it.name) != null }
                    EquipmentDoll(placed, current, onClick = ::openSlot, onActivate = ::activate)
                    if (others.isNotEmpty()) {
                        HorizontalDivider(color = PoeColors.Outline, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        for (slot in others) {
                            SlotRow(slot, current.item(slot.itemId), onActivate = { on -> activate(slot, on) }, onClick = { openSlot(slot) })
                        }
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
                            ItemIcon(item.title, item.base, item.rarity, 36.dp)
                            Spacer(Modifier.width(8.dp))
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
                vm, id, slotName, current?.item(id),
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
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ItemIcon(item.title, item.base, item.rarity, 32.dp)
                                Spacer(Modifier.width(8.dp))
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
private fun SlotRow(slot: ItemSlot, info: ItemInfo?, onActivate: (Boolean) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(slot.label, fontSize = 13.sp, color = PoeColors.TextDim, modifier = Modifier.width(110.dp), textAlign = TextAlign.End)
        Spacer(Modifier.width(8.dp))
        if (info != null) ItemIcon(info.title, info.base, info.rarity, 32.dp) else Spacer(Modifier.width(32.dp))
        Spacer(Modifier.width(8.dp))
        if (slot.item != null) PobLabel(slot.item, Modifier.weight(1f), fontSize = 14.sp, maxLines = 2)
        else Text("Empty", Modifier.weight(1f), fontSize = 13.sp, color = PoeColors.Outline)
        if (slot.canActivate && slot.itemId != 0) {
            Checkbox(checked = slot.active, onCheckedChange = onActivate)
        }
    }
}

/** A slot's box in the game's inventory: its top left corner and size, in inventory cells. */
private class DollPlace(val x: Float, val y: Float, val w: Int, val h: Int)

/** PoE2's inventory: weapons on the sides, the armour in the middle, flasks and charms below. */
private val dollPlaces = mapOf(
    "Weapon 1" to DollPlace(0f, 0.2f, 2, 4),
    "Helmet" to DollPlace(3.6f, 0f, 2, 2),
    "Weapon 2" to DollPlace(7.2f, 0.2f, 2, 4),
    "Amulet" to DollPlace(5.9f, 1.8f, 1, 1),
    "Body Armour" to DollPlace(3.6f, 2.2f, 2, 3),
    "Ring 1" to DollPlace(2.3f, 3.1f, 1, 1),
    "Ring 2" to DollPlace(5.9f, 3.1f, 1, 1),
    "Gloves" to DollPlace(1.3f, 4.4f, 2, 2),
    "Belt" to DollPlace(3.6f, 5.4f, 2, 1),
    "Boots" to DollPlace(5.9f, 4.4f, 2, 2),
    "Flask 1" to DollPlace(1.8f, 6.6f, 1, 2),
    "Charm 1" to DollPlace(3.0f, 7.1f, 1, 1),
    "Charm 2" to DollPlace(4.1f, 7.1f, 1, 1),
    "Charm 3" to DollPlace(5.2f, 7.1f, 1, 1),
    "Flask 2" to DollPlace(6.4f, 6.6f, 1, 2),
)
private const val DollWidth = 9.2f
private const val DollHeight = 8.6f

/** Where a slot is in the inventory (weapon set II's weapons where set I's are); null when it is not there. */
private fun dollPlace(slotName: String) = dollPlaces[slotName.removeSuffix(" Swap")]

private fun rarityColor(rarity: String?) = when (rarity) {
    "UNIQUE", "RELIC" -> PoeColors.Unique
    "RARE" -> PoeColors.Rare
    "MAGIC" -> PoeColors.Magic
    else -> PoeColors.Normal
}

/** The equipped items laid out as in the game's inventory, with their sockets. */
@Composable
private fun EquipmentDoll(slots: List<ItemSlot>, data: ItemsData, onClick: (ItemSlot) -> Unit, onActivate: (ItemSlot, Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        val cell = min(maxWidth / DollWidth, 60.dp)
        Box(Modifier.align(Alignment.TopCenter).size(cell * DollWidth, cell * DollHeight)) {
            for (slot in slots) {
                val place = dollPlace(slot.name) ?: continue
                DollSlot(
                    slot, data.item(slot.itemId), place, cell,
                    onClick = { onClick(slot) },
                    onActivate = { on -> onActivate(slot, on) },
                    modifier = Modifier.offset(cell * place.x, cell * place.y),
                )
            }
        }
    }
}

@Composable
private fun DollSlot(slot: ItemSlot, info: ItemInfo?, place: DollPlace, cell: Dp, onClick: () -> Unit, onActivate: (Boolean) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(4.dp)
    val width = cell * place.w - 2.dp
    val height = cell * place.h - 2.dp
    Box(
        modifier
            .size(width, height)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.3f))
            .border(1.dp, if (info != null) rarityColor(info.rarity).copy(alpha = 0.45f) else PoeColors.Outline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (info == null) {
            // Smaller and a word per line in the one-cell boxes ("Amulet", "Charm / 1")
            Text(
                if (place.w == 1) slot.label.replace(' ', '\n') else slot.label,
                fontSize = if (place.w == 1) 9.sp else 10.sp, lineHeight = 11.sp,
                color = PoeColors.TextDim.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            )
            return@Box
        }
        // A flask or charm that is not in use is dimmed
        val dim = slot.canActivate && !slot.active
        val art = rememberIcon(GameIcons.itemKey(info.title, info.base, info.rarity))
        if (art != null) {
            Image(
                art, contentDescription = info.title ?: info.base,
                Modifier.fillMaxSize().padding(3.dp).alpha(if (dim) 0.4f else 1f),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium,
            )
        } else {
            PobLabel(info.name, Modifier.padding(3.dp), fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 4)
        }
        if (info.sockets.isNotEmpty()) {
            // Side by side on items two cells wide, one above the other on narrow ones (wands, flasks, ...)
            val wide = place.w >= 2 && (art == null || art.width >= art.height * 0.45f)
            Sockets(info.sockets, wide, width, height, cell)
        }
        if (slot.canActivate) {
            ActiveBadge(slot.active, onActivate, Modifier.align(Alignment.TopEnd))
        }
    }
}

/** An item's sockets over its art, each with its rune (or soul core, ...) or empty. */
@Composable
private fun Sockets(sockets: List<String>, wide: Boolean, width: Dp, height: Dp, cell: Dp) {
    val columns = when {
        !wide -> 1
        sockets.size == 4 -> 2
        else -> minOf(sockets.size, 3)
    }
    val rows = (sockets.size + columns - 1) / columns
    val gap = cell * 0.08f
    val size = minOf(cell * 0.56f, (width - gap * (columns + 1)) / columns, (height - gap * (rows + 1)) / rows)
    Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in sockets.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (rune in row) Socket(rune, size)
            }
        }
    }
}

@Composable
private fun Socket(rune: String, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.75f))
            .border(1.5.dp, PoeColors.Gold.copy(alpha = 0.8f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (rune.isEmpty()) return@Box
        val icon = rememberIcon(GameIcons.runeKey(rune))
        if (icon != null) {
            Image(icon, contentDescription = rune, Modifier.fillMaxSize().padding(1.dp), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
        } else {
            Box(Modifier.size(size * 0.45f).clip(CircleShape).background(PoeColors.Ascendancy))
        }
    }
}

/** The in-use check box of a flask or charm. */
@Composable
private fun ActiveBadge(active: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(3.dp)
    Box(modifier.size(24.dp).clickable { onChange(!active) }, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(14.dp)
                .clip(shape)
                .background(if (active) PoeColors.Gold else Color.Black.copy(alpha = 0.6f))
                .border(1.dp, PoeColors.Gold, shape),
        ) {
            if (active) {
                val tick = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.52f)
                    lineTo(size.width * 0.42f, size.height * 0.72f)
                    lineTo(size.width * 0.78f, size.height * 0.3f)
                }
                drawPath(tick, Color.Black, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun ItemDialog(
    vm: TreeViewModel,
    id: Int,
    slotName: String?,
    info: ItemInfo?,
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
                    if (info != null) ItemArt(info.title, info.base, info.rarity, Modifier.padding(bottom = 6.dp))
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
