// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import com.borderkeys.data.entity.UserBigram

@Dao
interface UserBigramDao {

    /** The pairs pushed into the native model at service start, most used first. */
    @Query("SELECT * FROM user_bigrams ORDER BY count DESC LIMIT :limit")
    suspend fun topPairs(limit: Int): List<UserBigram>

    /** Adds [delta] to a pair's count, inserting it if it is new, in one statement. */
    @Query(
        """
        INSERT INTO user_bigrams (previousWord, word, count, lastUsedAt)
        VALUES (:previousWord, :word, :delta, :lastUsedAt)
        ON CONFLICT(previousWord, word) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt
        """,
    )
    suspend fun increment(previousWord: String, word: String, delta: Int, lastUsedAt: Long)

    @Transaction
    suspend fun incrementAll(pairs: List<LearnedBigram>) {
        for (pair in pairs) {
            increment(pair.previousWord, pair.word, pair.delta, pair.lastUsedAt)
        }
    }

    /** [UserWordDao.decayStale], for pairs. */
    @Query(
        """
        UPDATE user_bigrams SET count = MAX(1, count / 2), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > 1
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    /** Forgets every pair a word takes part in, on either side. */
    @Query("DELETE FROM user_bigrams WHERE previousWord = :word OR word = :word")
    suspend fun deleteInvolving(word: String)

    /** Forgets one pair, and nothing else. */
    @Query("DELETE FROM user_bigrams WHERE previousWord = :previousWord AND word = :word")
    suspend fun delete(previousWord: String, word: String)

    @Query("DELETE FROM user_bigrams")
    suspend fun deleteAll()

    /** Every pair, most used first, then most recent, kept live by Room. */
    @Query("SELECT * FROM user_bigrams ORDER BY count DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<UserBigram>>

    @Query("SELECT COUNT(*) FROM user_bigrams")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_bigrams")
    suspend fun count(): Int
}

/** One pending pair update, as accumulated in memory between flushes. */
data class LearnedBigram(
    val previousWord: String,
    val word: String,
    val delta: Int,
    val lastUsedAt: Long,
)
