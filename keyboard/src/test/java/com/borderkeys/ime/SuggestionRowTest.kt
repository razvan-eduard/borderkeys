// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.predict.Candidate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SuggestionRow]: the typed word first and the word a delimiter would apply in the middle,
 * whatever the engine returned and however many slots are shown.
 */
class SuggestionRowTest {

    private val row = SuggestionRow()

    private fun words(vararg items: String): List<Candidate> = items.map { Candidate(it) }

    @Test
    fun `the word a correction replaced leads the row while it can be put back`() {
        val shown = row.arrange(words("the", "and", "of"), typed = "", limit = 3,
                                correction = null, revertable = "teh")

        assertEquals(listOf("teh", "the", "and"), shown.map { it.text })
        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `a word in progress outranks the one a correction replaced`() {
        val shown = row.arrange(words("there", "they"), typed = "th", limit = 3,
                                correction = null, revertable = "teh")

        assertEquals(listOf("th", "there", "they"), shown.map { it.text })
    }

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

    @Test
    fun `a correction the row does not carry is inserted rather than mis-outlined`() {
        val words = words("puține", "putem", "puts")
        val shown = row.arrange(words, typed = "put", limit = 4, correction = "out")

        assertEquals("the outlined chip is the word space will commit",
            "out", shown[row.appliedIndex].text)
        assertEquals("put", shown[row.typedIndex].text)
        // The middle of a four-slot row is the third; the last candidate makes room.
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
    fun `a word the engine offered twice is shown once, in its first place`() {
        val shown = row.arrange(words("do", "do", "does", "don't"), typed = "do", limit = 4, correction = null)

        assertEquals(listOf("do", "does", "don't"), shown.map { it.text })
        assertEquals(0, row.typedIndex)
    }

    @Test
    fun `a double drops out of the predictions too, and its correction mark is kept`() {
        val predictions = words("the", "and", "the", "of")
        assertEquals(listOf("the", "and", "of"), row.arrange(predictions, typed = "", limit = 4, correction = null).map { it.text })

        val marked = listOf(Candidate("dacă"), Candidate("dar"), Candidate("dacă", isCorrection = true))
        val once = row.withoutDoubles(marked)
        assertEquals(listOf("dacă", "dar"), once.map { it.text })
        assertEquals(true, once[0].isCorrection)
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
        val words = words("carte", "cartea", "cărți")
        row.arrange(words, typed = "carte", limit = 3, correction = null)

        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `the middle is the middle of the row that is drawn, not of the setting`() {
        // Two words where five slots were allowed.
        val words = words("dacă", "dar")
        val shown = row.arrange(words, typed = "daca", limit = 5, correction = "dacă")

        assertEquals(3, shown.size)
        assertEquals(2, row.appliedIndex)
        assertEquals("dacă", shown[2].text)
    }

    @Test
    fun `a one-slot row shows what was typed and marks no correction`() {
        val words = words("dacă")
        val shown = row.arrange(words, typed = "daca", limit = 1, correction = "dacă")

        assertEquals(1, shown.size)
        assertEquals("daca", shown[0].text)
        assertEquals(0, row.typedIndex)
        assertEquals(-1, row.appliedIndex)
    }

    @Test
    fun `nothing is marked when no word is being typed`() {
        // Predictions for the next word.
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
