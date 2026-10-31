// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PersonalWordDecayTest {

    private val halfLife = PersonalWordDecay.halfLifeMillis(KeyboardPreferences.DEFAULT_UNLEARN_HALF_LIFE_DAYS)

    @Test
    fun `nothing has elapsed, nothing decays`() {
        assertEquals(100, PersonalWordDecay.decayed(count = 100, lastUsedAt = 1_000, now = 1_000, halfLifeMillis = halfLife))
    }

    @Test
    fun `a clock that moved backwards is not trusted to decay anything`() {
        assertEquals(100, PersonalWordDecay.decayed(count = 100, lastUsedAt = 2_000, now = 1_000, halfLifeMillis = halfLife))
    }

    @Test
    fun `one half-life is about half`() {
        val result = PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife)
        assertEquals(50, result)
    }

    @Test
    fun `two half-lives is about a quarter`() {
        val result = PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = halfLife * 2, halfLifeMillis = halfLife)
        assertEquals(25, result)
    }

    @Test
    fun `a heavily used word survives a quiet week unharmed`() {
        val oneWeek = 7L * 24 * 60 * 60 * 1000
        val result = PersonalWordDecay.decayed(count = 1000, lastUsedAt = 0, now = oneWeek, halfLifeMillis = halfLife)
        // A week against a ninety-day half-life should cost almost nothing.
        assertTrue("expected close to 1000, got $result", result >= 940)
    }

    @Test
    fun `decay never goes negative or above the original count`() {
        val farFuture = halfLife * 1000
        val result = PersonalWordDecay.decayed(count = 5, lastUsedAt = 0, now = farFuture, halfLifeMillis = halfLife)
        assertTrue(result in 0..5)
    }

    @Test
    fun `zero and negative counts are left alone`() {
        assertEquals(0, PersonalWordDecay.decayed(count = 0, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife))
        assertEquals(0, PersonalWordDecay.decayed(count = -3, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife))
    }

    @Test
    fun `the UserWord extension decays only the count`() {
        val word = UserWord(word = "salut", language = "ro", count = 100, lastUsedAt = 0)
        val decayed = word.decayed(now = halfLife, halfLifeMillis = halfLife)
        assertEquals(50, decayed.count)
        assertEquals(word.word, decayed.word)
        assertEquals(word.language, decayed.language)
        assertEquals(word.lastUsedAt, decayed.lastUsedAt)
    }

    @Test
    fun `a word chosen on purpose never decays below one use, one never chosen decays to none`() {
        val chosen = UserWord(word = "nadia", language = "en", count = 1, lastUsedAt = 0, asserted = 1)
        val never = chosen.copy(asserted = 0)
        val later = 3 * halfLife
        assertEquals(1, chosen.decayed(now = later, halfLifeMillis = halfLife).count)
        assertEquals(0, never.decayed(now = later, halfLifeMillis = halfLife).count)
        assertEquals(1, chosen.decayed(now = later, halfLifeMillis = halfLife).asserted)
    }

    @Test
    fun `a pair or triple chosen on purpose never decays below one use, one never chosen decays to none`() {
        val later = 3 * halfLife
        val pair = UserBigram("en", "we", "go", count = 1, lastUsedAt = 0, contextSeen = 1, languageSeen = 1)
        val triple = UserTrigram("en", "we", "go", "home", count = 1, lastUsedAt = 0, contextSeen = 1, languageSeen = 1)
        assertEquals(0, pair.decayed(now = later, halfLifeMillis = halfLife).count)
        assertEquals(1, pair.copy(asserted = 1).decayed(now = later, halfLifeMillis = halfLife).count)
        assertEquals(0, triple.decayed(now = later, halfLifeMillis = halfLife).count)
        assertEquals(1, triple.copy(asserted = 1).decayed(now = later, halfLifeMillis = halfLife).count)
    }

    @Test
    fun `an unconfirmed word is kept its set days, or for good with Off, a confirmed one is only ever halved`() {
        for (days in KeyboardPreferences.UNCONFIRMED_LIFE_STEPS - KeyboardPreferences.UNCONFIRMED_LIFE_OFF) {
            assertEquals(PersonalWordDecay.halfLifeMillis(days), PersonalWordDecay.unconfirmedLifeMillis(days))
        }
        assertNull(PersonalWordDecay.unconfirmedLifeMillis(KeyboardPreferences.UNCONFIRMED_LIFE_OFF))

        // Halving floors at one.
        assertEquals(1, PersonalWordDecay.decayed(1, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife))
    }

    @Test
    fun `the clean-up is due once its interval has passed`() {
        for (days in KeyboardPreferences.CLEAN_UP_STEPS) {
            val interval = TimeUnit.DAYS.toMillis(days.toLong())
            assertFalse(PersonalWordDecay.sweepDue(lastSweep = 0L, now = interval - 1, intervalDays = days))
            assertTrue(PersonalWordDecay.sweepDue(lastSweep = 0L, now = interval, intervalDays = days))
        }
    }

    @Test
    fun `a shorter half-life fades a word faster`() {
        val shorter = PersonalWordDecay.halfLifeMillis(KeyboardPreferences.UNLEARN_HALF_LIFE_STEPS.first())
        val count = 100
        val faded = PersonalWordDecay.decayed(count = count, lastUsedAt = 0, now = 2 * shorter, halfLifeMillis = shorter)
        assertEquals(count / PersonalWordDecay.HALVING / PersonalWordDecay.HALVING, faded)
        assertTrue(PersonalWordDecay.decayed(count = count, lastUsedAt = 0, now = 2 * shorter, halfLifeMillis = halfLife) > faded)
    }

    @Test
    fun `each half-life leaves one halving's share`() {
        val halving = PersonalWordDecay.HALVING.toDouble()
        assertEquals(1.0, PersonalWordDecay.remaining(0.0), 0.0)
        assertEquals(1 / halving, PersonalWordDecay.remaining(1.0), 0.0)
        assertEquals(1 / (halving * halving), PersonalWordDecay.remaining(2.0), 0.0)
    }

    @Test
    fun `a context's total decays as a count does, past what an Int holds`() {
        val count = 100
        assertEquals(
            PersonalWordDecay.decayed(count, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife).toLong(),
            PersonalWordDecay.decayed(count.toLong(), lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife),
        )
        val total = Int.MAX_VALUE.toLong() * PersonalWordDecay.HALVING
        assertEquals(
            total / PersonalWordDecay.HALVING,
            PersonalWordDecay.decayed(total, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife),
        )
    }
}
