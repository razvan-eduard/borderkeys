// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A pair of words this device has seen written one after the other, and how often.
 *
 * What turns a keyboard that knows your words into one that knows your phrases: after "vreau" it
 * can offer "să" because that is what you write, not because a corpus says so. The language
 * pack's own n-grams answer the same question for the language in general; this answers it for
 * you, and it is the only part of the model that grows with use.
 *
 * It is also the most revealing thing this application stores. A list of words says which words
 * you know; a list of pairs says how you put them together, which is closer to a record of what
 * you have written. So it lives in the same SQLCipher database, is excluded from backup along
 * with everything else, is never recorded in a private field, and is deleted by the same
 * "forget everything" that clears the words -- a keyboard that kept your phrases after you asked
 * it to forget your words would have missed the point of being asked.
 *
 * The two words are the primary key together, for the same reason [UserWord] uses the word: one
 * row per pair, enforced rather than assumed.
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
)
