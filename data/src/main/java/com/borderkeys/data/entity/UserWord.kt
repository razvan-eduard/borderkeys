// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A word this device has learned, and how often the user has confirmed it.
 *
 * The entire personalisation model of the project. There is no gradient and nothing is retrained:
 * [count] goes up when the user picks a word that was not already the top suggestion. That is why
 * the keyboard cannot slowly learn a typo -- a count only moves when a word is chosen on purpose.
 *
 * The word itself is the primary key rather than a generated id: there is exactly one row per
 * word, and making that a constraint removes the class of bug where learning the same word twice
 * produces two rows that then disagree.
 */
@Entity(
    tableName = "user_words",
    indices = [
        // The read that happens on every service start: the most-used words for the active
        // locales, in descending order, pushed straight into the native model.
        Index(value = ["locale", "count"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class UserWord(
    @PrimaryKey
    val word: String,
    val locale: String,
    val count: Int,
    val lastUsedAt: Long,
)
