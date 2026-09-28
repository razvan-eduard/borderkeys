// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Three words this device has seen written in a row, and how often. Never recorded in a private
 * field; deleted with any word it names.
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
