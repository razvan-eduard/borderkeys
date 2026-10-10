// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which saved words and phrases count as learned, as the keyboard counts them. */
class PersonalEntriesTest {

    private val now = 1_000_000_000L
    private val settings = KeyboardPreferences(learnAfterUses = 3)
    private fun word(text: String, count: Int, asserted: Int = 0, lastUsedAt: Long = now) =
        UserWord(word = text, locale = "en-US", count = count, lastUsedAt = lastUsedAt, asserted = asserted)
    private fun learned(vararg words: UserWord, with: KeyboardPreferences = settings) =
        PersonalEntries.words(words.toList(), with, now) { it.lowercase() }.map { it.word }

    @Test
    fun `a word is learned at the setting's number of uses, or once chosen`() {
        assertEquals(emptyList<String>(), learned(word("make", 2)))
        assertEquals(listOf("make"), learned(word("make", 3)))
        assertEquals(listOf("make"), learned(word("make", 1, asserted = 1)))
        assertEquals(listOf("make"), learned(word("make", 1), with = KeyboardPreferences(learnAfterUses = 1)))
    }

    @Test
    fun `a word's case forms count together, and all are listed`() {
        assertEquals(listOf("This", "this"), learned(word("This", 1), word("this", 2)))
        assertEquals(emptyList<String>(), learned(word("This", 1), word("this", 1)))
    }

    @Test
    fun `an unused word fades below the number and is no longer learned`() {
        val longAgo = now - PersonalWordDecay.halfLifeMillis(90)
        assertEquals(emptyList<String>(), learned(word("make", 4, lastUsedAt = longAgo)))
        assertEquals(listOf("make"), learned(word("make", 6, lastUsedAt = longAgo)))
    }

    @Test
    fun `a phrase is learned at the same number of uses`() {
        val phrases = listOf(
            UserPhrase(listOf("make", "this"), count = 3, lastUsedAt = now),
            UserPhrase(listOf("make", "that"), count = 2, lastUsedAt = now),
        )
        assertEquals(
            listOf(listOf("make", "this")),
            PersonalEntries.phrases(phrases, settings, now).map { it.words },
        )
    }

    @Test
    fun `forgetting a word finds every case it was learned in`() {
        assertEquals(listOf("This", "this", "THIS"), casesOf("this", listOf("This", "this", "THIS", "thistle", "thís")))
    }
}
