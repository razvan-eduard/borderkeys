// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalWordDecayTest {

    private val halfLife = PersonalWordDecay.halfLifeMillis(90)

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
        val word = UserWord(word = "salut", locale = "ro-RO", count = 100, lastUsedAt = 0)
        val decayed = word.decayed(now = halfLife, halfLifeMillis = halfLife)
        assertEquals(50, decayed.count)
        assertEquals(word.word, decayed.word)
        assertEquals(word.locale, decayed.locale)
        assertEquals(word.lastUsedAt, decayed.lastUsedAt)
    }

    @Test
    fun `an unconfirmed word is given a month at most, a confirmed one is only ever halved`() {
        val month = 30L * 24 * 60 * 60 * 1000
        assertEquals(month, PersonalWordDecay.UNCONFIRMED_LIFE_MILLIS)
        assertEquals("a month under a longer half-life", month, PersonalWordDecay.unconfirmedLifeMillis(halfLife))
        val week = PersonalWordDecay.halfLifeMillis(7)
        assertEquals("the half-life when it is shorter", week, PersonalWordDecay.unconfirmedLifeMillis(week))

        // Halving floors at one.
        assertEquals(1, PersonalWordDecay.decayed(1, lastUsedAt = 0, now = halfLife, halfLifeMillis = halfLife))
    }

    @Test
    fun `a shorter half-life fades a word faster`() {
        val month = PersonalWordDecay.halfLifeMillis(30)
        assertEquals(25, PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = 2 * month, halfLifeMillis = month))
        assertEquals(63, PersonalWordDecay.decayed(count = 100, lastUsedAt = 0, now = 2 * month, halfLifeMillis = halfLife))
    }
}
