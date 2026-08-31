// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.borderkeys.data.entity.LanguagePackEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface LanguagePackDao {

    @Query("SELECT * FROM language_packs ORDER BY enabled DESC, tag")
    fun observeAll(): Flow<List<LanguagePackEntry>>

    @Query("SELECT * FROM language_packs WHERE enabled = 1 ORDER BY weight DESC")
    suspend fun enabledPacks(): List<LanguagePackEntry>

    @Query("SELECT * FROM language_packs WHERE id = :id LIMIT 1")
    suspend fun find(id: Long): LanguagePackEntry?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LanguagePackEntry): Long

    @Update
    suspend fun update(entry: LanguagePackEntry)

    @Delete
    suspend fun delete(entry: LanguagePackEntry)

    @Query("UPDATE language_packs SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE language_packs SET weight = :weight WHERE id = :id")
    suspend fun setWeight(id: Long, weight: Float)

    /** Switches a pack off and records why, after a start-up hash check failed. */
    @Query("UPDATE language_packs SET enabled = 0, integrityFailedAt = :failedAt WHERE id = :id")
    suspend fun markIntegrityFailure(id: Long, failedAt: Long)
}
