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
 * The surface the keys sit on: a colour, optionally a gradient, optionally a pattern.
 *
 * Everything expensive happens when the theme changes. The pattern is rendered once into a small
 * tile and handed to a `BitmapShader` set to repeat, so drawing it is one `drawRect` regardless
 * of how many dots are on screen -- the alternative, a loop drawing a few hundred circles, is
 * several hundred draw operations per frame on the one view that is invalidated on every touch.
 *
 * The gradient is a `LinearGradient` rebuilt only when the height changes, for the same reason.
 *
 * Patterns are drawn in code rather than shipped as images. That keeps them resolution
 * independent, keeps them themeable -- the colour is the user's -- and keeps the package free
 * of artwork whose licence would have to be tracked.
 */
class KeyboardBackground {

    private val fill = Paint()

    /**
     * One paint per pattern being drawn, because a paint carries one shader.
     *
     * Patterns layer rather than replacing each other: dots over a grid is a third thing, and
     * nothing about a keyboard requires it to have an opinion on that.
     */
    private val patternPaints = ArrayList<Paint>(KeyboardTheme.PATTERN_COUNT)

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint()
    private val imageSource = android.graphics.Rect()
    private val imageTarget = android.graphics.RectF()
    private var image: android.graphics.Bitmap? = null
    private var dim = 0f

    private var gradient: LinearGradient? = null

    /** What the current tiles and gradient were built from, so neither is rebuilt for nothing. */
    private var tilePatterns: List<Int> = emptyList()
    private var tileSize = 0
    private var tileColor = 0
    private var topColor = 0
    private var bottomColor = 0
    private var gradientHeight = 0f

    /**
     * Re-reads the theme.
     *
     * Called from [ThemePaints.update], which is itself called only when the theme or the
     * density actually changed, so this is not on any path that runs per frame.
     */
    fun update(theme: KeyboardTheme, density: Float) {
        fill.color = theme.backgroundColor
        topColor = theme.backgroundColor
        bottomColor = theme.gradientEnd()
        // A new gradient is needed even at the same height, since the colours moved.
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

    /**
     * The picture to draw behind the keys, or null.
     *
     * Handed in rather than loaded here: the theme carries a file name, and resolving one needs
     * a Context, which a class that only draws has no business holding.
     */
    fun setImage(bitmap: android.graphics.Bitmap?) {
        image = bitmap
    }

    /**
     * Paints the background over the given box, in the canvas's own coordinates.
     *
     * The gradient runs from the top of the box to its bottom, so a keyboard that grows or
     * shrinks keeps the whole gradient rather than showing a slice of one.
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
        // Over the picture, not under it: a pattern is drawn on the surface, and a surface with
        // a photograph on it is still the surface.
        for (paint in patternPaints) {
            canvas.drawRect(left, top, right, bottom, paint)
        }
    }

    /**
     * The picture, filling the box without being stretched, and then darkened.
     *
     * Centre-cropped rather than squashed: a keyboard is a wide strip and almost no photograph
     * is, so fitting one to the box exactly would make every face in it a foot wide. The dim
     * that follows is what keeps the labels readable over whatever the picture happens to be.
     */
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

    /** Draws over the whole of a view, which is what every caller but the host wants. */
    fun draw(canvas: Canvas, width: Float, height: Float) =
        draw(canvas, 0f, 0f, width, height)

    private fun rebuildTiles(density: Float) {
        // The old bitmaps are dropped, not recycled: a shader holding one may still be inside a
        // display list that has not been played back yet, and drawing a recycled bitmap throws.
        // A handful of tiles at most 256 by 256 is not worth the risk of getting that ordering
        // wrong.
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
            // Every pattern is drawn so that its opposite edges match, or the repeat shows a
            // seam every tile. Dots sit in the middle, lines run edge to edge, and the diagonal
            // is drawn three times so the parts cut off at one corner arrive at the other.
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
        /** A tile smaller than this repeats into mush; larger than this is a wallpaper. */
        const val MIN_TILE_PX = 8
        const val MAX_TILE_PX = 256

        const val DOT_RADIUS_FRACTION = 0.12f
        const val LINE_WIDTH_DP = 1f
    }
}
