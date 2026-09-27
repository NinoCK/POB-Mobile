package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.CharacterImportOptions
import io.room.poe2tree.TreeViewModel

private const val ALL_LEAGUES = "All leagues"

/**
 * Character import, like the Character Import section of PoB's Import/Export tab: sign in to
 * pathofexile.com, choose a character, and import its passive tree and jewels and / or its items
 * and skills into a new build or the open one.
 */
@Composable
fun CharacterImportDialog(vm: TreeViewModel, onDismiss: () -> Unit) {
    val ci = vm.characterImport
    var options by remember { mutableStateOf(CharacterImportOptions()) }
    val progress = vm.characterImportProgress
    val characters = ci.characters

    LaunchedEffect(ci.signedIn) {
        if (ci.signedIn && ci.characters == null) ci.loadCharacters()
    }

    val close = {
        if (ci.signingIn) ci.cancel()
        onDismiss()
    }
    val character = characters?.firstOrNull { it.name == ci.selected }?.takeIf { ci.league == null || ci.league == ALL_LEAGUES || it.league == ci.league }

    AlertDialog(
        onDismissRequest = { if (progress == null) close() },
        title = { Text("Import character") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ci.signedIn) {
                    if (ci.signingIn) {
                        Busy("Waiting for the sign-in on pathofexile.com…")
                        Text(
                            "Sign in and allow access in the browser, then return to the app.",
                            fontSize = 13.sp, color = PoeColors.TextDim,
                        )
                        TextButton(onClick = { ci.reopenSignInPage() }) { Text("Open the sign-in page again") }
                    } else {
                        Text(
                            "Imports a character's passive tree, jewels, items and skills from your Path of Exile account, like Path of Building's character import.",
                            fontSize = 13.sp, color = PoeColors.TextDim,
                        )
                        Text(
                            "You sign in on pathofexile.com in your browser; the app gets read access to your characters.",
                            fontSize = 13.sp, color = PoeColors.TextDim,
                        )
                        OutlinedButton(onClick = { ci.signIn() }) { Text("Sign in with pathofexile.com") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Signed in" + (ci.username?.let { " as $it" } ?: ""),
                            fontSize = 13.sp, color = PoeColors.TextDim, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(enabled = progress == null, onClick = { ci.signOut() }) { Text("Sign out") }
                    }
                    if (characters == null) {
                        if (ci.loading) Busy("Loading characters…")
                        else TextButton(onClick = { ci.loadCharacters() }) { Text("Load characters") }
                    } else if (characters.isNotEmpty()) {
                        val leagues = characters.map { it.league }.distinct().sorted() + ALL_LEAGUES
                        val league = ci.league ?: leagues.first()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ChoiceField(leagues, leagues.indexOf(league) + 1, Modifier.weight(1f), caption = "League") { i ->
                                val chosen = leagues[i - 1]
                                ci.league = chosen
                                if (chosen != ALL_LEAGUES && characters.none { it.name == ci.selected && it.league == chosen }) {
                                    ci.selected = characters.firstOrNull { it.league == chosen }?.name
                                }
                            }
                            TextButton(enabled = !ci.loading && progress == null, onClick = { ci.loadCharacters() }) {
                                Text(if (ci.loading) "Loading…" else "Refresh")
                            }
                        }
                        val shown = characters.filter { league == ALL_LEAGUES || it.league == league }
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 232.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))
                                .verticalScroll(rememberScrollState())
                        ) {
                            for (c in shown) {
                                val sel = c.name == character?.name
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (sel) PoeColors.SurfaceHigh else PoeColors.Surface)
                                        .clickable(enabled = progress == null) { ci.selected = c.name }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        c.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                        color = if (sel) PoeColors.GoldBright else PoeColors.Text,
                                    )
                                    Text(
                                        listOfNotNull(vm.characterClassName(c.className), "Level ${c.level}", c.league.takeIf { league == ALL_LEAGUES }).joinToString(" · "),
                                        fontSize = 12.sp, color = PoeColors.TextDim,
                                    )
                                }
                            }
                        }
                        ImportOptions(vm, options, enabled = progress == null) { options = it }
                    }
                }
                ci.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                progress?.let { Busy(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ci.signedIn && character != null && (options.tree || options.items) && progress == null,
                onClick = { character?.let { vm.importCharacter(it, options, onDone = onDismiss) } },
            ) { Text("Import") }
        },
        dismissButton = { TextButton(enabled = progress == null, onClick = close) { Text("Close") } },
    )
}

@Composable
private fun ImportOptions(vm: TreeViewModel, options: CharacterImportOptions, enabled: Boolean, onChange: (CharacterImportOptions) -> Unit) {
    Column {
        Text("Import into", fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.padding(top = 4.dp))
        RadioRow("A new build", options.newBuild, enabled) { onChange(options.copy(newBuild = true)) }
        RadioRow("This build (${vm.buildName})", !options.newBuild, enabled) { onChange(options.copy(newBuild = false)) }
        Spacer(Modifier.height(4.dp))
        CheckRow("Passive tree and jewels", options.tree, enabled) { onChange(options.copy(tree = it)) }
        if (!options.newBuild) {
            CheckRow("Delete jewels", options.clearJewels, enabled && options.tree, indent = true) { onChange(options.copy(clearJewels = it)) }
        }
        CheckRow("Items and skills", options.items, enabled) { onChange(options.copy(items = it)) }
        if (!options.newBuild) {
            CheckRow("Delete skills", options.clearSkills, enabled && options.items, indent = true) { onChange(options.copy(clearSkills = it)) }
            CheckRow("Delete equipment", options.clearItems, enabled && options.items, indent = true) { onChange(options.copy(clearItems = it)) }
        }
        CheckRow("Ignore weapon swap", options.ignoreWeaponSwap, enabled && options.items, indent = true) { onChange(options.copy(ignoreWeaponSwap = it)) }
        if (!options.newBuild && options.tree) {
            Text(
                "The imported tree replaces this build's tree (undo restores it).",
                fontSize = 12.sp, color = PoeColors.TextDim, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (enabled) PoeColors.Text else PoeColors.TextDim)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 24.dp else 0.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onChange)
        Text(label, fontSize = 14.sp, color = if (enabled) PoeColors.Text else PoeColors.TextDim)
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp)) { CircularProgressIndicator(strokeWidth = 2.dp) }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = PoeColors.Text)
    }
}
