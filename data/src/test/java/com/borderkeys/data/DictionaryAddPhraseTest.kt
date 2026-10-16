// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.DictionaryRepository.Added
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.theme.KeyboardPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A phrase added by hand ([DictionaryRepository.addPhrase]), on a database in memory, and how it is listed. */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class DictionaryAddPhraseTest {

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
    fun `three words write both pairs and the triple, each one chosen use added by hand, and the words with them`() = runTest {
        assertEquals(Added.ADDED, repository.addPhrase(listOf("We", "go", "home"), LOCALE, AT, unblock = false))
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("we go", "go home"), rows.pairs.map { "${it.previousWord} ${it.word}" })
        assertEquals(listOf("we go home"), rows.triples.map { "${it.previousWord2} ${it.previousWord1} ${it.word}" })
        for (link in rows.pairs) {
            assertEquals(listOf(LANGUAGE, 1, 1, 1, AT), listOf(link.language, link.count, link.asserted, link.added, link.lastUsedAt))
        }
        val triple = rows.triples.single()
        assertEquals(listOf(LANGUAGE, 1, 1, 1), listOf(triple.language, triple.count, triple.asserted, triple.added))
        assertEquals(listOf("go" to 1L, "we" to 1L), rows.pairContexts.map { it.previousWord to it.total }.sortedBy { it.first })
        assertEquals(listOf(2L to 1L), rows.clocks.map { it.pairs to it.triples })
        val words = rows.words.associateBy { it.word }
        assertEquals(setOf("We", "go", "home"), words.keys)
        assertEquals(listOf(1, 1, 1, 1, LOCALE), words.getValue("We").let { listOf(it.count, it.asserted, it.added, it.deliberateCapitals, it.locale) })
        assertEquals(0, words.getValue("go").deliberateCapitals)
    }

    @Test
    fun `adding a phrase already stored at one use adds one use and the marks`() = runTest {
        repository.applyLearning(emptyList(), listOf(PairStep(LearnedBigram(LANGUAGE, "see", "you", 1, AT))), emptyList())
        assertEquals(Added.ADDED, repository.addPhrase(listOf("see", "you"), LOCALE, AT + 1, unblock = false))
        val pair = repository.personalRows(LIMIT).pairs.single()
        assertEquals(listOf<Any>(2, 1, 1, AT + 1), listOf(pair.count, pair.asserted, pair.added, pair.lastUsedAt))
    }

    @Test
    fun `two words write one pair`() = runTest {
        repository.addPhrase(listOf("see", "you"), LOCALE, AT, unblock = false)
        val rows = repository.personalRows(LIMIT)
        assertEquals(listOf("see you"), rows.pairs.map { "${it.previousWord} ${it.word}" })
        assertEquals(emptyList<String>(), rows.triples.map { it.word })
    }

    @Test
    fun `a blocked word among the phrase's words refuses the add unless unblocked`() = runTest {
        repository.block("home")
        assertEquals(Added.BLOCKED, repository.addPhrase(listOf("go", "Home"), LOCALE, AT, unblock = false))
        assertTrue(repository.isBlocked(listOf("go", "Home")))
        assertEquals(emptyList<String>(), repository.personalRows(LIMIT).pairs.map { it.word })

        assertEquals(Added.ADDED, repository.addPhrase(listOf("go", "Home"), LOCALE, AT, unblock = true))
        assertTrue(!repository.isBlocked(listOf("go", "Home")))
        assertEquals(listOf("go home"), repository.personalRows(LIMIT).pairs.map { "${it.previousWord} ${it.word}" })
    }

    @Test
    fun `an added phrase is listed at once, and apart from the phrases learned by use`() = runTest {
        repository.applyLearning(
            emptyList(),
            listOf(PairStep(LearnedBigram(LANGUAGE, "good", "morning", KeyboardPreferences().learnAfter, AT))),
            emptyList(),
        )
        repository.addPhrase(listOf("see", "you"), LOCALE, AT, unblock = false)
        val listed = PersonalEntries.phrases(phrases(), KeyboardPreferences(), AT)
        assertEquals(setOf("good morning", "see you"), listed.mapTo(HashSet()) { it.words.joinToString(" ") })
        assertEquals(
            listOf("see you"),
            PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.ADDED) { it.added }.map { it.words.joinToString(" ") },
        )
        assertEquals(
            listOf("good morning"),
            PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.LEARNED) { it.added }.map { it.words.joinToString(" ") },
        )
    }

    private suspend fun phrases(): List<UserPhrase> {
        val rows = repository.personalRows(LIMIT)
        return UserPhrase.merged(rows.pairs, rows.triples)
    }

    private companion object {
        const val LOCALE = "en-US"
        const val LANGUAGE = "en"
        const val AT = 1_000L
        const val LIMIT = 100
    }
}
