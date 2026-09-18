// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WordFoldTest {

    @Test
    fun `a plain lower-case word comes back as the same instance`() {
        val word = "masina"
        assertSame(word, WordFold.fold(word))
        val hyphenated = "ma-ta"
        assertSame(hyphenated, WordFold.fold(hyphenated))
    }

    @Test
    fun `case and accents fold away`() {
        assertEquals("masina", WordFold.fold("Mașina"))
        assertEquals("masina", WordFold.fold("MAȘINA"))
        assertEquals("ecole", WordFold.fold("École"))
        assertEquals("uber", WordFold.fold("Über"))
    }

    @Test
    fun `n with tilde is a letter of its own, not an accented n`() {
        assertEquals("niño", WordFold.fold("Niño"))
        assertEquals("cono", WordFold.fold("Cono"))
    }

    @Test
    fun `comma-below and cedilla spellings of a Romanian letter meet`() {
        assertEquals(WordFold.fold("mașina"), WordFold.fold("maşina"))
        assertEquals(WordFold.fold("ție"), WordFold.fold("ţie"))
    }

    @Test
    fun `hyphens and apostrophes survive the fold`() {
        assertEquals("ma-ta", WordFold.fold("Mă-ta"))
        assertEquals("don't", WordFold.fold("Don't"))
    }

    @Test
    fun `sharp s is a letter of its own, not stripped`() {
        assertEquals("straße", WordFold.fold("Straße"))
    }
}
