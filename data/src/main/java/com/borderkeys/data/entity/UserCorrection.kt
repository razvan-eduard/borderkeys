// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A correction this device has learned in one language: a spelling typed or swiped ([key]) that
 * was put right to [word] after [previousWord], and how often. The words are kept as
 * [com.borderkeys.data.PhraseKey] keys them, the previous word [UserBigram.SENTENCE_START] at a
 * sentence's start; the language, the key, the previous word and the word are the primary key.
 */
@Entity(
    tableName = "user_corrections",
    primaryKeys = ["language", "key", "previousWord", "word"],
    indices = [
        // The lookups when a word is forgotten or blocked, which take its corrections with it.
        Index(value = ["word"]),
        Index(value = ["previousWord"]),
    ],
)
data class UserCorrection(
    /** The primary language subtag the correction was learned in. */
    val language: String,
    /** The spelling put right, as [com.borderkeys.data.PhraseKey] keys it. */
    val key: String,
    val previousWord: String,
    val word: String,
    val count: Int,
    val lastUsedAt: Long,
)
