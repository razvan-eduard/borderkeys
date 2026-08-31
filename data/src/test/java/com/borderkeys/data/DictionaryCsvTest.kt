// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryCsvTest {

    @Test
    fun `words containing commas and quotes survive a round trip`() {
        val words = listOf(
            UserWord("mașina", "ro-RO", 12, 1000),
            UserWord("hello,world", "en-US", 3, 2000),
            UserWord("say \"what\"", "en-US", 5, 3000),
            UserWord("plain", "en-US", 1, 4000),
        )
        val decoded = DictionaryCsv.decode(DictionaryCsv.encode(words), now = 0)
        assertEquals(words.size, decoded.size)
        assertEquals(words.map { it.word }, decoded.map { it.word })
        assertEquals(words.map { it.count }, decoded.map { it.delta })
        assertEquals(words.map { it.lastUsedAt }, decoded.map { it.lastUsedAt })
    }

    @Test
    fun `the header is skipped and not read as a word`() {
        val decoded = DictionaryCsv.decode("${DictionaryCsv.HEADER}\nword,en-US,2,10\n", now = 0)
        assertEquals(1, decoded.size)
        assertEquals("word", decoded[0].word)
    }

    @Test
    fun `a file without a header still imports`() {
        // Someone will hand-write one of these. It should work.
        val decoded = DictionaryCsv.decode("alpha,en-US,4\nbeta,en-US,9\n", now = 77)
        assertEquals(2, decoded.size)
        assertEquals(77L, decoded[0].lastUsedAt)
    }

    @Test
    fun `bad rows are skipped without losing the good ones`() {
        val csv = """
            good,en-US,5,100
            missing-count,en-US
            zero,en-US,0,100
            negative,en-US,-4,100
            not-a-number,en-US,many,100
            ,en-US,3,100
            alsogood,ro-RO,2,200
        """.trimIndent()
        val decoded = DictionaryCsv.decode(csv, now = 0)
        assertEquals(listOf("good", "alsogood"), decoded.map { it.word })
    }

    @Test
    fun `an over-long word is refused`() {
        val decoded = DictionaryCsv.decode("${"a".repeat(65)},en-US,3,1\n", now = 0)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `escaping only quotes what needs it`() {
        assertEquals("plain", DictionaryCsv.escape("plain"))
        assertEquals("\"a,b\"", DictionaryCsv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", DictionaryCsv.escape("say \"hi\""))
    }

    @Test
    fun `parsing handles quoted fields containing separators`() {
        assertEquals(listOf("a,b", "c"), DictionaryCsv.parseLine("\"a,b\",c"))
        assertEquals(listOf("say \"hi\"", "x"), DictionaryCsv.parseLine("\"say \"\"hi\"\"\",x"))
        assertEquals(listOf("", "", ""), DictionaryCsv.parseLine(",,"))
    }
}
