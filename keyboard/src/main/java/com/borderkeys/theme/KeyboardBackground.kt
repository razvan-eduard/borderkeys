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
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var gradient: LinearGradient? = null

    /** What the current tile and gradient were built from, so neither is rebuilt for nothing. */
    private var tilePattern = KeyboardTheme.PATTERN_NONE
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

        val size = (theme.patternScaleDp * density).toInt().coerceIn(MIN_TILE_PX, MAX_TILE_PX)
        if (theme.backgroundPattern == tilePattern && size == tileSize &&
            theme.patternColor == tileColor
        ) {
            return
        }
        tilePattern = theme.backgroundPattern
        tileSize = size
        tileColor = theme.patternColor
        rebuildTile(density)
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
        if (patternPaint.shader != null) {
            canvas.drawRect(left, top, right, bottom, patternPaint)
        }
    }

    /** Draws over the whole of a view, which is what every caller but the host wants. */
    fun draw(canvas: Canvas, width: Float, height: Float) =
        draw(canvas, 0f, 0f, width, height)

    private fun rebuildTile(density: Float) {
        // The old bitmap is dropped, not recycled: the shader holding it may still be inside a
        // display list that has not been played back yet, and drawing a recycled bitmap throws.
        // One tile of at most 256 by 256 is not worth the risk of getting that ordering wrong.
        if (tilePattern == KeyboardTheme.PATTERN_NONE) {
            patternPaint.shader = null
            return
        }
        val size = tileSize
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor }
        val stroke = (density * LINE_WIDTH_DP).coerceAtLeast(1f)
        val edge = size.toFloat()
        when (tilePattern) {
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
        patternPaint.shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private companion object {
        /** A tile smaller than this repeats into mush; larger than this is a wallpaper. */
        const val MIN_TILE_PX = 8
        const val MAX_TILE_PX = 256

        const val DOT_RADIUS_FRACTION = 0.12f
        const val LINE_WIDTH_DP = 1f
    }
}
