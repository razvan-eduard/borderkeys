// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the load reads of one language's links. */
class PhraseCapTest {

    @Test
    fun `the load keeps each language's most used pairs, in the order they were first recorded`() {
        val most = DictionaryRepository.MAX_BIGRAMS_IN_MEMORY
        val more = 2
        val english = (0..most).map { UserBigram("en", "w$it", "x", if (it == 0) 1 else more, 1L, 1L, 1L) }
        val romanian = listOf(UserBigram("ro", "a", "b", 1, 1L, 1L, 1L))
        assertEquals(romanian + english.drop(1), DictionaryRepository.loadedPairs(romanian + english))
    }

    @Test
    fun `the load keeps a language's links chosen on purpose beyond its most used, listed first`() {
        val most = DictionaryRepository.MAX_BIGRAMS_IN_MEMORY
        val english = (1..most).map { UserBigram("en", "w$it", "x", 2, 1L, 1L, 1L) }
        val chosen = UserBigram("en", "see", "you", 1, 1L, 1L, 1L, asserted = 1)
        assertEquals(listOf(chosen) + english, DictionaryRepository.loadedPairs(english + chosen))
    }

    @Test
    fun `the load keeps each language's most used triples, in the order they were first recorded`() {
        val most = DictionaryRepository.MAX_TRIGRAMS_IN_MEMORY
        val more = 2
        val english = (0..most).map { UserTrigram("en", "v$it", "w", "x", if (it == 0) 1 else more, 1L, 1L, 1L) }
        val romanian = listOf(UserTrigram("ro", "a", "b", "c", 1, 1L, 1L, 1L))
        assertEquals(english.drop(1) + romanian, DictionaryRepository.loadedTriples(english + romanian))
    }
}
