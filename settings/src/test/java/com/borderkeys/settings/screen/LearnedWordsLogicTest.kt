// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.PersonalEntries
import com.borderkeys.data.entity.UserWord
import com.borderkeys.i18n.Keys
import com.borderkeys.language.LanguageTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Learned words page's rules: what can be added, the languages offered, how words are grouped, what the delete button says. */
class LearnedWordsLogicTest {

    @Test
    fun `a word is addable unless a listed word is that exact spelling`() {
        val listed = setOf("paris", "Nadia")
        assertTrue(addable("Paris", listed))
        assertTrue(addable("kubernetes", listed))
        assertFalse(addable("paris", listed))
        assertFalse(addable("Nadia", listed))
    }

    @Test
    fun `what is not a word is not addable`() {
        for (text in listOf("", "a", "a b", "42", "'tis")) {
            assertFalse(text, addable(text, emptySet()))
        }
    }

    @Test
    fun `the languages offered are the enabled ones, the current first`() {
        val enabled = listOf("en-US", "ro-RO", "de-DE")
        assertEquals(listOf("ro-RO", "en-US", "de-DE"), languageChoices(enabled, "ro-RO"))
        assertEquals(enabled, languageChoices(enabled, null))
        assertEquals(enabled, languageChoices(enabled, "fr-FR"))
    }

    @Test
    fun `words are grouped by language, the languages counted first, then those off, then the uses in no language known`() {
        fun listed(key: String, language: String) = PersonalEntries.ListedWord(key, language, key, 3, 0, listOf(key))
        val words = listOf(
            listed("taxi", UserWord.UNPLACED),
            listed("drum", "ro"),
            listed("casa", "ro"),
            listed("taxi", "en"),
            listed("zqxw", LanguageTags.UNKNOWN),
            listed("kelpa", "en"),
        )
        assertEquals(
            listOf(
                WordGroup("en", off = false, words = listOf(words[3], words[5])),
                WordGroup("ro", off = true, words = listOf(words[1], words[2])),
                WordGroup(LanguageTags.UNKNOWN, off = true, words = listOf(words[4])),
                WordGroup(UserWord.UNPLACED, off = false, words = listOf(words[0])),
            ),
            wordGroups(words, active = setOf("en")),
        )
        assertEquals(
            "with no pack active every language counts",
            listOf(false, false, false, false),
            wordGroups(words, active = emptySet()).map { it.off },
        )
    }

    @Test
    fun `a search or a filter narrows the list, and the delete button says which`() {
        assertFalse(narrowed("", PersonalEntries.Origin.ANY))
        assertTrue(narrowed(" x", PersonalEntries.Origin.ANY))
        assertTrue(narrowed("", PersonalEntries.Origin.ADDED))
        assertEquals(
            Keys.DICTIONARY_DELETE_ALL_WORDS to Keys.DICTIONARY_DELETE_ALL_WORDS_TITLE,
            deleteKeys(narrowed = false),
        )
        assertEquals(
            Keys.DICTIONARY_DELETE_FILTERED_WORDS to Keys.DICTIONARY_DELETE_FILTERED_WORDS_TITLE,
            deleteKeys(narrowed = true),
        )
    }
}
