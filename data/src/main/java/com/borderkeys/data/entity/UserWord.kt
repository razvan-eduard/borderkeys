// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A word this device has learned, how often it was written, and how often it was chosen.
 *
 * The entire personalisation model of the project. There is no gradient and nothing is retrained:
 * [count] goes up every time the word is committed -- typed or swiped and moved on from, or
 * picked from the strip -- and [asserted] goes up only when it was chosen on purpose: tapped on
 * the strip, or put back after a correction took it away. A word no dictionary holds is
 * offered as a completion, and left alone by autocorrect, once it has been asserted or written
 * often enough (`kMinPersonalEvidence` in engine.cpp); until then it is recorded and listed.
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
    /** How many times this word has been committed with a deliberate capital first letter --
     *  shift physically pressed for it, never auto-capitalise's own doing. Above zero, the
     *  native model suggests it capitalised regardless of how it is typed the next time. Never
     *  decremented: see [com.borderkeys.data.dao.UserWordDao.increment]. */
    val deliberateCapitals: Int = 0,
    /** How many times the user chose this word on purpose: picked it from the suggestion
     *  strip, or reverted a correction to get it back. Above zero, the native model offers the
     *  word however few times it was written and autocorrect leaves it alone. Never
     *  decremented, like [deliberateCapitals]. A word imported from a file arrives asserted. */
    val asserted: Int = 0,
)
