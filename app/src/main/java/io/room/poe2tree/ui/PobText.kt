package io.room.poe2tree.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

/**
 * Path of Building's text colour codes: ^0-^9 (SimpleGraphic's palette) and ^xRRGGBB, each applying
 * until the next code.
 */
object PobText {
    /** SimpleGraphic colours; black (^0) is drawn in the default colour on the dark background. */
    private val codeColors = arrayOf(
        null,
        Color(0xFFFF0000),
        Color(0xFF00FF00),
        Color(0xFF0000FF),
        Color(0xFFFFFF00),
        Color(0xFFFF00FF),
        Color(0xFF00FFFF),
        Color(0xFFFFFFFF),
        Color(0xFFB3B3B3),
        Color(0xFF666666),
    )

    private val codeRegex = Regex("\\^(x[0-9A-Fa-f]{6}|[0-9])")

    /** The text without colour codes. */
    fun strip(text: String): String = text.replace(codeRegex, "")

    /** Colour of a code such as "^xE05030" or "^7" (null for none / ^0). */
    fun color(code: String?): Color? {
        if (code == null) return null
        val m = codeRegex.find(code) ?: return null
        val c = m.groupValues[1]
        return if (c.startsWith("x")) Color(0xFF000000 or c.substring(1).toLong(16)) else codeColors[c[0] - '0']
    }

    fun annotate(text: String, default: Color): AnnotatedString = buildAnnotatedString {
        var color = default
        var pos = 0
        for (m in codeRegex.findAll(text)) {
            if (m.range.first > pos) withColor(color) { append(text, pos, m.range.first) }
            val c = m.groupValues[1]
            color = if (c.startsWith("x")) Color(0xFF000000 or c.substring(1).toLong(16)) else codeColors[c[0] - '0'] ?: default
            pos = m.range.last + 1
        }
        if (pos < text.length) withColor(color) { append(text, pos, text.length) }
    }

    private inline fun AnnotatedString.Builder.withColor(color: Color, block: AnnotatedString.Builder.() -> Unit) {
        val i = pushStyle(SpanStyle(color = color))
        block()
        pop(i)
    }
}

/** Text with PoB colour codes. */
@Composable
fun PobLabel(
    text: String,
    modifier: Modifier = Modifier,
    default: Color = PoeColors.Text,
    fontSize: TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val annotated = remember(text, default) { PobText.annotate(text, default) }
    Text(
        annotated,
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}
