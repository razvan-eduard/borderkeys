// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import kotlin.math.floor
import kotlin.math.pow

/** How the personal dictionary's counts fade with time unused, by a half-life the user sets. */
object PersonalWordDecay {
    private const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000

    /** The longest a word written exactly once is kept before its row is dropped. */
    const val UNCONFIRMED_LIFE_MILLIS: Long = 30L * DAY_MILLIS

    /** [days] as milliseconds. */
    fun halfLifeMillis(days: Int): Long = days * DAY_MILLIS

    /** How long a word written once is kept: [UNCONFIRMED_LIFE_MILLIS], or [halfLifeMillis] when shorter. */
    fun unconfirmedLifeMillis(halfLifeMillis: Long): Long = minOf(UNCONFIRMED_LIFE_MILLIS, halfLifeMillis)

    /**
     * [count], decayed by [halfLifeMillis] for the time between [lastUsedAt] and [now]: never
     * negative, never above [count], and [count] itself when [now] is not after [lastUsedAt].
     */
    fun decayed(count: Int, lastUsedAt: Long, now: Long, halfLifeMillis: Long): Int {
        if (count <= 0 || now <= lastUsedAt || halfLifeMillis <= 0) {
            return count.coerceAtLeast(0)
        }
        val halvings = (now - lastUsedAt).toDouble() / halfLifeMillis
        val factor = 0.5.pow(halvings)
        return floor(count * factor + 0.5).toInt().coerceIn(0, count)
    }
}

/** [UserWord.count] decayed as of [now], for the native model; the stored row is untouched. */
fun UserWord.decayed(now: Long, halfLifeMillis: Long): UserWord =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now, halfLifeMillis))

/** The pair equivalent of [UserWord.decayed]. */
fun UserBigram.decayed(now: Long, halfLifeMillis: Long): UserBigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now, halfLifeMillis))

/** The triple equivalent of [UserWord.decayed]. */
fun UserTrigram.decayed(now: Long, halfLifeMillis: Long): UserTrigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now, halfLifeMillis))
