// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.personal

import com.borderkeys.data.MemoryDictionary
import com.borderkeys.data.PhraseBudget
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link rows the lookup's commits change, written by a learning flush into the tables. */
class LinkFlushTest {

    private val memory = MemoryDictionary()
    private val lookup = PersonalLookup()
    private var nextId = 0

    /** Commits [word] after [previous] in the lookup, its words held and learnable, and returns the rows it changed. */
    private fun commit(word: String, previous: String): LinkWrites {
        val rows = lookup.commit(LANGUAGE, LearnedWord(word, LANGUAGE, 1, AT), previous, null)
        lookup.assigned(rows, IntArray(rows.size) { if (rows.ids[it] >= 0) rows.ids[it] else nextId++ }, BooleanArray(rows.size))
        return lookup.commitLinks(LANGUAGE, word, previous, null, false, false, AT) { words -> words.map { true } }
    }

    @Test
    fun `the budget runs once, at the commit, and the flush writes what the lookup kept and deletes what it gave up`() = runTest {
        val followers = (0 until PhraseBudget.PAIRS.most).map { "follower$it" }
        memory.repository.applyLearning(emptyList(), followers.fold(LinkWrites()) { writes, it -> writes.then(commit(it, CONTEXT)) })
        // A link the tables hold that the lookup was never handed.
        memory.pairs.upsert(UserBigram(LANGUAGE, CONTEXT, OUTSIDE, 1, AT, contextSeen = 0L, languageSeen = 0L))
        val last = commit("newest", CONTEXT)
        memory.repository.applyLearning(emptyList(), last)
        assertEquals("the commit gave up one link", 1, last.gonePairs.size)
        val stored = memory.pairs.inContext(LANGUAGE, CONTEXT).associateBy { it.word }
        val kept = lookup.pairsAfter(LANGUAGE, CONTEXT).links
        assertEquals(
            "the tables hold the lookup's links as it counts them, and the one it never held",
            kept.associate { it.word to listOf(it.count.toLong(), it.contextSeen, it.languageSeen) } +
                (OUTSIDE to listOf(1L, 0L, 0L)),
            stored.mapValues { (_, it) -> listOf(it.count.toLong(), it.contextSeen, it.languageSeen) },
        )
        assertTrue("the link given up is gone", last.gonePairs.single().word !in stored)
        assertEquals(
            "and the context's total and observations are the lookup's",
            lookup.pairsAfter(LANGUAGE, CONTEXT).let { listOf(it.total, it.seen) },
            memory.pairs.context(LANGUAGE, CONTEXT)?.let { listOf(it.total, it.seen) },
        )
    }

    @Test
    fun `a link the tables hold chosen on purpose keeps its place and its marks through a flush computed without them`() = runTest {
        val followers = (0 until PhraseBudget.PAIRS.most).map { "follower$it" }
        memory.repository.applyLearning(emptyList(), followers.fold(LinkWrites()) { writes, it -> writes.then(commit(it, CONTEXT)) })
        // A phrase added by hand marks two of the links in the tables alone.
        for (marked in followers.take(2)) {
            memory.pairs.inContext(LANGUAGE, CONTEXT).single { it.word == marked }.let { memory.pairs.upsert(it.copy(asserted = 1, added = 1)) }
        }
        val given = commit("newest", CONTEXT)
        val again = commit(followers[1], CONTEXT)
        memory.repository.applyLearning(emptyList(), given.then(again))
        assertEquals("the lookup gave up the stalest newcomer", listOf(followers[0]), given.gonePairs.map { it.word })
        val stored = memory.pairs.inContext(LANGUAGE, CONTEXT).associateBy { it.word }
        assertEquals("the tables keep it, chosen", 1, stored[followers[0]]?.asserted)
        assertEquals(
            "and a link written over keeps the marks the tables hold",
            listOf(2, 1, 1),
            stored[followers[1]]?.let { listOf(it.count, it.asserted, it.added) },
        )
    }

    private companion object {
        const val LANGUAGE = "en"
        const val CONTEXT = "the"
        const val OUTSIDE = "outside"
        const val AT = 1_000L
    }
}
