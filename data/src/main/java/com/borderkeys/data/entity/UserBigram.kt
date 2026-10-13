// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A pair of words this device has seen written one after the other in one language, and how
 * often. Never recorded in a private field; deleted with either of its words. The words are kept
 * in lower case ([com.borderkeys.data.PhraseKey]); the language, the previous word and the word
 * are the primary key.
 */
@Entity(
    tableName = "user_bigrams",
    primaryKeys = ["language", "previousWord", "word"],
    indices = [
        // The Learned phrases list, most used first.
        Index(value = ["count"], orders = [Index.Order.DESC]),
        // And the lookup when a word is forgotten, which has to take its pairs with it.
        Index(value = ["word"]),
        Index(value = ["previousWord"]),
    ],
)
data class UserBigram(
    /** The primary language subtag the pair was written in. */
    val language: String,
    val previousWord: String,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
    /** How many times [previousWord] had been seen in [language] when this pair last was. */
    val contextSeen: Long,
    /** The language's pair clock when this pair was last seen. */
    val languageSeen: Long,
) {

    companion object {
        /** The context of a pair whose word opened a sentence; no key types its first byte. */
        const val SENTENCE_START = "\u0002start"
    }
}

/** How many times a word has been seen as the previous word of a pair in one language. */
@Entity(
    tableName = "user_pair_contexts",
    primaryKeys = ["language", "previousWord"],
    indices = [Index(value = ["previousWord"])],
)
data class UserPairContext(
    val language: String,
    val previousWord: String,
    /** Every pair seen after [previousWord], those no longer kept included, faded with time. */
    val total: Long,
    val lastUsedAt: Long,
    /** Every pair seen after [previousWord], never faded: what its pairs' [UserBigram.contextSeen] count. */
    val seen: Long,
)

/** One language's clocks: how many pairs, and how many triples, it has recorded. */
@Entity(tableName = "user_phrase_clocks", primaryKeys = ["language"])
data class UserPhraseClock(
    val language: String,
    val pairs: Long,
    val triples: Long,
)
