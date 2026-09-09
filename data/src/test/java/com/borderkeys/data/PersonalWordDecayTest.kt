// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWordDecayTest {

    private val halfLife = PersonalWordDecay.HALF_LIFE_MILLIS

    @Test
    fun `nothing has elapsed, nothing decays`() {
        assertEquals(100, PersonalWordDecay.decayed(count = 100, lastUsedAt = 1_000, now = 1_000))
    }

    @Test
    fun `a clock that moved backwards is not trusted to decay anything`() {
        assertEquals(100, PersonalWordDecay.decayed(count = 100, lastUsedAt = 2_000, now = 1_000))
    }

    @Test
    fun `one half-life is about half`() {
        val result = PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = halfLife)
        assertEquals(50, result)
    }

    @Test
    fun `two half-lives is about a quarter`() {
        val result = PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = halfLife * 2)
        assertEquals(25, result)
    }

    @Test
    fun `a heavily used word survives a quiet week unharmed`() {
        val oneWeek = 7L * 24 * 60 * 60 * 1000
        val result = PersonalWordDecay.decayed(count = 1000, lastUsedAt = 0, now = oneWeek)
        // A week against a ninety-day half-life should cost almost nothing.
        assertTrue("expected close to 1000, got $result", result >= 940)
    }

    @Test
    fun `decay never goes negative or above the original count`() {
        val farFuture = halfLife * 1000
        val result = PersonalWordDecay.decayed(count = 5, lastUsedAt = 0, now = farFuture)
        assertTrue(result in 0..5)
    }

    @Test
    fun `zero and negative counts are left alone`() {
        assertEquals(0, PersonalWordDecay.decayed(count = 0, lastUsedAt = 0, now = halfLife))
        assertEquals(0, PersonalWordDecay.decayed(count = -3, lastUsedAt = 0, now = halfLife))
    }

    @Test
    fun `the UserWord extension decays only the count`() {
        val word = UserWord(word = "salut", locale = "ro-RO", count = 100, lastUsedAt = 0)
        val decayed = word.decayed(now = halfLife)
        assertEquals(50, decayed.count)
        assertEquals(word.word, decayed.word)
        assertEquals(word.locale, decayed.locale)
        assertEquals(word.lastUsedAt, decayed.lastUsedAt)
    }
}
