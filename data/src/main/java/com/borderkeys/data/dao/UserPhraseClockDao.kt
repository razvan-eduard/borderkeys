// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.borderkeys.data.entity.UserPhraseClock

@Dao
interface UserPhraseClockDao {

    @Query("SELECT * FROM user_phrase_clocks WHERE language = :language")
    suspend fun clock(language: String): UserPhraseClock?

    @Query("SELECT * FROM user_phrase_clocks")
    suspend fun all(): List<UserPhraseClock>

    @Upsert
    suspend fun upsert(clock: UserPhraseClock)

    @Query("DELETE FROM user_phrase_clocks")
    suspend fun deleteAll()
}
