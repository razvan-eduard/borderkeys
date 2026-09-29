// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyTouchDao {

    /** The total weight of the heatmap's taps, kept live by Room. */
    @Query("SELECT COALESCE(SUM(taps), 0) FROM key_touches")
    fun observeTaps(): Flow<Double>

    @Query("DELETE FROM key_touches")
    suspend fun deleteAll()
}
