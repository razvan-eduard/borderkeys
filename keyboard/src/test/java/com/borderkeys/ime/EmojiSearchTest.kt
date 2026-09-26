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
            "🎃\tjack-o-lantern",
            "🥧\tpie",
            "no tab here",
            "🫠\t",
        ),
    )

    private val keywords = EmojiSearch.parseKeywords(
        sequenceOf(
            "🎃\tcelebration|halloween|jack|jack-o-lantern|lantern|pumpkin",
            "🥧\tfilling|pastry|pumpkin pie",
            "🚕\tvehicle|Taxi Cab|yellow",
            "🐱\tpet|face",
            "no tab here",
            "🍌\t",
            "😀\t||",
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
        assertEquals(10, index.size)
    }

    @Test
    fun `a keyword finds an emoji its name does not`() {
        assertEquals(listOf("🎃", "🥧"), EmojiSearch.matches("pump", index, 10, keywords))
        assertEquals(listOf("🎃"), EmojiSearch.matches("halloween", index, 10, keywords))
    }

    @Test
    fun `a name match outranks a keyword match, and a keyword word-start outranks a name inside`() {
        // "taxi" names 🚕 and is a keyword of nothing else; "face" names 🐱 by its second word
        // and is a keyword of 🐱 too, so the name tier is where it lands.
        assertEquals(listOf("🚕"), EmojiSearch.matches("taxi", index, 10, keywords))
        assertEquals(listOf("😀", "🐱"), EmojiSearch.matches("face", index, 10, keywords))
        // "lantern" is inside the name "jack-o-lantern" and a keyword of it: the keyword
        // word-start tier ranks it above where the inside-the-name tier would.
        assertEquals(listOf("🎃"), EmojiSearch.matches("lantern", index, 10, keywords))
    }

    @Test
    fun `keywords are folded and split on the bar, and empty ones are dropped`() {
        assertEquals(listOf("vehicle", "taxi cab", "yellow"), keywords["🚕"])
        assertEquals(listOf("🚕"), EmojiSearch.matches("CAB", index, 10, keywords))
        assertTrue("🍌" !in keywords)
        assertTrue("😀" !in keywords)
        assertEquals(4, keywords.size)
    }
}
