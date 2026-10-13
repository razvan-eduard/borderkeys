// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.math.roundToLong

/** How the personal dictionary's counts fade with time unused, by a half-life the user sets. */
object PersonalWordDecay {
    /** What one half-life divides a count by. */
    const val HALVING = 2

    /** The least a stale halving leaves a count at. */
    const val STALE_FLOOR = 1

    /** The share of a count one half-life leaves. */
    private const val HALF_LIFE_SHARE = 1.0 / HALVING

    /** The share of a count left after [halvings] half-lives. */
    fun remaining(halvings: Double): Double = HALF_LIFE_SHARE.pow(halvings)

    /** [days] as milliseconds. */
    fun halfLifeMillis(days: Int): Long = daysMillis(days)

    /**
     * How long a word written once is kept, [KeyboardPreferences.unconfirmedLifeDays] [days] as
     * milliseconds; null for [KeyboardPreferences.UNCONFIRMED_LIFE_OFF], which keeps it.
     */
    fun unconfirmedLifeMillis(days: Int): Long? =
        if (days == KeyboardPreferences.UNCONFIRMED_LIFE_OFF) null else daysMillis(days)

    fun sweepDue(lastSweep: Long, now: Long, intervalDays: Int): Boolean =
        now - lastSweep >= daysMillis(intervalDays)

    private fun daysMillis(days: Int): Long = TimeUnit.DAYS.toMillis(days.toLong())

    /**
     * [count], decayed by [halfLifeMillis] for the time between [lastUsedAt] and [now]: never
     * negative, never above [count], and [count] itself when [now] is not after [lastUsedAt].
     */
    fun decayed(count: Int, lastUsedAt: Long, now: Long, halfLifeMillis: Long): Int =
        decayed(count.toLong(), lastUsedAt, now, halfLifeMillis).toInt()

    /** [decayed], for a context's total. */
    fun decayed(count: Long, lastUsedAt: Long, now: Long, halfLifeMillis: Long): Long {
        if (count <= 0 || now <= lastUsedAt || halfLifeMillis <= 0) {
            return count.coerceAtLeast(0L)
        }
        val halvings = (now - lastUsedAt).toDouble() / halfLifeMillis
        return (count * remaining(halvings)).roundToLong().coerceIn(0L, count)
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

/** A pair context's total decayed as [UserBigram.decayed] decays its pairs. */
fun UserPairContext.decayed(now: Long, halfLifeMillis: Long): UserPairContext =
    copy(total = PersonalWordDecay.decayed(total, lastUsedAt, now, halfLifeMillis))

/** A triple context's total decayed as [UserTrigram.decayed] decays its triples. */
fun UserTripleContext.decayed(now: Long, halfLifeMillis: Long): UserTripleContext =
    copy(total = PersonalWordDecay.decayed(total, lastUsedAt, now, halfLifeMillis))
