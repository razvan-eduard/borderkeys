// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceCaseTest {

    private fun isWord(code: Int) = Character.isLetterOrDigit(code) || code == '\''.code

    @Test
    fun `the initial flips between capital and lower case and nothing else moves`() {
        assertEquals("Apple", SentenceCase.toggleInitial("apple"))
        assertEquals("apple", SentenceCase.toggleInitial("Apple"))
        assertEquals("IPhone", SentenceCase.toggleInitial("iPhone"))
        assertEquals("Élan", SentenceCase.toggleInitial("élan"))
        assertEquals("42", SentenceCase.toggleInitial("42"))
        assertEquals("", SentenceCase.toggleInitial(""))
    }

    @Test
    fun `the word at the caret is the one it is inside, touching, or just after`() {
        val text = "hello there world"
        assertEquals(0 until 5, SentenceCase.wordAt(text, 2, ::isWord))
        assertEquals(0 until 5, SentenceCase.wordAt(text, 5, ::isWord))
        assertEquals(6 until 11, SentenceCase.wordAt(text, 6, ::isWord))
        assertEquals(6 until 11, SentenceCase.wordAt(text, 7, ::isWord))
        assertEquals(12 until 17, SentenceCase.wordAt(text, 17, ::isWord))
        assertEquals(12 until 17, SentenceCase.wordAt("hello there world  ", 19, ::isWord))
        assertNull(SentenceCase.wordAt("   ", 2, ::isWord))
        assertNull(SentenceCase.wordAt("", 0, ::isWord))
    }

    @Test
    fun `every sentence starts with a capital and the rest is untouched`() {
        assertEquals("Hello there. I am here", SentenceCase.capitaliseSentences("hello there. i am here"))
        assertEquals("Hello! Yes? No… Sure.", SentenceCase.capitaliseSentences("hello! yes? no… sure."))
        assertEquals("One line\nAnother line", SentenceCase.capitaliseSentences("one line\nanother line"))
        assertEquals("Already Fine. STAYS", SentenceCase.capitaliseSentences("Already Fine. STAYS"))
    }

    @Test
    fun `a mark without a space after it ends nothing`() {
        assertEquals("Costs 3.5 apples, e.g. These.", SentenceCase.capitaliseSentences("costs 3.5 apples, e.g. these."))
        assertEquals("See www.example.com now", SentenceCase.capitaliseSentences("see www.example.com now"))
    }

    @Test
    fun `quotes and brackets are looked through and digits start no sentence`() {
        assertEquals("\"Hello.\" She said (quietly). Then", SentenceCase.capitaliseSentences("\"hello.\" she said (quietly). then"))
        assertEquals("3 apples. Then 4 pears", SentenceCase.capitaliseSentences("3 apples. then 4 pears"))
        assertEquals("  Leading spaces", SentenceCase.capitaliseSentences("  leading spaces"))
    }

    @Test
    fun `the length never changes`() {
        val samples = listOf("hello there. i am here", "ß straße. ﬁne", "emoji 😀. next", "")
        for (sample in samples) {
            assertEquals(sample, sample.length, SentenceCase.capitaliseSentences(sample).length)
        }
    }
}
