// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import kotlin.math.floor
import kotlin.math.pow

/** How the personal dictionary's counts fade with time unused, by a half-life. */
object PersonalWordDecay {
    /** A word unused this long carries half the weight it did. */
    const val HALF_LIFE_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /** How long a word written exactly once is kept before its row is dropped. */
    const val UNCONFIRMED_LIFE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /**
     * [count], decayed for the time between [lastUsedAt] and [now]: never negative, never above
     * [count], and [count] itself when [now] is not after [lastUsedAt].
     */
    fun decayed(count: Int, lastUsedAt: Long, now: Long): Int {
        if (count <= 0 || now <= lastUsedAt) {
            return count.coerceAtLeast(0)
        }
        val halvings = (now - lastUsedAt).toDouble() / HALF_LIFE_MILLIS
        val factor = 0.5.pow(halvings)
        return floor(count * factor + 0.5).toInt().coerceIn(0, count)
    }
}

/** [UserWord.count] decayed as of [now], for the native model; the stored row is untouched. */
fun UserWord.decayed(now: Long): UserWord =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))

/** The pair equivalent of [UserWord.decayed]. */
fun UserBigram.decayed(now: Long): UserBigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))

/** The triple equivalent of [UserWord.decayed]. */
fun UserTrigram.decayed(now: Long): UserTrigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))
