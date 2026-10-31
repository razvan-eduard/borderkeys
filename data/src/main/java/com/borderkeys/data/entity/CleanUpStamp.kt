// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** When the personal dictionary's clean-up last ran: one row, under [ONLY], written after a run. */
@Entity(tableName = "clean_up_stamps")
data class CleanUpStamp(
    @PrimaryKey
    val id: Int = ONLY,
    val ranAt: Long,
) {

    companion object {
        /** The id of the one row. */
        const val ONLY = 0
    }
}
