// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.PersonalEntries
import com.borderkeys.i18n.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Learned words page's rules: what can be added, the languages offered, what the delete button says. */
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
