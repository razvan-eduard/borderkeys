// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.borderkeys.data.entity.UserWord
import kotlinx.coroutines.flow.Flow

@Dao
interface UserWordDao {

    @Query("SELECT * FROM user_words ORDER BY count DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<UserWord>>

    @Query(
        """
        SELECT * FROM user_words
        WHERE word LIKE '%' || :query || '%'
        ORDER BY count DESC, lastUsedAt DESC
        """,
    )
    fun observeMatching(query: String): Flow<List<UserWord>>

    /** The first [limit] words in [wordsBeyond]'s order: by count, then by recency. */
    @Query("SELECT * FROM user_words ORDER BY count DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun topWords(limit: Int): List<UserWord>

    @Query("SELECT * FROM user_words WHERE word = :word LIMIT 1")
    suspend fun find(word: String): UserWord?

    /** The entry for [word] whatever its ASCII case, by SQLite's NOCASE. */
    @Query("SELECT * FROM user_words WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun findIgnoreCase(word: String): UserWord?

    /**
     * Adds [delta] to a word's count, inserting it if it is new, in one statement. Two processes
     * write this table. [deliberateCapitalDelta] and [assertedDelta] are each 0 or 1, added the
     * same way; see [UserWord.deliberateCapitals] and [UserWord.asserted].
     */
    @Query(
        """
        INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted)
        VALUES (:word, :locale, :delta, :lastUsedAt, :deliberateCapitalDelta, :assertedDelta)
        ON CONFLICT(word) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt,
            locale = :locale,
            deliberateCapitals = deliberateCapitals + :deliberateCapitalDelta,
            asserted = asserted + :assertedDelta
        """,
    )
    suspend fun increment(
        word: String,
        locale: String,
        delta: Int,
        lastUsedAt: Long,
        deliberateCapitalDelta: Int,
        assertedDelta: Int,
    )

    @Transaction
    suspend fun incrementAll(words: List<LearnedWord>) {
        for (entry in words) {
            increment(
                entry.word, entry.locale, entry.delta, entry.lastUsedAt,
                if (entry.deliberateCapital) 1 else 0,
                if (entry.asserted) 1 else 0,
            )
        }
    }

    /** Lifts a word's deliberate-capital count to at least [count], for a restore. */
    @Query("UPDATE user_words SET deliberateCapitals = MAX(deliberateCapitals, :count) WHERE word = :word")
    suspend fun raiseDeliberateCapitals(word: String, count: Int)

    /** The same for the asserted count -- see [raiseDeliberateCapitals]. */
    @Query("UPDATE user_words SET asserted = MAX(asserted, :count) WHERE word = :word")
    suspend fun raiseAsserted(word: String, count: Int)

    /**
     * Halves every count above one not used since [cutoff], in one statement, and stamps those
     * rows with [now] so the next halving waits a full half-life.
     */
    @Query(
        """
        UPDATE user_words SET count = MAX(1, count / 2), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > 1
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    /** Drops the words written exactly once and not since [cutoff]. */
    @Query("DELETE FROM user_words WHERE count <= 1 AND lastUsedAt < :cutoff")
    suspend fun deleteUnconfirmedBefore(cutoff: Long): Int

    /** The words such a sweep would remove, read before the delete so the phrases naming them
     *  can be cleared in the same transaction. */
    @Query("SELECT word FROM user_words WHERE count <= 1 AND lastUsedAt < :cutoff")
    suspend fun unconfirmedBefore(cutoff: Long): List<String>

    /**
     * The words past the first [keep], ordered by count and then by recency, worst last.
     * `LIMIT -1` is SQLite's "no limit".
     */
    @Query(
        """
        SELECT word FROM user_words
        ORDER BY count DESC, lastUsedAt DESC
        LIMIT -1 OFFSET :keep
        """,
    )
    suspend fun wordsBeyond(keep: Int): List<String>

    @Query("DELETE FROM user_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("DELETE FROM user_words")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM user_words")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM user_words")
    fun observeCount(): Flow<Int>
}

/** One pending learning update, as accumulated in memory between flushes. */
data class LearnedWord(
    val word: String,
    val locale: String,
    val delta: Int,
    val lastUsedAt: Long,
    /** See [UserWord.deliberateCapitals]. */
    val deliberateCapital: Boolean = false,
    /** See [UserWord.asserted]: the word was chosen on purpose, not merely written. */
    val asserted: Boolean = false,
)
