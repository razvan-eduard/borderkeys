// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.DictionaryRepository.Added
import com.borderkeys.data.dao.LearnedWord
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
    fun `an added word is one chosen use, added by hand, under the locale given, its first capital the user's`() = runTest {
        assertEquals(Added.ADDED, repository.add("Nadia", LOCALE, AT, unblock = false))
        assertEquals(Added.ADDED, repository.add("kubernetes", LOCALE, AT, unblock = false))
        val rows = words().associateBy { it.word }
        val name = rows.getValue("Nadia")
        assertEquals(
            listOf(1, 1, 1, 1),
            listOf(name.count, name.asserted, name.added, name.deliberateCapitals),
        )
        assertEquals(LOCALE to AT, name.locale to name.lastUsedAt)
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
    fun `adding a spelling already stored adds one use and the marks, and keeps its history`() = runTest {
        repository.applyLearning(listOf(LearnedWord("nadia", LOCALE, 3, AT)), emptyList(), emptyList())
        assertEquals(Added.ADDED, repository.add("nadia", LOCALE, AT + 1, unblock = false))
        val row = words().single()
        assertEquals(listOf(4, 1, 1), listOf(row.count, row.asserted, row.added))
        assertEquals(AT + 1, row.lastUsedAt)
    }

    @Test
    fun `an added word is listed at once, and apart from the words learned by use`() = runTest {
        repository.applyLearning(
            listOf(LearnedWord("often", LOCALE, 3, AT), LearnedWord("once", LOCALE, 1, AT)),
            emptyList(),
            emptyList(),
        )
        repository.add("nadia", LOCALE, AT, unblock = false)
        val listed = PersonalEntries.words(words(), KeyboardPreferences(), AT)
        assertEquals(setOf("often", "nadia"), listed.mapTo(HashSet()) { it.word })
        assertEquals(listOf("nadia"), PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.ADDED).map { it.word })
        assertEquals(listOf("often"), PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.LEARNED).map { it.word })
        assertEquals(listed, PersonalEntries.ofOrigin(listed, PersonalEntries.Origin.ANY))
    }

    private companion object {
        const val LOCALE = "en-US"
        const val AT = 1_000L
        const val LIMIT = 100
    }
}
