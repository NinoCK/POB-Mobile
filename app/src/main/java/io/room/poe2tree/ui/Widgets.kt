package io.room.poe2tree.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.room.poe2tree.engine.Breakdown
import io.room.poe2tree.engine.BreakdownSection

/** A dropdown field. [selected] is 1-based (PoB's indices); [options] may contain colour codes. */
@Composable
fun ChoiceField(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    caption: String? = null,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val label = options.getOrNull(selected - 1) ?: ""
    Column(modifier) {
        if (caption != null) Text(caption, fontSize = 11.sp, color = PoeColors.TextDim, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, PoeColors.Outline, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled && options.size > 1) { open = true }
                    .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PobLabel(label, Modifier.weight(1f), default = if (enabled) PoeColors.Text else PoeColors.TextDim, maxLines = 1)
                Icon(AppIcons.DropDown, null, tint = if (enabled && options.size > 1) PoeColors.TextDim else PoeColors.Outline)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { PobLabel(o) }, onClick = { open = false; if (i + 1 != selected) onSelect(i + 1) })
                }
            }
        }
    }
}

/**
 * A number field that commits when the keyboard's Done is pressed or the field loses focus.
 * An empty field commits null (PoB then uses its default / placeholder).
 */
@Composable
fun NumberInput(
    value: Double?,
    placeholder: Double?,
    modifier: Modifier = Modifier,
    decimals: Boolean = false,
    allowNegative: Boolean = false,
    enabled: Boolean = true,
    onCommit: (Double?) -> Unit,
) {
    fun format(v: Double?) = when {
        v == null -> ""
        v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
        else -> v.toString()
    }
    var text by remember(value) { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    fun commit() {
        val parsed = text.toDoubleOrNull()
        if (parsed != value) onCommit(parsed)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            text = v.filterIndexed { i, c -> c.isDigit() || (decimals && c == '.') || (allowNegative && c == '-' && i == 0) }.take(9)
        },
        placeholder = { if (placeholder != null) Text(format(placeholder), color = PoeColors.TextDim) },
        singleLine = true,
        enabled = enabled,
        textStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = PoeColors.Outline),
        modifier = modifier.onFocusChanged { state ->
            if (focused && !state.isFocused) commit()
            focused = state.isFocused
        },
    )
}

/** A titled panel. */
@Composable
fun Panel(title: String?, modifier: Modifier = Modifier, titleColor: Color = PoeColors.Gold, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(PoeColors.SurfaceHigh)
            .border(1.dp, PoeColors.Outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (title != null) {
            PobLabel(title, default = titleColor, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
        }
        content()
    }
}

@Composable
fun Centered(message: String, modifier: Modifier = Modifier, progress: Boolean = false) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (progress) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
            }
            Text(message, color = PoeColors.TextDim, fontSize = 14.sp)
        }
    }
}

/** A label: value line of PoB's sidebar style. */
@Composable
fun StatLine(label: String, value: String, modifier: Modifier = Modifier, labelWidth: Dp? = null, fontSize: Int = 14) {
    Row(modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        PobLabel(
            "$label:",
            if (labelWidth != null) Modifier.width(labelWidth) else Modifier.weight(1f),
            default = PoeColors.Text,
            fontSize = fontSize.sp,
        )
        Spacer(Modifier.width(8.dp))
        PobLabel(value, if (labelWidth != null) Modifier.weight(1f) else Modifier.weight(1f), default = PoeColors.Text, fontSize = fontSize.sp)
    }
}

/**
 * A calculation breakdown (PoB's CalcBreakdownControl): formula lines and modifier tables.
 * [onShowNode] jumps to a passive listed as a modifier source.
 */
@Composable
fun BreakdownDialog(title: String, breakdown: Breakdown?, loading: Boolean, onShowNode: ((Int) -> Unit)?, onDismiss: () -> Unit) {
    var totals by remember { mutableStateOf<List<String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                PobLabel(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                breakdown?.value?.takeIf { it.isNotBlank() }?.let { PobLabel(it, fontSize = 14.sp, default = PoeColors.GoldBright) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                when {
                    loading -> Centered("Calculating…", progress = true)
                    breakdown == null || breakdown.sections.isEmpty() -> Text("No breakdown for this value.", color = PoeColors.TextDim)
                    else -> for (section in breakdown.sections) {
                        when (section) {
                            is BreakdownSection.Lines -> Column(Modifier.padding(bottom = 10.dp)) {
                                for (line in section.lines) PobLabel(line, fontSize = 14.sp)
                            }
                            is BreakdownSection.Table -> BreakdownTable(section, onShowNode) { totals = it }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    totals?.let { lines ->
        AlertDialog(
            onDismissRequest = { totals = null },
            text = { Column { for (l in lines) PobLabel(l, fontSize = 14.sp) } },
            confirmButton = { TextButton(onClick = { totals = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun BreakdownTable(table: BreakdownSection.Table, onShowNode: ((Int) -> Unit)?, onTotals: (List<String>) -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        table.label?.let { PobLabel("$it:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp)) }
        // Columns sized to their content; the table scrolls sideways on narrow screens
        val widths = table.columns.indices.map { c ->
            val longest = maxOf(table.columns[c].label.length, table.rows.maxOfOrNull { PobText.strip(it.cells.getOrElse(c) { "" }).length } ?: 0)
            (longest.coerceIn(3, 38) * 7 + 14).dp
        }
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(PoeColors.Surface).padding(vertical = 3.dp)) {
                table.columns.forEachIndexed { c, col ->
                    Text(col.label, fontSize = 12.sp, color = PoeColors.TextDim, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(widths[c]).padding(horizontal = 4.dp))
                }
            }
            for (row in table.rows) {
                HorizontalDivider(color = PoeColors.Outline.copy(alpha = 0.4f))
                Row(
                    Modifier
                        .clickable(enabled = row.nodeId != null && onShowNode != null || row.sourceTotals.isNotEmpty()) {
                            if (row.nodeId != null && onShowNode != null) onShowNode(row.nodeId) else onTotals(row.sourceTotals)
                        }
                        .padding(vertical = 3.dp),
                ) {
                    table.columns.forEachIndexed { c, _ ->
                        val cell = row.cells.getOrElse(c) { "" }
                        PobLabel(
                            cell,
                            Modifier.width(widths[c]).padding(horizontal = 4.dp),
                            default = if (c == table.columns.size - 1 && row.nodeId != null) PoeColors.Notable else PoeColors.Text,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        table.footer?.let { PobLabel(it, fontSize = 12.sp, default = PoeColors.TextDim, modifier = Modifier.padding(top = 4.dp)) }
        if (table.rows.any { it.nodeId != null } && onShowNode != null) {
            Text("Tap a passive to show it on the tree.", fontSize = 11.sp, color = PoeColors.TextDim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A small caption. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = PoeColors.TextDim) {
    Text(text, fontSize = 12.sp, color = color, modifier = modifier, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/** Waits for [key] before running [block] once per key (used to load screen data). */
@Composable
fun <T> rememberLoaded(vararg keys: Any?, load: suspend () -> T?): Pair<T?, Boolean> {
    var value by remember { mutableStateOf<T?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(*keys) {
        loading = true
        val result = load()
        if (result != null) value = result
        loading = false
    }
    return value to loading
}
