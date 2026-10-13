// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learned words through [Pipeline], against the shipped packs. */
class PersonalWordsPipelineTest {

    @Test
    fun `as many learned words as the setting keeps all load`() {
        Pipeline.require()
        val english = Pipeline.open("en-US")
        try {
            val words = (0 until KeyboardPreferences.MAX_LEARNED_WORD_LIMIT).map(::madeUpWord)
            english.loadWords(words, KeyboardPreferences.DEFAULT_LEARN_AFTER_USES)
            val last = words.last()
            val strip = english.strip(last)
            assertTrue(strip.toString(), last in strip)
        } finally {
            english.close()
        }
    }

    @Test
    fun `a phrase after a one-letter word is suggested once learned`() {
        Pipeline.require()
        val english = Pipeline.open("en-US")
        try {
            english.learnHistory(List(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) { listOf("a", "book") })
            val strip = english.strip("", previous = "a")
            assertEquals(strip.toString(), "book", strip.firstOrNull())
        } finally {
            english.close()
        }
    }

    /** A word no pack holds: [PREFIX] and [index] written in letters. */
    private fun madeUpWord(index: Int): String {
        val word = StringBuilder(PREFIX)
        var rest = index
        repeat(LETTERS) {
            word.append('a' + rest % ALPHABET)
            rest /= ALPHABET
        }
        return word.toString()
    }

    private companion object {
        const val PREFIX = "zq"
        const val ALPHABET = 26

        /** Enough letters to write every index up to the setting's limit. */
        const val LETTERS = 4
    }
}
