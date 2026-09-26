package io.room.poe2tree.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.LoadState
import io.room.poe2tree.TreeViewModel

private enum class DialogKind { None, Import, Export, Settings, Summary, Builds, NewBuild, Rename, Reset, About }

@Composable
fun AppRoot(vm: TreeViewModel) {
    Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            when (val s = vm.loadState) {
                LoadState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Loading passive tree…", color = PoeColors.TextDim)
                }
                is LoadState.Failed -> Text(
                    "Failed to load the passive tree:\n${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                LoadState.Ready -> TreeScreen(vm)
            }
        }
    }
}

/** Callbacks shared by both layouts. */
private class ScreenActions(
    val onBuilds: () -> Unit,
    val onSearch: () -> Unit,
    val onMenu: (DialogKind) -> Unit,
    val onPoints: () -> Unit,
)

@Composable
private fun TreeScreen(vm: TreeViewModel) {
    var dialog by remember { mutableStateOf(DialogKind.None) }
    var searching by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val zoom = rememberZoomController(vm)

    LaunchedEffect(vm.message) {
        val m = vm.message ?: return@LaunchedEffect
        // Clear only after showing: changing the key earlier would cancel this effect
        snackbar.showSnackbar(m)
        if (vm.message == m) vm.message = null
    }

    BackHandler(enabled = vm.selected >= 0 || searching) {
        if (vm.selected >= 0) vm.clearSelection()
        else {
            searching = false
            vm.updateSearch("")
        }
    }

    val actions = ScreenActions(
        onBuilds = { dialog = DialogKind.Builds },
        onSearch = {
            searching = !searching
            if (!searching) vm.updateSearch("")
        },
        onMenu = { dialog = it },
        onPoints = { dialog = DialogKind.Settings },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            val sidebarWidth = (maxWidth * 0.36f).coerceIn(270.dp, 360.dp)
            LandscapeLayout(vm, zoom, snackbar, searching, sidebarWidth, actions)
        } else {
            PortraitLayout(vm, zoom, snackbar, searching, actions)
        }
    }

    ClassChangeDialog(vm)
    when (dialog) {
        DialogKind.Import -> ImportDialog(vm) { dialog = DialogKind.None }
        DialogKind.Export -> ExportDialog(vm) { dialog = DialogKind.None }
        DialogKind.Settings -> SettingsDialog(vm) { dialog = DialogKind.None }
        DialogKind.Summary -> SummaryDialog(vm) { dialog = DialogKind.None }
        DialogKind.Builds -> BuildsDialog(vm, onDismiss = { dialog = DialogKind.None }, onNew = { dialog = DialogKind.NewBuild })
        DialogKind.NewBuild -> NewBuildDialog(vm) { dialog = DialogKind.None }
        DialogKind.Rename -> NameDialog("Rename build", vm.buildName, "Rename", { vm.renameBuild(vm.buildId, it) }) { dialog = DialogKind.None }
        DialogKind.Reset -> ConfirmDialog(
            "Reset tree?", "Removes all allocated passives from this build. You can undo this.", "Reset",
            onConfirm = { vm.resetTree() }, onDismiss = { dialog = DialogKind.None },
        )
        DialogKind.About -> ConfirmDialog(
            "About",
            "PoE2 Passive Tree ${vm.tree.treeVersion.replace('_', '.')}\n\nPassive tree data and allocation logic are ported from Path of Building Community (PoE2), MIT licensed. " +
                "Game art © Grinding Gear Games. This app is not affiliated with Grinding Gear Games.",
            null, onConfirm = {}, onDismiss = { dialog = DialogKind.None },
        )
        DialogKind.None -> {}
    }
}

/** Portrait: controls on top, tree below, node details in a bottom panel over the tree. */
@Composable
private fun PortraitLayout(vm: TreeViewModel, zoom: ZoomController, snackbar: SnackbarHostState, searching: Boolean, actions: ScreenActions) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
    ) {
        TopBar(vm, actions)
        ControlsRow(vm)
        PointsRow(vm, onClick = actions.onPoints)
        if (searching) SearchBar(vm)
        TreeArea(vm, zoom, snackbar, bottomPanel = true, overlayInsets = WindowInsets(0), modifier = Modifier.weight(1f).fillMaxWidth())
    }
}

/** Landscape: controls and node details in a panel on the left, tree on the right. */
@Composable
private fun LandscapeLayout(
    vm: TreeViewModel,
    zoom: ZoomController,
    snackbar: SnackbarHostState,
    searching: Boolean,
    sidebarWidth: Dp,
    actions: ScreenActions,
) {
    val bars = WindowInsets.safeDrawing
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(bars.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical))
                .width(sidebarWidth)
                .fillMaxHeight()
        ) {
            TopBar(vm, actions, showLevel = true)
            SidebarControls(vm)
            PointsFlow(vm, onClick = actions.onPoints)
            if (searching) SearchBar(vm)
            HorizontalDivider(color = PoeColors.Outline)
            NodeSidebarDetails(vm, Modifier.weight(1f).fillMaxWidth())
        }
        VerticalDivider(color = PoeColors.Outline)
        TreeArea(
            vm, zoom, snackbar, bottomPanel = false,
            overlayInsets = bars.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/** The tree with its floating buttons, weapon-set banner, messages and (portrait) node panel. */
@Composable
private fun TreeArea(
    vm: TreeViewModel,
    zoom: ZoomController,
    snackbar: SnackbarHostState,
    bottomPanel: Boolean,
    overlayInsets: WindowInsets,
    modifier: Modifier,
) {
    Box(modifier) {
        TreeCanvas(vm, zoom, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().windowInsetsPadding(overlayInsets)) {
            TreeOverlays(vm, zoom, snackbar, bottomPanel)
        }
    }
}

@Composable
private fun BoxScope.TreeOverlays(vm: TreeViewModel, zoom: ZoomController, snackbar: SnackbarHostState, bottomPanel: Boolean) {
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(AppIcons.Plus, "Zoom in", enabled = vm.canZoomIn) { zoom.zoomBy(ZoomController.STEP) }
        RoundButton(AppIcons.Minus, "Zoom out", enabled = vm.canZoomOut) { zoom.zoomBy(1f / ZoomController.STEP) }
        RoundButton(AppIcons.ZoomOut, "Show whole tree") { vm.zoomOutFull() }
        RoundButton(AppIcons.Locate, "Go to class start") { vm.focusOnStart() }
    }
    if (vm.allocMode > 0) {
        val color = if (vm.allocMode == 1) PoeColors.Negative else PoeColors.Positive
        Text(
            "Allocating weapon set ${vm.allocMode} points",
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .background(color.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .then(if (bottomPanel) Modifier.navigationBarsPadding() else Modifier)
    ) {
        SnackbarHost(snackbar)
        if (bottomPanel) NodePanel(vm)
    }
}

@Composable
private fun TopBar(vm: TreeViewModel, actions: ScreenActions, showLevel: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = actions.onBuilds)
                .padding(vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.buildName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = PoeColors.GoldBright, modifier = Modifier.weight(1f, fill = false),
                )
                Icon(AppIcons.DropDown, contentDescription = "Builds", tint = PoeColors.TextDim)
            }
            val version = "Tree ${vm.tree.treeVersion.replace('_', '.')}"
            Text(if (showLevel) "$version · Lv ${vm.settings.level}" else version, fontSize = 11.sp, color = PoeColors.TextDim)
        }
        IconButton(onClick = actions.onSearch) { Icon(AppIcons.Search, contentDescription = "Search") }
        IconButton(onClick = { vm.undo() }, enabled = vm.canUndo) { Icon(AppIcons.Undo, contentDescription = "Undo") }
        IconButton(onClick = { vm.redo() }, enabled = vm.canRedo) { Icon(AppIcons.Redo, contentDescription = "Redo") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(AppIcons.More, contentDescription = "Menu") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                val pick = { kind: DialogKind -> menu = false; actions.onMenu(kind) }
                MenuItem("Builds…") { pick(DialogKind.Builds) }
                MenuItem("New build…") { pick(DialogKind.NewBuild) }
                MenuItem("Rename build…") { pick(DialogKind.Rename) }
                HorizontalDivider()
                MenuItem("Import PoB code…") { pick(DialogKind.Import) }
                MenuItem("Export PoB code…") { pick(DialogKind.Export) }
                HorizontalDivider()
                MenuItem("Allocated stats") { pick(DialogKind.Summary) }
                MenuItem("Passive points…") { pick(DialogKind.Settings) }
                MenuItem("Reset tree…") { pick(DialogKind.Reset) }
                HorizontalDivider()
                MenuItem("About") { pick(DialogKind.About) }
            }
        }
    }
}

// Composables that read vm.spec must read vm.revision themselves: the spec is not snapshot state,
// and Compose skips a child whose parameters (vm, modifier) did not change.

@Composable
private fun ClassDropdown(vm: TreeViewModel, modifier: Modifier) {
    vm.revision
    Dropdown(
        label = vm.spec.currentClass.name,
        options = vm.tree.classes.map { it.name },
        modifier = modifier,
    ) { i -> vm.selectClass(vm.tree.classes[i].integerId) }
}

@Composable
private fun AscendancyDropdown(vm: TreeViewModel, modifier: Modifier) {
    vm.revision
    val cls = vm.spec.currentClass
    Dropdown(
        label = vm.spec.currentAscendancy?.name ?: "No ascendancy",
        options = listOf("None") + cls.ascendancies.map { it.name },
        modifier = modifier,
    ) { i -> vm.selectAscendancy(if (i == 0) 0 else cls.ascendancies[i - 1].index) }
}

/** Portrait: class, ascendancy and allocation mode in one row. */
@Composable
private fun ControlsRow(vm: TreeViewModel) {
    vm.revision
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ClassDropdown(vm, Modifier.weight(1f))
        AscendancyDropdown(vm, Modifier.weight(1.3f))
        ModeSelector(vm)
    }
}

/** Landscape side panel: class and ascendancy side by side, allocation mode below. */
@Composable
private fun SidebarControls(vm: TreeViewModel) {
    vm.revision
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ClassDropdown(vm, Modifier.weight(1f))
            AscendancyDropdown(vm, Modifier.weight(1.3f))
        }
        ModeSelector(vm, Modifier.fillMaxWidth(), fill = true)
    }
}

@Composable
private fun Dropdown(label: String, options: List<String>, modifier: Modifier, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(AppIcons.DropDown, null, tint = PoeColors.TextDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onSelect(i) })
            }
        }
    }
}

/** Main tree / weapon set 1 / weapon set 2 allocation mode. [fill] stretches the segments. */
@Composable
private fun ModeSelector(vm: TreeViewModel, modifier: Modifier = Modifier, fill: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))
    ) {
        val labels = listOf("Main", "WS1", "WS2")
        val colors = listOf(PoeColors.Gold, PoeColors.Negative, PoeColors.Positive)
        labels.forEachIndexed { i, l ->
            val active = vm.allocMode == i
            Text(
                l,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) Color.Black else PoeColors.TextDim,
                textAlign = TextAlign.Center,
                modifier = (if (fill) Modifier.weight(1f) else Modifier)
                    .background(if (active) colors[i] else Color.Transparent)
                    .clickable { vm.changeAllocMode(i) }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun PointChips(vm: TreeViewModel) {
    vm.revision
    val c = vm.spec.counts()
    val s = vm.settings
    PointChip("Points", c.normal, s.normalMax, PoeColors.GoldBright)
    PointChip("WS1", c.ws1, s.weaponSetMax, PoeColors.Negative)
    PointChip("WS2", c.ws2, s.weaponSetMax, PoeColors.Positive)
    PointChip("Asc", c.ascUsed, s.ascendancyPoints, PoeColors.Ascendancy)
}

/** Portrait: point usage in one row. */
@Composable
private fun PointsRow(vm: TreeViewModel, onClick: () -> Unit) {
    vm.revision
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PointChips(vm)
        Spacer(Modifier.weight(1f))
        Text("Lv ${vm.settings.level}", fontSize = 12.sp, color = PoeColors.TextDim)
    }
    HorizontalDivider(color = PoeColors.Outline)
}

/** Landscape side panel: point usage, wrapping onto a second line when narrow. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PointsFlow(vm: TreeViewModel, onClick: () -> Unit) {
    vm.revision
    FlowRow(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PointChips(vm)
    }
}

@Composable
private fun PointChip(label: String, used: Int, max: Int, color: Color) {
    val over = used > max
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", fontSize = 12.sp, color = PoeColors.TextDim)
        Text(
            "$used/$max",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (over) Color(0xFFFF5252) else color,
        )
    }
}

@Composable
private fun SearchBar(vm: TreeViewModel) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.updateSearch(it) },
            placeholder = { Text("Search nodes (name or stat)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.weight(1f).focusRequester(focus),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                vm.nextSearchResult()
            }),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PoeColors.Outline),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        val count = vm.searchResults.size
        Text(
            if (vm.searchQuery.isBlank()) "" else if (vm.searchPosition > 0) "${vm.searchPosition}/$count" else "$count",
            fontSize = 12.sp,
            color = PoeColors.TextDim,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        IconButton(onClick = { vm.nextSearchResult(-1) }, enabled = count > 0) { Icon(AppIcons.Up, "Previous result") }
        IconButton(onClick = { vm.nextSearchResult(1) }, enabled = count > 0) { Icon(AppIcons.Down, "Next result") }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        modifier = Modifier
            .size(42.dp)
            .border(1.dp, PoeColors.Outline, CircleShape),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, description, tint = if (enabled) PoeColors.Gold else PoeColors.TextDim.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { if (confirm != null) onConfirm(); onDismiss() }) {
                Text(confirm ?: "OK")
            }
        },
        dismissButton = if (confirm != null) {
            { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } }
        } else null,
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick)
}
