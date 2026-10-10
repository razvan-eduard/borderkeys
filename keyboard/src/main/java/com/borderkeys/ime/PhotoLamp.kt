// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.graphics.RectF
import kotlin.math.pow

/**
 * The lamp a pasted photo rises in: the image's rows leave the chip one after another, the top
 * row first, each widening from the chip's width to the target's as it climbs, so the shape is
 * narrow at the chip and wide above; the whole fades as it grows.
 */
object PhotoLamp {

    /** The mesh's columns and rows. */
    const val COLUMNS = 8
    const val ROWS = 16

    /** How long the rise takes, in milliseconds. */
    const val DURATION_MILLIS = 786L

    /** How much of the rise the bottom row waits before it leaves the chip. */
    private const val ROW_LAG = 0.45f

    /** The image's opacity as it leaves the chip, out of 1. */
    private const val START_ALPHA = 0.85f

    /** The number of floats [mesh] writes: an x and a y for every vertex. */
    const val VERTEX_FLOATS = (COLUMNS + 1) * (ROWS + 1) * 2

    /**
     * Writes into [out] the mesh's vertices at [progress], 0 to 1, for an image rising from
     * [from] to fill [to].
     */
    fun mesh(progress: Float, from: RectF, to: RectF, out: FloatArray) {
        var index = 0
        for (row in 0..ROWS) {
            val fromTop = row.toFloat() / ROWS
            val own = ((progress - ROW_LAG * fromTop) / (1f - ROW_LAG)).coerceIn(0f, 1f)
            val climbed = 1f - (1f - own).pow(3)
            val widened = climbed.pow(0.5f)
            val y = lerp(from.top, to.top + to.height() * fromTop, climbed)
            val left = lerp(from.left, to.left, widened)
            val right = lerp(from.right, to.right, widened)
            for (column in 0..COLUMNS) {
                out[index++] = lerp(left, right, column.toFloat() / COLUMNS)
                out[index++] = y
            }
        }
    }

    /** The image's opacity at [progress], 0 to 255: the more it has grown, the fainter. */
    fun alpha(progress: Float): Int = (255f * START_ALPHA * (1f - progress).coerceIn(0f, 1f)).toInt()

    /** [bitmapWidth] by [bitmapHeight] fitted, centred, inside [area] with [margin] around it, into [out]. */
    fun fit(bitmapWidth: Int, bitmapHeight: Int, area: RectF, margin: Float, out: RectF) {
        val width = (area.width() - margin * 2f).coerceAtLeast(1f)
        val height = (area.height() - margin * 2f).coerceAtLeast(1f)
        val scale = minOf(width / bitmapWidth.coerceAtLeast(1), height / bitmapHeight.coerceAtLeast(1))
        val fittedWidth = bitmapWidth * scale
        val fittedHeight = bitmapHeight * scale
        val left = area.centerX() - fittedWidth / 2f
        val top = area.centerY() - fittedHeight / 2f
        out.set(left, top, left + fittedWidth, top + fittedHeight)
    }

    private fun lerp(from: Float, to: Float, amount: Float): Float = from + (to - from) * amount
}
