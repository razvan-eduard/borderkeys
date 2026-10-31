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
import com.borderkeys.data.personal.recordUses
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairs and triples the tables hold: uses recorded as typing records them, through the lookup
 * and a flush's write ([recordUses]), and the forgets, phrases added by hand and restores.
 */
class PhraseRecorderTest {

    private val pairs = MemoryPairs()
    private val triples = MemoryTriples()
    private val clocks = MemoryClocks()
    private val recorder = PhraseRecorder(pairs, triples, clocks)

    private fun pair(previous: String, word: String, uses: Int = 1, language: String = "en", at: Long = 1L) =
        LearnedBigram(language, previous, word, uses, at)

    /** Records [pairUses] and [tripleUses] as typing does, and returns the rows written. */
    private suspend fun record(pairUses: List<LearnedBigram> = emptyList(), tripleUses: List<LearnedTrigram> = emptyList()) =
        recordUses(pairs, triples, clocks, pairUses, tripleUses)

    @Test
    fun `each use adds to the pair, its context's total and its language's clock, and stamps both`() = runTest {
        record(pairUses = listOf(pair("vreau", "să", language = "ro")))
        record(pairUses = listOf(pair("vreau", "acasă", language = "ro")))
        record(pairUses = listOf(pair("vreau", "să", uses = 2, language = "ro", at = 5L)))
        val held = pairs.rows.getValue(listOf("ro", "vreau", "să"))
        assertEquals(3, held.count)
        assertEquals(5L, held.lastUsedAt)
        assertEquals(4L, pairs.contexts.getValue(listOf("ro", "vreau")).total)
        assertEquals(4L, held.contextSeen)
        assertEquals(4L, held.languageSeen)
        assertEquals(UserPhraseClock("ro", pairs = 4L, triples = 0L), clocks.rows.getValue("ro"))
    }

    @Test
    fun `a language's pairs are kept apart from another's`() = runTest {
        record(pairUses = listOf(pair("de", "la", language = "ro"), pair("de", "la", language = "es")))
        assertEquals(2, pairs.rows.size)
        assertEquals(1L, pairs.contexts.getValue(listOf("ro", "de")).total)
        assertEquals(1L, pairs.contexts.getValue(listOf("es", "de")).total)
    }

    @Test
    fun `a full context gives up its stalest newcomer, never the pair just recorded`() = runTest {
        val established = PhraseBudget.PAIRS.established
        val newcomers = PhraseBudget.PAIRS.newcomers
        for (i in 0 until established) {
            record(pairUses = listOf(pair("the", "word$i", uses = PhraseBudget.PAIRS.promotion)))
        }
        for (i in 0 until newcomers) {
            record(pairUses = listOf(pair("the", "new$i")))
        }
        val dropped = record(pairUses = listOf(pair("the", "newest"))).gonePairs
        assertEquals(listOf("new0"), dropped.map { it.word })
        assertEquals(PhraseBudget.PAIRS.most, pairs.inContext("en", "the").size)
        assertTrue(pairs.rows.containsKey(listOf("en", "the", "newest")))
        assertEquals(
            "the context's total keeps the dropped pair's use",
            (established * PhraseBudget.PAIRS.promotion + newcomers + 1).toLong(),
            pairs.contexts.getValue(listOf("en", "the")).total,
        )
    }

    @Test
    fun `a full context the store holds gives up its stalest link at its next use`() = runTest {
        for (i in 0 until PhraseBudget.PAIRS.most) {
            pairs.upsert(UserBigram("en", "the", "held$i", 1, 1L, contextSeen = i + 1L, languageSeen = i + 1L))
        }
        val most = PhraseBudget.PAIRS.most.toLong()
        pairs.upsertContext(UserPairContext("en", "the", most, 1L, seen = most))
        clocks.upsert(UserPhraseClock("en", most, 0L))
        val dropped = record(pairUses = listOf(pair("the", "newest"))).gonePairs
        assertEquals(listOf("held0"), dropped.map { it.word })
        assertEquals(PhraseBudget.PAIRS.most, pairs.inContext("en", "the").size)
        assertTrue(pairs.rows.containsKey(listOf("en", "the", "newest")))
    }

    @Test
    fun `a language past its cap is pruned to nine in ten, never the pair just recorded`() = runTest {
        for (i in 0 until PhraseBudget.LANGUAGE_CAP) {
            pairs.upsert(UserBigram("en", "w$i", "x$i", 1, 1L, contextSeen = 1L, languageSeen = i.toLong()))
            pairs.upsertContext(UserPairContext("en", "w$i", 1L, 1L, seen = 1L))
        }
        clocks.upsert(UserPhraseClock("en", PhraseBudget.LANGUAGE_CAP.toLong(), 0L))
        val dropped = record(pairUses = listOf(pair("fresh", "pair"))).gonePairs
        assertEquals(PhraseBudget.LANGUAGE_CAP + 1 - PhraseBudget.LANGUAGE_KEEP, dropped.size)
        assertEquals(PhraseBudget.LANGUAGE_KEEP, pairs.countIn("en"))
        assertTrue(pairs.rows.containsKey(listOf("en", "fresh", "pair")))
        assertFalse("the stalest go first", pairs.rows.containsKey(listOf("en", "w0", "x0")))
        assertNull("and their contexts with them", pairs.contexts[listOf("en", "w0")])
    }

    @Test
    fun `the language prune breaks ties on a share of no less than the context's links`() = runTest {
        val clock = PhraseBudget.LANGUAGE_CAP.toLong()
        val low = PhraseBudget.PAIRS.promotion
        val high = low + 1
        fun link(previous: String, word: String, count: Int) =
            UserBigram("en", previous, word, count, 1L, contextSeen = 1L, languageSeen = clock)
        val below = 1L
        pairs.upsert(link("a", "x", low))
        pairs.upsert(link("a", "y", low))
        pairs.upsertContext(UserPairContext("en", "a", below, 1L, seen = below))
        val above = (low + 1).toLong()
        pairs.upsert(link("b", "z", low))
        pairs.upsertContext(UserPairContext("en", "b", above, 1L, seen = above))
        val filled = pairs.countIn("en")
        for (i in 0 until PhraseBudget.LANGUAGE_CAP - filled) {
            pairs.upsert(link("f$i", "g$i", high))
            pairs.upsertContext(UserPairContext("en", "f$i", high.toLong(), 1L, seen = high.toLong()))
        }
        clocks.upsert(UserPhraseClock("en", clock, 0L))
        val dropped = record(pairUses = listOf(pair("fresh", "pair"))).gonePairs
        assertEquals(listOf("x", "y", "z"), dropped.take(filled).map { it.word })
    }

    @Test
    fun `a context's observations count every use, and the sweep halves only its uses`() = runTest {
        val cat = 4
        val dog = 2
        record(pairUses = listOf(pair("the", "cat", uses = cat), pair("the", "dog", uses = dog)))
        val uses = (cat + dog).toLong()
        assertEquals(uses, pairs.contexts.getValue(listOf("en", "the")).seen)
        pairs.decayStaleContexts(cutoff = Long.MAX_VALUE, now = 2L)
        val swept = pairs.contexts.getValue(listOf("en", "the"))
        assertEquals(uses / PersonalWordDecay.HALVING, swept.total)
        assertEquals(uses, swept.seen)
        record(pairUses = listOf(pair("the", "cat")))
        assertEquals(uses + 1, pairs.rows.getValue(listOf("en", "the", "cat")).contextSeen)
    }

    @Test
    fun `a context no link follows any more goes`() = runTest {
        record(pairUses = listOf(pair("the", "cat"), pair("a", "cat"), pair("a", "dog")))
        record(tripleUses = listOf(LearnedTrigram("en", "on", "the", "cat", 1, 1L), LearnedTrigram("en", "in", "a", "row", 1, 1L)))
        recorder.forgetPair(pairs.rows.getValue(listOf("en", "a", "dog")))
        assertTrue("a context with a link left stays", pairs.contexts.containsKey(listOf("en", "a")))
        recorder.forgetTriple(triples.rows.getValue(listOf("en", "in", "a", "row")))
        assertNull(triples.contexts[listOf("en", "in", "a")])
        recorder.forget("cat")
        assertNull(pairs.contexts[listOf("en", "the")])
        assertNull(pairs.contexts[listOf("en", "a")])
        assertTrue(triples.contexts.isEmpty())
    }

    @Test
    fun `forgetting a pair takes its uses off its context's total and observations`() = runTest {
        val cat = 2
        val dog = 3
        record(pairUses = listOf(pair("the", "cat", uses = cat), pair("the", "dog", uses = dog)))
        recorder.forgetPair(pairs.rows.getValue(listOf("en", "the", "cat")))
        val context = pairs.contexts.getValue(listOf("en", "the"))
        assertEquals(dog.toLong(), context.total)
        assertEquals(dog.toLong(), context.seen)
    }

    @Test
    fun `forgetting a word takes its pairs out, and their uses out of the totals they counted in`() = runTest {
        record(pairUses = listOf(pair("the", "cat", uses = 2), pair("the", "dog", uses = 3), pair("cat", "sat")))
        record(tripleUses = listOf(LearnedTrigram("en", "the", "cat", "sat", 1, 1L)))
        recorder.forget("Cat")
        assertNull(pairs.rows[listOf("en", "the", "cat")])
        assertNull(pairs.rows[listOf("en", "cat", "sat")])
        assertNull("a context the word was goes", pairs.contexts[listOf("en", "cat")])
        assertEquals(3L, pairs.contexts.getValue(listOf("en", "the")).total)
        assertTrue(triples.rows.isEmpty())
        assertTrue(triples.contexts.isEmpty())
    }

    @Test
    fun `forgetting a pair takes the triples through it`() = runTest {
        record(pairUses = listOf(pair("good", "morning")))
        record(tripleUses = listOf(
                LearnedTrigram("en", "good", "morning", "all", 1, 1L),
                LearnedTrigram("en", "very", "good", "morning", 1, 1L),
                LearnedTrigram("en", "good", "night", "all", 1, 1L),
            ))
        recorder.forgetPair(pairs.rows.getValue(listOf("en", "good", "morning")))
        assertTrue(pairs.rows.isEmpty())
        assertEquals(listOf("night"), triples.rows.values.map { it.previousWord1 })
    }

    /** A pair a restore reads: [uses] of [word] after [previous] in English, last used [at], [seen] its observations stamp. */
    private fun restored(previous: String, word: String, uses: Int = 1, at: Long = 1L, seen: Long = 0L, asserted: Int = 0) =
        UserBigram("en", previous, word, uses, at, seen, 0L, asserted)

    @Test
    fun `a restore keeps every link it brings, the budget being the lookup's at the context's next commit`() = runTest {
        val links = (0 until PhraseBudget.PAIRS.most + 2).map { restored("the", "w$it", uses = it + 1) }
        recorder.restore(links, emptyList(), emptyList(), emptyList())
        assertEquals(PhraseBudget.PAIRS.most + 2, pairs.inContext("en", "the").size)
    }

    @Test
    fun `a phrase added by hand to a language past its cap keeps every link, the budget being the lookup's`() = runTest {
        val held = PhraseBudget.LANGUAGE_CAP + 1
        for (i in 0 until held) {
            pairs.upsert(UserBigram("en", "w$i", "x$i", 1, 1L, contextSeen = 1L, languageSeen = i.toLong()))
            pairs.upsertContext(UserPairContext("en", "w$i", 1L, 1L, seen = 1L))
        }
        clocks.upsert(UserPhraseClock("en", held.toLong(), 0L))
        recorder.addPhrase("en", listOf("fresh", "pair"), 1L)
        assertEquals(held + 1, pairs.countIn("en"))
    }

    @Test
    fun `a restore keeps the larger of each link's count, stamps and marks, and each context the largest total and observations`() = runTest {
        record(pairUses = listOf(pair("the", "cat", uses = 5, at = 9L)))
        recorder.restore(
            listOf(restored("the", "cat", uses = 2, at = 4L, seen = 50L, asserted = 1), restored("the", "dog", uses = 3, at = 4L, seen = 7L)),
            listOf(UserPairContext("en", "the", 6L, 4L, 40L)),
            listOf(UserTrigram("en", "on", "the", "cat", 2, 4L, 0L, 0L)),
            listOf(UserTripleContext("en", "on", "the", 1L, 4L, 1L)),
        )
        val cat = pairs.rows.getValue(listOf("en", "the", "cat"))
        assertEquals(listOf(5L, 9L, 50L, 1L), listOf(cat.count.toLong(), cat.lastUsedAt, cat.contextSeen, cat.asserted.toLong()))
        assertEquals("the device's links and the file's together", UserPairContext("en", "the", 8L, 9L, 40L), pairs.contexts.getValue(listOf("en", "the")))
        assertEquals("a context below its links' uses held at them", UserTripleContext("en", "on", "the", 2L, 4L, 2L), triples.contexts.getValue(listOf("en", "on", "the")))
    }

    @Test
    fun `restoring the same links again changes nothing`() = runTest {
        record(pairUses = listOf(pair("the", "cat", uses = 2)))
        val links = listOf(restored("the", "cat", uses = 4, at = 3L), restored("the", "dog"))
        val contexts = listOf(UserPairContext("en", "the", 9L, 3L, 11L))
        recorder.restore(links, contexts, emptyList(), emptyList())
        val once = pairs.rows.toMap() to pairs.contexts.toMap()
        recorder.restore(links, contexts, emptyList(), emptyList())
        assertEquals(once, pairs.rows.toMap() to pairs.contexts.toMap())
    }

    @Test
    fun `a restored context no restored link follows is left out`() = runTest {
        recorder.restore(listOf(restored("the", "dog")), listOf(UserPairContext("en", "a", 2L, 1L, 2L)), emptyList(), emptyList())
        assertNull(pairs.contexts[listOf("en", "a")])
    }

    @Test
    fun `a restore stamps each link at the language's clock without moving it, and at its context's observations when it carries none`() = runTest {
        val clock = 7L
        clocks.upsert(UserPhraseClock("en", clock, clock))
        val uses = 3
        recorder.restore(
            listOf(restored("the", "cat", uses = uses)), emptyList(),
            listOf(UserTrigram("en", "on", "the", "cat", uses, 1L, 0L, 0L)), emptyList(),
        )
        val pair = pairs.rows.getValue(listOf("en", "the", "cat"))
        assertEquals(clock, pair.languageSeen)
        assertEquals(uses.toLong(), pair.contextSeen)
        assertEquals(clock, triples.rows.getValue(listOf("en", "on", "the", "cat")).languageSeen)
        assertEquals(UserPhraseClock("en", clock, clock), clocks.rows.getValue("en"))
    }

    @Test
    fun `triples are kept by their two-word context`() = runTest {
        val established = PhraseBudget.TRIPLES.established
        for (i in 0 until established) {
            record(tripleUses = listOf(LearnedTrigram("en", "on", "the", "w$i", PhraseBudget.TRIPLES.promotion, 1L)))
        }
        for (i in 0..PhraseBudget.TRIPLES.newcomers) {
            record(tripleUses = listOf(LearnedTrigram("en", "on", "the", "new$i", 1, 1L)))
        }
        assertEquals(PhraseBudget.TRIPLES.most, triples.inContext("en", "on", "the").size)
        assertFalse(triples.rows.containsKey(listOf("en", "on", "the", "new0")))
        assertEquals(
            (established * PhraseBudget.TRIPLES.promotion + PhraseBudget.TRIPLES.newcomers + 1).toLong(),
            triples.contexts.getValue(listOf("en", "on", "the")).total,
        )
    }
}
