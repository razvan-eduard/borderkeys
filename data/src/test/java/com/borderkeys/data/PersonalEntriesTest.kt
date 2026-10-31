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
    private fun word(text: String, count: Int, asserted: Int = 0, lastUsedAt: Long = now, language: String = "en", added: Int = 0) =
        UserWord(word = text, language = language, count = count, lastUsedAt = lastUsedAt, asserted = asserted, added = added)
    private fun learned(vararg words: UserWord, with: KeyboardPreferences = settings) =
        PersonalEntries.byLanguage(words.toList(), with, now).flatMap { it.spellings }

    @Test
    fun `a word is learned at the setting's number of uses, or once chosen`() {
        assertEquals(emptyList<String>(), learned(word("make", 2)))
        assertEquals(listOf("make"), learned(word("make", 3)))
        assertEquals(listOf("make"), learned(word("make", 1, asserted = 1)))
        assertEquals(listOf("make"), learned(word("make", 1), with = KeyboardPreferences(learnAfterUses = 1)))
    }

    @Test
    fun `a word's case forms count together, and all are listed under the spelling served`() {
        assertEquals(listOf("This", "this"), learned(word("This", 1), word("this", 2)))
        assertEquals(emptyList<String>(), learned(word("This", 1), word("this", 1)))
        assertEquals(
            listOf(PersonalEntries.ListedWord("this", "en", "this", 3, 0, listOf("This", "this"))),
            PersonalEntries.byLanguage(listOf(word("This", 1), word("this", 2)), settings, now),
        )
    }

    @Test
    fun `a word is listed under each language it was written in, its uses in no language known counted in each and listed apart`() {
        val rows = listOf(
            word("taxi", 2, language = "ro"),
            word("taxi", 2, language = "en"),
            word("taxi", 1, language = UserWord.UNPLACED),
            word("Kelpa", 1, language = "en", asserted = 1, added = 1),
            word("drum", 2, language = "ro"),
        )
        assertEquals(
            listOf(
                PersonalEntries.ListedWord("taxi", "ro", "taxi", 3, 0, listOf("taxi")),
                PersonalEntries.ListedWord("taxi", "en", "taxi", 3, 0, listOf("taxi")),
                PersonalEntries.ListedWord("kelpa", "en", "Kelpa", 1, 1, listOf("Kelpa")),
            ),
            PersonalEntries.byLanguage(rows, settings, now),
        )
        assertEquals(
            "uses in no language known, listed on their own once learned",
            listOf("taxi" to "ro", "taxi" to "en", "taxi" to UserWord.UNPLACED, "kelpa" to "en"),
            PersonalEntries.byLanguage(rows + word("taxi", 2, language = UserWord.UNPLACED), settings, now).map { it.key to it.language },
        )
    }

    @Test
    fun `a word's mark forms are other words`() {
        assertEquals(emptyList<String>(), learned(word("masă", 2), word("masa", 2)))
        assertEquals(listOf("Masă", "masă"), learned(word("Masă", 1), word("masă", 2), word("masa", 1)))
    }

    @Test
    fun `an unused word fades below the number and is no longer learned`() {
        val longAgo = now - PersonalWordDecay.halfLifeMillis(KeyboardPreferences.DEFAULT_UNLEARN_HALF_LIFE_DAYS)
        assertEquals(emptyList<String>(), learned(word("make", 4, lastUsedAt = longAgo)))
        assertEquals(listOf("make"), learned(word("make", 6, lastUsedAt = longAgo)))
    }

    @Test
    fun `a phrase is learned at the same number of uses`() {
        val phrases = listOf(
            UserPhrase("en", listOf("make", "this"), count = 3, lastUsedAt = now),
            UserPhrase("en", listOf("make", "that"), count = 2, lastUsedAt = now),
        )
        assertEquals(
            listOf(listOf("make", "this")),
            PersonalEntries.phrases(phrases, settings, now).map { it.words },
        )
    }

    @Test
    fun `each word is spelled as it is served, its forms in every language as one`() {
        val rows = listOf(
            word("Paris", 2, language = "en"),
            word("paris", 2, language = "ro"),
            word("paris", 1, language = UserWord.UNPLACED),
            word("Kelpa", 1, added = 1),
            word("kelpa", 4),
        )
        assertEquals(mapOf("paris" to "paris", "kelpa" to "Kelpa"), PersonalEntries.spellings(rows, settings, now))
    }

    @Test
    fun `forgetting a word finds every case it was learned in`() {
        assertEquals(listOf("This", "this", "THIS"), casesOf("this", listOf("This", "this", "THIS", "thistle", "thís")))
    }
}
