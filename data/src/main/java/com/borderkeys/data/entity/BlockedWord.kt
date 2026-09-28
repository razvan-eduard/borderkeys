// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A word the user has refused; the engine never proposes it again. */
@Entity(tableName = "blocked_words")
data class BlockedWord(
    @PrimaryKey
    val word: String,
)
