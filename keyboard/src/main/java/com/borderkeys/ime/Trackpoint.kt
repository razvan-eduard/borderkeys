// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.abs
import kotlin.math.max

/**
 * The space bar held still as a joystick: each tick moves the caret one step along each axis
 * the finger has left the dead zone on, and the next tick comes sooner the further the finger
 * sits from where the hold began, down to [MIN_DELAY_MILLIS] at half the key's diagonal.
 */
object Trackpoint {

    /** How long the space bar is held still before the joystick starts. */
    const val HOLD_MILLIS = 600L

    /** How far the finger may drift during the hold, and must leave the centre to move, in dp. */
    const val DEAD_ZONE_DP = 15f

    const val MAX_DELAY_MILLIS = 200L
    const val MIN_DELAY_MILLIS = 30L

    class Tick(val xSteps: Int, val ySteps: Int, val delayMillis: Long)

    /**
     * The step along each axis, -1, 0 or 1, for a finger [dx] right and [dy] down of the
     * centre, and the delay to the next tick; [speedPercent] shortens the delay above 100.
     */
    fun tick(dx: Float, dy: Float, deadZonePx: Float, halfDiagonalPx: Float, speedPercent: Int): Tick {
        val xSteps = if (abs(dx) > deadZonePx) (if (dx > 0) 1 else -1) else 0
        val ySteps = if (abs(dy) > deadZonePx) (if (dy > 0) 1 else -1) else 0
        val reach = max(abs(dx), abs(dy)) - deadZonePx
        val span = max(halfDiagonalPx - deadZonePx, 1f)
        val fraction = (reach / span).coerceIn(0f, 1f)
        val eased = MAX_DELAY_MILLIS - (MAX_DELAY_MILLIS - MIN_DELAY_MILLIS) * fraction
        val scaled = eased * 100f / speedPercent.coerceIn(1, 1_000)
        return Tick(xSteps, ySteps, scaled.toLong().coerceIn(MIN_DELAY_MILLIS, MAX_DELAY_MILLIS * 2))
    }
}
