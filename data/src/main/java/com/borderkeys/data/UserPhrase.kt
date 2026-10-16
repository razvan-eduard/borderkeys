// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram

/**
 * A learned pair or triple: the primary language subtag it was written in, its words in order,
 * how often it was written, when last, how often it was chosen on purpose and how often added
 * by hand ([UserBigram.asserted], [UserBigram.added]).
 */
data class UserPhrase(
    val language: String,
    val words: List<String>,
    val count: Int,
    val lastUsedAt: Long,
    val asserted: Int = 0,
    val added: Int = 0,
) {

    /** Whether this is a pair rather than a triple. */
    val isPair: Boolean
        get() = words.size == PAIR_WORDS

    /** Whether this is a pair whose word opened a sentence. */
    val opensSentence: Boolean
        get() = words.first() == UserBigram.SENTENCE_START

    companion object {
        /** How many words a pair holds. */
        const val PAIR_WORDS = 2

        /** How many words a triple holds, the most a phrase does. */
        const val TRIPLE_WORDS = 3

        /** [pairs] and [triples] as one list, most used first, then most recent. */
        fun merged(pairs: List<UserBigram>, triples: List<UserTrigram>): List<UserPhrase> {
            val phrases =
                pairs.map {
                    UserPhrase(
                        it.language, listOf(it.previousWord, it.word), it.count, it.lastUsedAt, it.asserted, it.added,
                    )
                } +
                    triples.map {
                        UserPhrase(
                            it.language, listOf(it.previousWord2, it.previousWord1, it.word), it.count,
                            it.lastUsedAt, it.asserted, it.added,
                        )
                    }
            return phrases.sortedWith(
                compareByDescending<UserPhrase> { it.count }.thenByDescending { it.lastUsedAt },
            )
        }
    }
}
