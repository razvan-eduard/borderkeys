// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningTextTest {

    private fun admits(word: String, before: Char? = ' ') =
        RunningText.admits(word) { before }

    @Test
    fun `an ordinary word in a sentence is corrected`() {
        assertTrue(admits("cuvant"))
        assertTrue("the first word of a field has nothing in front of it", admits("hello", null))
    }

    @Test
    fun `a word that continues an address or a path is left alone`() {
        assertFalse("user@example", admits("example", '@'))
        assertFalse("example.com", admits("com", '.'))
        assertFalse("src/main", admits("main", '/'))
        assertFalse("border_keys", admits("keys", '_'))
        assertFalse("a query string", admits("value", '='))
        assertFalse("a fragment", admits("section", '#'))
        assertFalse("a home path", admits("src", '~'))
        assertFalse("after a digit", admits("beta", '2'))
    }

    @Test
    fun `a word carrying a digit is left alone whatever precedes it`() {
        assertFalse(admits("sha256sum"))
        assertFalse(admits("v2"))
    }

    @Test
    fun `an apostrophe or a hyphen in front of a word does not block it`() {
        assertTrue("l'homme", admits("homme", '\''))
        assertTrue("aşa-zis", admits("zis", '-'))
    }

    @Test
    fun `punctuation that only ever ends a clause does not block the next word`() {
        assertTrue(admits("apoi", ','))
        assertTrue(admits("da", '!'))
        assertTrue(admits("acum", ';'))
    }

    @Test
    fun `punctuation that also opens an address blocks, on purpose`() {
        assertFalse(admits("Apoi", '.'))
        assertFalse(admits("ce", '?'))
        assertFalse(admits("acum", ':'))
    }

    @Test
    fun `the lambda is only consulted once the word itself passes`() {
        var asked = 0
        RunningText.admits("v2") { asked++; ' ' }
        assertTrue("a word with a digit is settled without an editor read", asked == 0)
        RunningText.admits("cuvant") { asked++; ' ' }
        assertTrue("an ordinary word does need the character in front", asked == 1)
    }
}
