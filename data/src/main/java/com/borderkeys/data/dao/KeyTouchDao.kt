// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.borderkeys.data.entity.KeyTouch
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyTouchDao {

    /** The total weight of the heatmap's taps, kept live by Room. */
    @Query("SELECT COALESCE(SUM(taps), 0) FROM key_touches")
    fun observeTaps(): Flow<Double>

    /** Every bucket's totals, kept live by Room. */
    @Query("SELECT * FROM key_touches")
    fun observeAll(): Flow<List<KeyTouch>>

    @Query("SELECT * FROM key_touches WHERE bucket = :bucket")
    suspend fun inBucket(bucket: String): List<KeyTouch>

    @Query("SELECT * FROM key_touches WHERE bucket = :bucket AND code = :code")
    suspend fun find(bucket: String, code: Int): KeyTouch?

    @Upsert
    suspend fun upsert(touch: KeyTouch)

    @Query("DELETE FROM key_touches")
    suspend fun deleteAll()
}
