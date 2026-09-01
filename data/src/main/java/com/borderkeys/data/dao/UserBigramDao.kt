// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.borderkeys.data.entity.UserBigram

@Dao
interface UserBigramDao {

    /**
     * The read that happens once at service start and is pushed straight into the native model.
     *
     * Capped for the same reason the words are: these are held in RAM in the IME process, and
     * the native side has its own cap of four thousand pairs. Asking for more than it will keep
     * would be reading rows to throw them away.
     */
    @Query("SELECT * FROM user_bigrams ORDER BY count DESC LIMIT :limit")
    suspend fun topPairs(limit: Int): List<UserBigram>

    /**
     * Adds [delta] to a pair's count, inserting it if it is new.
     *
     * An upsert for the same reason as the words: the learning flush and the settings screen are
     * two writers and a read-modify-write between them loses an update.
     */
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

    /**
     * Forgets every pair a word takes part in, on either side.
     *
     * Called when the word itself is forgotten or blocked. Leaving the pairs behind would keep
     * predicting a word the user has just asked never to see again, reached through a phrase
     * instead of through the dictionary.
     */
    @Query("DELETE FROM user_bigrams WHERE previousWord = :word OR word = :word")
    suspend fun deleteInvolving(word: String)

    @Query("DELETE FROM user_bigrams")
    suspend fun deleteAll()

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
