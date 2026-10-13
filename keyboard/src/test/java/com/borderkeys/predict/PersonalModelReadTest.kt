// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.InMemoryDictionary
import com.borderkeys.data.PhraseRecorder
import com.borderkeys.data.SharedNativeTextRunner
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.OffensiveWords
import com.borderkeys.typing.LearningBatch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A learning batch written into the personal dictionary ([LearningBatch.writeTo]) and the model
 * the keyboard reads back from it ([PersonalModelLoad.read]), on a database in memory.
 */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class PersonalModelReadTest {

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
    fun `a learning batch writes its words, pairs and triples, each where it belongs`() = runTest {
        val batch = LearningBatch(
            updates = listOf(word("cat", 1)),
            pairs = listOf(PairStep(LearnedBigram(LANGUAGE, "the", "cat", 1, AT))),
            triples = listOf(TripleStep(LearnedTrigram(LANGUAGE, "on", "the", "cat", 1, AT))),
        )
        assertEquals(PhraseRecorder.Dropped(), batch.writeTo(repository))
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("cat"), rows.words.map { it.word })
        assertEquals(listOf("the cat"), rows.pairs.map { "${it.previousWord} ${it.word}" })
        assertEquals(listOf("on the cat"), rows.triples.map { "${it.previousWord2} ${it.previousWord1} ${it.word}" })
    }

    @Test
    fun `the model read holds the words used most up to the setting's limit, offensive ones left out`() = runTest {
        repository.applyLearning(listOf(word("most", 3), word("damn", 2), word("rare", 1)), emptyList(), emptyList())
        val settings = KeyboardPreferences(learnedWordLimit = 2, blockOffensiveWords = true)
        val model = PersonalModelLoad.read(repository, settings, OffensiveWords.parse("damn\n"), AT)
        assertEquals(listOf("most"), model.words.map { it.word })
    }

    private fun word(text: String, uses: Int) = LearnedWord(text, LANGUAGE, uses, AT)

    private companion object {
        const val LANGUAGE = "en"
        const val AT = 1_000L

        /** More words than any test here learns. */
        const val LIMIT = 100
    }
}
