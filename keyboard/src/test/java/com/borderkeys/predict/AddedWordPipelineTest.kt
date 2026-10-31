// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.MemoryDictionary
import com.borderkeys.data.theme.KeyboardPreferences
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A word added by hand ([com.borderkeys.data.DictionaryRepository.add]) to the personal dictionary
 * on tables in memory, read back as the keyboard loads its personal model ([PersonalModelLoad.read])
 * and typed through the whole path.
 */
class AddedWordPipelineTest {

    private lateinit var pipeline: Pipeline
    private val dictionary = MemoryDictionary()

    @Before
    fun open() {
        Pipeline.require()
        pipeline = Pipeline.open("en-US")
        pipeline.learning = true
    }

    @After
    fun close() {
        if (this::pipeline.isInitialized) {
            pipeline.close()
        }
    }

    @Test
    fun `a word added by hand is kept as typed and offered as a completion once the keyboard reloads`() = runTest {
        assertEquals("the", pipeline.commit("teh").committed)
        dictionary.repository.add("teh", LOCALE, AT, unblock = false)
        pipeline.load(PersonalModelLoad.read(dictionary.repository, KeyboardPreferences(), emptySet(), AT))
        // Null: the field kept what was typed.
        assertNull(pipeline.commit("teh").committed)
        assertTrue(pipeline.strip("te").toString(), "teh" in pipeline.strip("te"))
    }

    private companion object {
        const val LOCALE = "en-US"
        const val AT = 1_000L
    }
}
