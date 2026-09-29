// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.withTransaction
import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.KeyTouchDao
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/**
 * The personal dictionary: what the keyboard has learned, and what it has been told to forget.
 */
class DictionaryRepository internal constructor(
    private val database: BorderKeysDatabase,
    private val userWords: UserWordDao,
    private val blockedWords: BlockedWordDao,
    private val userBigrams: UserBigramDao,
    private val userTrigrams: UserTrigramDao,
    private val keyTouches: KeyTouchDao,
) {
    val words: Flow<List<UserWord>> = userWords.observeAll()
    val blocked: Flow<List<BlockedWord>> = blockedWords.observeAll()

    /** The pairs and triples the settings screen lists, most used first. */
    fun topPairsLive(limit: Int = MAX_PHRASES_LISTED): Flow<List<UserBigram>> =
        userBigrams.observeTop(limit)

    fun topTriplesLive(limit: Int = MAX_PHRASES_LISTED): Flow<List<UserTrigram>> =
        userTrigrams.observeTop(limit)

    val pairCount: Flow<Int> = userBigrams.observeCount()
    val tripleCount: Flow<Int> = userTrigrams.observeCount()

    /** How many taps the heatmap rests on, each weighing less as it ages, rounded. */
    val touchTaps: Flow<Int> = keyTouches.observeTaps().map { it.roundToInt() }

    /**
     * Fires after an edit made by hand -- a word or a phrase forgotten, a word blocked or
     * unblocked, everything forgotten, a file imported -- so the keyboard can reload what it
     * holds in memory. The learning flush is not an edit and does not fire it.
     */
    val edits: SharedFlow<Unit> get() = editsFlow
    private val editsFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private fun edited() {
        editsFlow.tryEmit(Unit)
    }

    fun search(query: String): Flow<List<UserWord>> = userWords.observeMatching(query)

    /** The set pushed into the native engine at service start. */
    suspend fun topWords(limit: Int = MAX_WORDS_IN_MEMORY): List<UserWord> =
        userWords.topWords(limit)

    suspend fun blockedWordSet(): Set<String> = blockedWords.allWords().toSet()

    /** The word pairs pushed into the native engine at service start. */
    suspend fun topBigrams(limit: Int = MAX_BIGRAMS_IN_MEMORY): List<UserBigram> =
        userBigrams.topPairs(limit)

    /** How many pairs are remembered. */
    suspend fun bigramCount(): Int = userBigrams.count()

    /** The three-word sequences pushed into the native engine at service start. */
    suspend fun topTrigrams(limit: Int = MAX_TRIGRAMS_IN_MEMORY): List<UserTrigram> =
        userTrigrams.topTriples(limit)

    /** Applies a batch of learning updates in one transaction. */
    suspend fun applyLearned(updates: List<LearnedWord>) {
        if (updates.isEmpty()) {
            return
        }
        userWords.incrementAll(updates)
    }

    /** The same, for the pairs. Flushed in the same batch as the words. */
    suspend fun applyLearnedBigrams(updates: List<LearnedBigram>) {
        if (updates.isEmpty()) {
            return
        }
        userBigrams.incrementAll(updates)
    }

    suspend fun applyLearnedTrigrams(updates: List<LearnedTrigram>) {
        if (updates.isEmpty()) {
            return
        }
        userTrigrams.incrementAll(updates)
    }

    /**
     * Halves the stored count of every word, pair and triple nobody has written in a
     * [PersonalWordDecay.HALF_LIFE_MILLIS] or longer, then deletes the words written once before
     * [PersonalWordDecay.UNCONFIRMED_LIFE_MILLIS] and those past [MAX_WORDS_IN_MEMORY], with
     * their phrases.
     */
    suspend fun decayStaleEntries(now: Long = System.currentTimeMillis()) {
        val cutoff = now - PersonalWordDecay.HALF_LIFE_MILLIS
        val unconfirmedCutoff = now - PersonalWordDecay.UNCONFIRMED_LIFE_MILLIS
        database.withTransaction {
            userWords.decayStale(cutoff, now)
            userBigrams.decayStale(cutoff, now)
            userTrigrams.decayStale(cutoff, now)
            for (word in userWords.unconfirmedBefore(unconfirmedCutoff)) {
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
            }
            userWords.deleteUnconfirmedBefore(unconfirmedCutoff)

            for (word in userWords.wordsBeyond(MAX_WORDS_IN_MEMORY)) {
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
                userWords.delete(word)
            }
        }
    }

    suspend fun findIgnoreCase(word: String): UserWord? = userWords.findIgnoreCase(word)

    /** Forgets a word and every phrase it was part of. */
    suspend fun forget(word: String) {
        database.withTransaction {
            userWords.delete(word)
            userBigrams.deleteInvolving(word)
            userTrigrams.deleteInvolving(word)
        }
        edited()
    }

    /** Forgets one pair and every triple that runs through it. The words stay. */
    suspend fun forgetPair(previousWord: String, word: String) {
        database.withTransaction {
            userBigrams.delete(previousWord, word)
            userTrigrams.deleteContainingPair(previousWord, word)
        }
        edited()
    }

    /** Forgets one triple. Its pairs and words stay. */
    suspend fun forgetTriple(previousWord2: String, previousWord1: String, word: String) {
        userTrigrams.delete(previousWord2, previousWord1, word)
        edited()
    }

    /** Forgets every learned word, pair and triple, and the heatmap. */
    suspend fun forgetEverything() {
        database.withTransaction {
            userWords.deleteAll()
            userBigrams.deleteAll()
            userTrigrams.deleteAll()
            keyTouches.deleteAll()
        }
        edited()
    }

    /** Forgets where taps land on every key. The words stay. */
    suspend fun forgetTouchPattern() {
        keyTouches.deleteAll()
        edited()
    }

    /** Refuses a word permanently and removes whatever was learned about it. */
    suspend fun block(word: String) {
        database.withTransaction {
            blockedWords.insert(BlockedWord(word))
            userWords.delete(word)
            userBigrams.deleteInvolving(word)
            userTrigrams.deleteInvolving(word)
        }
        edited()
    }

    suspend fun unblock(word: String) {
        blockedWords.delete(word)
        edited()
    }

    /** The personal dictionary as CSV, in the format [DictionaryCsv] defines. */
    suspend fun exportCsv(): CsvExport {
        val words = userWords.topWords(Int.MAX_VALUE)
        return CsvExport(DictionaryCsv.encode(words), words.size)
    }

    /** The CSV text and how many words it carries. */
    class CsvExport(val csv: String, val words: Int)

    /**
     * Imports an export, merging counts into whatever is already here. Returns the row count.
     */
    suspend fun importCsv(csv: String, now: Long = System.currentTimeMillis()): Int {
        val updates = DictionaryCsv.decode(csv, now)
        applyLearned(updates)
        edited()
        return updates.size
    }

    private companion object {
        const val MAX_WORDS_IN_MEMORY = 20_000

        /** Matches UserModel::kMaxBigrams. */
        const val MAX_BIGRAMS_IN_MEMORY = 4_096

        /** Matches UserModel::kMaxTrigrams. */
        const val MAX_TRIGRAMS_IN_MEMORY = 2_048

        /** How many pairs, and how many triples, the settings screen lists. */
        const val MAX_PHRASES_LISTED = 100
    }
}
