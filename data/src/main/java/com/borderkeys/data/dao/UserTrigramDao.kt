// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.borderkeys.data.PersonalWordDecay
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import kotlinx.coroutines.flow.Flow

@Dao
interface UserTrigramDao {

    /** Every triple, in the order each was first recorded. */
    @Query("SELECT * FROM user_trigrams ORDER BY rowid")
    suspend fun all(): List<UserTrigram>

    /** The triples after [previousWord2] [previousWord1] in [language]. */
    @Query(
        """
        SELECT * FROM user_trigrams
        WHERE language = :language AND previousWord2 = :previousWord2 AND previousWord1 = :previousWord1
        """,
    )
    suspend fun inContext(language: String, previousWord2: String, previousWord1: String): List<UserTrigram>

    /** Every triple of [language]. */
    @Query("SELECT * FROM user_trigrams WHERE language = :language")
    suspend fun inLanguage(language: String): List<UserTrigram>

    @Query("SELECT COUNT(*) FROM user_trigrams WHERE language = :language")
    suspend fun countIn(language: String): Int

    /** The triples [word] takes part in, wherever it sits, in every language. */
    /** The triples used at most [uses] times, in the order each was first recorded. */
    @Query("SELECT * FROM user_trigrams WHERE count <= :uses ORDER BY rowid")
    suspend fun usedAtMost(uses: Int): List<UserTrigram>

    @Query("SELECT * FROM user_trigrams WHERE previousWord2 = :word OR previousWord1 = :word OR word = :word")
    suspend fun involving(word: String): List<UserTrigram>

    /** The triples that run through the pair [first] [second] in [language], on either side. */
    @Query(
        """
        SELECT * FROM user_trigrams
        WHERE language = :language
          AND ((previousWord2 = :first AND previousWord1 = :second)
            OR (previousWord1 = :first AND word = :second))
        """,
    )
    suspend fun containingPair(language: String, first: String, second: String): List<UserTrigram>

    @Upsert
    suspend fun upsert(triple: UserTrigram)

    @Delete
    suspend fun delete(triples: List<UserTrigram>)

    /** [UserWordDao.decayStale], for triples. */
    @Query(
        """
        UPDATE user_trigrams SET count = MAX(${PersonalWordDecay.STALE_FLOOR}, count / ${PersonalWordDecay.HALVING}), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND count > ${PersonalWordDecay.STALE_FLOOR}
        """,
    )
    suspend fun decayStale(cutoff: Long, now: Long)

    @Query("DELETE FROM user_trigrams")
    suspend fun deleteAll()

    /** Every triple, most used first, then most recent, kept live by Room. */
    @Query("SELECT * FROM user_trigrams ORDER BY count DESC, lastUsedAt DESC")
    fun observeAll(): Flow<List<UserTrigram>>

    @Query("SELECT COUNT(*) FROM user_trigrams")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM user_trigrams")
    suspend fun count(): Int

    @Query(
        """
        SELECT * FROM user_triple_contexts
        WHERE language = :language AND previousWord2 = :previousWord2 AND previousWord1 = :previousWord1
        """,
    )
    suspend fun context(language: String, previousWord2: String, previousWord1: String): UserTripleContext?

    @Query("SELECT * FROM user_triple_contexts WHERE language = :language")
    suspend fun contextsIn(language: String): List<UserTripleContext>

    @Query("SELECT * FROM user_triple_contexts")
    suspend fun allContexts(): List<UserTripleContext>

    @Upsert
    suspend fun upsertContext(context: UserTripleContext)

    /** Forgets the contexts [word] is part of, in every language. */
    @Query("DELETE FROM user_triple_contexts WHERE previousWord2 = :word OR previousWord1 = :word")
    suspend fun deleteContextsOf(word: String)

    /** [decayStale], for the contexts' totals. */
    @Query(
        """
        UPDATE user_triple_contexts SET total = MAX(${PersonalWordDecay.STALE_FLOOR}, total / ${PersonalWordDecay.HALVING}), lastUsedAt = :now
        WHERE lastUsedAt < :cutoff AND total > ${PersonalWordDecay.STALE_FLOOR}
        """,
    )
    suspend fun decayStaleContexts(cutoff: Long, now: Long)

    @Query("DELETE FROM user_triple_contexts")
    suspend fun deleteAllContexts()

    /** Deletes the contexts no triple follows any more. */
    @Query(
        """
        DELETE FROM user_triple_contexts WHERE NOT EXISTS (
            SELECT 1 FROM user_trigrams WHERE user_trigrams.language = user_triple_contexts.language
            AND user_trigrams.previousWord2 = user_triple_contexts.previousWord2
            AND user_trigrams.previousWord1 = user_triple_contexts.previousWord1
        )
        """,
    )
    suspend fun deleteEmptyContexts()
}

/** One pending triple update, as accumulated in memory between flushes; its words in lower case. */
data class LearnedTrigram(
    /** The primary language subtag the triple was written in. */
    val language: String,
    val previousWord2: String,
    val previousWord1: String,
    val word: String,
    val delta: Int,
    val lastUsedAt: Long,
)

/** [PairStep], for the triples. */
data class TripleStep(val use: LearnedTrigram?, val dropped: List<UserTrigram> = emptyList())
