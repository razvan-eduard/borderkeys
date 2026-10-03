// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What a press that moved was: a tap, a flick in one of eight directions, or a word swipe.
 * Distances are fractions of the key's diagonal. The finger has left the key once it has been
 * more than [leftKey]'s maximum away from where it went down; a press that left the key is a
 * swipe when its path is at least half a key width or it lasted longer than [SWIPE_MILLIS].
 * Anything else is a tap below the minimum distance and a flick from it.
 */
object FlickClassifier {

    const val TAP = -1
    const val SWIPE = -2

    /** A press longer than this that left the key is a swipe whatever its path. */
    const val SWIPE_MILLIS = 150L

    /** The sectors a direction is read in for a flick's fallback: 16 of 22.5 degrees. */
    private const val FINE_SECTORS = 16

    /** Whether a finger [distance] from where it went down is past [maxFraction] of the key's diagonal. */
    fun leftKey(distance: Float, keyWidth: Float, keyHeight: Float, maxFraction: Float): Boolean =
        distance > maxFraction * hypot(keyWidth, keyHeight)

    /** Whether the finger has moved past [minFraction] of the key's diagonal: no longer a tap. */
    fun pastTap(
        startX: Float,
        startY: Float,
        x: Float,
        y: Float,
        keyWidth: Float,
        keyHeight: Float,
        minFraction: Float,
    ): Boolean = hypot(x - startX, y - startY) >= minFraction * hypot(keyWidth, keyHeight)

    /**
     * [TAP], [SWIPE], or a direction 0..7 clockwise from north, for a press from ([startX],
     * [startY]) to ([x], [y]) over a key of [keyWidth] by [keyHeight], its path [pathLength]
     * long and [elapsedMillis] long; [leftKey] is whether it ever went past the key's maximum.
     */
    fun classify(
        startX: Float,
        startY: Float,
        x: Float,
        y: Float,
        pathLength: Float,
        elapsedMillis: Long,
        keyWidth: Float,
        keyHeight: Float,
        minFraction: Float,
        leftKey: Boolean,
    ): Int {
        if (leftKey && (pathLength >= keyWidth / 2 || elapsedMillis > SWIPE_MILLIS)) {
            return SWIPE
        }
        if (hypot(x - startX, y - startY) < minFraction * hypot(keyWidth, keyHeight)) {
            return TAP
        }
        return direction(x - startX, y - startY)
    }

    /** The 45-degree sector of ([dx], [dy]) in view coordinates, 0 north and clockwise. */
    fun direction(dx: Float, dy: Float): Int = (fineSector(dx, dy) + 1) / 2 % 8

    /**
     * The direction a flick of ([dx], [dy]) takes on a key whose flick directions [has]: its
     * own sector's when set, else the one a 22.5-degree sector away on its side, else none (-1).
     */
    fun flickDirection(dx: Float, dy: Float, has: (Int) -> Boolean): Int {
        val fine = fineSector(dx, dy)
        val exact = (fine + 1) / 2 % 8
        if (has(exact)) {
            return exact
        }
        for (neighbour in intArrayOf(fine - 1, fine + 1)) {
            val direction = ((neighbour + FINE_SECTORS) % FINE_SECTORS + 1) / 2 % 8
            if (direction != exact && has(direction)) {
                return direction
            }
        }
        return -1
    }

    /** The 22.5-degree sector of ([dx], [dy]), 0 from north clockwise to 15. */
    private fun fineSector(dx: Float, dy: Float): Int {
        val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
        val positive = if (degrees < 0) degrees + 360.0 else degrees
        return (positive / (360.0 / FINE_SECTORS)).toInt() % FINE_SECTORS
    }
}
