// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import kotlin.math.floor
import kotlin.math.pow

/**
 * How the personal dictionary forgets, without ever being told to.
 *
 * [DictionaryRepository.forget] and [DictionaryRepository.block] are what the user asks for on
 * purpose. This is the opposite: a word or phrase nobody has written in a long time should carry
 * less weight even though nobody deleted it -- otherwise the dictionary only ever grows, and a
 * phrase typed daily for a month two years ago outranks one typed daily this week, forever,
 * because nothing ever asked whether it was still true.
 *
 * Exponential, by a half-life, rather than a fixed amount subtracted per day: a word used
 * constantly survives a quiet week or month unharmed (the exponent is nearly zero), while one
 * used once and never again keeps fading the whole time nobody writes it, rather than sitting at
 * a fixed count and then being deleted outright on some arbitrary day. The same shape frequency
 * itself already has, just running against time instead of against use.
 */
object PersonalWordDecay {
    /**
     * A word unused this long carries half the weight it did.
     *
     * Ninety days: long enough that not writing a particular word for a month costs it nothing
     * noticeable, short enough that a genuinely abandoned one -- an old project's jargon, a
     * former routine -- fades within a season rather than sitting at the top of a suggestion
     * list for years after it stopped being true.
     */
    const val HALF_LIFE_MILLIS: Long = 90L * 24 * 60 * 60 * 1000

    /**
     * [count], decayed for the time between [lastUsedAt] and [now].
     *
     * Never negative and never larger than [count] itself -- decay only ever takes weight away.
     * A [now] at or before [lastUsedAt] (nothing has elapsed, or a clock went backwards) leaves
     * [count] untouched rather than guessing.
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

/** [UserWord.count], decayed as of [now]. Used only for what is pushed into the native model --
 *  the stored row is untouched, see [DictionaryRepository.decayStaleEntries] for the row itself. */
fun UserWord.decayed(now: Long): UserWord =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))

/** The pair equivalent of [UserWord.decayed]. */
fun UserBigram.decayed(now: Long): UserBigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))

/** The triple equivalent of [UserWord.decayed]. */
fun UserTrigram.decayed(now: Long): UserTrigram =
    copy(count = PersonalWordDecay.decayed(count, lastUsedAt, now))
