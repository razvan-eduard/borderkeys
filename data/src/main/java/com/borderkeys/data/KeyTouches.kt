// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.KeyTouch
import kotlin.math.pow

/** Arithmetic on [KeyTouch] totals, each tap weighing half as much every half-life. */
object KeyTouches {

    /** How long a half-life of [days] is. */
    fun halfLifeMillis(days: Int): Long = days * DAY_MILLIS

    /** [touch] weighed down to [now]: every total times one half per [halfLifeMillis] since its last tap. */
    fun decayed(touch: KeyTouch, now: Long, halfLifeMillis: Long): KeyTouch {
        val age = now - touch.lastUsedAt
        if (age <= 0L || halfLifeMillis <= 0L) {
            return touch
        }
        val factor = 0.5.pow(age.toDouble() / halfLifeMillis)
        return touch.copy(
            taps = touch.taps * factor,
            sumX = touch.sumX * factor,
            sumY = touch.sumY * factor,
            sumXX = touch.sumXX * factor,
            sumYY = touch.sumYY * factor,
            sumXY = touch.sumXY * factor,
            lastUsedAt = now,
        )
    }

    /** [newer] with [older], weighed down to [newer]'s last tap, added in; [newer] when there is none. */
    fun merged(older: KeyTouch?, newer: KeyTouch, halfLifeMillis: Long): KeyTouch {
        if (older == null) {
            return newer
        }
        val base = decayed(older, newer.lastUsedAt, halfLifeMillis)
        return newer.copy(
            taps = base.taps + newer.taps,
            sumX = base.sumX + newer.sumX,
            sumY = base.sumY + newer.sumY,
            sumXX = base.sumXX + newer.sumXX,
            sumYY = base.sumYY + newer.sumYY,
            sumXY = base.sumXY + newer.sumXY,
        )
    }

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
