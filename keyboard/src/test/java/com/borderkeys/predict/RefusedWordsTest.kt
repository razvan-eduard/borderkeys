// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefusedWordsTest {

    @Test
    fun `nothing is refused by the empty lists`() {
        assertTrue(RefusedWords.NONE.isEmpty)
        assertFalse(RefusedWords.NONE.refuses("maine"))
    }

    @Test
    fun `a blocked word is refused in any case`() {
        val refused = RefusedWords.of(setOf("maine"), emptySet())
        assertTrue(refused.refuses("maine"))
        assertTrue(refused.refuses("Maine"))
        assertTrue(refused.refuses("MAINE"))
    }

    @Test
    fun `a word stored capitalised is blocked in any case`() {
        val refused = RefusedWords.of(setOf("Emanuel"), emptySet())
        assertTrue(refused.refuses("emanuel"))
        assertTrue(refused.refuses("Emanuel"))
    }

    @Test
    fun `a blocked word leaves the words that differ from it by an accent`() {
        val maine = RefusedWords.of(setOf("maine"), emptySet())
        assertFalse(maine.refuses("mâine"))
        assertFalse(maine.refuses("Mâine"))
        val tomorrow = RefusedWords.of(setOf("mâine"), emptySet())
        assertTrue(tomorrow.refuses("Mâine"))
        assertFalse(tomorrow.refuses("maine"))
        val ca = RefusedWords.of(setOf("ca"), emptySet())
        assertFalse(ca.refuses("că"))
    }

    @Test
    fun `an offensive word is refused in any case or spelling`() {
        val refused = RefusedWords.of(emptySet(), setOf(WordFold.fold("căcat")))
        assertTrue(refused.refuses("căcat"))
        assertTrue(refused.refuses("Căcat"))
        assertTrue(refused.refuses("cacat"))
        assertFalse(refused.refuses("capat"))
    }

    @Test
    fun `both lists apply at once`() {
        val refused = RefusedWords.of(setOf("maine"), setOf(WordFold.fold("căcat")))
        assertTrue(refused.refuses("Maine"))
        assertTrue(refused.refuses("CACAT"))
        assertFalse(refused.refuses("mâine"))
    }
}
