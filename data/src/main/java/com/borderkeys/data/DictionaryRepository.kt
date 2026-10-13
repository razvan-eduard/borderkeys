// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import androidx.room.withTransaction
import com.borderkeys.data.dao.BlockedWordDao
import com.borderkeys.data.dao.KeyTouchDao
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.dao.UserBigramDao
import com.borderkeys.data.dao.UserPhraseClockDao
import com.borderkeys.data.dao.UserTrigramDao
import com.borderkeys.data.dao.UserWordDao
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
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
    private val userPhraseClocks: UserPhraseClockDao,
) {
    private val phraseRecorder = PhraseRecorder(userBigrams, userTrigrams, userPhraseClocks)

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

    /**
     * The pairs the native engine loads at service start: each language's [MAX_BIGRAMS_IN_MEMORY]
     * most used, in the order each was first recorded.
     */
    suspend fun pairsToLoad(): List<UserBigram> = loadedPairs(userBigrams.all())

    /** [pairsToLoad], for the triples and [MAX_TRIGRAMS_IN_MEMORY]. */
    suspend fun triplesToLoad(): List<UserTrigram> = loadedTriples(userTrigrams.all())

    /** How many times each previous word of a pair has been seen, per language. */
    suspend fun pairContexts(): List<UserPairContext> = userBigrams.allContexts()

    /** How many times each two-word start of a triple has been seen, per language. */
    suspend fun tripleContexts(): List<UserTripleContext> = userTrigrams.allContexts()

    /** Each language's pair clock and triple clock. */
    suspend fun phraseClocks(): List<UserPhraseClock> = userPhraseClocks.all()

    /** Applies a batch of learning updates in one transaction. */
    suspend fun applyLearned(updates: List<LearnedWord>) {
        if (updates.isEmpty()) {
            return
        }
        userWords.incrementAll(updates)
    }

    /**
     * The same, for the pairs: the engine's steps replayed under [PhraseBudget]
     * ([PhraseRecorder.recordPairs]). Returns the pairs given up beyond the engine's.
     */
    suspend fun applyLearnedBigrams(steps: List<PairStep>): List<UserBigram> {
        if (steps.isEmpty()) {
            return emptyList()
        }
        return database.withTransaction { phraseRecorder.recordPairs(steps) }
    }

    /** [applyLearnedBigrams], for the triples. */
    suspend fun applyLearnedTrigrams(steps: List<TripleStep>): List<UserTrigram> {
        if (steps.isEmpty()) {
            return emptyList()
        }
        return database.withTransaction { phraseRecorder.recordTriples(steps) }
    }

    /**
     * Adds pairs and triples from a backup, with the contexts it carried
     * ([PhraseRecorder.importLinks]); the caller holds the transaction.
     */
    internal suspend fun importPhrases(
        pairs: List<LearnedBigram>,
        triples: List<LearnedTrigram>,
        pairContexts: List<UserPairContext>,
        tripleContexts: List<UserTripleContext>,
    ) {
        phraseRecorder.importLinks(pairs, triples, pairContexts, tripleContexts)
    }

    /**
     * Halves the stored count of every word, pair and triple, and the total of every context,
     * nobody has written in a [halfLifeMillis] or longer, then deletes the words written once and
     * unused for [unconfirmedLifeMillis] (none when it is null) and those past the [keep] used
     * most, with the phrases of those no case of which is left.
     */
    suspend fun decayStaleEntries(
        keep: Int,
        halfLifeMillis: Long,
        unconfirmedLifeMillis: Long?,
        now: Long = System.currentTimeMillis(),
    ) {
        val cutoff = now - halfLifeMillis
        database.withTransaction {
            userWords.decayStale(cutoff, now)
            userBigrams.decayStale(cutoff, now)
            userBigrams.decayStaleContexts(cutoff, now)
            userTrigrams.decayStale(cutoff, now)
            userTrigrams.decayStaleContexts(cutoff, now)
            if (unconfirmedLifeMillis != null) {
                val unconfirmedCutoff = now - unconfirmedLifeMillis
                val unconfirmed = userWords.unconfirmedBefore(unconfirmedCutoff)
                userWords.deleteUnconfirmedBefore(unconfirmedCutoff)
                forgetPhrasesOfGone(unconfirmed)
            }
            deleteWordsBeyond(keep)
        }
    }

    /**
     * Deletes the pairs and triples used at most [PhraseBudget.PURGE_MOST_USES] times that hold a
     * word [learnable] answers false for, in one transaction; [learnable] answers for each word of
     * a list, in order, and is never asked about the sentence start. Returns what it deleted.
     */
    suspend fun purgeUnlearnable(learnable: suspend (List<String>) -> List<Boolean>): PhraseRecorder.Dropped =
        database.withTransaction {
            val pairs = userBigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES)
            val triples = userTrigrams.usedAtMost(PhraseBudget.PURGE_MOST_USES)
            val words = (
                pairs.flatMap { listOf(it.previousWord, it.word) } +
                    triples.flatMap { listOf(it.previousWord2, it.previousWord1, it.word) }
                ).filter { it != UserBigram.SENTENCE_START }.distinct()
            if (words.isEmpty()) {
                return@withTransaction PhraseRecorder.Dropped()
            }
            val unlearnable = words.zip(learnable(words)).filterNot { it.second }.mapTo(HashSet()) { it.first }
            val gonePairs = pairs.filter { it.previousWord in unlearnable || it.word in unlearnable }
            val goneTriples = triples.filter {
                it.previousWord2 in unlearnable || it.previousWord1 in unlearnable || it.word in unlearnable
            }
            phraseRecorder.purge(gonePairs, goneTriples)
            PhraseRecorder.Dropped(gonePairs, goneTriples)
        }

    /** Deletes the words past the [keep] used most, with the phrases of those no case of which is left. */
    suspend fun keepWords(keep: Int) {
        database.withTransaction { deleteWordsBeyond(keep) }
        edited()
    }

    private suspend fun deleteWordsBeyond(keep: Int) {
        val beyond = userWords.wordsBeyond(keep)
        beyond.forEach { userWords.delete(it) }
        forgetPhrasesOfGone(beyond)
    }

    /** Forgets the phrases of each of [words] that no learned word is any longer, in any case. */
    private suspend fun forgetPhrasesOfGone(words: Collection<String>) {
        if (words.isEmpty()) {
            return
        }
        val left = userWords.allWords().mapTo(HashSet(), PhraseKey::word)
        for (key in words.mapTo(LinkedHashSet(), PhraseKey::word)) {
            if (key !in left) {
                phraseRecorder.forget(key)
            }
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

    /**
     * Forgets [words], and every phrase each is part of in any case and in every language, in one
     * transaction.
     */
    suspend fun forget(words: Collection<String>) {
        if (words.isEmpty()) {
            return
        }
        database.withTransaction {
            words.forEach { userWords.delete(it) }
            words.mapTo(LinkedHashSet(), PhraseKey::word).forEach { phraseRecorder.forget(it) }
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
                if (phrase.isPair) {
                    val (previousWord, word) = phrase.words
                    userBigrams.inContext(phrase.language, previousWord)
                        .firstOrNull { it.word == word }
                        ?.let { phraseRecorder.forgetPair(it) }
                } else {
                    val (previousWord2, previousWord1, word) = phrase.words
                    userTrigrams.inContext(phrase.language, previousWord2, previousWord1)
                        .firstOrNull { it.word == word }
                        ?.let { phraseRecorder.forgetTriple(it) }
                }
            }
        }
        edited()
    }

    /** Forgets every pair and triple, with their contexts and clocks. The words stay. */
    suspend fun forgetAllPhrases() {
        database.withTransaction { phraseRecorder.forgetAll() }
        edited()
    }

    /** Forgets every learned word, pair and triple, and the heatmap. */
    suspend fun forgetEverything() {
        database.withTransaction {
            userWords.deleteAll()
            phraseRecorder.forgetAll()
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
            phraseRecorder.forget(word)
        }
        edited()
    }

    suspend fun unblock(word: String) {
        blockedWords.delete(word)
        edited()
    }

    companion object {
        /** The pairs of one language the native engine holds; matches `UserModel::kMaxBigrams`. */
        const val MAX_BIGRAMS_IN_MEMORY = PhraseBudget.LANGUAGE_CAP

        /** [MAX_BIGRAMS_IN_MEMORY], for the triples; matches `UserModel::kMaxTrigrams`. */
        const val MAX_TRIGRAMS_IN_MEMORY = PhraseBudget.LANGUAGE_CAP

        /** Of [pairs], in their order, the ones [pairsToLoad] gives. */
        fun loadedPairs(pairs: List<UserBigram>): List<UserBigram> =
            mostUsedPerLanguage(pairs, MAX_BIGRAMS_IN_MEMORY, UserBigram::language, UserBigram::count)

        /** Of [triples], in their order, the ones [triplesToLoad] gives. */
        fun loadedTriples(triples: List<UserTrigram>): List<UserTrigram> =
            mostUsedPerLanguage(triples, MAX_TRIGRAMS_IN_MEMORY, UserTrigram::language, UserTrigram::count)

        /** Of [links], in their order, each language's [most] with the highest counts. */
        private fun <T> mostUsedPerLanguage(
            links: List<T>,
            most: Int,
            languageOf: (T) -> String,
            countOf: (T) -> Int,
        ): List<T> {
            val kept = links.groupBy(languageOf).values.flatMap { inLanguage ->
                if (inLanguage.size <= most) inLanguage else inLanguage.sortedByDescending(countOf).take(most)
            }.toHashSet()
            return links.filter { it in kept }
        }
    }
}

/** The entries of [stored] that are [word] in some case: "This", "this" and "THIS" are one word. */
internal fun casesOf(word: String, stored: Collection<String>): List<String> =
    stored.filter { it.equals(word, ignoreCase = true) }
