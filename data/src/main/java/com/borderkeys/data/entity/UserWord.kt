// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A word this device has learned in one language, how often it was written there, and how often
 * it was chosen.
 *
 * [count] goes up every time the word is committed in [language]; [asserted] only when it was
 * chosen on purpose, by one for each choice. A word no dictionary holds is offered as a
 * completion, and left alone by autocorrect, once it has been asserted or written as many times as
 * `KeyboardPreferences.learnAfter` asks. The spelling and the language are the primary key.
 */
@Entity(
    tableName = "user_words",
    primaryKeys = ["word", "language"],
    indices = [
        // The load: the words added by hand, then the most used others, each by count and recency.
        Index(value = ["added", "count", "lastUsedAt"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]),
    ],
)
data class UserWord(
    val word: String,
    /**
     * The primary language subtag the word was committed in, or [UNPLACED] for uses learned before
     * a word's language was kept, which the keyboard places at each load.
     */
    val language: String,
    val count: Int,
    /**
     * When the word was last used in [language]; on a row the clean-up halved, when it was halved.
     * A row kept from before version 14 carries the same stamp: the last use or the last halving.
     */
    val lastUsedAt: Long,
    /**
     * How many times this word was committed with a first-letter capital the user chose, with shift
     * or under a caps lock they set, not one auto-capitalise or the field gave. Above zero, the
     * native model suggests it capitalised. Never decremented.
     */
    val deliberateCapitals: Int = 0,
    /**
     * How many times the user chose this word on purpose: picked it on the strip over the word the
     * strip led with, kept it with the typed chip, picked it from the swipe ring below the top
     * word, or added it by hand. Above zero, the native model offers it and autocorrect leaves it
     * alone. Never decremented. A row kept from before version 14 holds only its adds by hand.
     */
    val asserted: Int = 0,
    /** How many times the word was added by hand on the Learned words screen. Never decremented. */
    val added: Int = 0,
) {

    companion object {
        /** The language of uses learned before a word's language was kept: none known yet. */
        const val UNPLACED = ""
    }
}
