package io.room.poe2tree.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * The game's icons of item bases, uniques, gems and runes (assets/icons, from
 * tools/build_icons.py), looked up by name.
 */
object GameIcons {
    private class Index(val bases: Map<String, String>, val uniques: Map<String, String>, val gems: Map<String, String>, val runes: Map<String, String>)

    @Volatile
    private var index: Index? = null

    /** Icon file of each looked-up key ("" when there is none). */
    private val paths = ConcurrentHashMap<String, String>()

    private val bitmaps = object : LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Names as the build script folds them: poe2db writes "Mórrigan's Insight", PoB "Morrigan's Insight". */
    fun fold(name: String): String =
        Normalizer.normalize(name.replace('’', '\''), Normalizer.Form.NFKD).filter { it.code < 128 }.lowercase().trim()

    fun itemKey(title: String?, base: String?, rarity: String?) = "item:${rarity.orEmpty()}:${title.orEmpty()}:${base.orEmpty()}"
    fun gemKey(name: String) = "gem:$name"
    fun runeKey(name: String) = "rune:$name"

    /** The bitmap for a key if it is already loaded. */
    fun cached(key: String): ImageBitmap? = paths[key]?.takeIf { it.isNotEmpty() }?.let { bitmaps.get(it) }

    /** Loads the bitmap for a key (call off the main thread); null when there is no icon. */
    fun load(assets: AssetManager, key: String): ImageBitmap? {
        val path = paths.getOrPut(key) { resolve(index(assets), key).orEmpty() }
        if (path.isEmpty()) return null
        bitmaps.get(path)?.let { return it }
        val bitmap = try {
            assets.open("icons/$path").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
        return bitmap?.also { bitmaps.put(path, it) }
    }

    private fun resolve(index: Index, key: String): String? {
        if (key.startsWith("gem:")) return index.gems[fold(key.removePrefix("gem:"))]
        if (key.startsWith("rune:")) return index.runes[fold(key.removePrefix("rune:"))]
        val (_, rarity, title, base) = key.split(':', limit = 4)
        // A unique's own art, else its base's (and for rares, magic and normal items)
        val unique = if (rarity == "UNIQUE" || rarity == "RELIC") index.uniques[fold(title)] else null
        return unique ?: index.bases[fold(base)]
    }

    private fun index(assets: AssetManager): Index = index ?: synchronized(this) {
        index ?: try {
            val json = assets.open("icons/index.json").bufferedReader().use { JSONObject(it.readText()) }
            fun map(name: String): Map<String, String> = json.optJSONObject(name)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
            Index(map("bases"), map("uniques"), map("gems"), map("runes"))
        } catch (e: Exception) {
            Index(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }.also { index = it }
    }
}

/** The bitmap for an icon key, loaded in the background. */
@Composable
fun rememberIcon(key: String): ImageBitmap? {
    val assets = LocalContext.current.assets
    val bitmap by produceState(GameIcons.cached(key), key) {
        if (value == null) value = withContext(Dispatchers.IO) { GameIcons.load(assets, key) }
    }
    return bitmap
}

@Composable
private fun IconBox(bitmap: ImageBitmap?, size: Dp, modifier: Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
        }
    }
}

/** An item's art fitted in a [size] square: the unique's own art, else its base's (empty when there is none). */
@Composable
fun ItemIcon(title: String?, base: String?, rarity: String?, size: Dp, modifier: Modifier = Modifier) {
    IconBox(rememberIcon(GameIcons.itemKey(title, base, rarity)), size, modifier)
}

/** A gem's (or granted skill's) icon fitted in a [size] square. */
@Composable
fun GemIcon(name: String, size: Dp, modifier: Modifier = Modifier) {
    IconBox(rememberIcon(GameIcons.gemKey(name)), size, modifier)
}

/** A rune's (or soul core's, ...) icon fitted in a [size] square. */
@Composable
fun RuneIcon(name: String, size: Dp, modifier: Modifier = Modifier) {
    IconBox(rememberIcon(GameIcons.runeKey(name)), size, modifier)
}

/** An item's art at its own proportions, as in the inventory (a 1×1 item is 36 dp, a 2×4 one 80 dp tall). */
@Composable
fun ItemArt(title: String?, base: String?, rarity: String?, modifier: Modifier = Modifier) {
    val bitmap = rememberIcon(GameIcons.itemKey(title, base, rarity)) ?: return
    Image(
        bitmap, contentDescription = null,
        modifier.size((bitmap.width / 3f).dp, (bitmap.height / 3f).dp),
        filterQuality = FilterQuality.Medium,
    )
}
