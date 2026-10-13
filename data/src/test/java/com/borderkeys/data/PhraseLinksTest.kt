// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseLinksTest {

    private val pairs = MemoryPairs()
    private val triples = MemoryTriples()

    @Test
    fun `a pair's row holds the word before it and comes back as the same pair`() {
        val pair = UserBigram("ro", "vreau", "să", 3, 5L, contextSeen = 7L, languageSeen = 9L)
        assertEquals(listOf("vreau"), pair.row().context)
        assertEquals(pair, pair.row().toBigram())
        val context = UserPairContext("ro", "vreau", 7L, 5L, 8L)
        assertEquals(listOf("vreau"), context.row().context)
        assertEquals(context, context.row().toPairContext())
        assertEquals(
            LinkUpdate("ro", listOf("vreau"), "să", 2, 4L),
            LearnedBigram("ro", "vreau", "să", 2, 4L).update(),
        )
    }

    @Test
    fun `a triple's row holds the two words before it in order and comes back as the same triple`() {
        val triple = UserTrigram("ro", "mi", "e", "dor", 3, 5L, contextSeen = 7L, languageSeen = 9L)
        assertEquals(listOf("mi", "e"), triple.row().context)
        assertEquals(triple, triple.row().toTrigram())
        val context = UserTripleContext("ro", "mi", "e", 7L, 5L, 8L)
        assertEquals(listOf("mi", "e"), context.row().context)
        assertEquals(context, context.row().toTripleContext())
        assertEquals(
            LinkUpdate("ro", listOf("mi", "e"), "dor", 2, 4L),
            LearnedTrigram("ro", "mi", "e", "dor", 2, 4L).update(),
        )
    }

    @Test
    fun `the pair table reads and writes the pairs`() = runTest {
        checkTable(PairTable(pairs), listOf("de"))
        assertEquals(setOf(listOf("es", "de", "todo")), pairs.rows.keys)
        assertTrue(triples.rows.isEmpty())
    }

    @Test
    fun `the triple table reads and writes the triples`() = runTest {
        checkTable(TripleTable(triples), listOf("mult", "de"))
        assertEquals(setOf(listOf("es", "mult", "de", "todo")), triples.rows.keys)
        assertTrue(pairs.rows.isEmpty())
    }

    @Test
    fun `each table reads and moves its own clock`() {
        val clock = UserPhraseClock("ro", pairs = 1L, triples = 2L)
        assertEquals(1L, PairTable(pairs).clockIn(clock))
        assertEquals(2L, TripleTable(triples).clockIn(clock))
        assertEquals(UserPhraseClock("ro", pairs = 7L, triples = 2L), PairTable(pairs).withClock(clock, 7L))
        assertEquals(UserPhraseClock("ro", pairs = 1L, triples = 7L), TripleTable(triples).withClock(clock, 7L))
    }

    /** Writes a link after [context] in two languages through [table], reads each back, then deletes one. */
    private suspend fun checkTable(table: LinkTable, context: List<String>) {
        val link = LinkRow("ro", context, "tot", 2, 5L, contextSeen = 3L, languageSeen = 4L)
        val other = LinkRow("es", context, "todo", 1, 6L, contextSeen = 1L, languageSeen = 1L)
        val held = ContextRow("ro", context, 3L, 5L, 3L)
        table.upsert(link)
        table.upsert(other)
        table.upsertContext(held)
        table.upsertContext(ContextRow("es", context, 1L, 6L, 1L))
        assertEquals(listOf(link), table.inContext("ro", context))
        assertEquals(listOf(link), table.inLanguage("ro"))
        assertEquals(1, table.countIn("ro"))
        assertEquals(held, table.context("ro", context))
        assertEquals(listOf(held), table.contextsIn("ro"))
        assertEquals(listOf(link), table.involving("tot"))
        assertEquals(listOf(link, other), table.involving(context.last()))
        table.delete(listOf(link))
        table.deleteEmptyContexts()
        assertNull(table.context("ro", context))
        table.deleteContextsOf(context.first())
        assertNull(table.context("es", context))
    }
}
