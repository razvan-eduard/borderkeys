// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.borderkeys.data.entity.AssistModelEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface AssistModelDao {

    @Query("SELECT * FROM assist_models ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<AssistModelEntry>>

    @Query("SELECT * FROM assist_models WHERE active = 1 LIMIT 1")
    suspend fun activeModel(): AssistModelEntry?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AssistModelEntry): Long

    @Delete
    suspend fun delete(entry: AssistModelEntry)

    /** Exactly one model is active; making one active deactivates the rest in the same step. */
    @Query("UPDATE assist_models SET active = (id = :id)")
    suspend fun setActive(id: Long)

    @Query("UPDATE assist_models SET active = 0, integrityFailedAt = :failedAt WHERE id = :id")
    suspend fun markIntegrityFailure(id: Long, failedAt: Long)
}
