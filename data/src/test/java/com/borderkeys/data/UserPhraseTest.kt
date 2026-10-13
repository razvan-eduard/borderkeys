// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPhraseTest {

    @Test
    fun `pairs and triples interleave, most used first, then most recent`() {
        val merged = UserPhrase.merged(
            pairs = listOf(UserBigram("en", "good", "morning", 3, 100, 3, 3), UserBigram("en", "see", "you", 5, 50, 5, 8)),
            triples = listOf(
                UserTrigram("en", "see", "you", "soon", 3, 200, 3, 3),
                UserTrigram("en", "good", "morning", "all", 1, 300, 1, 4),
            ),
        )
        assertEquals(
            listOf("see you", "see you soon", "good morning", "good morning all"),
            merged.map { it.words.joinToString(" ") },
        )
    }

    @Test
    fun `each phrase keeps its words in order, its count and when it was last written`() {
        val merged = UserPhrase.merged(
            pairs = listOf(UserBigram("en", "good", "morning", 3, 100, 3, 3)),
            triples = listOf(UserTrigram("en", "see", "you", "soon", 2, 200, 2, 2)),
        )
        assertEquals(
            listOf(
                UserPhrase("en", listOf("good", "morning"), 3, 100),
                UserPhrase("en", listOf("see", "you", "soon"), 2, 200),
            ),
            merged,
        )
    }

    @Test
    fun `only a pair after the sentence start opens a sentence`() {
        assertTrue(UserPhrase("en", listOf(UserBigram.SENTENCE_START, "hello"), 1, 0).opensSentence)
        assertFalse(UserPhrase("en", listOf("say", "hello"), 1, 0).opensSentence)
        assertFalse(UserPhrase("en", listOf("say", "hello", "there"), 1, 0).opensSentence)
    }
}
