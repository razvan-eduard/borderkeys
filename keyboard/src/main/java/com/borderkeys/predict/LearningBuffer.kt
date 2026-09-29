// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord

/**
 * Holds committed words, pairs and triples in memory until they are flushed to the database,
 * when the buffer is old enough, full enough, or the input session ends.
 *
 * Not thread safe; written and drained on the main thread by BorderKeysService.
 */
class LearningBuffer(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val pending = LinkedHashMap<Key, Entry>()
    private val pendingPairs = LinkedHashMap<PairKey, Entry>()
    private val pendingTriples = LinkedHashMap<TripleKey, Entry>()
    private var oldestRecordedAt = 0L
    private var refusedWords: RefusedWords = RefusedWords.NONE

    /**
     * Whether anything is recorded. False for a password field or a field that asks for no
     * personalised learning.
     */
    var enabled: Boolean = true

    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    /** Words that are never learned. */
    fun setRefusedWords(words: RefusedWords) {
        refusedWords = words
    }

    private fun refused(word: String): Boolean = refusedWords.refuses(word)

    /** Records that [word] followed [previousWord]. Returns true if it was accepted. */
    fun recordPair(previousWord: String, word: String, nowMillis: Long): Boolean {
        if (!enabled || previousWord.isEmpty() || word.isEmpty()) {
            return false
        }
        if (previousWord.length > MAX_WORD_LENGTH || word.length > MAX_WORD_LENGTH) {
            return false
        }
        if (refused(previousWord) || refused(word) || previousWord == word) {
            return false
        }
        val key = PairKey(previousWord, word)
        val existing = pendingPairs[key]
        if (existing != null) {
            existing.delta++
            existing.lastUsedAt = nowMillis
            return true
        }
        if (pendingPairs.size >= maxEntries) {
            pendingPairs.remove(pendingPairs.keys.first())
        }
        pendingPairs[key] = Entry(delta = 1, lastUsedAt = nowMillis)
        return true
    }

    /** The same as [recordPair], one word further back. */
    fun recordTriple(
        previousWord2: String,
        previousWord1: String,
        word: String,
        nowMillis: Long,
    ): Boolean {
        if (!enabled || previousWord2.isEmpty() || previousWord1.isEmpty() || word.isEmpty()) {
            return false
        }
        if (previousWord2.length > MAX_WORD_LENGTH || previousWord1.length > MAX_WORD_LENGTH ||
            word.length > MAX_WORD_LENGTH
        ) {
            return false
        }
        if (refused(previousWord2) || refused(previousWord1) || refused(word)) {
            return false
        }
        if (previousWord1 == word) {
            return false
        }
        val key = TripleKey(previousWord2, previousWord1, word)
        val existing = pendingTriples[key]
        if (existing != null) {
            existing.delta++
            existing.lastUsedAt = nowMillis
            return true
        }
        if (pendingTriples.size >= maxEntries) {
            pendingTriples.remove(pendingTriples.keys.first())
        }
        pendingTriples[key] = Entry(delta = 1, lastUsedAt = nowMillis)
        return true
    }

    /**
     * Records one committed word. Returns true if it was accepted. [deliberateCapital] and
     * [asserted] are kept for the word once either is seen.
     */
    fun record(
        word: String,
        locale: String,
        nowMillis: Long,
        deliberateCapital: Boolean = false,
        asserted: Boolean = false,
    ): Boolean {
        if (!enabled || word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) {
            return false
        }
        if (refused(word)) {
            return false
        }
        if (pending.isEmpty()) {
            oldestRecordedAt = nowMillis
        }
        val key = Key(word, locale)
        val existing = pending[key]
        if (existing != null) {
            existing.delta++
            existing.lastUsedAt = nowMillis
            existing.deliberateCapital = existing.deliberateCapital || deliberateCapital
            existing.asserted = existing.asserted || asserted
            return true
        }
        if (pending.size >= maxEntries) {
            val oldest = pending.keys.first()
            pending.remove(oldest)
        }
        pending[key] = Entry(
            delta = 1, lastUsedAt = nowMillis, deliberateCapital = deliberateCapital,
            asserted = asserted,
        )
        return true
    }

    /** True when the buffer should be written out. */
    fun isDue(nowMillis: Long): Boolean {
        if (pending.isEmpty()) {
            return false
        }
        return pending.size >= maxEntries || nowMillis - oldestRecordedAt >= debounceMillis
    }

    /** Empties the buffer and returns what it held. */
    fun drain(): List<LearnedWord> {
        if (pending.isEmpty()) {
            return emptyList()
        }
        val drained = ArrayList<LearnedWord>(pending.size)
        for ((key, entry) in pending) {
            drained += LearnedWord(
                word = key.word,
                locale = key.locale,
                delta = entry.delta,
                lastUsedAt = entry.lastUsedAt,
                deliberateCapital = entry.deliberateCapital,
                asserted = entry.asserted,
            )
        }
        pending.clear()
        oldestRecordedAt = 0L
        return drained
    }

    /** Empties the pair buffer and returns what it held. */
    fun drainPairs(): List<LearnedBigram> {
        if (pendingPairs.isEmpty()) {
            return emptyList()
        }
        val drained = ArrayList<LearnedBigram>(pendingPairs.size)
        for ((key, entry) in pendingPairs) {
            drained += LearnedBigram(
                previousWord = key.previousWord,
                word = key.word,
                delta = entry.delta,
                lastUsedAt = entry.lastUsedAt,
            )
        }
        pendingPairs.clear()
        return drained
    }

    /** Empties the triple buffer and returns what it held. */
    fun drainTriples(): List<LearnedTrigram> {
        if (pendingTriples.isEmpty()) {
            return emptyList()
        }
        val drained = ArrayList<LearnedTrigram>(pendingTriples.size)
        for ((key, entry) in pendingTriples) {
            drained += LearnedTrigram(
                previousWord2 = key.previousWord2,
                previousWord1 = key.previousWord1,
                word = key.word,
                delta = entry.delta,
                lastUsedAt = entry.lastUsedAt,
            )
        }
        pendingTriples.clear()
        return drained
    }

    /** Discards everything without writing it. */
    fun discard() {
        pending.clear()
        pendingPairs.clear()
        pendingTriples.clear()
        oldestRecordedAt = 0L
    }

    private data class Key(val word: String, val locale: String)

    private data class PairKey(val previousWord: String, val word: String)

    private data class TripleKey(
        val previousWord2: String,
        val previousWord1: String,
        val word: String,
    )

    private class Entry(
        var delta: Int,
        var lastUsedAt: Long,
        var deliberateCapital: Boolean = false,
        var asserted: Boolean = false,
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 4_000L
        const val DEFAULT_MAX_ENTRIES = 64

        /** The longest word recorded. */
        const val MAX_WORD_LENGTH = 64

        /** The shortest word recorded. */
        const val MIN_WORD_LENGTH = 2
    }
}
