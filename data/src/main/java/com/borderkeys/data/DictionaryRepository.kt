// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.withTransaction
import com.borderkeys.data.dao.BlockedWordDao
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

/**
 * The personal dictionary: what the keyboard has learned, and what it has been told to forget.
 */
class DictionaryRepository internal constructor(
    private val database: BorderKeysDatabase,
    private val userWords: UserWordDao,
    private val blockedWords: BlockedWordDao,
    private val userBigrams: UserBigramDao,
    private val userTrigrams: UserTrigramDao,
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

    /** How many pairs are remembered. Shown in Settings, because it should be visible. */
    suspend fun bigramCount(): Int = userBigrams.count()

    /** The three-word sequences pushed into the native engine at service start. */
    suspend fun topTrigrams(limit: Int = MAX_TRIGRAMS_IN_MEMORY): List<UserTrigram> =
        userTrigrams.topTriples(limit)

    /**
     * Applies a batch of learning updates in one transaction.
     *
     * Batched because this is the flush of an in-memory buffer, not a per-keystroke write. A
     * single INSERT on the path of a key press would put a disk write, an encryption pass and a
     * transaction inside a two-millisecond budget.
     */
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
     * [PersonalWordDecay.HALF_LIFE_MILLIS] or longer.
     *
     * Called occasionally from the learning flush, not on every one -- see the call site for the
     * throttle. This is the half of decay that actually shrinks what is on disk; the other half
     * ([PersonalWordDecay.decayed], applied to [topWords]/[topBigrams]/[topTriples] when they are
     * pushed into the native model) makes the influence of a stale entry correct on every load
     * even between sweeps, but never rewrites the row it read. Without this one, a count that
     * stopped being touched years ago would still occupy one of the limited slots the native
     * model or [topWords]'s own `LIMIT` keeps room for, crowding out something written last week.
     */
    suspend fun decayStaleEntries(now: Long = System.currentTimeMillis()) {
        val cutoff = now - PersonalWordDecay.HALF_LIFE_MILLIS
        val unconfirmedCutoff = now - PersonalWordDecay.UNCONFIRMED_LIFE_MILLIS
        database.withTransaction {
            userWords.decayStale(cutoff, now)
            userBigrams.decayStale(cutoff, now)
            userTrigrams.decayStale(cutoff, now)
            // And then the rows halving can never reach: written once, never again, and
            // otherwise kept for the life of the install. Their phrases go with them for the
            // reason [forget] gives -- a word predicted through a pair after the word itself
            // is gone is the dictionary appearing not to work in the most alarming way.
            for (word in userWords.unconfirmedBefore(unconfirmedCutoff)) {
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
            }
            userWords.deleteUnconfirmedBefore(unconfirmedCutoff)

            // And a ceiling, so the table cannot outgrow what the engine will ever read from it.
            // [MAX_WORDS_IN_MEMORY] limits the query that loads the model, not the store behind
            // it, so without this the rows past that limit are kept for the life of the install
            // while being permanently invisible -- cost with no benefit. Keeping exactly what is
            // loadable is what makes the two numbers one decision instead of two.
            for (word in userWords.wordsBeyond(MAX_WORDS_IN_MEMORY)) {
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
                userWords.delete(word)
            }
        }
    }

    /**
     * Forgets a word, and every phrase it was part of.
     *
     * The pairs go with it. Keeping them would leave the word being predicted through a phrase
     * after the user deleted it from their dictionary, which is the setting appearing not to
     * work in the most alarming possible way.
     */
    suspend fun findIgnoreCase(word: String): UserWord? = userWords.findIgnoreCase(word)

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

    suspend fun forgetEverything() {
        database.withTransaction {
            userWords.deleteAll()
            userBigrams.deleteAll()
            userTrigrams.deleteAll()
        }
        edited()
    }

    /**
     * Refuses a word permanently and removes whatever was learned about it.
     *
     * Both halves matter. Blocking without deleting leaves a count the user asked to be rid of;
     * deleting without blocking means the word comes back from the language pack the next time
     * it is typed, which reads as the setting not having worked.
     */
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

    /**
     * The personal dictionary as CSV.
     *
     * The only form of "sync" an application with no network can offer, and it is entirely the
     * user's: they export a file, they move it, they carry it, they import it. Nothing leaves
     * the device unless a person carries it. The format lives in [DictionaryCsv], where it can
     * be tested without a database.
     */
    suspend fun exportCsv(): CsvExport {
        val words = userWords.topWords(Int.MAX_VALUE)
        return CsvExport(DictionaryCsv.encode(words), words.size)
    }

    /** The CSV text and how many words it carries -- the count a screen reports is this one,
     *  not the length of whichever (searched, capped) list happened to be showing. */
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

        /**
         * Matches UserModel::kMaxBigrams on the native side, which is where they end up.
         * Reading more rows than that would be reading them to discard them.
         */
        const val MAX_BIGRAMS_IN_MEMORY = 4_096

        /** Matches UserModel::kMaxTrigrams. */
        const val MAX_TRIGRAMS_IN_MEMORY = 2_048

        /** How many pairs, and how many triples, the settings screen lists. */
        const val MAX_PHRASES_LISTED = 100
    }
}
