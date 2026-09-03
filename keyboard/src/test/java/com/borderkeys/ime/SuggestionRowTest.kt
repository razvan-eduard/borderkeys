// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the two chips that mean something end up.
 *
 * The row is read at a glance or not at all, so the guarantees under test are about position:
 * the typed word is first every time, and the word a delimiter would apply is in the middle
 * every time, whatever the engine returned and however many slots are shown.
 */
class SuggestionRowTest {

    private val row = SuggestionRow()

    private fun words(vararg items: String?): Array<String?> =
        Array(SuggestionStripView.MAX_SUGGESTIONS) { index -> items.getOrNull(index) }

    private fun shownWords(words: Array<String?>, shown: Int): List<String?> =
        (0 until shown).map { words[it] }

    @Test
    fun `the typed word leads the row even when the engine did not offer it`() {
        val words = words("dacă", "daca ce", "dar")
        val shown = row.arrange(words, 3, typed = "daca", limit = 3, correcting = true)

        assertEquals(3, shown)
        assertEquals(0, row.typedIndex)
        assertEquals("daca", words[0])
    }

    @Test
    fun `the word a delimiter would apply sits in the middle`() {
        val words = words("dacă", "dar", "din")
        val shown = row.arrange(words, 3, typed = "daca", limit = 3, correcting = true)

        assertEquals(1, row.appliedIndex)
        assertEquals("dacă", words[row.appliedIndex])
        // Everything else keeps the engine's order around it.
        assertEquals(listOf("daca", "dacă", "dar"), shownWords(words, shown))
    }

    @Test
    fun `a typed word already among the candidates is moved rather than repeated`() {
        val words = words("dacă", "dar", "daca")
        val shown = row.arrange(words, 3, typed = "daca", limit = 3, correcting = true)

        assertEquals(3, shown)
        assertEquals(listOf("daca", "dacă", "dar"), shownWords(words, shown))
        assertEquals(0, row.typedIndex)
        assertEquals(1, row.appliedIndex)
    }

    @Test
    fun `with nothing to correct the typed chip is the one that acts`() {
        // A delimiter commits what was typed, so the mark for "this is what happens if you do
        // nothing" belongs on the typed word -- not on the engine's first guess, which in this
        // case is not going anywhere near the text.
        val words = words("carte", "cartea", "cărți")
        row.arrange(words, 3, typed = "carte", limit = 3, correcting = false)

        assertEquals(0, row.typedIndex)
        assertEquals(0, row.appliedIndex)
    }

    @Test
    fun `the middle is the middle of the row that is drawn, not of the setting`() {
        // The engine returned two words where five slots were allowed. Marking slot two would
        // mark an empty one.
        val words = words("dacă", "dar")
        val shown = row.arrange(words, 2, typed = "daca", limit = 5, correcting = true)

        assertEquals(3, shown)
        assertEquals(2, row.appliedIndex)
        assertEquals("dacă", words[2])
    }

    @Test
    fun `a one-slot row shows what was typed and marks no correction`() {
        // Honest rather than convenient: the correction is not on the row, so nothing on the row
        // is outlined as the thing a space would do.
        val words = words("dacă")
        val shown = row.arrange(words, 1, typed = "daca", limit = 1, correcting = true)

        assertEquals(1, shown)
        assertEquals("daca", words[0])
        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `nothing is marked when no word is being typed`() {
        // Predictions for the next word, not candidates for this one.
        val words = words("și", "de", "la")
        val shown = row.arrange(words, 3, typed = "", limit = 3, correcting = false)

        assertEquals(3, shown)
        assertEquals(-1, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
        assertEquals(listOf("și", "de", "la"), shownWords(words, shown))
    }

    @Test
    fun `the row never grows past the number of slots asked for`() {
        val words = words("dacă", "dar", "din")
        val shown = row.arrange(words, 3, typed = "daca", limit = 2, correcting = true)

        assertEquals(2, shown)
        assertEquals(listOf("daca", "dacă"), shownWords(words, shown))
        assertEquals(1, row.appliedIndex)
    }
}
