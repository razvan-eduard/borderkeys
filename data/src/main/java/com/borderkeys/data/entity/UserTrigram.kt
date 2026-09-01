// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Three words this device has seen written in a row, and how often.
 *
 * The narrower half of the context model. A pair says what usually follows one word; this says
 * what follows a particular two, which is both rarer and more certain when it fires -- so the
 * scorer prefers it and falls back to the pair when there is no triple, the same way the
 * language pack's own n-grams back off.
 *
 * Everything true of [UserBigram] about privacy is more true here: three words in sequence is
 * closer still to a record of what was written. Same encrypted database, same exclusion from
 * backup, never recorded in a private field, and deleted by the same "forget everything" and by
 * forgetting any word it names.
 */
@Entity(
    tableName = "user_trigrams",
    primaryKeys = ["previousWord2", "previousWord1", "word"],
    indices = [
        Index(value = ["count"], orders = [Index.Order.DESC]),
        Index(value = ["word"]),
        Index(value = ["previousWord1"]),
    ],
)
data class UserTrigram(
    val previousWord2: String,
    val previousWord1: String,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
)
