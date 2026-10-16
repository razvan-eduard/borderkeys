// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.borderkeys.data.PersonalWordDecay
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

    /** Every learned spelling, in no order. */
    @Query("SELECT word FROM user_words")
    suspend fun allWords(): List<String>

    /**
     * Adds [delta] to a word's count, inserting it if it is new, in one statement. Two processes
     * write this table. [deliberateCapitalDelta], [assertedDelta] and [addedDelta] are each 0 or
     * 1, added the same way; see [UserWord.deliberateCapitals], [UserWord.asserted] and
     * [UserWord.added].
     */
    @Query(
        """
        INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals, asserted, added)
        VALUES (:word, :locale, :delta, :lastUsedAt, :deliberateCapitalDelta, :assertedDelta, :addedDelta)
        ON CONFLICT(word) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt,
            locale = :locale,
            deliberateCapitals = deliberateCapitals + :deliberateCapitalDelta,
            asserted = asserted + :assertedDelta,
            added = added + :addedDelta
        """,
    )
    suspend fun increment(
        word: String,
        locale: String,
        delta: Int,
        lastUsedAt: Long,
        deliberateCapitalDelta: Int,
        assertedDelta: Int,
        addedDelta: Int,
    )

    @Transaction
    suspend fun incrementAll(words: List<LearnedWord>) {
        for (entry in words) {
            increment(
                entry.word, entry.locale, entry.delta, entry.lastUsedAt,
                if (entry.deliberateCapital) 1 else 0,
                if (entry.asserted) 1 else 0,
                addedDelta = 0,
            )
        }
    }

    /** Lifts a word's deliberate-capital count to at least [count], for a restore. */
    @Query("UPDATE user_words SET deliberateCapitals = MAX(deliberateCapitals, :count) WHERE word = :word")
    suspend fun raiseDeliberateCapitals(word: String, count: Int)

    /** The same for the asserted count -- see [raiseDeliberateCapitals]. */
    @Query("UPDATE user_words SET asserted = MAX(asserted, :count) WHERE word = :word")
    suspend fun raiseAsserted(word: String, count: Int)

    /** The same for the added-by-hand count -- see [raiseDeliberateCapitals]. */
    @Query("UPDATE user_words SET added = MAX(added, :count) WHERE word = :word")
    suspend fun raiseAdded(word: String, count: Int)

    /**
     * Halves every count above one not used since [cutoff], in one statement, and stamps those
     * rows with [now] so the next halving waits a full half-life.
     */
    @Query(
        """
        UPDATE user_words SET count = MAX(${PersonalWordDecay.STALE_FLOOR}, count / ${PersonalWordDecay.HALVING}), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > ${PersonalWordDecay.STALE_FLOOR}
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    /** Drops the words written exactly once, never chosen on purpose and not used since [cutoff]. */
    @Query("DELETE FROM user_words WHERE $SINGLE_USE_NEVER_CHOSEN")
    suspend fun deleteUnconfirmedBefore(cutoff: Long): Int

    /** The words such a sweep would remove, read before the delete so the phrases naming them
     *  can be cleared in the same transaction. */
    @Query("SELECT word FROM user_words WHERE $SINGLE_USE_NEVER_CHOSEN")
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

    companion object {
        /** The rows the single-use clean-up removes: written once, never chosen on purpose, unused since the cutoff. */
        const val SINGLE_USE_NEVER_CHOSEN = "count <= 1 AND asserted = 0 AND lastUsedAt < :cutoff"
    }
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
