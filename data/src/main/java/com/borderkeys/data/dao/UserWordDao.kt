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

    /** The first [limit] rows in [rowsBeyond]'s order: by count, then by recency. */
    @Query("SELECT * FROM user_words ORDER BY count DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun topWords(limit: Int): List<UserWord>

    /** Every word added by hand, by count and then by recency. */
    @Query("SELECT * FROM user_words WHERE added > 0 ORDER BY count DESC, lastUsedAt DESC")
    suspend fun addedWords(): List<UserWord>

    /** The first [limit] words not added by hand, by count and then by recency. */
    @Query("SELECT * FROM user_words WHERE added = 0 ORDER BY count DESC, lastUsedAt DESC LIMIT :limit")
    suspend fun topWordsNotAdded(limit: Int): List<UserWord>

    /** Every language's row of the spelling [word]. */
    @Query("SELECT * FROM user_words WHERE word = :word")
    suspend fun rows(word: String): List<UserWord>

    /** Every learned spelling, each once, in no order. */
    @Query("SELECT DISTINCT word FROM user_words")
    suspend fun allWords(): List<String>

    /**
     * Adds [delta] to a word's count in [language], inserting its row if it is new, in one
     * statement. [deliberateCapitalDelta], [assertedDelta] and [addedDelta] are added the same way;
     * see [UserWord.deliberateCapitals], [UserWord.asserted] and [UserWord.added].
     */
    @Query(
        """
        INSERT INTO user_words (word, language, count, lastUsedAt, deliberateCapitals, asserted, added)
        VALUES (:word, :language, :delta, :lastUsedAt, :deliberateCapitalDelta, :assertedDelta, :addedDelta)
        ON CONFLICT(word, language) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt,
            deliberateCapitals = deliberateCapitals + :deliberateCapitalDelta,
            asserted = asserted + :assertedDelta,
            added = added + :addedDelta
        """,
    )
    suspend fun increment(
        word: String,
        language: String,
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
                entry.word, entry.language, entry.delta, entry.lastUsedAt,
                if (entry.deliberateCapital) 1 else 0,
                entry.asserted,
                addedDelta = 0,
            )
        }
    }

    /**
     * Merges [row] into the row of its word and language, as a restore does: each count and the
     * last use the larger of the two, the row inserted when new.
     */
    @Query(
        """
        INSERT INTO user_words (word, language, count, lastUsedAt, deliberateCapitals, asserted, added)
        VALUES (:word, :language, :count, :lastUsedAt, :deliberateCapitals, :asserted, :added)
        ON CONFLICT(word, language) DO UPDATE SET
            count = MAX(count, :count),
            lastUsedAt = MAX(lastUsedAt, :lastUsedAt),
            deliberateCapitals = MAX(deliberateCapitals, :deliberateCapitals),
            asserted = MAX(asserted, :asserted),
            added = MAX(added, :added)
        """,
    )
    suspend fun merge(
        word: String,
        language: String,
        count: Int,
        lastUsedAt: Long,
        deliberateCapitals: Int,
        asserted: Int,
        added: Int,
    )

    /** [merge] of [row]. */
    suspend fun merge(row: UserWord) =
        merge(row.word, row.language, row.count, row.lastUsedAt, row.deliberateCapitals, row.asserted, row.added)

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
     * The rows past the first [keep] not added by hand, ordered by count and then by recency,
     * worst last. `LIMIT -1` is SQLite's "no limit".
     */
    @Query(
        """
        SELECT * FROM user_words
        WHERE added = 0
        ORDER BY count DESC, lastUsedAt DESC
        LIMIT -1 OFFSET :keep
        """,
    )
    suspend fun rowsBeyond(keep: Int): List<UserWord>

    /** Deletes the spelling [word] in every language. */
    @Query("DELETE FROM user_words WHERE word = :word")
    suspend fun delete(word: String)

    /** Deletes the spelling [word]'s row in [language]. */
    @Query("DELETE FROM user_words WHERE word = :word AND language = :language")
    suspend fun delete(word: String, language: String)

    @Query("DELETE FROM user_words")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM user_words")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM user_words")
    fun observeCount(): Flow<Int>

    /** How many words the cap can take: those not added by hand. */
    @Query("SELECT COUNT(*) FROM user_words WHERE added = 0")
    fun observeCountNotAdded(): Flow<Int>

    companion object {
        /** The rows the single-use clean-up removes: written once, never chosen on purpose, unused since the cutoff. */
        const val SINGLE_USE_NEVER_CHOSEN = "count <= 1 AND asserted = 0 AND lastUsedAt < :cutoff"
    }
}

/** One pending learning update, as accumulated in memory between flushes. */
data class LearnedWord(
    val word: String,
    /** The primary language subtag the word was committed in, [UserWord.language]. */
    val language: String,
    val delta: Int,
    val lastUsedAt: Long,
    /** See [UserWord.deliberateCapitals]. */
    val deliberateCapital: Boolean = false,
    /** See [UserWord.asserted]: how many of these uses were choices. */
    val asserted: Int = 0,
)
