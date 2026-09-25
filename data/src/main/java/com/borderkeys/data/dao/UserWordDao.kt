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
     * [deliberateCapitalDelta] and [assertedDelta] are each 0 or 1 -- see
     * [UserWord.deliberateCapitals] and [UserWord.asserted] for what they mean. A plain
     * addition on both branches, the same shape as [delta] itself: there is no revoke path, so
     * nothing here ever needs to read the existing value to decide what to write.
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

    /**
     * Lifts a word's deliberate-capital count to at least [count] -- for a restore, which
     * carries the count another device had reached rather than one more commit's worth. Never
     * lowers it, for the same reason [increment] never does.
     */
    @Query("UPDATE user_words SET deliberateCapitals = MAX(deliberateCapitals, :count) WHERE word = :word")
    suspend fun raiseDeliberateCapitals(word: String, count: Int)

    /** The same for the asserted count -- see [raiseDeliberateCapitals]. */
    @Query("UPDATE user_words SET asserted = MAX(asserted, :count) WHERE word = :word")
    suspend fun raiseAsserted(word: String, count: Int)

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

    /**
     * Drops words written exactly once and not written since.
     *
     * The one case [decayStale] above cannot reach, because it only touches rows with a count
     * above one: a word seen a single time keeps that count for ever and its row is never
     * removed by anything, so the table only grows. Most of them are not vocabulary at all --
     * a name from one conversation, a code identifier pasted into a message, a typo that
     * survived to a delimiter -- and a personal dictionary that keeps every one of them for
     * the life of the install is a store nobody can curate by hand.
     *
     * Only count = 1, and only past the cutoff. Anything written twice has been confirmed by
     * the person writing it and is dealt with by halving, never by deletion: forgetting a word
     * somebody actually uses because they had a quiet season would be far worse than keeping
     * one they do not.
     */
    @Query("DELETE FROM user_words WHERE count <= 1 AND lastUsedAt < :cutoff")
    suspend fun deleteUnconfirmedBefore(cutoff: Long): Int

    /** The words such a sweep would remove, read before the delete so the phrases naming them
     *  can be cleared in the same transaction. */
    @Query("SELECT word FROM user_words WHERE count <= 1 AND lastUsedAt < :cutoff")
    suspend fun unconfirmedBefore(cutoff: Long): List<String>

    /**
     * The least valuable words past [keep], worst last, for a table that has outgrown its cap.
     *
     * Ordered the way the dictionary itself is: how often the word was confirmed first, and how
     * recently it was written to break a tie. A word written twenty times two years ago
     * therefore outranks one written twice last week, which is right -- twenty confirmations is
     * evidence and the halving sweep is what deals with its age.
     *
     * `LIMIT -1 OFFSET :keep` is SQLite's way of saying "everything after the first :keep". A
     * plain LIMIT cannot express it, and the alternative of reading every row into memory to
     * slice it is the thing a cap exists to avoid.
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
