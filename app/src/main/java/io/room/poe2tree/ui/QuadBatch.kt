package io.room.poe2tree.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader

/**
 * Collects textured, tinted quads that share one texture and draws them with a single
 * [Canvas.drawVertices] call. Vertex colours are multiplied with the texture, which gives
 * the same result as PoB's SetDrawColor tinting.
 *
 * Hardware-accelerated drawVertices needs API 29; callers fall back to per-sprite drawing below that.
 */
class QuadBatch(val bitmap: Bitmap, tileX: Shader.TileMode = Shader.TileMode.CLAMP, tileY: Shader.TileMode = Shader.TileMode.CLAMP) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        shader = BitmapShader(bitmap, tileX, tileY)
    }
    private var verts = FloatArray(INITIAL_QUADS * 8)
    private var texs = FloatArray(INITIAL_QUADS * 8)
    // Sized per float rather than per vertex: some platform versions validate it against vertexCount
    private var colors = IntArray(INITIAL_QUADS * 8)
    private var quads = 0

    val isEmpty get() = quads == 0

    /** Axis-aligned quad. Texture coordinates are in bitmap pixels. */
    fun addRect(
        canvas: Canvas,
        x0: Float, y0: Float, x1: Float, y1: Float,
        u0: Float, v0: Float, u1: Float, v1: Float,
        color: Int,
    ) {
        val base = reserve(canvas)
        val v = verts
        v[base] = x0; v[base + 1] = y0
        v[base + 2] = x1; v[base + 3] = y0
        v[base + 4] = x1; v[base + 5] = y1
        v[base + 6] = x0; v[base + 7] = y1
        val t = texs
        t[base] = u0; t[base + 1] = v0
        t[base + 2] = u1; t[base + 3] = v0
        t[base + 4] = u1; t[base + 5] = v1
        t[base + 6] = u0; t[base + 7] = v1
        fillColor(base, color)
    }

    /** Arbitrary quad: 4 corners (x,y) in [pos] and matching texture coordinates in [tex] (bitmap pixels). */
    fun addQuad(canvas: Canvas, pos: FloatArray, tex: FloatArray, color: Int) {
        val base = reserve(canvas)
        System.arraycopy(pos, 0, verts, base, 8)
        System.arraycopy(tex, 0, texs, base, 8)
        fillColor(base, color)
    }

    private fun fillColor(base: Int, color: Int) {
        val c = base / 2
        colors[c] = color; colors[c + 1] = color; colors[c + 2] = color; colors[c + 3] = color
    }

    private fun reserve(canvas: Canvas): Int {
        if (quads >= MAX_QUADS) flush(canvas)
        val needed = (quads + 1) * 8
        if (needed > verts.size) {
            val size = minOf(verts.size * 2, MAX_QUADS * 8)
            verts = verts.copyOf(size)
            texs = texs.copyOf(size)
            colors = colors.copyOf(size)
        }
        return quads++ * 8
    }

    fun flush(canvas: Canvas) {
        if (quads == 0) return
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES,
            quads * 8, verts, 0,
            texs, 0,
            colors, 0,
            INDICES, 0, quads * 6,
            paint,
        )
        quads = 0
    }

    companion object {
        private const val INITIAL_QUADS = 256
        /** Indices are 16-bit: at most 32768 vertices per call. */
        private const val MAX_QUADS = 8000

        private val INDICES = ShortArray(MAX_QUADS * 6).also { idx ->
            for (q in 0 until MAX_QUADS) {
                val v = q * 4
                val i = q * 6
                idx[i] = v.toShort(); idx[i + 1] = (v + 1).toShort(); idx[i + 2] = (v + 2).toShort()
                idx[i + 3] = v.toShort(); idx[i + 4] = (v + 2).toShort(); idx[i + 5] = (v + 3).toShort()
            }
        }
    }
}
