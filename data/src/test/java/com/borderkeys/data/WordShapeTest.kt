// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WordShape]: what the personal dictionary takes as a word. */
class WordShapeTest {

    @Test
    fun `a word is letters, with apostrophes and hyphens after the first`() {
        for (word in listOf("ab", "Nadia", "don't", "e-mail", "Ștefan", "ñandú", "a".repeat(WordShape.MAX_LENGTH))) {
            assertTrue(word, WordShape.isWord(word))
        }
        for (text in listOf("", "a", "a".repeat(WordShape.MAX_LENGTH + 1), "'tis", "-ish", "a1", "a b", "a.b", "42")) {
            assertFalse(text, WordShape.isWord(text))
        }
    }

    @Test
    fun `the length rule alone is what the learning buffer reads`() {
        assertTrue(WordShape.isWordLength("a1"))
        assertFalse(WordShape.isWordLength("a"))
        assertFalse(WordShape.isWordLength("a".repeat(WordShape.MAX_LENGTH + 1)))
    }

    @Test
    fun `a word character is a letter, an apostrophe or a hyphen`() {
        assertEquals(
            listOf(true, true, true, true, false, false, false),
            listOf('a', 'É', '\'', '-', '1', ' ', '.').map { WordShape.isWordCharacter(it.code) },
        )
    }

    @Test
    fun `a capital first letter is read, in any script that has one`() {
        assertEquals(
            listOf(true, true, false, false, false),
            listOf("Nadia", "Élan", "nadia", "iPhone", "").map(WordShape::startsWithCapital),
        )
    }
}
