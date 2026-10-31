// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.linkUses
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The choices a flush carries ([DictionaryRepository.applyLearning]), on a database in memory. */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class LearnedChoicesTest {

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
    fun `each flush adds its choices to the word's and to the pair's`() = runTest {
        repeat(FLUSHES) {
            repository.applyLearning(
                listOf(LearnedWord("nadia", LOCALE, WORD_CHOICES, AT, asserted = WORD_CHOICES)),
                repository.linkUses(pairUses = listOf(LearnedBigram(LANGUAGE, "hi", "nadia", 1, AT, asserted = 1))),
            )
        }
        val rows = repository.personalRows(LIMIT)
        assertEquals(FLUSHES * WORD_CHOICES, rows.words.single().asserted)
        assertEquals(FLUSHES, rows.pairs.single().asserted)
    }

    private companion object {
        const val LOCALE = "en-US"
        const val LANGUAGE = "en"
        const val AT = 1_000L
        const val LIMIT = 100
        const val FLUSHES = 2
        const val WORD_CHOICES = 2
    }
}
