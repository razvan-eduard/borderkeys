// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTripleContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseRecorderTest {

    private val pairs = MemoryPairs()
    private val triples = MemoryTriples()
    private val clocks = MemoryClocks()
    private val recorder = PhraseRecorder(pairs, triples, clocks)

    private fun pair(previous: String, word: String, uses: Int = 1, language: String = "en", at: Long = 1L) =
        LearnedBigram(language, previous, word, uses, at)

    @Test
    fun `each use adds to the pair, its context's total and its language's clock, and stamps both`() = runTest {
        recorder.recordPairs(listOf(pair("vreau", "să", language = "ro")).map(::PairStep))
        recorder.recordPairs(listOf(pair("vreau", "acasă", language = "ro")).map(::PairStep))
        recorder.recordPairs(listOf(pair("vreau", "să", uses = 2, language = "ro", at = 5L)).map(::PairStep))
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
        recorder.recordPairs(listOf(pair("de", "la", language = "ro"), pair("de", "la", language = "es")).map(::PairStep))
        assertEquals(2, pairs.rows.size)
        assertEquals(1L, pairs.contexts.getValue(listOf("ro", "de")).total)
        assertEquals(1L, pairs.contexts.getValue(listOf("es", "de")).total)
    }

    @Test
    fun `a full context gives up its stalest newcomer, never the pair just recorded`() = runTest {
        val established = PhraseBudget.PAIRS.established
        val newcomers = PhraseBudget.PAIRS.newcomers
        for (i in 0 until established) {
            recorder.recordPairs(listOf(pair("the", "word$i", uses = PhraseBudget.PAIRS.promotion)).map(::PairStep))
        }
        for (i in 0 until newcomers) {
            recorder.recordPairs(listOf(pair("the", "new$i")).map(::PairStep))
        }
        val dropped = recorder.recordPairs(listOf(pair("the", "newest")).map(::PairStep))
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
    fun `a replay takes out the pairs the engine dropped at their step, and gives up no more`() = runTest {
        val cat = UserBigram("en", "the", "cat", 1, 1L, contextSeen = 1L, languageSeen = 1L)
        val dropped = recorder.recordPairs(
            listOf(
                PairStep(pair("the", "cat")),
                PairStep(pair("the", "dog")),
                PairStep(pair("the", "fox"), listOf(cat)),
                PairStep(pair("the", "cat")),
            ),
        )
        assertEquals(emptyList<UserBigram>(), dropped)
        assertEquals(setOf("dog", "fox", "cat"), pairs.inContext("en", "the").map { it.word }.toSet())
        assertEquals(
            "a pair dropped and written again counts from its new use",
            1, pairs.rows.getValue(listOf("en", "the", "cat")).count,
        )
        assertEquals("the context's total keeps every use", 4L, pairs.contexts.getValue(listOf("en", "the")).total)
    }

    @Test
    fun `a pair the engine never held is given up by the store's own pass and reported`() = runTest {
        // The store holds a full context the engine was never handed, so the engine drops nothing.
        for (i in 0 until PhraseBudget.PAIRS.most) {
            pairs.upsert(UserBigram("en", "the", "held$i", 1, 1L, contextSeen = i + 1L, languageSeen = i + 1L))
        }
        val most = PhraseBudget.PAIRS.most.toLong()
        pairs.upsertContext(UserPairContext("en", "the", most, 1L, seen = most))
        clocks.upsert(UserPhraseClock("en", most, 0L))
        val dropped = recorder.recordPairs(listOf(PairStep(pair("the", "newest"))))
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
        val dropped = recorder.recordPairs(listOf(pair("fresh", "pair")).map(::PairStep))
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
        val dropped = recorder.recordPairs(listOf(pair("fresh", "pair")).map(::PairStep))
        assertEquals(listOf("x", "y", "z"), dropped.take(filled).map { it.word })
    }

    @Test
    fun `a context's observations count every use, and the sweep halves only its uses`() = runTest {
        val cat = 4
        val dog = 2
        recorder.recordPairs(listOf(pair("the", "cat", uses = cat), pair("the", "dog", uses = dog)).map(::PairStep))
        val uses = (cat + dog).toLong()
        assertEquals(uses, pairs.contexts.getValue(listOf("en", "the")).seen)
        pairs.decayStaleContexts(cutoff = Long.MAX_VALUE, now = 2L)
        val swept = pairs.contexts.getValue(listOf("en", "the"))
        assertEquals(uses / PersonalWordDecay.HALVING, swept.total)
        assertEquals(uses, swept.seen)
        recorder.recordPairs(listOf(pair("the", "cat")).map(::PairStep))
        assertEquals(uses + 1, pairs.rows.getValue(listOf("en", "the", "cat")).contextSeen)
    }

    @Test
    fun `a context no link follows any more goes`() = runTest {
        recorder.recordPairs(listOf(pair("the", "cat"), pair("a", "cat"), pair("a", "dog")).map(::PairStep))
        recorder.recordTriples(
            listOf(LearnedTrigram("en", "on", "the", "cat", 1, 1L), LearnedTrigram("en", "in", "a", "row", 1, 1L)).map(::TripleStep),
        )
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
        recorder.recordPairs(listOf(pair("the", "cat", uses = cat), pair("the", "dog", uses = dog)).map(::PairStep))
        recorder.forgetPair(pairs.rows.getValue(listOf("en", "the", "cat")))
        val context = pairs.contexts.getValue(listOf("en", "the"))
        assertEquals(dog.toLong(), context.total)
        assertEquals(dog.toLong(), context.seen)
    }

    @Test
    fun `forgetting a word takes its pairs out, and their uses out of the totals they counted in`() = runTest {
        recorder.recordPairs(listOf(pair("the", "cat", uses = 2), pair("the", "dog", uses = 3), pair("cat", "sat")).map(::PairStep))
        recorder.recordTriples(listOf(LearnedTrigram("en", "the", "cat", "sat", 1, 1L)).map(::TripleStep))
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
        recorder.recordPairs(listOf(pair("good", "morning")).map(::PairStep))
        recorder.recordTriples(
            listOf(
                LearnedTrigram("en", "good", "morning", "all", 1, 1L),
                LearnedTrigram("en", "very", "good", "morning", 1, 1L),
                LearnedTrigram("en", "good", "night", "all", 1, 1L),
            ).map(::TripleStep),
        )
        recorder.forgetPair(pairs.rows.getValue(listOf("en", "good", "morning")))
        assertTrue(pairs.rows.isEmpty())
        assertEquals(listOf("night"), triples.rows.values.map { it.previousWord1 })
    }

    @Test
    fun `an import keeps each context by aged score once everything is in`() = runTest {
        val links = (0 until PhraseBudget.PAIRS.most + 2).map { pair("the", "w$it", uses = it + 1) }
        val dropped = recorder.importLinks(links, emptyList())
        assertEquals(listOf("w0", "w1"), dropped.pairs.map { it.word }.sorted())
        assertEquals(PhraseBudget.PAIRS.most, pairs.inContext("en", "the").size)
    }

    @Test
    fun `an import with a context's carried uses ends with the device's plus the file's`() = runTest {
        val device = 2
        recorder.recordPairs(listOf(pair("the", "cat", uses = device)).map(::PairStep))
        recorder.recordTriples(listOf(LearnedTrigram("en", "on", "the", "cat", device, 1L)).map(::TripleStep))
        val linkUses = 3
        val carriedTotal = 10L
        val carriedSeen = 12L
        recorder.importLinks(
            listOf(pair("the", "dog", uses = linkUses)),
            listOf(LearnedTrigram("en", "on", "the", "dog", linkUses, 1L)),
            listOf(UserPairContext("en", "the", carriedTotal, 1L, carriedSeen)),
            listOf(UserTripleContext("en", "on", "the", carriedTotal, 1L, carriedSeen)),
        )
        val pairContext = pairs.contexts.getValue(listOf("en", "the"))
        assertEquals(device + carriedTotal, pairContext.total)
        assertEquals(device + carriedSeen, pairContext.seen)
        val tripleContext = triples.contexts.getValue(listOf("en", "on", "the"))
        assertEquals(device + carriedTotal, tripleContext.total)
        assertEquals(device + carriedSeen, tripleContext.seen)
    }

    @Test
    fun `a carried count below its links' uses adds the links alone, and one no link follows adds nothing`() = runTest {
        val linkUses = 3
        val below = linkUses - 1L
        recorder.importLinks(
            listOf(pair("the", "dog", uses = linkUses)),
            emptyList(),
            listOf(UserPairContext("en", "the", below, 1L, below), UserPairContext("en", "a", below, 1L, below)),
        )
        assertEquals(linkUses.toLong(), pairs.contexts.getValue(listOf("en", "the")).total)
        assertNull(pairs.contexts[listOf("en", "a")])
    }

    @Test
    fun `an imported last use stands, and a last use never moves back`() = runTest {
        val earlier = 5L
        val later = 9L
        recorder.importLinks(listOf(pair("the", "cat", at = earlier)), emptyList())
        assertEquals(earlier, pairs.rows.getValue(listOf("en", "the", "cat")).lastUsedAt)
        recorder.recordPairs(listOf(pair("the", "cat", at = later)).map(::PairStep))
        recorder.importLinks(listOf(pair("the", "cat", at = earlier)), emptyList())
        assertEquals(later, pairs.rows.getValue(listOf("en", "the", "cat")).lastUsedAt)
        assertEquals(later, pairs.contexts.getValue(listOf("en", "the")).lastUsedAt)
    }

    @Test
    fun `an import stamps each link at the language's clock without moving it`() = runTest {
        val clock = 7L
        clocks.upsert(UserPhraseClock("en", clock, clock))
        val uses = 3
        recorder.importLinks(
            listOf(pair("the", "cat", uses = uses)), listOf(LearnedTrigram("en", "on", "the", "cat", uses, 1L)),
        )
        val pair = pairs.rows.getValue(listOf("en", "the", "cat"))
        assertEquals(clock, pair.languageSeen)
        assertEquals(uses.toLong(), pair.contextSeen)
        assertEquals(clock, triples.rows.getValue(listOf("en", "on", "the", "cat")).languageSeen)
        assertEquals(UserPhraseClock("en", clock, clock), clocks.rows.getValue("en"))
    }

    @Test
    fun `an import leaves out a pair whose two words are one, and such a triple`() = runTest {
        recorder.importLinks(listOf(pair("very", "Very")), listOf(LearnedTrigram("en", "so", "very", "VERY", 1, 1L)))
        assertTrue(pairs.rows.isEmpty())
        assertTrue(triples.rows.isEmpty())
        assertTrue(pairs.contexts.isEmpty())
    }

    @Test
    fun `triples are kept by their two-word context`() = runTest {
        val established = PhraseBudget.TRIPLES.established
        for (i in 0 until established) {
            recorder.recordTriples(listOf(LearnedTrigram("en", "on", "the", "w$i", PhraseBudget.TRIPLES.promotion, 1L)).map(::TripleStep))
        }
        for (i in 0..PhraseBudget.TRIPLES.newcomers) {
            recorder.recordTriples(listOf(LearnedTrigram("en", "on", "the", "new$i", 1, 1L)).map(::TripleStep))
        }
        assertEquals(PhraseBudget.TRIPLES.most, triples.inContext("en", "on", "the").size)
        assertFalse(triples.rows.containsKey(listOf("en", "on", "the", "new0")))
        assertEquals(
            (established * PhraseBudget.TRIPLES.promotion + PhraseBudget.TRIPLES.newcomers + 1).toLong(),
            triples.contexts.getValue(listOf("en", "on", "the")).total,
        )
    }
}
