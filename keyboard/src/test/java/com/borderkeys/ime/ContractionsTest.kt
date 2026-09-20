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

    /**
     * The rule the objector column exists for. "lets" is an ordinary word in Romanian and
     * German, so the mapping is right for someone writing only English and wrong the moment
     * either of those is switched on.
     */
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
    fun `two languages merge into one table`() {
        val french = listOf(Contractions.Entry("cest", "c'est", emptyList()))
        val table = Contractions.of(listOf(english, french), listOf("en-US", "fr-FR"))
        assertEquals("c'est", Contractions.expansionFor("cest", table))
        assertEquals("don't", Contractions.expansionFor("dont", table))
    }
}
