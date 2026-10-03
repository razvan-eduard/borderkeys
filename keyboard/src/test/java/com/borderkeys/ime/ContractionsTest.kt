// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractionsTest {

    private val english = listOf(
        Contractions.Entry("dont", "don't", emptyList()),
        Contractions.Entry("cant", "can't", emptyList()),
        Contractions.Entry("lets", "let's", listOf("ro-RO", "de-DE")),
    )

    @Test
    fun `a bare spelling is written with its apostrophe`() {
        val table = Contractions.of(listOf(english), listOf("en-US"))
        assertEquals("don't", Contractions.expansionFor("dont", table))
        assertEquals("can't", Contractions.expansionFor("cant", table))
    }

    @Test
    fun `a word with no mapping is left exactly as typed`() {
        val table = Contractions.of(listOf(english), listOf("en-US"))
        assertNull(Contractions.expansionFor("its", table))
        assertNull(Contractions.expansionFor("were", table))
        assertNull("nothing at all is not a lookup", Contractions.expansionFor("", table))
    }

    @Test
    fun `a capital the user typed survives the rewrite`() {
        val table = Contractions.of(listOf(english), listOf("en-US"))
        assertEquals("Don't", Contractions.expansionFor("Dont", table))
    }

    @Test
    fun `an entry another enabled language claims is dropped`() {
        val alone = Contractions.of(listOf(english), listOf("en-US"))
        assertEquals("let's", Contractions.expansionFor("lets", alone))

        val withRomanian = Contractions.of(listOf(english), listOf("en-US", "ro-RO"))
        assertNull(Contractions.expansionFor("lets", withRomanian))
        assertEquals(
            "and the entries nobody objects to are untouched",
            "don't", Contractions.expansionFor("dont", withRomanian),
        )
    }

    @Test
    fun `the file format survives comments, blanks and short lines`() {
        val entries = Contractions.parse(
            """
            # a comment

            dont	don't
            lets	let's	ro-RO,de-DE
            broken
            	written
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals("don't", entries[0].written)
        assertTrue(entries[0].objectors.isEmpty())
        assertEquals(listOf("ro-RO", "de-DE"), entries[1].objectors)
    }

    @Test
    fun `the English pronoun is written with its capital`() {
        val entries = Contractions.parse(
            "i\tI\tit-IT\nim\tI'm\tro-RO\nive\tI've\tro-RO\n",
        )
        val table = Contractions.of(listOf(entries), listOf("en-US"))
        assertEquals("I", Contractions.expansionFor("i", table))
        assertEquals("I'm", Contractions.expansionFor("im", table))
        assertEquals("I've", Contractions.expansionFor("ive", table))
    }

    @Test
    fun `the pronoun stands down for a language that uses the letter`() {
        val entries = Contractions.parse("i\tI\tit-IT\n")
        val both = Contractions.of(listOf(entries), listOf("en-US", "it-IT"))
        assertNull(Contractions.expansionFor("i", both))
    }

    @Test
    fun `a pair line carries both counts, and a malformed one is skipped`() {
        val pairs = Contractions.parsePairs("# header\nill\tI'll\t1989\t2613\nwell\twe'll\tx\t3\nits\tit's\n")
        assertEquals(listOf(Contractions.PairEntry("ill", "I'll", 1989, 2613)), pairs)
    }

    @Test
    fun `an English twin goes ahead only when commoner and never as an 's form`() {
        val english = listOf(
            Contractions.PairEntry("ill", "I'll", 1989, 2613),
            Contractions.PairEntry("its", "it's", 113572, 60120),
            Contractions.PairEntry("peoples", "people's", 1608, 3592),
        )
        val twins = Contractions.twinsOf(listOf("en-US" to english))
        assertEquals(Contractions.Twin("I'll", ahead = true), twins["ill"])
        assertEquals(Contractions.Twin("it's", ahead = false), twins["its"])
        assertEquals(Contractions.Twin("people's", ahead = false), twins["peoples"])
    }

    @Test
    fun `outside English a twin stays second, and the first language that pairs a spelling keeps it`() {
        val french = listOf(Contractions.PairEntry("lange", "l'ange", 76, 178))
        val italian = listOf(Contractions.PairEntry("lange", "l'ange", 1, 9))
        val twins = Contractions.twinsOf(listOf("fr-FR" to french, "it-IT" to italian))
        assertEquals(Contractions.Twin("l'ange", ahead = false), twins["lange"])
        assertEquals(twins["lange"], Contractions.twinOf("Lange", twins))
    }

    @Test
    fun `two languages merge into one table`() {
        val french = listOf(Contractions.Entry("cest", "c'est", emptyList()))
        val table = Contractions.of(listOf(english, french), listOf("en-US", "fr-FR"))
        assertEquals("c'est", Contractions.expansionFor("cest", table))
        assertEquals("don't", Contractions.expansionFor("dont", table))
    }
}
