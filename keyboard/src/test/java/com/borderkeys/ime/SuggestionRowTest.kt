// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.predict.Candidate
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

    private fun words(vararg items: String): List<Candidate> = items.map { Candidate(it) }

    @Test
    fun `the typed word leads the row even when the engine did not offer it`() {
        val words = words("dacă", "daca ce", "dar")
        val shown = row.arrange(words, typed = "daca", limit = 3, correction = "dacă")

        assertEquals(3, shown.size)
        assertEquals(0, row.typedIndex)
        assertEquals("daca", shown[0].text)
    }

    @Test
    fun `the word a delimiter would apply sits in the middle`() {
        val words = words("dacă", "dar", "din")
        val shown = row.arrange(words, typed = "daca", limit = 3, correction = "dacă")

        assertEquals(1, row.appliedIndex)
        assertEquals("dacă", shown[row.appliedIndex].text)
        // Everything else keeps the engine's order around it.
        assertEquals(listOf("daca", "dacă", "dar"), shown.map { it.text })
    }

    /**
     * The row is the ranked list and the correction comes from the corrections heap, so the word
     * a delimiter will commit is often nowhere on the row. It still has to be the outlined one.
     *
     * Reported from a device: typing "put" outlined "putem" and the space bar committed "out" --
     * a word that had never been on the row. Outlining by position rather than by value is what
     * let the row say one thing while the delimiter did another.
     */
    @Test
    fun `a correction the row does not carry is inserted rather than mis-outlined`() {
        val words = words("puține", "putem", "puts")
        val shown = row.arrange(words, typed = "put", limit = 4, correction = "out")

        assertEquals("the outlined chip is the word space will commit",
            "out", shown[row.appliedIndex].text)
        assertEquals("put", shown[row.typedIndex].text)
        // The middle of a four-slot row is the third, and the candidate that had been last is
        // what makes room -- the slot nobody reads paying for the one that has to be right.
        assertEquals(listOf("put", "puține", "out", "putem"), shown.map { it.text })
    }

    @Test
    fun `a correction absent from a full row displaces the slot nobody reads`() {
        val words = words("puține", "putem", "puts")
        val shown = row.arrange(words, typed = "put", limit = 3, correction = "out")

        assertEquals(3, shown.size)
        assertEquals("out", shown[row.appliedIndex].text)
        assertEquals(listOf("put", "out", "puține"), shown.map { it.text })
    }

    @Test
    fun `a typed word already among the candidates is moved rather than repeated`() {
        val words = words("dacă", "dar", "daca")
        val shown = row.arrange(words, typed = "daca", limit = 3, correction = "dacă")

        assertEquals(3, shown.size)
        assertEquals(listOf("daca", "dacă", "dar"), shown.map { it.text })
        assertEquals(0, row.typedIndex)
        assertEquals(1, row.appliedIndex)
    }

    @Test
    fun `with nothing to correct nothing is outlined`() {
        // The typed word is italic and unmarked; there is no correction, so no chip is
        // outlined. Outlining the typed word would be pointing at what space already does.
        val words = words("carte", "cartea", "cărți")
        row.arrange(words, typed = "carte", limit = 3, correction = null)

        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `the middle is the middle of the row that is drawn, not of the setting`() {
        // The engine returned two words where five slots were allowed. Marking slot two would
        // mark an empty one.
        val words = words("dacă", "dar")
        val shown = row.arrange(words, typed = "daca", limit = 5, correction = "dacă")

        assertEquals(3, shown.size)
        assertEquals(2, row.appliedIndex)
        assertEquals("dacă", shown[2].text)
    }

    @Test
    fun `a one-slot row shows what was typed and marks no correction`() {
        // Honest rather than convenient: the correction is not on the row, so nothing on the row
        // is outlined as the thing a space would do.
        val words = words("dacă")
        val shown = row.arrange(words, typed = "daca", limit = 1, correction = "dacă")

        assertEquals(1, shown.size)
        assertEquals("daca", shown[0].text)
        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `nothing is marked when no word is being typed`() {
        // Predictions for the next word, not candidates for this one.
        val words = words("și", "de", "la")
        val shown = row.arrange(words, typed = "", limit = 3, correction = null)

        assertEquals(3, shown.size)
        assertEquals(-1, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
        assertEquals(listOf("și", "de", "la"), shown.map { it.text })
    }

    @Test
    fun `the row never grows past the number of slots asked for`() {
        val words = words("dacă", "dar", "din")
        val shown = row.arrange(words, typed = "daca", limit = 2, correction = "dacă")

        assertEquals(2, shown.size)
        assertEquals(listOf("daca", "dacă"), shown.map { it.text })
        assertEquals(1, row.appliedIndex)
    }
}
