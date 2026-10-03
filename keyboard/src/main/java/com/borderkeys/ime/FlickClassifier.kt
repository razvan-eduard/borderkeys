// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * What a press that moved was: a tap, a flick in one of eight directions, or a word swipe.
 * Distances are fractions of the key's diagonal: below [minFraction] the finger did not leave
 * the tap; up to [maxFraction] of path it flicked; once its path is longer it has left the key,
 * and the press is a swipe once it lasted longer than [SWIPE_MILLIS] or its path bent, a flick
 * being one straight stroke, wherever it ends.
 */
object FlickClassifier {

    const val TAP = -1
    const val SWIPE = -2

    /** The longest a press may last and still be a flick after leaving the key. */
    const val SWIPE_MILLIS = 150L

    /** How much longer than the straight line a path is once it bent: a swipe, not a flick. */
    const val BENT_PATH_RATIO = 1.25f

    /** Whether a press whose path is [pathLength] long has gone past [maxFraction] of the key's diagonal. */
    fun leftKey(pathLength: Float, keyWidth: Float, keyHeight: Float, maxFraction: Float): Boolean =
        pathLength > maxFraction * hypot(keyWidth, keyHeight)

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
     * long and [elapsedMillis] long; [leftKey] is whether any point of it was past [leftKey]'s
     * radius.
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
        val distance = hypot(x - startX, y - startY)
        if (leftKey) {
            return if (elapsedMillis > SWIPE_MILLIS || pathLength > distance * BENT_PATH_RATIO) {
                SWIPE
            } else {
                direction(x - startX, y - startY)
            }
        }
        if (distance < minFraction * hypot(keyWidth, keyHeight)) {
            return TAP
        }
        return direction(x - startX, y - startY)
    }

    /** The 45-degree sector of ([dx], [dy]) in view coordinates, 0 north and clockwise. */
    fun direction(dx: Float, dy: Float): Int {
        // atan2 with y up; a sector is 45 degrees centred on its direction.
        val degrees = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble()))
        val positive = if (degrees < 0) degrees + 360.0 else degrees
        return (((positive + 22.5) / 45.0).toInt()) % 8
    }
}
