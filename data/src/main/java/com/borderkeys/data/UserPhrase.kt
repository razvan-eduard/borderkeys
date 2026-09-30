// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram

/** A learned pair or triple: its words in order, how often it was written, and when last. */
data class UserPhrase(val words: List<String>, val count: Int, val lastUsedAt: Long) {

    /** Whether this is a pair whose word opened a sentence. */
    val opensSentence: Boolean
        get() = words.first() == UserBigram.SENTENCE_START

    companion object {
        /** [pairs] and [triples] as one list, most used first, then most recent. */
        fun merged(pairs: List<UserBigram>, triples: List<UserTrigram>): List<UserPhrase> {
            val phrases = pairs.map { UserPhrase(listOf(it.previousWord, it.word), it.count, it.lastUsedAt) } +
                triples.map {
                    UserPhrase(listOf(it.previousWord2, it.previousWord1, it.word), it.count, it.lastUsedAt)
                }
            return phrases.sortedWith(
                compareByDescending<UserPhrase> { it.count }.thenByDescending { it.lastUsedAt },
            )
        }
    }
}
