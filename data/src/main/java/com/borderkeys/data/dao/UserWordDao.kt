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

    /**
     * The read that happens once at service start and is pushed straight into the native model.
     *
     * Capped rather than unbounded: the native side holds these in RAM in the IME process, and a
     * dictionary that grew for years should not decide how much memory the keyboard needs.
     */
    @Query("SELECT * FROM user_words ORDER BY count DESC LIMIT :limit")
    suspend fun topWords(limit: Int): List<UserWord>

    @Query("SELECT * FROM user_words WHERE word = :word LIMIT 1")
    suspend fun find(word: String): UserWord?

    /** The entry for [word] whatever its case -- a chip label is re-cased for the row it sits
     *  in, and the stored spelling is whatever the word was last committed as. ASCII folding
     *  only, which is SQLite's own NOCASE; an accented capital is rare enough to live with. */
    @Query("SELECT * FROM user_words WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun findIgnoreCase(word: String): UserWord?

    /**
     * Adds [delta] to a word's count, inserting it if it is new, in one statement.
     *
     * Written as an upsert rather than read-modify-write on purpose. The learning flush and a
     * user editing their dictionary in Settings are two writers, in two processes when the
     * settings screen is open, and a read-modify-write between them loses whichever update
     * finished second.
     *
     * [deliberateCapitalDelta] is 0 or 1 -- see [UserWord.deliberateCapitals] for what it means.
     * A plain addition on both branches, the same shape as [delta] itself: there is no revoke
     * path, so nothing here ever needs to read the existing value to decide what to write.
     */
    @Query(
        """
        INSERT INTO user_words (word, locale, count, lastUsedAt, deliberateCapitals)
        VALUES (:word, :locale, :delta, :lastUsedAt, :deliberateCapitalDelta)
        ON CONFLICT(word) DO UPDATE SET
            count = count + :delta,
            lastUsedAt = :lastUsedAt,
            locale = :locale,
            deliberateCapitals = deliberateCapitals + :deliberateCapitalDelta
        """,
    )
    suspend fun increment(
        word: String,
        locale: String,
        delta: Int,
        lastUsedAt: Long,
        deliberateCapitalDelta: Int,
    )

    @Transaction
    suspend fun incrementAll(words: List<LearnedWord>) {
        for (entry in words) {
            increment(
                entry.word, entry.locale, entry.delta, entry.lastUsedAt,
                if (entry.deliberateCapital) 1 else 0,
            )
        }
    }

    /**
     * Lifts a word's deliberate-capital count to at least [count] -- for a restore, which
     * carries the count another device had reached rather than one more commit's worth. Never
     * lowers it, for the same reason [increment] never does.
     */
    @Query("UPDATE user_words SET deliberateCapitals = MAX(deliberateCapitals, :count) WHERE word = :word")
    suspend fun raiseDeliberateCapitals(word: String, count: Int)

    /**
     * Halves the count of anything not used since [cutoff], and nothing else.
     *
     * A plain conditional `UPDATE`, not a read-modify-write: [increment] above is deliberately
     * one atomic statement because the learning flush and a person editing Settings are two
     * writers that must not lose one another's update, and reading a count here to decay it
     * before writing it back would reintroduce exactly that race. This has no such race, because
     * `lastUsedAt < :cutoff` can never be true of a row someone is writing to right now -- if it
     * were being written to, its `lastUsedAt` would be recent enough to fail that test. The
     * `lastUsedAt` bump on the rows it does touch is what stops the same row from being halved
     * again on the very next flush, rather than waiting out a full half-life.
     */
    @Query(
        """
        UPDATE user_words SET count = MAX(1, count / 2), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > 1
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    @Query("DELETE FROM user_words WHERE word = :word")
    suspend fun delete(word: String)

    @Query("DELETE FROM user_words")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM user_words")
    suspend fun count(): Int
}

/** One pending learning update, as accumulated in memory between flushes. */
data class LearnedWord(
    val word: String,
    val locale: String,
    val delta: Int,
    val lastUsedAt: Long,
    /** See [UserWord.deliberateCapitals]. */
    val deliberateCapital: Boolean = false,
)
