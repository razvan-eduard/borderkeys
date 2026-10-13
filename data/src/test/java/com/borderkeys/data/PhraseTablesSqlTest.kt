// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phrase tables' own SQL, on an in-memory database: the sweep's halving and forget's
 * subtraction. Rows are written already keyed, so nothing here reaches the native library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [PhraseTablesSqlTest.SDK])
class PhraseTablesSqlTest {

    private lateinit var database: BorderKeysDatabase
    private lateinit var recorder: PhraseRecorder

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), BorderKeysDatabase::class.java,
        ).allowMainThreadQueries().build()
        recorder = PhraseRecorder(database.userBigramDao(), database.userTrigramDao(), database.userPhraseClockDao())
    }

    @After
    fun close() {
        database.close()
    }

    /** Writes the pair [previous] [word] used [uses] times, its uses added to its context. */
    private suspend fun holdPair(previous: String, word: String, uses: Int) {
        val pairs = database.userBigramDao()
        val context = pairs.context(LANGUAGE, previous)
        val seen = (context?.seen ?: 0L) + uses
        pairs.upsertContext(UserPairContext(LANGUAGE, previous, (context?.total ?: 0L) + uses, AT, seen))
        pairs.upsert(UserBigram(LANGUAGE, previous, word, uses, AT, seen, seen))
    }

    @Test
    fun `the sweep halves what went unused, context totals included, and leaves what was used`() = runTest {
        val uses = PersonalWordDecay.HALVING * PersonalWordDecay.HALVING
        val pairs = database.userBigramDao()
        val triples = database.userTrigramDao()
        val words = database.userWordDao()
        holdPair("the", "cat", uses)
        triples.upsertContext(UserTripleContext(LANGUAGE, "on", "the", uses.toLong(), AT, uses.toLong()))
        triples.upsert(UserTrigram(LANGUAGE, "on", "the", "cat", uses, AT, uses.toLong(), uses.toLong()))
        words.incrementAll(listOf(LearnedWord("cat", LANGUAGE, uses, AT), LearnedWord("dog", LANGUAGE, uses, LATER)))
        val now = LATER + 1

        words.decayStale(cutoff = LATER, now = now)
        pairs.decayStale(cutoff = LATER, now = now)
        pairs.decayStaleContexts(cutoff = LATER, now = now)
        triples.decayStale(cutoff = LATER, now = now)
        triples.decayStaleContexts(cutoff = LATER, now = now)

        val halved = uses / PersonalWordDecay.HALVING
        assertEquals(halved, words.find("cat")!!.count)
        assertEquals("a word used since stays", uses, words.find("dog")!!.count)
        assertEquals(halved, pairs.all().single().count)
        val pairContext = pairs.context(LANGUAGE, "the")!!
        assertEquals(halved.toLong(), pairContext.total)
        assertEquals("observations never fade", uses.toLong(), pairContext.seen)
        assertEquals(now, pairContext.lastUsedAt)
        assertEquals(halved, triples.all().single().count)
        assertEquals(halved.toLong(), triples.context(LANGUAGE, "on", "the")!!.total)
    }

    @Test
    fun `forgetting a pair takes its uses off its context, and a context left with no link goes`() = runTest {
        val cat = 2
        val dog = 3
        val pairs = database.userBigramDao()
        holdPair("the", "cat", cat)
        holdPair("the", "dog", dog)
        holdPair("cat", "sat", 1)
        recorder.forgetPair(pairs.inContext(LANGUAGE, "the").single { it.word == "cat" })
        val context = pairs.context(LANGUAGE, "the")!!
        assertEquals(dog.toLong(), context.total)
        assertEquals(dog.toLong(), context.seen)

        recorder.forgetPair(pairs.inContext(LANGUAGE, "cat").single())
        assertNull(pairs.context(LANGUAGE, "cat"))
        assertEquals(listOf("dog"), pairs.all().map { it.word })
    }

    @Test
    fun `the phrases used at most so many times are read in the order each was first recorded`() = runTest {
        val most = PhraseBudget.PURGE_MOST_USES
        holdPair("the", "cat", most)
        holdPair("the", "dog", most + 1)
        holdPair("a", "cow", 1)
        val triples = database.userTrigramDao()
        triples.upsert(UserTrigram(LANGUAGE, "on", "the", "cat", most, AT, most.toLong(), most.toLong()))
        triples.upsert(UserTrigram(LANGUAGE, "on", "the", "dog", most + 1, AT, most + 1L, most + 1L))
        assertEquals(listOf("cat", "cow"), database.userBigramDao().usedAtMost(most).map { it.word })
        assertEquals(listOf("cat"), triples.usedAtMost(most).map { it.word })
    }

    companion object {
        /** The newest Android Robolectric runs. */
        const val SDK = 35

        const val LANGUAGE = "en"

        /** When the links were written, and a later use. */
        const val AT = 1L
        const val LATER = 2L
    }
}
