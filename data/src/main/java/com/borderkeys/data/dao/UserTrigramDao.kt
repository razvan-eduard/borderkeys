// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.borderkeys.data.entity.UserTrigram

@Dao
interface UserTrigramDao {

    @Query("SELECT * FROM user_trigrams ORDER BY count DESC LIMIT :limit")
    suspend fun topTriples(limit: Int): List<UserTrigram>

    @Query(
        """
        INSERT INTO user_trigrams (previousWord2, previousWord1, word, count, lastUsedAt)
        VALUES (:previousWord2, :previousWord1, :word, :delta, :lastUsedAt)
        ON CONFLICT(previousWord2, previousWord1, word) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt
        """,
    )
    suspend fun increment(
        previousWord2: String,
        previousWord1: String,
        word: String,
        delta: Int,
        lastUsedAt: Long,
    )

    @Transaction
    suspend fun incrementAll(triples: List<LearnedTrigram>) {
        for (triple in triples) {
            increment(triple.previousWord2, triple.previousWord1, triple.word, triple.delta,
                triple.lastUsedAt)
        }
    }

    /** Forgets every triple a word takes part in, wherever it sits. */
    @Query(
        "DELETE FROM user_trigrams WHERE previousWord2 = :word OR previousWord1 = :word " +
            "OR word = :word",
    )
    suspend fun deleteInvolving(word: String)

    @Query("DELETE FROM user_trigrams")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM user_trigrams")
    suspend fun count(): Int
}

/** One pending triple update, as accumulated in memory between flushes. */
data class LearnedTrigram(
    val previousWord2: String,
    val previousWord1: String,
    val word: String,
    val delta: Int,
    val lastUsedAt: Long,
)
