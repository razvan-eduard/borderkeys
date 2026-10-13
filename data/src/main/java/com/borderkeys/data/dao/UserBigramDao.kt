// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.borderkeys.data.PersonalWordDecay
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import kotlinx.coroutines.flow.Flow

@Dao
interface UserBigramDao {

    /** Every pair, in the order each was first recorded. */
    @Query("SELECT * FROM user_bigrams ORDER BY rowid")
    suspend fun all(): List<UserBigram>

    /** The pairs after [previousWord] in [language]. */
    @Query("SELECT * FROM user_bigrams WHERE language = :language AND previousWord = :previousWord")
    suspend fun inContext(language: String, previousWord: String): List<UserBigram>

    /** Every pair of [language]. */
    @Query("SELECT * FROM user_bigrams WHERE language = :language")
    suspend fun inLanguage(language: String): List<UserBigram>

    @Query("SELECT COUNT(*) FROM user_bigrams WHERE language = :language")
    suspend fun countIn(language: String): Int

    /** The pairs [word] takes part in, on either side, in every language. */
    @Query("SELECT * FROM user_bigrams WHERE previousWord = :word OR word = :word")
    suspend fun involving(word: String): List<UserBigram>

    /** The pairs used at most [uses] times. */
    @Query("SELECT * FROM user_bigrams WHERE count <= :uses")
    suspend fun usedAtMost(uses: Int): List<UserBigram>

    @Upsert
    suspend fun upsert(pair: UserBigram)

    @Delete
    suspend fun delete(pairs: List<UserBigram>)

    /** [UserWordDao.decayStale], for pairs. */
    @Query(
        """
        UPDATE user_bigrams SET count = MAX(${PersonalWordDecay.STALE_FLOOR}, count / ${PersonalWordDecay.HALVING}), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > ${PersonalWordDecay.STALE_FLOOR}
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    @Query("DELETE FROM user_bigrams")
    suspend fun deleteAll()

    /** Every pair, most used first, then most recent, kept live by Room. */
    @Query("SELECT * FROM user_bigrams ORDER BY count DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<UserBigram>>

    @Query("SELECT COUNT(*) FROM user_bigrams")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_bigrams")
    suspend fun count(): Int

    @Query("SELECT * FROM user_pair_contexts WHERE language = :language AND previousWord = :previousWord")
    suspend fun context(language: String, previousWord: String): UserPairContext?

    @Query("SELECT * FROM user_pair_contexts WHERE language = :language")
    suspend fun contextsIn(language: String): List<UserPairContext>

    @Query("SELECT * FROM user_pair_contexts")
    suspend fun allContexts(): List<UserPairContext>

    @Upsert
    suspend fun upsertContext(context: UserPairContext)

    /** Forgets the contexts [word] is, in every language. */
    @Query("DELETE FROM user_pair_contexts WHERE previousWord = :word")
    suspend fun deleteContextsOf(word: String)

    /** [decayStale], for the contexts' totals. */
    @Query(
        """
        UPDATE user_pair_contexts SET total = MAX(${PersonalWordDecay.STALE_FLOOR}, total / ${PersonalWordDecay.HALVING}), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND total > ${PersonalWordDecay.STALE_FLOOR}
        """,
    )
    suspend fun decayStaleContexts(cutoff: Long, now: Long)

    @Query("DELETE FROM user_pair_contexts")
    suspend fun deleteAllContexts()

    /** Deletes the contexts no pair follows any more. */
    @Query(
        """
        DELETE FROM user_pair_contexts WHERE NOT EXISTS (
            SELECT 1 FROM user_bigrams WHERE user_bigrams.language = user_pair_contexts.language
            AND user_bigrams.previousWord = user_pair_contexts.previousWord
        )
        """,
    )
    suspend fun deleteEmptyContexts()
}

/** One pending pair update, as accumulated in memory between flushes; its words in lower case. */
data class LearnedBigram(
    /** The primary language subtag the pair was written in. */
    val language: String,
    val previousWord: String,
    val word: String,
    val delta: Int,
    val lastUsedAt: Long,
)

/**
 * One step of learning pairs as the engine took it: the pair use it recorded, or none once that use
 * was forgotten, and the pairs it dropped right after, in order.
 */
data class PairStep(val use: LearnedBigram?, val dropped: List<UserBigram> = emptyList())
