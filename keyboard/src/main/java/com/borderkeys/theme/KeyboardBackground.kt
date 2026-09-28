// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.borderkeys.data.theme.KeyboardTheme

/**
 * The surface the keys sit on: a colour or a gradient, a picture, and patterns drawn as repeating
 * tiles. The tiles are built when the theme changes, the gradient when the height changes.
 */
class KeyboardBackground {

    private val fill = Paint()

    /** One paint per pattern, drawn in order over one another. */
    private val patternPaints = ArrayList<Paint>(KeyboardTheme.PATTERN_COUNT)

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val imageSource = android.graphics.Rect()
    private val imageTarget = android.graphics.RectF()
    private var image: android.graphics.Bitmap? = null
    private var dim = 0f

    private var gradient: LinearGradient? = null

    /** What the current tiles and gradient were built from. */
    private var tilePatterns: List<Int> = emptyList()
    private var tileSize = 0
    private var tileColor = 0
    private var topColor = 0
    private var bottomColor = 0
    private var gradientHeight = 0f

    /** Re-reads the theme. */
    fun update(theme: KeyboardTheme, density: Float) {
        fill.color = theme.backgroundColor
        topColor = theme.backgroundColor
        bottomColor = theme.gradientEnd()
        // The gradient is rebuilt on the next draw.
        gradient = null
        gradientHeight = 0f

        dim = theme.backgroundImageDim.coerceIn(0f, 1f)
        dimPaint.color = android.graphics.Color.BLACK
        dimPaint.alpha = (dim * 255).toInt().coerceIn(0, 255)

        val size = (theme.patternScaleDp * density).toInt().coerceIn(MIN_TILE_PX, MAX_TILE_PX)
        if (theme.backgroundPatterns == tilePatterns && size == tileSize &&
            theme.patternColor == tileColor
        ) {
            return
        }
        tilePatterns = theme.backgroundPatterns
        tileSize = size
        tileColor = theme.patternColor
        rebuildTiles(density)
    }

    /** The picture to draw behind the keys, or null. */
    fun setImage(bitmap: android.graphics.Bitmap?) {
        image = bitmap
    }

    /**
     * Paints the background over the given box, in the canvas's own coordinates, the gradient
     * running from the box's top to its bottom.
     */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val height = bottom - top
        if (height <= 0f || right <= left) {
            return
        }
        if (topColor != bottomColor) {
            if (gradient == null || gradientHeight != height) {
                gradientHeight = height
                gradient = LinearGradient(
                    0f, top, 0f, bottom, topColor, bottomColor, Shader.TileMode.CLAMP,
                )
                fill.shader = gradient
            }
        } else if (fill.shader != null) {
            fill.shader = null
        }
        canvas.drawRect(left, top, right, bottom, fill)
        drawImage(canvas, left, top, right, bottom)
        // Patterns over the picture.
        for (paint in patternPaints) {
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    /** The picture, centre-cropped to fill the box, then dimmed. */
    private fun drawImage(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val bitmap = image ?: return
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return
        }
        val boxWidth = right - left
        val boxHeight = bottom - top
        val scale = maxOf(boxWidth / bitmap.width, boxHeight / bitmap.height)
        val visibleWidth = (boxWidth / scale).coerceAtMost(bitmap.width.toFloat())
        val visibleHeight = (boxHeight / scale).coerceAtMost(bitmap.height.toFloat())
        imageSource.set(
            ((bitmap.width - visibleWidth) / 2f).toInt(),
            ((bitmap.height - visibleHeight) / 2f).toInt(),
            ((bitmap.width + visibleWidth) / 2f).toInt(),
            ((bitmap.height + visibleHeight) / 2f).toInt(),
        )
        imageTarget.set(left, top, right, bottom)
        canvas.drawBitmap(bitmap, imageSource, imageTarget, imagePaint)
        if (dim > 0f) {
            canvas.drawRect(imageTarget, dimPaint)
        }
    }

    /** Draws over the whole of a view. */
    fun draw(canvas: Canvas, width: Float, height: Float) =
        draw(canvas, 0f, 0f, width, height)

    private fun rebuildTiles(density: Float) {
        // Old tiles are dropped, never recycled: a display list not yet played back may hold one.
        patternPaints.clear()
        for (pattern in tilePatterns) {
            buildTile(pattern, density)?.let { patternPaints.add(it) }
        }
    }

    private fun buildTile(pattern: Int, density: Float): Paint? {
        if (pattern == KeyboardTheme.PATTERN_NONE) {
            return null
        }
        val size = tileSize
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor }
        val stroke = (density * LINE_WIDTH_DP).coerceAtLeast(1f)
        val edge = size.toFloat()
        when (pattern) {
            // Opposite tile edges match: dots centred, lines edge to edge, the diagonal thrice.
            KeyboardTheme.PATTERN_DOTS ->
                canvas.drawCircle(edge / 2f, edge / 2f, edge * DOT_RADIUS_FRACTION, ink)

            KeyboardTheme.PATTERN_GRID -> {
                ink.style = Paint.Style.FILL
                canvas.drawRect(0f, 0f, edge, stroke, ink)
                canvas.drawRect(0f, 0f, stroke, edge, ink)
            }

            KeyboardTheme.PATTERN_DIAGONAL -> {
                ink.style = Paint.Style.STROKE
                ink.strokeWidth = stroke
                for (step in -1..1) {
                    canvas.drawLine(step * edge, edge, (step + 1) * edge, 0f, ink)
                }
            }

            KeyboardTheme.PATTERN_CHECKS -> {
                val half = edge / 2f
                canvas.drawRect(0f, 0f, half, half, ink)
                canvas.drawRect(half, half, edge, edge, ink)
            }

            KeyboardTheme.PATTERN_STRIPES ->
                canvas.drawRect(0f, 0f, edge / 2f, edge, ink)
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    private companion object {
        /** Tile size bounds, in pixels. */
        const val MIN_TILE_PX = 8
        const val MAX_TILE_PX = 256

        const val DOT_RADIUS_FRACTION = 0.12f
        const val LINE_WIDTH_DP = 1f
    }
}
