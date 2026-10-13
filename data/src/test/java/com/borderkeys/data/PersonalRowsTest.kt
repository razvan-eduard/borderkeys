// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a learning flush writes ([DictionaryRepository.applyLearning]) and what the keyboard reads
 * back to load ([DictionaryRepository.personalRows]), on a database in memory.
 */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class PersonalRowsTest {

    private lateinit var dictionary: InMemoryDictionary
    private val repository get() = dictionary.repository

    @Before
    fun open() {
        dictionary = InMemoryDictionary(ApplicationProvider.getApplicationContext())
    }

    @After
    fun close() {
        dictionary.close()
    }

    @Test
    fun `the words read are the most used, the later first among equals, up to the limit`() = runTest {
        repository.applyLearning(
            listOf(word("rare", 1, AT), word("old", 3, AT), word("new", 3, AT + 1), word("most", 5, AT)),
            emptyList(),
            emptyList(),
        )
        val expected = listOf("most", "new", "old")
        assertEquals(expected, repository.personalRows(expected.size).words.map { it.word })
    }

    @Test
    fun `a flush's words, pairs and triples are written keyed, and the links past a context's budget given up`() = runTest {
        val pairFollowers = followers(PhraseBudget.PAIRS.most + 1)
        val tripleFollowers = followers(PhraseBudget.TRIPLES.most + 1)
        val dropped = repository.applyLearning(
            listOf(word("The", 1, AT)),
            pairFollowers.mapIndexed { at, follower -> PairStep(LearnedBigram(LANGUAGE, "The", follower, 1, AT + at)) },
            tripleFollowers.mapIndexed { at, follower ->
                TripleStep(LearnedTrigram(LANGUAGE, "On", "The", follower, 1, AT + at))
            },
        )
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("The"), rows.words.map { it.word })
        assertEquals(PhraseBudget.PAIRS.most, rows.pairs.size)
        assertEquals(setOf("the"), rows.pairs.mapTo(HashSet()) { it.previousWord })
        assertEquals(1, dropped.pairs.size)
        assertEquals(pairFollowers.toSet(), (rows.pairs + dropped.pairs).mapTo(HashSet()) { it.word })
        assertEquals(PhraseBudget.TRIPLES.most, rows.triples.size)
        assertEquals(setOf("on the"), rows.triples.mapTo(HashSet()) { "${it.previousWord2} ${it.previousWord1}" })
        assertEquals(1, dropped.triples.size)
        assertEquals(tripleFollowers.toSet(), (rows.triples + dropped.triples).mapTo(HashSet()) { it.word })
        assertEquals(listOf("the"), rows.pairContexts.map { it.previousWord })
        assertEquals(listOf("on the"), rows.tripleContexts.map { "${it.previousWord2} ${it.previousWord1}" })
        assertEquals(listOf(LANGUAGE), rows.clocks.map { it.language })
    }

    /** [count] words, keyed as the store keys them. */
    private fun followers(count: Int) = (0 until count).map { "follower${'a' + it}" }

    private fun word(text: String, uses: Int, at: Long) = LearnedWord(text, LANGUAGE, uses, at)

    private companion object {
        const val LANGUAGE = "en"
        const val AT = 1_000L

        /** More words than any test here learns. */
        const val LIMIT = 100
    }
}
