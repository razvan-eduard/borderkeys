// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.linkUses
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The clean-up's SQL ([DictionaryRepository.decayStaleEntries]) on a database in memory: which
 * words written once, and which words past the cap, it removes with their phrases, and which it
 * leaves.
 */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class SingleUseSweepSqlTest {

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

    /** Writes [word] once at [AT], chosen on purpose when [asserted], with a pair ending in it. */
    private suspend fun writeOnce(word: String, asserted: Boolean) {
        repository.applyLearning(
            listOf(LearnedWord(word, LANGUAGE, 1, AT, asserted = if (asserted) 1 else 0)),
            repository.linkUses(pairUses = listOf(LearnedBigram(LANGUAGE, "hi", word, 1, AT))),
        )
    }

    private suspend fun sweep(now: Long) = repository.decayStaleEntries(LIMIT, HALF_LIFE, UNCONFIRMED_LIFE, now)

    @Test
    fun `a word written once and never chosen goes with its phrases, one chosen on purpose stays with them`() = runTest {
        writeOnce("once", asserted = false)
        writeOnce("nadia", asserted = true)
        sweep(now = AT + UNCONFIRMED_LIFE + 1)
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("nadia"), rows.words.map { it.word })
        assertEquals(listOf("nadia"), rows.pairs.map { it.word })
    }

    @Test
    fun `a word written once is kept with its phrases until its days have passed`() = runTest {
        writeOnce("once", asserted = false)
        sweep(now = AT + UNCONFIRMED_LIFE)
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("once"), rows.words.map { it.word })
        assertEquals(listOf("once"), rows.pairs.map { it.word })
    }

    @Test
    fun `past the cap, a word added by hand stays with its phrase, a word learned once goes`() = runTest {
        repository.applyLearning(listOf(LearnedWord("big", LANGUAGE, 5, AT), LearnedWord("once", LANGUAGE, 1, AT)), LinkWrites())
        repository.addPhrase(listOf("hi", "nadia"), LANGUAGE, AT, unblock = false)
        repository.decayStaleEntries(keep = 1, HALF_LIFE, unconfirmedLifeMillis = null, now = AT)
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("big", "hi", "nadia"), rows.words.map { it.word }.sorted())
        assertEquals(listOf("nadia"), rows.pairs.map { it.word })
    }

    private companion object {
        const val LANGUAGE = "en"
        const val AT = 1_000L
        const val LIMIT = 100
        val HALF_LIFE: Long = TimeUnit.DAYS.toMillis(90)
        val UNCONFIRMED_LIFE: Long = TimeUnit.DAYS.toMillis(30)
    }
}
