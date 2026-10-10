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
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
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
    val wordCount: Flow<Int> = userWords.observeCount()
    val blocked: Flow<List<BlockedWord>> = blockedWords.observeAll()

    /** Every learned pair and triple, most used first. */
    val phrases: Flow<List<UserPhrase>> =
        combine(userBigrams.observeAll(), userTrigrams.observeAll()) { pairs, triples ->
            UserPhrase.merged(pairs, triples)
        }

    val pairCount: Flow<Int> = userBigrams.observeCount()
    val tripleCount: Flow<Int> = userTrigrams.observeCount()

    /** The heatmap's totals in every bucket, as stored. */
    val touches: Flow<List<KeyTouch>> = keyTouches.observeAll()

    /** How many taps the heatmap rests on, each weighing less as it ages, rounded. */
    val touchTaps: Flow<Int> = keyTouches.observeTaps().map { it.roundToInt() }

    /**
     * Fires after an edit made by hand -- a word or a phrase forgotten, a word blocked or
     * unblocked, everything forgotten, a backup restored -- so the keyboard can reload what it
     * holds in memory. The learning flush is not an edit and does not fire it.
     */
    val edits: SharedFlow<Unit> get() = editsFlow
    private val editsFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal fun edited() {
        editsFlow.tryEmit(Unit)
    }

    fun search(query: String): Flow<List<UserWord>> = userWords.observeMatching(query)

    /** The [limit] words used most, the set pushed into the native engine. */
    suspend fun topWords(limit: Int): List<UserWord> = userWords.topWords(limit)

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
     * [halfLifeMillis] or longer, then deletes the words written once before
     * [PersonalWordDecay.unconfirmedLifeMillis] and those past the [keep] used most, with their
     * phrases.
     */
    suspend fun decayStaleEntries(keep: Int, halfLifeMillis: Long, now: Long = System.currentTimeMillis()) {
        val cutoff = now - halfLifeMillis
        val unconfirmedCutoff = now - PersonalWordDecay.unconfirmedLifeMillis(halfLifeMillis)
        database.withTransaction {
            userWords.decayStale(cutoff, now)
            userBigrams.decayStale(cutoff, now)
            userTrigrams.decayStale(cutoff, now)
            for (word in userWords.unconfirmedBefore(unconfirmedCutoff)) {
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
            }
            userWords.deleteUnconfirmedBefore(unconfirmedCutoff)
            deleteWordsBeyond(keep)
        }
    }

    /** Deletes the words past the [keep] used most, with every phrase they are part of. */
    suspend fun keepWords(keep: Int) {
        database.withTransaction { deleteWordsBeyond(keep) }
        edited()
    }

    private suspend fun deleteWordsBeyond(keep: Int) {
        for (word in userWords.wordsBeyond(keep)) {
            userBigrams.deleteInvolving(word)
            userTrigrams.deleteInvolving(word)
            userWords.delete(word)
        }
    }

    /** Forgets a word and every phrase it was part of. */
    suspend fun forget(word: String) = forget(listOf(word))

    /**
     * Forgets [word] in every case it was learned in, each with its phrases. Returns whether the
     * personal dictionary held any of them.
     */
    suspend fun forgetEveryCase(word: String): Boolean {
        val cases = casesOf(word, userWords.allWords())
        forget(cases)
        return cases.isNotEmpty()
    }

    /** Forgets [words] and every phrase any of them was part of, in one transaction. */
    suspend fun forget(words: Collection<String>) {
        if (words.isEmpty()) {
            return
        }
        database.withTransaction {
            for (word in words) {
                userWords.delete(word)
                userBigrams.deleteInvolving(word)
                userTrigrams.deleteInvolving(word)
            }
        }
        edited()
    }

    /**
     * Forgets [phrase]: a pair with every triple that runs through it, or one triple, whose pairs
     * stay. The words stay.
     */
    suspend fun forgetPhrase(phrase: UserPhrase) = forgetPhrases(listOf(phrase))

    /** Forgets [phrases] as [forgetPhrase] does, in one transaction. */
    suspend fun forgetPhrases(phrases: Collection<UserPhrase>) {
        if (phrases.isEmpty()) {
            return
        }
        database.withTransaction {
            for (phrase in phrases) {
                val words = phrase.words
                if (words.size == 2) {
                    userBigrams.delete(words[0], words[1])
                    userTrigrams.deleteContainingPair(words[0], words[1])
                } else {
                    userTrigrams.delete(words[0], words[1], words[2])
                }
            }
        }
        edited()
    }

    /** Forgets every pair and triple. The words stay. */
    suspend fun forgetAllPhrases() {
        database.withTransaction {
            userBigrams.deleteAll()
            userTrigrams.deleteAll()
        }
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

    /** The heatmap's totals for [bucket], as stored. */
    suspend fun touchesIn(bucket: String): List<KeyTouch> = keyTouches.inBucket(bucket)

    /**
     * Adds [touches], totals of taps since the last write, to what is stored, the stored totals
     * first weighed down by their age.
     */
    suspend fun applyTouches(touches: List<KeyTouch>, halfLifeMillis: Long) {
        if (touches.isEmpty()) {
            return
        }
        database.withTransaction {
            for (touch in touches) {
                keyTouches.upsert(
                    KeyTouches.merged(keyTouches.find(touch.bucket, touch.code), touch, halfLifeMillis),
                )
            }
        }
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

    private companion object {
        /** Matches UserModel::kMaxBigrams. */
        const val MAX_BIGRAMS_IN_MEMORY = 4_096

        /** Matches UserModel::kMaxTrigrams. */
        const val MAX_TRIGRAMS_IN_MEMORY = 2_048
    }
}

/** The entries of [stored] that are [word] in some case: "This", "this" and "THIS" are one word. */
internal fun casesOf(word: String, stored: Collection<String>): List<String> =
    stored.filter { it.equals(word, ignoreCase = true) }
