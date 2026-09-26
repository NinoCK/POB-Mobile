package io.room.poe2tree.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object PoeColors {
    val Gold = Color(0xFFC8A45A)
    val GoldBright = Color(0xFFE8C77E)
    val Background = Color(0xFF0B0A08)
    val Surface = Color(0xFF17140F)
    val SurfaceHigh = Color(0xFF221E17)
    val Outline = Color(0xFF4A4232)
    val Text = Color(0xFFE6DFCF)
    val TextDim = Color(0xFF9A927F)

    /** PoB tooltip colours */
    val Magic = Color(0xFF8888FF)
    val Normal = Color(0xFFC8C8C8)
    val Unique = Color(0xFFAF6025)
    val Notable = Color(0xFFE0C27A)
    val Keystone = Color(0xFFFFD27A)
    val Ascendancy = Color(0xFF7FC3E0)
    val Negative = Color(0xFFDD0022)
    val Positive = Color(0xFF33FF77)
    val Tip = Color(0xFF80A080)
    val Str = Color(0xFFE05030)
    val Dex = Color(0xFF70FF70)
    val Int = Color(0xFF7070FF)
}

private val scheme = darkColorScheme(
    primary = PoeColors.Gold,
    onPrimary = Color(0xFF1B1407),
    primaryContainer = Color(0xFF3A2F18),
    onPrimaryContainer = PoeColors.GoldBright,
    secondary = Color(0xFF9FB6C8),
    background = PoeColors.Background,
    onBackground = PoeColors.Text,
    surface = PoeColors.Surface,
    onSurface = PoeColors.Text,
    surfaceVariant = PoeColors.SurfaceHigh,
    onSurfaceVariant = PoeColors.TextDim,
    surfaceContainer = PoeColors.Surface,
    surfaceContainerHigh = PoeColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF2A251C),
    surfaceContainerLow = PoeColors.Surface,
    outline = PoeColors.Outline,
    outlineVariant = Color(0xFF332D22),
    error = Color(0xFFFF6B6B),
)

@Composable
fun PoeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A few Material icons, defined inline to avoid the large icons dependency. */
object AppIcons {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        .build()

    val Search = icon("search", "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z")
    val Undo = icon("undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Redo = icon("redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z")
    val More = icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
    val Close = icon("close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val DropDown = icon("dropdown", "M7,10l5,5 5,-5z")
    val Up = icon("up", "M7.41,15.41L12,10.83l4.59,4.58L18,14l-6,-6 -6,6z")
    val Down = icon("down", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z")
    val Locate = icon("locate", "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94V1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11H1v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94V23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94H23v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z")
    val Plus = icon("plus", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Minus = icon("minus", "M19,13H5v-2h14v2z")
    val ZoomOut = icon("zoomout","M15,3l2.3,2.3 -2.89,2.87 1.42,1.42L18.7,6.7 21,9V3zM3,9l2.3,-2.3 2.87,2.89 1.42,-1.42L6.7,5.3 9,3H3zM9,21l-2.3,-2.3 2.89,-2.87 -1.42,-1.42L5.3,17.3 3,15v6zM21,15l-2.3,2.3 -2.87,-2.89 -1.42,1.42 2.89,2.87L15,21h6z")
}
