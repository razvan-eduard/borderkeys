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
            pairs = listOf(UserBigram("good", "morning", 3, 100), UserBigram("see", "you", 5, 50)),
            triples = listOf(
                UserTrigram("see", "you", "soon", 3, 200),
                UserTrigram("good", "morning", "all", 1, 300),
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
            pairs = listOf(UserBigram("good", "morning", 3, 100)),
            triples = listOf(UserTrigram("see", "you", "soon", 2, 200)),
        )
        assertEquals(
            listOf(
                UserPhrase(listOf("good", "morning"), 3, 100),
                UserPhrase(listOf("see", "you", "soon"), 2, 200),
            ),
            merged,
        )
    }

    @Test
    fun `only a pair after the sentence start opens a sentence`() {
        assertTrue(UserPhrase(listOf(UserBigram.SENTENCE_START, "hello"), 1, 0).opensSentence)
        assertFalse(UserPhrase(listOf("say", "hello"), 1, 0).opensSentence)
        assertFalse(UserPhrase(listOf("say", "hello", "there"), 1, 0).opensSentence)
    }
}
