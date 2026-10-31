// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.DictionaryRepository.Added
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.theme.KeyboardPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A word added by hand ([DictionaryRepository.add]), on a database in memory, and how it is listed. */
@RunWith(SharedNativeTextRunner::class)
@Config(sdk = [SharedNativeTextRunner.SDK])
class DictionaryAddTest {

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

    private suspend fun words() = repository.personalRows(LIMIT).words

    @Test
    fun `an added word is one chosen use, added by hand, in the locale's language, its first capital the user's`() = runTest {
        assertEquals(Added.ADDED, repository.add("Nadia", LOCALE, AT, unblock = false))
        assertEquals(Added.ADDED, repository.add("kubernetes", LOCALE, AT, unblock = false))
        val rows = words().associateBy { it.word }
        val name = rows.getValue("Nadia")
        assertEquals(
            listOf(1, 1, 1, 1),
            listOf(name.count, name.asserted, name.added, name.deliberateCapitals),
        )
        assertEquals(LANGUAGE to AT, name.language to name.lastUsedAt)
        assertEquals(0, rows.getValue("kubernetes").deliberateCapitals)
    }

    @Test
    fun `a blocked word is not added unless unblocked, and then its block goes in every case`() = runTest {
        repository.block("nadia")
        assertEquals(Added.BLOCKED, repository.add("Nadia", LOCALE, AT, unblock = false))
        assertTrue(repository.isBlocked("Nadia"))
        assertEquals(emptyList<String>(), words().map { it.word })

        assertEquals(Added.ADDED, repository.add("Nadia", LOCALE, AT, unblock = true))
        assertFalse(repository.isBlocked("nadia"))
        assertEquals(listOf("Nadia"), words().map { it.word })
    }

    @Test
    fun `blocking a word forgets it in every case and language, and blocks the spelling given`() = runTest {
        repository.applyLearning(
            listOf(
                LearnedWord("Paris", LANGUAGE, 2, AT),
                LearnedWord("paris", OTHER_LANGUAGE, 3, AT),
                LearnedWord("PARIS", LANGUAGE, 1, AT),
                LearnedWord("parish", LANGUAGE, 1, AT),
            ),
            LinkWrites(),
        )
        repository.block("paris")
        assertEquals(listOf("parish"), words().map { it.word })
        assertEquals(setOf("paris"), repository.blockedWordSet())
    }

    @Test
    fun `adding a spelling already stored adds one use and the marks, and keeps its history`() = runTest {
        repository.applyLearning(listOf(LearnedWord("nadia", LANGUAGE, 3, AT)), LinkWrites())
        assertEquals(Added.ADDED, repository.add("nadia", LOCALE, AT + 1, unblock = false))
        val row = words().single()
        assertEquals(listOf(4, 1, 1), listOf(row.count, row.asserted, row.added))
        assertEquals(AT + 1, row.lastUsedAt)
    }

    @Test
    fun `an added word is listed at once, and apart from the words learned by use`() = runTest {
        repository.applyLearning(
            listOf(LearnedWord("often", LANGUAGE, 3, AT), LearnedWord("once", LANGUAGE, 1, AT)),
            LinkWrites(),
        )
        repository.add("nadia", LOCALE, AT, unblock = false)
        val listed = PersonalEntries.byLanguage(words(), KeyboardPreferences(), AT)
        assertEquals(setOf("often", "nadia"), listed.mapTo(HashSet()) { it.spelling })
        assertEquals(listOf("nadia"), PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.ADDED) { it.added }.map { it.spelling })
        assertEquals(listOf("often"), PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.LEARNED) { it.added }.map { it.spelling })
        assertEquals(listed, PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.ANY) { it.added })
    }

    @Test
    fun `a word added by hand is loaded past the words-kept limit`() = runTest {
        repository.applyLearning(
            listOf(LearnedWord("often", LANGUAGE, 5, AT), LearnedWord("also", LANGUAGE, 4, AT)),
            LinkWrites(),
        )
        repository.add("nadia", LOCALE, AT, unblock = false)
        assertEquals(listOf("nadia", "often", "also"), repository.personalRows(2).words.map { it.word })
    }

    private companion object {
        const val LOCALE = "en-US"
        const val LANGUAGE = "en"
        const val OTHER_LANGUAGE = "fr"
        const val AT = 1_000L
        const val LIMIT = 100
    }
}
