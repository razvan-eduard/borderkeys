// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.borderkeys.data.entity.BlockedWord
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedWordDao {

    @Query("SELECT * FROM blocked_words ORDER BY word")
    fun observeAll(): Flow<List<BlockedWord>>

    /** Loaded once at service start and held as a set, so the check costs no query per keystroke. */
    @Query("SELECT word FROM blocked_words")
    suspend fun allWords(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(word: BlockedWord)

    @Query("DELETE FROM blocked_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_words WHERE word = :word)")
    suspend fun isBlocked(word: String): Boolean
}
