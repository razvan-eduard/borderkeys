// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.withTransaction
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.KeyboardPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What [DictionaryRepository]'s forget and its sweep take out of the phrase tables. */
class DictionaryRepositoryForgetTest {

    private val pairs = MemoryPairs()
    private val triples = MemoryTriples()
    private val clocks = MemoryClocks()
    private val words = mockk<UserWordDao>(relaxed = true)
    private val database = mockk<BorderKeysDatabase>(relaxed = true)
    private lateinit var repository: DictionaryRepository

    @Before
    fun open() {
        mockkStatic(ROOM_DATABASE_KT)
        coEvery { database.withTransaction<Any?>(any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        repository = DictionaryRepository(
            database, words, mockk(relaxed = true), pairs, triples, mockk(relaxed = true), clocks,
        )
    }

    @After
    fun close() {
        unmockkStatic(ROOM_DATABASE_KT)
    }

    /** Pairs and a triple through "paris" in each of [LANGUAGES]. */
    private suspend fun learnParis() {
        val recorder = PhraseRecorder(pairs, triples, clocks)
        for (language in LANGUAGES) {
            recorder.recordPairs(
                listOf(LearnedBigram(language, "in", "Paris", 1, AT), LearnedBigram(language, "Paris", "now", 1, AT)).map(::PairStep),
            )
            recorder.recordTriples(listOf(LearnedTrigram(language, "back", "in", "Paris", 1, AT)).map(::TripleStep))
        }
    }

    @Test
    fun `forgetting one case of a word forgets its phrases in every language, whatever case is left`() = runTest {
        learnParis()
        assertTrue(pairs.rows.isNotEmpty() && triples.rows.isNotEmpty())
        coEvery { words.allWords() } returns listOf("paris")
        repository.forget(listOf("Paris"))
        assertTrue(pairs.rows.isEmpty())
        assertTrue(triples.rows.isEmpty())
    }

    @Test
    fun `forgetting every case of a word keeps the word that differs from it by a mark, and its phrases`() = runTest {
        PhraseRecorder(pairs, triples, clocks).recordPairs(
            listOf(LearnedBigram("ro", "pe", "masă", 1, AT), LearnedBigram("ro", "pe", "masa", 1, AT)).map(::PairStep),
        )
        coEvery { words.allWords() } returns listOf("masă", "Masă", "masa")
        val deleted = mutableListOf<String>()
        coEvery { words.delete(capture(deleted)) } returns Unit
        assertTrue(repository.forgetEveryCase("Masă"))
        assertEquals(listOf("masă", "Masă"), deleted)
        assertEquals(listOf("masa"), pairs.rows.values.map { it.word })
    }

    @Test
    fun `the sweep leaves the phrases of a word one case of which is still learned`() = runTest {
        learnParis()
        val pairsHeld = pairs.rows.size
        val triplesHeld = triples.rows.size
        coEvery { words.unconfirmedBefore(any()) } returns listOf("Paris")
        coEvery { words.allWords() } returns listOf("paris")
        coEvery { words.wordsBeyond(any()) } returns emptyList()
        repository.decayStaleEntries(
            keep = Int.MAX_VALUE,
            halfLifeMillis = AT,
            unconfirmedLifeMillis = PersonalWordDecay.unconfirmedLifeMillis(KeyboardPreferences.DEFAULT_UNCONFIRMED_LIFE_DAYS),
            now = AT,
        )
        assertEquals(pairsHeld, pairs.rows.size)
        assertEquals(triplesHeld, triples.rows.size)
    }

    @Test
    fun `a word used once and unused for the set days goes with its phrases, whatever the half-life`() = runTest {
        learnParis()
        val life = PersonalWordDecay.unconfirmedLifeMillis(KeyboardPreferences.DEFAULT_UNCONFIRMED_LIFE_DAYS)!!
        val shorter = PersonalWordDecay.halfLifeMillis(KeyboardPreferences.UNLEARN_HALF_LIFE_STEPS.first())
        val now = AT + life
        val cutoffs = mutableListOf<Long>()
        coEvery { words.unconfirmedBefore(capture(cutoffs)) } returns listOf("Paris")
        coEvery { words.allWords() } returns emptyList()
        coEvery { words.wordsBeyond(any()) } returns emptyList()
        repository.decayStaleEntries(keep = Int.MAX_VALUE, halfLifeMillis = shorter, unconfirmedLifeMillis = life, now = now)
        assertEquals(listOf(now - life), cutoffs)
        coVerify { words.deleteUnconfirmedBefore(now - life) }
        assertTrue(pairs.rows.isEmpty())
        assertTrue(triples.rows.isEmpty())
    }

    @Test
    fun `with Off a word used once stays, however long unused`() = runTest {
        learnParis()
        val pairsHeld = pairs.rows.size
        val triplesHeld = triples.rows.size
        coEvery { words.wordsBeyond(any()) } returns emptyList()
        val halfLife = PersonalWordDecay.halfLifeMillis(KeyboardPreferences.DEFAULT_UNLEARN_HALF_LIFE_DAYS)
        repository.decayStaleEntries(
            keep = Int.MAX_VALUE,
            halfLifeMillis = halfLife,
            unconfirmedLifeMillis = PersonalWordDecay.unconfirmedLifeMillis(KeyboardPreferences.UNCONFIRMED_LIFE_OFF),
            now = AT + PersonalWordDecay.halfLifeMillis(KeyboardPreferences.UNLEARN_HALF_LIFE_STEPS.last()),
        )
        coVerify(exactly = 0) { words.unconfirmedBefore(any()) }
        coVerify(exactly = 0) { words.deleteUnconfirmedBefore(any()) }
        assertEquals(pairsHeld, pairs.rows.size)
        assertEquals(triplesHeld, triples.rows.size)
    }

    @Test
    fun `the purge deletes a phrase used at most twice holding an unlearnable word, and keeps the rest`() = runTest {
        val most = PhraseBudget.PURGE_MOST_USES
        val recorder = PhraseRecorder(pairs, triples, clocks)
        recorder.recordPairs(
            listOf(
                LearnedBigram("en", "the", UNLEARNABLE, most, AT),
                LearnedBigram("en", "a", UNLEARNABLE, most + 1, AT),
                LearnedBigram("en", "the", "cat", 1, AT),
                LearnedBigram("en", UserBigram.SENTENCE_START, "cat", 1, AT),
            ).map(::PairStep),
        )
        recorder.recordTriples(listOf(LearnedTrigram("en", "on", "the", UNLEARNABLE, 1, AT)).map(::TripleStep))
        val asked = mutableListOf<String>()
        val purged = repository.purgeUnlearnable { words ->
            asked += words
            words.map { it != UNLEARNABLE }
        }
        assertEquals(listOf("the" to UNLEARNABLE), purged.pairs.map { it.previousWord to it.word })
        assertEquals(listOf(UNLEARNABLE), purged.triples.map { it.word })
        assertEquals(
            setOf("a" to UNLEARNABLE, "the" to "cat", UserBigram.SENTENCE_START to "cat"),
            pairs.rows.values.map { it.previousWord to it.word }.toSet(),
        )
        assertTrue(triples.rows.isEmpty())
        assertEquals("the context keeps the purged uses", (most + 1).toLong(), pairs.contexts.getValue(listOf("en", "the")).total)
        assertTrue("a context left with no link goes", triples.contexts.isEmpty())
        assertFalse("the sentence start is never asked about", UserBigram.SENTENCE_START in asked)
    }

    @Test
    fun `the purge keeps a pair and a triple whose uses rise past the threshold while the verdicts are taken`() = runTest {
        val most = PhraseBudget.PURGE_MOST_USES
        val recorder = PhraseRecorder(pairs, triples, clocks)
        val pair = LearnedBigram("en", "the", UNLEARNABLE, most, AT)
        val triple = LearnedTrigram("en", "on", "the", UNLEARNABLE, most, AT)
        recorder.recordPairs(listOf(PairStep(pair)))
        recorder.recordTriples(listOf(TripleStep(triple)))
        val purged = repository.purgeUnlearnable { words ->
            recorder.recordPairs(listOf(PairStep(pair.copy(delta = 1))))
            recorder.recordTriples(listOf(TripleStep(triple.copy(delta = 1))))
            words.map { it != UNLEARNABLE }
        }
        assertTrue(purged.pairs.isEmpty())
        assertTrue(purged.triples.isEmpty())
        assertEquals(listOf(most + 1), pairs.rows.values.map { it.count })
        assertEquals(listOf(most + 1), triples.rows.values.map { it.count })
    }

    @Test
    fun `the purge keeps a phrase first recorded while the verdicts are taken`() = runTest {
        val recorder = PhraseRecorder(pairs, triples, clocks)
        recorder.recordPairs(listOf(PairStep(LearnedBigram("en", "the", UNLEARNABLE, 1, AT))))
        recorder.recordTriples(listOf(TripleStep(LearnedTrigram("en", "on", "the", UNLEARNABLE, 1, AT))))
        val purged = repository.purgeUnlearnable { words ->
            recorder.recordPairs(listOf(PairStep(LearnedBigram("en", "a", UNLEARNABLE, 1, AT))))
            recorder.recordTriples(listOf(TripleStep(LearnedTrigram("en", "in", "a", UNLEARNABLE, 1, AT))))
            words.map { it != UNLEARNABLE }
        }
        assertEquals(listOf("the" to UNLEARNABLE), purged.pairs.map { it.previousWord to it.word })
        assertEquals(listOf("a" to UNLEARNABLE), pairs.rows.values.map { it.previousWord to it.word })
        assertEquals(listOf("on"), purged.triples.map { it.previousWord2 })
        assertEquals(listOf("in"), triples.rows.values.map { it.previousWord2 })
    }

    @Test
    fun `a purge that finds no unlearnable word opens no transaction and deletes nothing`() = runTest {
        PhraseRecorder(pairs, triples, clocks).recordPairs(listOf(PairStep(LearnedBigram("en", "the", "cat", 1, AT))))
        val purged = repository.purgeUnlearnable { words -> words.map { true } }
        assertTrue(purged.pairs.isEmpty())
        assertTrue(purged.triples.isEmpty())
        assertEquals(1, pairs.rows.size)
        coVerify(exactly = 0) { database.withTransaction<Any?>(any()) }
    }

    @Test
    fun `the purge keeps the phrases of a word the verdicts give no answer for`() = runTest {
        PhraseRecorder(pairs, triples, clocks)
            .recordPairs(listOf(PairStep(LearnedBigram("en", "the", UNLEARNABLE, 1, AT))))
        val purged = repository.purgeUnlearnable { words -> words.dropLast(1).map { true } }
        assertTrue(purged.pairs.isEmpty())
        assertEquals(1, pairs.rows.size)
    }

    private companion object {
        const val ROOM_DATABASE_KT = "androidx.room.RoomDatabaseKt"

        /** A word the purge's test answers is not learnable. */
        const val UNLEARNABLE = "zqx"
        val LANGUAGES = listOf("en", "fr")

        /** When the phrases were written, and the sweep's clock: nothing is stale. */
        const val AT = 10L
    }
}
