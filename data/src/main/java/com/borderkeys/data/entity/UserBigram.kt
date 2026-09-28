// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A pair of words this device has seen written one after the other, and how often. Never
 * recorded in a private field; deleted with either of its words. One row per pair: the two words
 * are the primary key.
 */
@Entity(
    tableName = "user_bigrams",
    primaryKeys = ["previousWord", "word"],
    indices = [
        // The read on every service start: the strongest pairs, most used first.
        Index(value = ["count"], orders = [Index.Order.DESC]),
        // And the lookup when a word is forgotten, which has to take its pairs with it.
        Index(value = ["word"]),
    ],
)
data class UserBigram(
    val previousWord: String,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
) {

    companion object {
        /** The context of a pair whose word opened a sentence; no key types its first byte. */
        const val SENTENCE_START = "\u0002start"
    }
}
