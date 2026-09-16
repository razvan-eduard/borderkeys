// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Resolves a fractional 0..1 position to a pixel offset within some shape, at spawn time only.
 *
 * Not called from the per-frame draw loop -- only once per spawned particle, a handful of times a
 * second at most, so unlike [ParticleMotion]/[ParticleColor] these do not need to justify every
 * cycle: a caller is free to pass in `kotlin.random.Random.nextFloat()` for [fraction01] without
 * it becoming a hot-path concern.
 */
object EmitterShape {

    fun rectangleX(left: Float, right: Float, fraction01: Float): Float = left + (right - left) * fraction01

    fun rectangleY(top: Float, bottom: Float, fraction01: Float): Float = top + (bottom - top) * fraction01

    /** A point on a circle's perimeter -- [angleFraction01] 0..1 is the whole turn, not degrees
     *  or radians, so a caller never has to know this shape's own angle convention. */
    fun ringX(centerX: Float, radius: Float, angleFraction01: Float): Float =
        centerX + radius * cos(angleFraction01 * TWO_PI)

    fun ringY(centerY: Float, radius: Float, angleFraction01: Float): Float =
        centerY + radius * sin(angleFraction01 * TWO_PI)

    fun lineX(x1: Float, x2: Float, fraction01: Float): Float = x1 + (x2 - x1) * fraction01

    fun lineY(y1: Float, y2: Float, fraction01: Float): Float = y1 + (y2 - y1) * fraction01

    /**
     * A point walking a rectangle's perimeter by arc length -- clockwise from the top-left
     * corner (top edge left to right, right edge top to bottom, bottom edge right to left, left
     * edge bottom to top) -- so a uniform [phase01] gives a uniform *distance* around the
     * perimeter, not a distribution skewed toward whichever edge a naive per-edge phase split
     * would hand more phase-space to. Unlike [rectangleX]/[rectangleY], both functions need all
     * four bounds: which edge a given distance falls on depends on the *other* axis's extent
     * too, not just this axis's own two bounds.
     */
    fun rectanglePerimeterX(left: Float, top: Float, right: Float, bottom: Float, phase01: Float): Float {
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)
        if (perimeter <= 0f) {
            return left
        }
        val distance = phase01.coerceIn(0f, 1f) * perimeter
        return when {
            distance <= width -> left + distance
            distance <= width + height -> right
            distance <= 2f * width + height -> right - (distance - width - height)
            else -> left
        }
    }

    fun rectanglePerimeterY(left: Float, top: Float, right: Float, bottom: Float, phase01: Float): Float {
        val width = right - left
        val height = bottom - top
        val perimeter = 2f * (width + height)
        if (perimeter <= 0f) {
            return top
        }
        val distance = phase01.coerceIn(0f, 1f) * perimeter
        return when {
            distance <= width -> top
            distance <= width + height -> top + (distance - width)
            distance <= 2f * width + height -> bottom
            else -> bottom - (distance - 2f * width - height)
        }
    }

    /** A point on a circular arc, in [android.graphics.Canvas.drawArc]'s own degree convention
     *  (0 is straight right, increasing clockwise) -- the same convention
     *  [com.borderkeys.ime.RadialSuggestionMenuView.wedgeCentreDegrees] already uses, so that
     *  view's own wedge geometry plugs in with no conversion. [phase01] 0 lands on [startDeg],
     *  1 lands on `startDeg + sweepDeg`. */
    fun arcX(centerX: Float, radius: Float, startDeg: Float, sweepDeg: Float, phase01: Float): Float {
        val angleRad = (startDeg + sweepDeg * phase01.coerceIn(0f, 1f)) * DEG_TO_RAD
        return centerX + radius * cos(angleRad)
    }

    fun arcY(centerY: Float, radius: Float, startDeg: Float, sweepDeg: Float, phase01: Float): Float {
        val angleRad = (startDeg + sweepDeg * phase01.coerceIn(0f, 1f)) * DEG_TO_RAD
        return centerY + radius * sin(angleRad)
    }

    private val TWO_PI = (2.0 * PI).toFloat()
    private val DEG_TO_RAD = (PI / 180.0).toFloat()
}
