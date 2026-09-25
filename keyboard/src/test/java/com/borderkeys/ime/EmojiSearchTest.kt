// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiSearchTest {

    private val index = EmojiSearch.parse(
        sequenceOf(
            "😀\tgrinning face",
            "🍌\tbanana",
            "🐈\tcat",
            "🐱\tcat face",
            "🚕\ttaxi",
            "🐈‍⬛\tblack cat",
            "🎂\tbirthday cake",
            "❤️\tred heart",
            "no tab here",
            "🫠\t",
        ),
    )

    @Test
    fun `a name is found by the word that begins it, best first`() {
        assertEquals(listOf("🐈", "🐱", "🐈‍⬛"), EmojiSearch.matches("cat", index, 10))
    }

    @Test
    fun `an exact name outranks a longer one`() {
        assertEquals("🍌", EmojiSearch.matches("banana", index, 10).first())
        assertEquals("🎂", EmojiSearch.matches("birthday", index, 10).first())
    }

    @Test
    fun `a match inside a word comes last`() {
        assertEquals(listOf("❤️"), EmojiSearch.matches("art", index, 10))
    }

    @Test
    fun `case and accents are folded`() {
        assertEquals(listOf("🍌"), EmojiSearch.matches("BANÁNA", index, 10))
    }

    @Test
    fun `a query too short answers nothing`() {
        assertTrue(EmojiSearch.matches("c", index, 10).isEmpty())
        assertTrue(EmojiSearch.matches("", index, 10).isEmpty())
    }

    @Test
    fun `the limit holds and malformed lines are skipped`() {
        assertEquals(2, EmojiSearch.matches("cat", index, 2).size)
        assertEquals(8, index.size)
    }
}
