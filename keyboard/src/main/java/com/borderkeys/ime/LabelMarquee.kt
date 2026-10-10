// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Where a label too long for its room is scrolled to, over time: still at its start for a
 * while, past at a readable pace, still at its end for as long, and over again from the start.
 * The animation speed shortens the holds and quickens the pace alike.
 */
object LabelMarquee {

    /** How long the label stands at either end, at speed 1. */
    const val HOLD_MILLIS = 2000f

    /** How fast it moves, in dp per second, at speed 1. */
    const val PACE_DP_PER_SECOND = 40f

    /**
     * The offset to draw the label at, from 0 to [overflowPx], at [elapsedMillis] since the
     * cycle began, with [pacePxPerSecond] the pace in pixels and [speed] the animation speed.
     */
    fun offsetPx(elapsedMillis: Long, overflowPx: Float, pacePxPerSecond: Float, speed: Float): Float {
        if (overflowPx <= 0f || speed <= 0f) {
            return 0f
        }
        val hold = HOLD_MILLIS / speed
        val travel = overflowPx / (pacePxPerSecond * speed) * 1000f
        val cycle = hold + travel + hold
        val t = (elapsedMillis % cycle.toLong()).toFloat()
        return when {
            t < hold -> 0f
            t < hold + travel -> (t - hold) / travel * overflowPx
            else -> overflowPx
        }
    }
}
