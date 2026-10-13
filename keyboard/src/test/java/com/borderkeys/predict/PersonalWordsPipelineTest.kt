// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learned words through [Pipeline], against the shipped packs. */
class PersonalWordsPipelineTest {

    @Test
    fun `as many learned words as the setting keeps all load`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
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
        val english = Pipeline.open(ENGLISH)
        try {
            english.learnHistory(List(KeyboardPreferences.DEFAULT_LEARN_AFTER_USES) { listOf("a", "book") })
            val strip = english.strip("", previous = "a")
            assertEquals(strip.toString(), "book", strip.firstOrNull())
        } finally {
            english.close()
        }
    }

    /** Checks the words, the links and their contexts' counts; the engine reads no clock back. */
    @Test
    fun `a loaded personal model is what the engine holds, words, links and their contexts, until the next replaces it`() {
        Pipeline.require()
        val english = Pipeline.open(ENGLISH)
        try {
            val language = LanguageTags.primary(ENGLISH)
            val word = LEARNED
            val uses = KeyboardPreferences.DEFAULT_LEARN_AFTER_USES
            val model = PersonalModelLoad(
                words = listOf(UserWord(word, ENGLISH, uses, AT)),
                pairs = listOf(UserBigram(language, PREVIOUS1, word, uses, AT, SEEN, SEEN)),
                pairContexts = listOf(UserPairContext(language, PREVIOUS1, TOTAL, AT, SEEN)),
                triples = listOf(UserTrigram(language, PREVIOUS2, PREVIOUS1, word, uses, AT, SEEN, SEEN)),
                tripleContexts = listOf(UserTripleContext(language, PREVIOUS2, PREVIOUS1, TOTAL, AT, SEEN)),
                clocks = listOf(UserPhraseClock(language, SEEN, SEEN)),
            )
            english.load(model)
            assertTrue(english.strip(word).toString(), word in english.strip(word))
            assertEquals(setOf(word), english.heldAfter(language, PREVIOUS1))
            assertEquals(setOf(word), english.heldAfter(language, PREVIOUS1, PREVIOUS2))
            assertEquals(listOf(TOTAL, SEEN), english.contextCounts(language, PREVIOUS1))
            assertEquals(listOf(TOTAL, SEEN), english.contextCounts(language, PREVIOUS1, PREVIOUS2))
            english.load(PersonalSnapshot.EMPTY)
            assertFalse(english.strip(word).toString(), word in english.strip(word))
            assertEquals(emptySet<String>(), english.heldAfter(language, PREVIOUS1))
            assertEquals(emptySet<String>(), english.heldAfter(language, PREVIOUS1, PREVIOUS2))
            assertEquals(listOf(0L, 0L), english.contextCounts(language, PREVIOUS1))
            assertEquals(listOf(0L, 0L), english.contextCounts(language, PREVIOUS1, PREVIOUS2))
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
        const val ENGLISH = "en-US"
        const val PREVIOUS2 = "oh"
        const val PREVIOUS1 = "so"

        /** A word no pack holds, of letters the engine takes for a word. */
        const val LEARNED = "zqbrelt"

        /** A use's time. */
        const val AT = 1_000L

        /** A context's total, above its one link's uses, and its observations. */
        const val TOTAL = 5L
        const val SEEN = 7L

        const val PREFIX = "zq"
        const val ALPHABET = 26

        /** Enough letters to write every index up to the setting's limit. */
        const val LETTERS = 4
    }
}
