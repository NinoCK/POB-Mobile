package io.room.poe2tree.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.LruCache
import io.room.poe2tree.tree.SpriteInfo
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One atlas page holding every node sprite scaled to [level] pixels. */
class SpriteAtlas(val level: Int, val bitmap: Bitmap, private val rects: Map<String, RectF>) {
    fun rect(name: String?): RectF? = name?.let { rects[it] }
}

/**
 * Loads tree sprites from assets/tree.
 *
 * - Node art is packed into small atlases (loaded up front) for batched drawing when zoomed out.
 * - Small sprites (full resolution icons, frames, connector art) are loaded on demand and kept.
 * - Large sprites (class / ascendancy art, mastery effects) are loaded at a resolution matching
 *   how big they appear on screen, and kept in an LRU cache.
 */
class SpriteCache(
    private val assets: AssetManager,
    private val sprites: Map<String, SpriteInfo>,
    private val onLoaded: () -> Unit,
) {
    private val small = ConcurrentHashMap<String, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)

    /** Large bitmaps keyed by "file@sample". */
    private val large = object : LruCache<String, Bitmap>(LARGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Atlases, smallest level first. Empty if the atlas files are missing. */
    var atlases: List<SpriteAtlas> = emptyList()
        private set

    /** Loads the atlases synchronously (call from a background thread). */
    fun loadAtlases() {
        val json = try {
            assets.open("tree/atlas.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            return
        }
        val levels = json.getJSONObject("levels")
        val out = ArrayList<SpriteAtlas>()
        for (key in levels.keys()) {
            val lv = levels.getJSONObject(key)
            val bmp = decode(lv.getString("file"), 1, mipmap = false) ?: continue
            val rectsJson = lv.getJSONObject("rects")
            val rects = HashMap<String, RectF>(rectsJson.length() * 2)
            for (name in rectsJson.keys()) {
                val r = rectsJson.getJSONArray(name)
                val x = r.getInt(0).toFloat()
                val y = r.getInt(1).toFloat()
                rects[name] = RectF(x, y, x + r.getInt(2), y + r.getInt(3))
            }
            out += SpriteAtlas(key.toInt(), bmp, rects)
        }
        atlases = out.sortedBy { it.level }
    }

    /** Preloads the given small sprites in the background (e.g. connector art). */
    fun preload(names: Collection<String>) {
        val files = names.mapNotNull { sprites[it] }.filter { !isLarge(it) }.map { it.file }.distinct()
        executor.execute {
            for (file in files) {
                if (!small.containsKey(file)) decode(file, 1)?.let { small[file] = it }
            }
            onLoaded()
        }
    }

    fun info(name: String?): SpriteInfo? = name?.let { sprites[it] }

    /** Is the full-resolution version of a small sprite loaded? Requests it if not. */
    fun isReady(name: String?): Boolean {
        val info = name?.let { sprites[it] } ?: return true
        if (isLarge(info)) return true
        if (small.containsKey(info.file)) return true
        request(info.file, 1, small = true)
        return false
    }

    /**
     * Returns a bitmap for the sprite, or null if it is not loaded yet (loading is then started).
     * [screenPx] is the approximate on-screen size of the sprite, used to choose the resolution of
     * large sprites; without it they are loaded at full resolution.
     */
    fun get(name: String?, screenPx: Float = Float.MAX_VALUE): Bitmap? {
        val info = name?.let { sprites[it] } ?: return null
        if (!isLarge(info)) {
            small[info.file]?.let { return it }
            request(info.file, 1, small = true)
            return null
        }
        val want = sampleFor(info, screenPx)
        large.get(key(info.file, want))?.let { return it }
        request(info.file, want, small = false)
        // Fall back to any other resolution we already have
        var s = want * 2
        while (s <= 16) {
            large.get(key(info.file, s))?.let { return it }
            s *= 2
        }
        s = want / 2
        while (s >= 1) {
            large.get(key(info.file, s))?.let { return it }
            s /= 2
        }
        return null
    }

    private fun request(file: String, sample: Int, small: Boolean) {
        val k = key(file, sample)
        if (!pending.add(k)) return
        executor.execute {
            try {
                val bmp = decode(file, sample)
                if (bmp != null) {
                    if (small) this.small[file] = bmp else large.put(k, bmp)
                    onLoaded()
                }
            } finally {
                pending.remove(k)
            }
        }
    }

    private fun decode(file: String, sample: Int, mipmap: Boolean = true): Bitmap? = try {
        assets.open("tree/$file").use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })?.apply {
                // Node art is often drawn scaled down; mipmaps avoid shimmering
                setHasMipMap(mipmap)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun sampleFor(info: SpriteInfo, screenPx: Float): Int {
        val stored = storedSize(info)
        var sample = 1
        while (sample < 16 && stored / (sample * 2) >= screenPx * 0.9f) sample *= 2
        return sample
    }

    private fun storedSize(info: SpriteInfo) = maxOf(info.storedWidth, info.storedHeight)

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val LARGE_CACHE_BYTES = 64 * 1024 * 1024

        fun isLarge(info: SpriteInfo) = info.width * info.height > 300 * 300

        private fun key(file: String, sample: Int) = "$file@$sample"
    }
}
