// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.personal.linkUses
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A learning flush ([DictionaryRepository.applyLearning]) on Room, written as one transaction. */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class LearningBatchRoomTest {

    private lateinit var database: BorderKeysDatabase

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BorderKeysDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun close() {
        database.close()
    }

    @Test
    fun `a batch lands whole, and a write that fails leaves none of its words, pairs, triples or taps`() = runTest {
        val refusing = object : UserTrigramDao by database.userTrigramDao() {
            override suspend fun upsert(triple: UserTrigram) = throw IllegalStateException("the disk is full")
        }
        val repository = DictionaryRepository(
            testWorker(), RoomTransactor(database), database.userWordDao(), database.blockedWordDao(),
            database.userBigramDao(), refusing, database.keyTouchDao(), database.userPhraseClockDao(),
            database.userCorrectionDao(),
        )
        val failed = runCatching {
            repository.applyLearning(
                listOf(LearnedWord("cat", LOCALE, 1, AT)),
                repository.linkUses(
                    pairUses = listOf(LearnedBigram(LANGUAGE, "the", "cat", 1, AT)),
                    tripleUses = listOf(LearnedTrigram(LANGUAGE, "a", "the", "cat", 1, AT)),
                ),
                listOf(KeyTouch(BUCKET, 'c'.code, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, KEY_SIZE, KEY_SIZE, DENSITY, AT)),
                HALF_LIFE)
        }
        assertTrue(failed.isFailure)
        val rows = repository.personalRows(LIMIT)
        assertEquals(emptyList<String>(), rows.words.map { it.word })
        assertEquals(emptyList<String>(), rows.pairs.map { it.word })
        assertEquals(emptyList<KeyTouch>(), repository.touches.first())
    }

    private companion object {
        const val LOCALE = "en-US"
        const val LANGUAGE = "en"
        const val AT = 1_000L
        const val LIMIT = 100
        const val BUCKET = "portrait"
        const val KEY_SIZE = 100f
        const val DENSITY = 2f
        const val HALF_LIFE = 3_000_000L
    }
}
