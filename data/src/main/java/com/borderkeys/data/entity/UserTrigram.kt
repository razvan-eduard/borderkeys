// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Three words this device has seen written in a row in one language, and how often. Never
 * recorded in a private field; deleted with any word it names. The words are kept in lower case.
 */
@Entity(
    tableName = "user_trigrams",
    primaryKeys = ["language", "previousWord2", "previousWord1", "word"],
    indices = [
        Index(value = ["count"], orders = [Index.Order.DESC]),
        Index(value = ["word"]),
        Index(value = ["previousWord1"]),
        Index(value = ["previousWord2"]),
    ],
)
data class UserTrigram(
    /** The primary language subtag the triple was written in. */
    val language: String,
    val previousWord2: String,
    val previousWord1: String,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
    /** How many times [previousWord2] [previousWord1] had been seen in [language] when this triple last was. */
    val contextSeen: Long,
    /** The language's triple clock when this triple was last seen. */
    val languageSeen: Long,
)

/** How many times two words have been seen as the start of a triple in one language. */
@Entity(
    tableName = "user_triple_contexts",
    primaryKeys = ["language", "previousWord2", "previousWord1"],
    indices = [Index(value = ["previousWord1"]), Index(value = ["previousWord2"])],
)
data class UserTripleContext(
    val language: String,
    val previousWord2: String,
    val previousWord1: String,
    /** Every triple seen after the two words, those no longer kept included, faded with time. */
    val total: Long,
    val lastUsedAt: Long,
    /** Every triple seen after the two words, never faded: what their [UserTrigram.contextSeen] count. */
    val seen: Long,
)
