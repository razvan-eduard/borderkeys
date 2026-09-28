// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A word this device has learned, how often it was written, and how often it was chosen.
 *
 * [count] goes up every time the word is committed; [asserted] only when it was chosen on
 * purpose: tapped on the strip, or put back after a correction took it away. A word no dictionary
 * holds is offered as a completion, and left alone by autocorrect, once it has been asserted or
 * written often enough (`kMinPersonalEvidence` in engine.cpp). The word is the primary key.
 */
@Entity(
    tableName = "user_words",
    indices = [
        // For the service-start read: the most-used words per locale.
        Index(value = ["locale", "count"], orders = [Index.Order.ASC, Index.Order.DESC]),
    ],
)
data class UserWord(
    @PrimaryKey
    val word: String,
    val locale: String,
    val count: Int,
    val lastUsedAt: Long,
    /**
     * How many times this word was committed with shift pressed for its first letter, not by
     * auto-capitalise. Above zero, the native model suggests it capitalised. Never decremented.
     */
    val deliberateCapitals: Int = 0,
    /**
     * How many times the user chose this word on purpose: picked it from the strip, or reverted a
     * correction to get it back. Above zero, the native model offers it and autocorrect leaves it
     * alone. Never decremented; a word imported from a file arrives asserted.
     */
    val asserted: Int = 0,
)
