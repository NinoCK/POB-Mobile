package io.room.poe2tree.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import io.room.poe2tree.tree.Connector
import io.room.poe2tree.tree.NodeState
import io.room.poe2tree.tree.NodeType
import io.room.poe2tree.tree.PassiveSpec
import io.room.poe2tree.tree.PassiveTree
import io.room.poe2tree.tree.SocketJewel
import io.room.poe2tree.tree.TreeJewels
import kotlin.math.atan2
import kotlin.math.max

/** Screen transform: screen = tree * scale + offset. */
data class Camera(val offsetX: Float, val offsetY: Float, val scale: Float) {
    fun toTreeX(sx: Float) = (sx - offsetX) / scale
    fun toTreeY(sy: Float) = (sy - offsetY) / scale
    fun toScreenX(tx: Float) = tx * scale + offsetX
    fun toScreenY(ty: Float) = ty * scale + offsetY
}

/** Everything interactive the renderer needs besides the spec. */
class RenderState(
    val camera: Camera,
    val selected: Int,
    /** Nodes on the preview path of the selected node. */
    val hoverPath: BooleanArray?,
    /** Nodes that would be removed if the selected (allocated) node is deallocated. */
    val hoverDep: BooleanArray?,
    val searchMatches: BooleanArray?,
    /** Heat map colour of each node's art (unallocated nodes), or null. */
    val heat: IntArray? = null,
)

/**
 * Draws the passive tree onto an Android canvas, following PoB's PassiveTreeView draw order:
 * background, class art, ascendancy art, mastery art, connectors, nodes, search highlights.
 */
class TreeRenderer(private val tree: PassiveTree, private val sprites: SpriteCache) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shaderPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()

    private val filters = HashMap<Int, ColorFilter>()
    private fun tint(color: Int): ColorFilter? =
        if (color == WHITE) null else filters.getOrPut(color) { LightingColorFilter(color and 0xFFFFFF, 0) }

    /** Batched drawing with drawVertices needs hardware support from API 29. */
    private val batching = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Connector art at decreasing resolutions (manual mipmaps: shaders don't use bitmap mipmaps). */
    private val connectorMips = HashMap<Bitmap, Array<Bitmap?>>()
    private val connectorBatches = LinkedHashMap<Bitmap, QuadBatch>()
    private val atlasBatches = HashMap<SpriteAtlas, QuadBatch>()
    private val texTmp = FloatArray(8)
    private val quadTmp = FloatArray(8)
    private var meshTmp = FloatArray(256)
    private val polySrc = FloatArray(6)

    /** Shaders per bitmap, with the tile mode appropriate for the connector kind. */
    private val shaders = HashMap<Bitmap, BitmapShader>()

    /** Cached straight connector paths (fallback renderer). */
    private val connectorPaths = HashMap<Long, Path>()

    private var backgroundShader: BitmapShader? = null
    private var backgroundBitmap: Bitmap? = null

    fun draw(canvas: Canvas, width: Int, height: Int, spec: PassiveSpec, state: RenderState) {
        val cam = state.camera
        fillPaint.color = Color.rgb(8, 8, 10)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        drawBackgroundTile(canvas, width, height)

        // Visible tree-space rectangle (with margin for large art)
        val vx0 = cam.toTreeX(0f)
        val vy0 = cam.toTreeY(0f)
        val vx1 = cam.toTreeX(width.toFloat())
        val vy1 = cam.toTreeY(height.toFloat())

        canvas.save()
        canvas.translate(cam.offsetX, cam.offsetY)
        canvas.scale(cam.scale, cam.scale)

        drawClassArt(canvas, spec, cam)
        drawAscendancyArt(canvas, spec, cam, vx0, vy0, vx1, vy1)

        val detailed = cam.scale >= DETAIL_SCALE
        if (detailed) {
            drawMasteryArt(canvas, spec, state, vx0, vy0, vx1, vy1)
            drawConnectors(canvas, spec, state, vx0, vy0, vx1, vy1)
            drawNodes(canvas, spec, state, vx0, vy0, vx1, vy1)
        } else {
            drawSimplified(canvas, spec, state, vx0, vy0, vx1, vy1)
        }
        drawJewelRadii(canvas, spec, state)
        drawHighlights(canvas, spec, state, vx0, vy0, vx1, vy1)
        canvas.restore()
    }

    // ---------------------------------------------------------------------------------------

    private fun drawBackgroundTile(canvas: Canvas, width: Int, height: Int) {
        val bmp = sprites.get("Background2", 512f) ?: return
        if (bmp !== backgroundBitmap) {
            backgroundBitmap = bmp
            backgroundShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                val m = Matrix()
                val s = 1f // drawn 1:1 so the tile stays seamless and sharp
                m.setScale(s, s)
                setLocalMatrix(m)
            }
        }
        shaderPaint.shader = backgroundShader
        shaderPaint.colorFilter = null
        shaderPaint.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        shaderPaint.shader = null
    }

    private fun drawSprite(canvas: Canvas, name: String?, x: Float, y: Float, halfW: Float, halfH: Float, scale: Float, color: Int = WHITE, alpha: Int = 255) {
        if (name == null || halfW <= 0f) return
        val bmp = sprites.get(name, halfW * 2 * scale) ?: return
        rect.set(x - halfW, y - halfH, x + halfW, y + halfH)
        bitmapPaint.colorFilter = tint(color)
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(bmp, null, rect, bitmapPaint)
    }

    private fun drawClassArt(canvas: Canvas, spec: PassiveSpec, cam: Camera) {
        val cls = spec.currentClass
        val bg = cls.background ?: return
        val asc = spec.currentAscendancy
        val image = asc?.background?.image ?: bg.image
        drawSprite(canvas, image, bg.x, bg.y, bg.width, bg.height, cam.scale)

        // Glow pointing at the class start, then the centre frame
        val start = cls.startNodeIdx.takeIf { it >= 0 }?.let { tree.nodes[it] }
        if (start != null && bg.activeWidth > 0f) {
            val angle = Math.toDegrees(Math.PI / 2 + atan2((start.y - bg.y).toDouble(), (start.x - bg.x).toDouble())).toFloat()
            canvas.save()
            canvas.rotate(angle, bg.x, bg.y)
            drawSprite(canvas, "BGTreeActive", bg.x, bg.y, bg.activeWidth, bg.activeHeight, cam.scale)
            canvas.restore()
        }
        if (bg.bgWidth > 0f) drawSprite(canvas, "BGTree", bg.x, bg.y, bg.bgWidth, bg.bgHeight, cam.scale)
    }

    private fun drawAscendancyArt(canvas: Canvas, spec: PassiveSpec, cam: Camera, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val current = spec.currentAscendancy
        for (cls in tree.classes) for (asc in cls.ascendancies) {
            val bg = asc.background ?: continue
            if (asc.replaceBy != null && asc.replaceBy == current?.id) continue
            if (asc.replace != null && asc.id != current?.id) continue
            if (bg.x + bg.width < vx0 || bg.x - bg.width > vx1 || bg.y + bg.height < vy0 || bg.y - bg.height > vy1) continue
            val color = if (asc.id == current?.id) WHITE else HALF_GRAY
            drawSprite(canvas, bg.image, bg.x, bg.y, bg.width, bg.height, cam.scale, color)
        }
    }

    /** Allocated, or allocated by an item (PoB draws both as allocated). */
    private fun drawnAlloc(spec: PassiveSpec, i: Int) = spec.alloc[i] || spec.jewels.isGranted(i)

    private fun unlockMet(spec: PassiveSpec, idx: Int): Boolean {
        val c = tree.nodes[idx].unlockIdx ?: return true
        return c.all { spec.alloc[it] }
    }

    /** Mastery group art and notable glow effects (PoB layer 15, under connectors). */
    private fun drawMasteryArt(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val scale = state.camera.scale
        for (node in tree.nodes) {
            val image = node.activeEffectImage ?: continue
            val size = if (node.type == NodeType.OnlyImage) node.targetSize.base else node.targetSize.effect
            if (size <= 0f) continue
            if (node.x + size < vx0 || node.x - size > vx1 || node.y + size < vy0 || node.y - size > vy1) continue
            if (!unlockMet(spec, node.idx)) continue
            val lit = node.type != NodeType.OnlyImage &&
                (drawnAlloc(spec, node.idx) || state.hoverPath?.get(node.idx) == true)
            drawSprite(canvas, image, node.x, node.y, size, size, scale, alpha = if (lit) 255 else 38)
        }
    }

    // ---- Connectors ----

    private fun willChangeAllocMode(spec: PassiveSpec, state: RenderState, i: Int): Boolean {
        val sel = state.selected
        val hp = state.hoverPath ?: return false
        return sel >= 0 && hp[i] && !spec.alloc[sel] && spec.currentAllocMode == 0 && spec.allocMode[i] > 0
    }

    private fun isHoverPathEndpoint(spec: PassiveSpec, state: RenderState, i: Int): Boolean {
        if (i == state.selected || state.hoverPath?.get(i) == true) return true
        if (spec.alloc[i]) {
            val mode = spec.allocMode[i]
            return mode == 0 || mode == spec.currentAllocMode
        }
        return false
    }

    /** 0 = Normal, 1 = Intermediate, 2 = Active (PoB getState). */
    private fun connectorState(spec: PassiveSpec, state: RenderState, a: Int, b: Int): Int {
        val hp = state.hoverPath
        val m1 = spec.allocMode[a]
        val m2 = spec.allocMode[b]
        if (hp != null && isHoverPathEndpoint(spec, state, a) && isHoverPathEndpoint(spec, state, b) && hp[a] && hp[b] &&
            (willChangeAllocMode(spec, state, a) || willChangeAllocMode(spec, state, b))
        ) return 1
        if (drawnAlloc(spec, a) && drawnAlloc(spec, b) && (m1 == 0 || m2 == 0 || m1 == m2)) return 2
        if (hp != null && isHoverPathEndpoint(spec, state, a) && isHoverPathEndpoint(spec, state, b) &&
            (!spec.alloc[a] || !spec.alloc[b] || (hp[a] && hp[b]))
        ) return 1
        return 0
    }

    private fun connectorColor(spec: PassiveSpec, state: RenderState, c: Connector, st: Int): Int {
        var color = WHITE
        val a = c.node1
        val b = c.node2
        if (st == 1 && spec.currentAllocMode > 0 && c.ascendancyName == null) {
            color = if (spec.currentAllocMode == 1) WS1 else WS2
        }
        if (st == 2 && c.ascendancyName == null) {
            val mode = spec.allocMode[a].takeIf { it != 0 } ?: spec.allocMode[b]
            if (mode == 1) color = WS1 else if (mode == 2) color = WS2
        }
        val dep = state.hoverDep
        if (dep != null && dep[a] && dep[b]) {
            color = RED
        } else if (c.ascendancyName != null && !isCurrentAscendancy(spec, c.ascendancyName)) {
            color = INACTIVE_GRAY
        }
        return color
    }

    private fun isCurrentAscendancy(spec: PassiveSpec, name: String): Boolean {
        val asc = spec.currentAscendancy ?: return false
        return name == asc.id || name == asc.replace
    }

    private fun drawConnectors(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        if (batching) drawConnectorsBatched(canvas, spec, state, vx0, vy0, vx1, vy1)
        else drawConnectorsPaths(canvas, spec, state, vx0, vy0, vx1, vy1)
    }

    /** Pick a pre-shrunk copy of the connector art so it is drawn at most ~1.6x smaller than stored. */
    private fun connectorMip(bmp: Bitmap, scale: Float): Bitmap {
        var level = 0
        var s = scale
        while (s < 0.6f && level < MAX_CONNECTOR_MIP) {
            s *= 2f
            level++
        }
        if (level == 0) return bmp
        val chain = connectorMips.getOrPut(bmp) { arrayOfNulls<Bitmap>(MAX_CONNECTOR_MIP + 1).also { it[0] = bmp } }
        for (l in 1..level) {
            if (chain[l] == null) {
                val prev = chain[l - 1]!!
                // Exact halving with filtering = 2x2 box average, like a GPU mipmap
                chain[l] = Bitmap.createScaledBitmap(prev, max(1, prev.width / 2), max(1, prev.height / 2), true)
            }
        }
        return chain[level]!!
    }

    private fun drawConnectorsBatched(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val scale = state.camera.scale
        for (c in tree.connectors) {
            if (c.maxX < vx0 || c.minX > vx1 || c.maxY < vy0 || c.minY > vy1) continue
            if (!unlockMet(spec, c.node1) || !unlockMet(spec, c.node2)) continue
            val st = connectorState(spec, state, c.node1, c.node2)
            val full = sprites.get(c.assetName(st)) ?: continue
            val bmp = connectorMip(full, scale)
            // All connector art is the line texture: repeat along the connector, clamp across it
            val batch = connectorBatches.getOrPut(bmp) { QuadBatch(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP) }
            val color = connectorColor(spec, state, c, st)
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val rib = c.ribbon
            if (rib == null) {
                val t = c.tex
                for (k in 0 until 8 step 2) {
                    texTmp[k] = t[k] * w
                    texTmp[k + 1] = t[k + 1] * h
                }
                batch.addQuad(canvas, c.verts, texTmp, color)
            } else {
                // Arc: one quad per step of the ribbon (inner k, outer k, outer k+1, inner k+1)
                val u = c.ribbonU!!
                for (k in 0 until u.size - 1) {
                    val a = k * 4
                    val b = a + 4
                    quadTmp[0] = rib[a]; quadTmp[1] = rib[a + 1]
                    quadTmp[2] = rib[a + 2]; quadTmp[3] = rib[a + 3]
                    quadTmp[4] = rib[b + 2]; quadTmp[5] = rib[b + 3]
                    quadTmp[6] = rib[b]; quadTmp[7] = rib[b + 1]
                    val u0 = u[k] * w
                    val u1 = u[k + 1] * w
                    texTmp[0] = u0; texTmp[1] = h
                    texTmp[2] = u0; texTmp[3] = 0f
                    texTmp[4] = u1; texTmp[5] = 0f
                    texTmp[6] = u1; texTmp[7] = h
                    batch.addQuad(canvas, quadTmp, texTmp, color)
                }
            }
        }
        for (batch in connectorBatches.values) batch.flush(canvas)
    }

    /** Fallback without drawVertices (API < 29): shader-filled quads for lines, bitmap meshes for arcs. */
    private fun drawConnectorsPaths(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        for ((ci, c) in tree.connectors.withIndex()) {
            if (c.maxX < vx0 || c.minX > vx1 || c.maxY < vy0 || c.minY > vy1) continue
            if (!unlockMet(spec, c.node1) || !unlockMet(spec, c.node2)) continue
            val st = connectorState(spec, state, c.node1, c.node2)
            val bmp = sprites.get(c.assetName(st)) ?: continue
            val color = connectorColor(spec, state, c, st)
            val rib = c.ribbon
            if (rib != null) {
                drawArcMesh(canvas, rib, bmp, color)
                continue
            }
            val shader = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP) }
            val v = c.verts
            val t = c.tex
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            // Map three quad corners from texture pixels to tree space
            polySrc[0] = t[0] * w; polySrc[1] = t[1] * h
            polySrc[2] = t[2] * w; polySrc[3] = t[3] * h
            polySrc[4] = t[4] * w; polySrc[5] = t[5] * h
            if (!matrix.setPolyToPoly(polySrc, 0, v, 0, 3)) continue
            shader.setLocalMatrix(matrix)
            val path = connectorPaths.getOrPut(ci.toLong()) {
                Path().apply {
                    moveTo(v[0], v[1]); lineTo(v[2], v[3]); lineTo(v[4], v[5]); lineTo(v[6], v[7]); close()
                }
            }
            shaderPaint.shader = shader
            shaderPaint.colorFilter = tint(color)
            shaderPaint.alpha = 255
            canvas.drawPath(path, shaderPaint)
        }
        shaderPaint.shader = null
    }

    /** Arc ribbon as a 1-row bitmap mesh: the line art is stretched along the arc (it is uniform lengthwise). */
    private fun drawArcMesh(canvas: Canvas, rib: FloatArray, bmp: Bitmap, color: Int) {
        val points = rib.size / 4
        if (meshTmp.size < points * 4) meshTmp = FloatArray(points * 4)
        for (k in 0 until points) {
            // Row 0 (top of the bitmap) = outer edge, row 1 = inner edge
            meshTmp[k * 2] = rib[k * 4 + 2]
            meshTmp[k * 2 + 1] = rib[k * 4 + 3]
            meshTmp[(points + k) * 2] = rib[k * 4]
            meshTmp[(points + k) * 2 + 1] = rib[k * 4 + 1]
        }
        bitmapPaint.colorFilter = tint(color)
        bitmapPaint.alpha = 255
        canvas.drawBitmapMesh(bmp, points - 1, 1, meshTmp, 0, null, 0, bitmapPaint)
    }

    // ---- Nodes ----

    /** Atlas to use for a sprite drawn at [px] screen pixels, or null to draw the full-resolution sprite. */
    private fun atlasFor(px: Float): SpriteAtlas? {
        if (!batching) return null
        for (a in sprites.atlases) if (px <= a.level * ATLAS_MAGNIFY) return a
        return null
    }

    private fun batchSprite(canvas: Canvas, atlas: SpriteAtlas, name: String?, x: Float, y: Float, half: Float, color: Int) {
        val r = atlas.rect(name) ?: return
        atlasBatches.getOrPut(atlas) { QuadBatch(atlas.bitmap) }
            .addRect(canvas, x - half, y - half, x + half, y + half, r.left, r.top, r.right, r.bottom, color)
    }

    /**
     * Draws a node's icon and frame. Small nodes go into atlas batches; large ones are drawn
     * from full-resolution bitmaps once those are loaded.
     */
    private fun drawNodeArt(
        canvas: Canvas, scale: Float, x: Float, y: Float,
        icon: String?, iconHalf: Float, iconColor: Int,
        frame: String?, frameHalf: Float, frameColor: Int,
    ) {
        val largest = max(if (icon != null) iconHalf else 0f, if (frame != null) frameHalf else 0f)
        var atlas = atlasFor(largest * 2 * scale)
        if (atlas == null && batching && (!sprites.isReady(icon) || !sprites.isReady(frame))) {
            // Full-resolution art still loading: keep showing the atlas version meanwhile
            atlas = sprites.atlases.lastOrNull()
        }
        if (atlas != null) {
            if (icon != null) {
                val iconAtlas = atlasFor(iconHalf * 2 * scale) ?: atlas
                if (iconAtlas.rect(icon) != null) batchSprite(canvas, iconAtlas, icon, x, y, iconHalf, iconColor)
                else drawSprite(canvas, icon, x, y, iconHalf, iconHalf, scale, iconColor)
            }
            if (frame != null) batchSprite(canvas, atlas, frame, x, y, frameHalf, frameColor)
        } else {
            if (icon != null) drawSprite(canvas, icon, x, y, iconHalf, iconHalf, scale, iconColor)
            if (frame != null) drawSprite(canvas, frame, x, y, frameHalf, frameHalf, scale, frameColor)
        }
    }

    private fun drawNodes(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val scale = state.camera.scale
        val hp = state.hoverPath
        val dep = state.hoverDep
        val selected = state.selected
        val radiusTints = radiusTints(spec, selected)
        val socketArt = ArrayList<Int>()
        for (node in tree.nodes) {
            val i = node.idx
            if (node.type == NodeType.ClassStart || node.type == NodeType.OnlyImage) continue
            val r = max(node.targetSize.overlay, node.targetSize.base)
            if (node.x + r < vx0 || node.x - r > vx1 || node.y + r < vy0 || node.y - r > vy1) continue
            if (!unlockMet(spec, i)) continue
            val alloc = drawnAlloc(spec, i)
            val view = spec.views[i]

            if (node.type == NodeType.AscendClassStart) {
                drawNodeArt(canvas, scale, node.x, node.y, null, 0f, WHITE,
                    "AscendancyMiddle", node.targetSize.overlay, if (alloc) WHITE else HALF_GRAY)
                continue
            }

            val nodeState = when {
                alloc || i == selected -> NodeState.Alloc
                hp?.get(i) == true -> NodeState.Path
                else -> NodeState.Unalloc
            }

            if (node.type == NodeType.Socket || node.containJewelSocket) {
                val color = if (dep?.get(i) == true && i != selected) RED else WHITE
                drawNodeArt(canvas, scale, node.x, node.y, null, 0f, WHITE,
                    view.overlay?.forState(nodeState), node.targetSize.base, color)
                // The socketed jewel's art, drawn over the batched frames below
                if (alloc && spec.jewels.bySocket[i] != null) socketArt += i
                continue
            }

            val previewMode = if (!alloc && hp?.get(i) == true && spec.currentAllocMode > 0 && node.ascendancyName == null && !node.isGlobal)
                spec.currentAllocMode else spec.allocMode[i]
            var frameColor = when {
                previewMode == 1 && !willChangeAllocMode(spec, state, i) -> WS1
                previewMode == 2 && !willChangeAllocMode(spec, state, i) -> WS2
                else -> WHITE
            }
            if (selected >= 0 && selected != i && dep?.get(i) == true) frameColor = RED
            else if (radiusTints != null && radiusTints[i] != 0) frameColor = radiusTints[i]
            val heat = state.heat
            drawNodeArt(
                canvas, scale, node.x, node.y,
                view.icon, node.targetSize.base, if (alloc) WHITE else heat?.get(i) ?: HALF_GRAY,
                view.overlay?.forState(nodeState), node.targetSize.overlay, frameColor,
            )
        }
        for (atlas in sprites.atlases) atlasBatches[atlas]?.flush(canvas)
        for (i in socketArt) {
            val node = tree.nodes[i]
            val art = jewelArt(spec.jewels.bySocket[i]!!) ?: continue
            drawSprite(canvas, art, node.x, node.y, node.targetSize.overlay, node.targetSize.overlay, scale)
        }
    }

    // ---- Jewels (PoB PassiveTreeView: socket art, radius rings, radius of the hovered socket) ----

    /** Socket art of a jewel: a unique's own art when the tree has it, else its base type's. */
    private fun jewelArt(j: SocketJewel): String? =
        j.title?.takeIf { j.rarity == "UNIQUE" && tree.sprites.containsKey(it) } ?: j.baseName?.takeIf { tree.sprites.containsKey(it) }

    private var tintKey: Pair<Int, TreeJewels>? = null
    private var tintCache: IntArray? = null

    /**
     * With a jewel socket selected, the colour of the smallest jewel radius each node is in (rings
     * for a jewel with a ring radius, discs otherwise), like hovering a socket in PoB. Null otherwise.
     */
    private fun radiusTints(spec: PassiveSpec, selected: Int): IntArray? {
        if (selected < 0 || !isRadiusSocket(selected)) return null
        val jewels = spec.jewels
        val key = selected to jewels
        if (key == tintKey) return tintCache
        val radii = jewels.radii
        val variable = jewels.bySocket[selected]?.variable == true
        val sets = spec.radiusIndex.of(selected, radii)
        val out = IntArray(tree.nodes.size)
        for (i in out.indices) {
            for ((r, radius) in radii.withIndex()) {
                if ((radius.inner > 0f) == variable && sets[r][i]) {
                    out[i] = radius.color
                    break
                }
            }
        }
        tintKey = key
        tintCache = out
        return out
    }

    private fun isRadiusSocket(i: Int): Boolean {
        val node = tree.nodes[i]
        return node.type == NodeType.Socket && !node.containJewelSocket && node.name != "Charm Socket"
    }

    private fun drawRingPair(canvas: Canvas, first: String, second: String, x: Float, y: Float, half: Float, scale: Float, alpha: Int) {
        if (half <= 0f) return
        drawSprite(canvas, first, x, y, half, half, scale, alpha = alpha)
        drawSprite(canvas, second, x, y, half, half, scale, alpha = alpha)
    }

    private fun drawJewelRadii(canvas: Canvas, spec: PassiveSpec, state: RenderState) {
        val scale = state.camera.scale
        val jewels = spec.jewels
        for (sj in jewels.sockets) {
            val socket = tree.nodes[sj.socket]
            if (!spec.alloc[sj.socket] || !isRadiusSocket(sj.socket)) continue
            val radius = jewels.radius(sj.radiusIndex) ?: continue
            val outer = radius.outer
            val conqueror = sj.conqueror
            if (conqueror != null) {
                // Conquering jewels have their own circle art
                val (c1, c2) = if (conqueror == "abyss") {
                    val a = "art/textures/interface/2d/2dart/uiimages/ingame/abyss/abysspassiveskillscreenjewelcircle1.dds"
                    a to a
                } else {
                    val name = if (conqueror == "kalguur") "kalguuran" else conqueror
                    "art/textures/interface/2d/2dart/uiimages/ingame/passiveskillscreen${name}jewelcircle1.dds" to
                        "art/textures/interface/2d/2dart/uiimages/ingame/passiveskillscreen${name}jewelcircle2.dds"
                }
                drawRingPair(canvas, c1, c2, socket.x, socket.y, outer, scale, RING_ALPHA)
            } else if (sj.fromNothing.isNotEmpty()) {
                // From Nothing: the ring is around the keystone
                for (k in sj.fromNothing) {
                    val key = tree.nodes[k]
                    drawRingPair(canvas, "ShadedOuterRing", "ShadedOuterRingFlipped", key.x, key.y, outer, scale, RING_ALPHA)
                    drawRingPair(canvas, "ShadedInnerRing", "ShadedInnerRingFlipped", key.x, key.y, 150f, scale, RING_ALPHA)
                }
            } else {
                drawRingPair(canvas, "ShadedOuterRing", "ShadedOuterRingFlipped", socket.x, socket.y, outer, scale, RING_ALPHA)
                drawRingPair(canvas, "ShadedInnerRing", "ShadedInnerRingFlipped", socket.x, socket.y, radius.inner * 1.06f, scale, RING_ALPHA)
            }
        }
        // Passives allowing allocation near allocated keystones
        for (source in jewels.leapSources) {
            if (!spec.alloc[source.node] || source.from != "Keystone") continue
            val radius = jewels.radius(source.radiusIndex) ?: continue
            for (key in tree.nodes) {
                if (key.type != NodeType.Keystone || !spec.alloc[key.idx]) continue
                drawRingPair(canvas, "ShadedOuterRing", "ShadedOuterRingFlipped", key.x, key.y, radius.outer, scale, RING_ALPHA)
            }
        }
        // The radii of the selected socket, in PoB's colours
        val sel = state.selected
        if (sel >= 0 && isRadiusSocket(sel) && jewels.radii.isNotEmpty()) {
            val node = tree.nodes[sel]
            val variable = jewels.bySocket[sel]?.variable == true
            ringPaint.strokeWidth = 3f / scale
            for (radius in jewels.radii) {
                if ((radius.inner > 0f) != variable) continue
                ringPaint.color = radius.color
                canvas.drawCircle(node.x, node.y, radius.outer, ringPaint)
                if (radius.inner > 0f) canvas.drawCircle(node.x, node.y, radius.inner, ringPaint)
            }
        }
    }

    // ---- Zoomed-out view: simple shapes are much cheaper than thousands of bitmaps ----

    private fun drawSimplified(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val scale = state.camera.scale
        val px = 1f / scale
        linePaint.strokeWidth = 1.6f * px
        for (b in lineBuckets.values) b.size = 0
        val buckets = lineBuckets
        for (c in tree.connectors) {
            if (c.maxX < vx0 || c.minX > vx1 || c.maxY < vy0 || c.minY > vy1) continue
            if (!unlockMet(spec, c.node1) || !unlockMet(spec, c.node2)) continue
            val st = connectorState(spec, state, c.node1, c.node2)
            var color = when (st) {
                2 -> SIMPLE_ACTIVE
                1 -> SIMPLE_PATH
                else -> SIMPLE_NORMAL
            }
            val tinted = connectorColor(spec, state, c, st)
            if (tinted == WS1 || tinted == WS2 || tinted == RED) color = tinted
            else if (tinted == INACTIVE_GRAY) color = SIMPLE_INACTIVE
            val lines = lineBatch
            val rib = c.ribbon
            if (rib == null) {
                val a = tree.nodes[c.node1]
                val b = tree.nodes[c.node2]
                if (lines != null) addLine(canvas, lines, a.x, a.y, b.x, b.y, 1.7f * px, color)
                else buckets.getOrPut(color) { FloatArrayBuilder() }.add4(a.x, a.y, b.x, b.y)
            } else {
                // Follow the arc's centre line with a few segments
                val points = rib.size / 4
                val step = max(1, (points - 1) / SIMPLE_ARC_SEGMENTS)
                var k = 0
                while (k < points - 1) {
                    val n = minOf(k + step, points - 1)
                    val x0 = (rib[k * 4] + rib[k * 4 + 2]) / 2
                    val y0 = (rib[k * 4 + 1] + rib[k * 4 + 3]) / 2
                    val x1 = (rib[n * 4] + rib[n * 4 + 2]) / 2
                    val y1 = (rib[n * 4 + 1] + rib[n * 4 + 3]) / 2
                    if (lines != null) addLine(canvas, lines, x0, y0, x1, y1, 1.7f * px, color)
                    else buckets.getOrPut(color) { FloatArrayBuilder() }.add4(x0, y0, x1, y1)
                    k = n
                }
            }
        }
        lineBatch?.flush(canvas)
        for ((color, pts) in buckets) {
            if (pts.size == 0) continue
            linePaint.color = color
            canvas.drawLines(pts.data, 0, pts.size, linePaint)
        }

        val hp = state.hoverPath
        val dep = state.hoverDep
        for (node in tree.nodes) {
            val i = node.idx
            if (node.type == NodeType.ClassStart || node.type == NodeType.OnlyImage) continue
            if (node.x < vx0 || node.x > vx1 || node.y < vy0 || node.y > vy1) continue
            if (!unlockMet(spec, i)) continue
            val alloc = drawnAlloc(spec, i)
            val base = when (node.type) {
                NodeType.Keystone -> 4.5f
                NodeType.Notable, NodeType.Socket -> 3.2f
                NodeType.AscendClassStart -> 3.5f
                else -> 2.1f
            }
            var color = when {
                alloc -> when (spec.allocMode[i]) {
                    1 -> WS1
                    2 -> WS2
                    else -> SIMPLE_ACTIVE_NODE
                }
                hp?.get(i) == true -> SIMPLE_PATH
                node.type == NodeType.Keystone -> SIMPLE_KEYSTONE
                node.type == NodeType.Notable -> SIMPLE_NOTABLE
                node.type == NodeType.Socket -> SIMPLE_SOCKET
                else -> SIMPLE_NODE
            }
            if (dep?.get(i) == true && i != state.selected) color = RED
            if (node.ascendancyName != null && !isCurrentAscendancy(spec, node.ascendancyName) && !alloc) {
                color = SIMPLE_INACTIVE
            } else if (!alloc && state.heat != null && hp?.get(i) != true && node.type != NodeType.AscendClassStart) {
                color = state.heat[i]
            }
            val radius = max(base * px, node.targetSize.base * 0.8f)
            val dots = dotBatch
            if (dots != null) {
                val q = radius * DOT_TEX / (DOT_TEX - 2f)
                dots.addRect(canvas, node.x - q, node.y - q, node.x + q, node.y + q, 0f, 0f, DOT_TEX, DOT_TEX, color)
                continue
            }
            val key = (color.toLong() shl 32) or (radius.toRawBits().toLong() and 0xFFFFFFFFL)
            pointBuckets.getOrPut(key) { FloatArrayBuilder() }.add2(node.x, node.y)
        }
        dotBatch?.flush(canvas)
        pointPaint.strokeCap = Paint.Cap.ROUND
        for ((key, pts) in pointBuckets) {
            if (pts.size == 0) continue
            pointPaint.color = (key ushr 32).toInt()
            pointPaint.strokeWidth = Float.fromBits(key.toInt()) * 2f
            canvas.drawPoints(pts.data, 0, pts.size, pointPaint)
            pts.size = 0
        }
    }

    // ---- Selection ring and search results ----

    private fun drawHighlights(canvas: Canvas, spec: PassiveSpec, state: RenderState, vx0: Float, vy0: Float, vx1: Float, vy1: Float) {
        val px = 1f / state.camera.scale
        val matches = state.searchMatches
        if (matches != null) {
            ringPaint.color = SEARCH
            ringPaint.strokeWidth = 2.5f * px
            val rings = ringBatch
            for (node in tree.nodes) {
                if (!matches[node.idx]) continue
                if (node.x < vx0 || node.x > vx1 || node.y < vy0 || node.y > vy1) continue
                val r = max(node.hitRadius * 1.25f, 9f * px)
                if (rings != null) {
                    // Texture ring spans 8..(64-8) of a 128 px square: scale the quad so the ring lands on r
                    val q = r * 64f / 56f
                    rings.addRect(canvas, node.x - q, node.y - q, node.x + q, node.y + q, 0f, 0f, 128f, 128f, SEARCH)
                } else {
                    canvas.drawCircle(node.x, node.y, r, ringPaint)
                }
            }
            rings?.flush(canvas)
        }
        val sel = state.selected
        if (sel >= 0) {
            val node = tree.nodes[sel]
            ringPaint.color = SELECTED
            ringPaint.strokeWidth = 3f * px
            val r = max(node.hitRadius * 1.15f, 11f * px)
            canvas.drawCircle(node.x, node.y, r, ringPaint)
        }
    }

    private class FloatArrayBuilder {
        var data = FloatArray(256)
        var size = 0
        fun add4(a: Float, b: Float, c: Float, d: Float) {
            if (size + 4 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a; data[size++] = b; data[size++] = c; data[size++] = d
        }
        fun add2(a: Float, b: Float) {
            if (size + 2 > data.size) data = data.copyOf(data.size * 2)
            data[size++] = a; data[size++] = b
        }
    }

    private val lineBuckets = HashMap<Int, FloatArrayBuilder>()
    private val pointBuckets = HashMap<Long, FloatArrayBuilder>()
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Adds a line from (x0,y0) to (x1,y1) as a quad [halfWidth] wide on each side. */
    private fun addLine(canvas: Canvas, batch: QuadBatch, x0: Float, y0: Float, x1: Float, y1: Float, halfWidth: Float, color: Int) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len <= 0f) return
        val nx = -dy / len * halfWidth
        val ny = dx / len * halfWidth
        linePos[0] = x0 + nx; linePos[1] = y0 + ny
        linePos[2] = x1 + nx; linePos[3] = y1 + ny
        linePos[4] = x1 - nx; linePos[5] = y1 - ny
        linePos[6] = x0 - nx; linePos[7] = y0 - ny
        batch.addQuad(canvas, linePos, LINE_TEX_COORDS, color)
    }

    private val linePos = FloatArray(8)

    /** Soft-edged white line texture: opaque core, transparent edges (cheap anti-aliasing). */
    private val lineBatch: QuadBatch? by lazy {
        if (!batching) return@lazy null
        val bmp = Bitmap.createBitmap(4, LINE_TEX_H, Bitmap.Config.ARGB_8888)
        for (y in 0 until LINE_TEX_H) {
            val d = kotlin.math.abs((y + 0.5f) / LINE_TEX_H - 0.5f) * 2f
            val a = ((1f - d) * 2f).coerceIn(0f, 1f)
            val c = Color.argb((a * 255).toInt(), 255, 255, 255)
            for (x in 0 until 4) bmp.setPixel(x, y, c)
        }
        QuadBatch(bmp)
    }

    /** White anti-aliased disc, tinted per quad, for zoomed-out nodes. */
    private val dotBatch: QuadBatch? by lazy {
        if (!batching) return@lazy null
        val size = DOT_TEX.toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawCircle(size / 2f, size / 2f, size / 2f - 1f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        bmp.setHasMipMap(true)
        QuadBatch(bmp)
    }

    /** White ring texture, tinted per quad, for batched search / selection highlights. */
    private val ringBatch: QuadBatch? by lazy {
        if (!batching) return@lazy null
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            color = Color.WHITE
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f - 8f, p)
        bmp.setHasMipMap(true)
        QuadBatch(bmp)
    }

    companion object {
        /** Below this zoom (screen px per tree unit) the simplified renderer is used. */
        const val DETAIL_SCALE = 0.045f
        /** Atlas sprites may be drawn up to this much larger than their stored size. */
        private const val ATLAS_MAGNIFY = 1.25f
        private const val MAX_CONNECTOR_MIP = 6
        /** Segments per arc in the zoomed-out view. */
        private const val SIMPLE_ARC_SEGMENTS = 4
        private const val LINE_TEX_H = 16
        private const val DOT_TEX = 64f
        private val LINE_TEX_COORDS = floatArrayOf(2f, 0f, 2f, 0f, 2f, LINE_TEX_H.toFloat(), 2f, LINE_TEX_H.toFloat())

        const val WHITE = 0xFFFFFFFF.toInt()
        private const val HALF_GRAY = 0xFF808080.toInt()
        private const val INACTIVE_GRAY = 0xFFBFBFBF.toInt()
        const val RED = 0xFFFF0000.toInt()
        /** PoB colorCodes.NEGATIVE / POSITIVE used for weapon sets 1 / 2. */
        const val WS1 = 0xFFDD0022.toInt()
        const val WS2 = 0xFF33FF77.toInt()
        private const val SEARCH = 0xFFFF3030.toInt()
        /** PoB draws jewel radius rings at 70% opacity. */
        private const val RING_ALPHA = 179
        private const val SELECTED = 0xFFFFE08A.toInt()

        private const val SIMPLE_NORMAL = 0xFF4A4538.toInt()
        private const val SIMPLE_PATH = 0xFF8FB8FF.toInt()
        private const val SIMPLE_ACTIVE = 0xFFE3C27A.toInt()
        private const val SIMPLE_INACTIVE = 0xFF2E2C28.toInt()
        private const val SIMPLE_NODE = 0xFF6D6656.toInt()
        private const val SIMPLE_NOTABLE = 0xFF9C8A5E.toInt()
        private const val SIMPLE_KEYSTONE = 0xFFB59A62.toInt()
        private const val SIMPLE_SOCKET = 0xFF7F9CB0.toInt()
        private const val SIMPLE_ACTIVE_NODE = 0xFFF5D68C.toInt()
    }
}
