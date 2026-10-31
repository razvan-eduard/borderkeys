// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import com.borderkeys.data.entity.UserCorrection

@Dao
interface UserCorrectionDao {

    /** Every learned correction, in no order. */
    @Query("SELECT * FROM user_corrections")
    suspend fun all(): List<UserCorrection>

    /**
     * Merges [correction] into the row of its language, key, previous word and word, as a restore
     * does: the count and the last use the larger of the two, the row inserted when new.
     */
    @Query(
        """
        INSERT INTO user_corrections (language, `key`, previousWord, word, count, lastUsedAt)
        VALUES (:language, :key, :previousWord, :word, :count, :lastUsedAt)
        ON CONFLICT(language, `key`, previousWord, word) DO UPDATE SET
            count = MAX(count, :count),
            lastUsedAt = MAX(lastUsedAt, :lastUsedAt)
        """,
    )
    suspend fun merge(language: String, key: String, previousWord: String, word: String, count: Int, lastUsedAt: Long)

    /** [merge] of [correction]. */
    suspend fun merge(correction: UserCorrection) = merge(
        correction.language, correction.key, correction.previousWord, correction.word, correction.count,
        correction.lastUsedAt,
    )

    /** Deletes every correction to the word keyed [word], and every one learned after it. */
    @Query("DELETE FROM user_corrections WHERE word = :word OR previousWord = :word")
    suspend fun deleteWord(word: String)

    @Query("DELETE FROM user_corrections")
    suspend fun deleteAll()
}
