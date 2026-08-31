// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A word the user has explicitly refused. The engine never proposes it again.
 *
 * Separate from deleting it from [UserWord]: deleting only forgets that it was learned, and the
 * word comes straight back from the language pack the next time it is typed. This is a
 * permanent no, and it is the only place in the application where the user can make the keyboard
 * stop suggesting something.
 */
@Entity(tableName = "blocked_words")
data class BlockedWord(
    @PrimaryKey
    val word: String,
)
