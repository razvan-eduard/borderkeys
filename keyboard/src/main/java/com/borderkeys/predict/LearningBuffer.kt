// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord

/**
 * Accumulates what the user confirms, so that the database is written on a debounce rather than
 * on a keystroke.
 *
 * A key press has two milliseconds to reach `InputConnection`. An INSERT is a transaction, a
 * disk write and an encryption pass, and putting one on that path would blow the budget on every
 * word. So confirmations land here, in memory, and are flushed to Room and to the native
 * snapshot when the buffer is old enough, full enough, or the input session ends.
 *
 * Deliberately free of Android and of coroutines: it is a counter with a clock passed in, which
 * makes the debounce and the eviction testable on the JVM instead of on a device.
 *
 * Not thread safe. It is touched from the prediction thread and drained from the same one.
 */
class LearningBuffer(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val pending = LinkedHashMap<Key, Entry>()
    private val pendingPairs = LinkedHashMap<PairKey, Entry>()
    private val pendingTriples = LinkedHashMap<TripleKey, Entry>()
    private var oldestRecordedAt = 0L
    private var blocked: Set<String> = emptySet()

    /**
     * Whether anything is recorded at all.
     *
     * Set to false for a password field or when the editor asks for no personalised learning.
     * The service also refuses to call [record] there, so this is the second of two independent
     * checks -- which is the right number for a rule whose failure mode is a password ending up
     * in the personal dictionary.
     */
    var enabled: Boolean = true

    val size: Int get() = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    /** Words the user has refused. Never learned, however often they are typed. */
    fun setBlockedWords(words: Set<String>) {
        blocked = words
    }

    /**
     * Records one confirmed word. Returns true if it was accepted.
     *
     * A word is confirmed when the user picks it from the suggestion strip, or commits it by
     * typing a delimiter after it. Nothing is learned from what is merely on screen.
     */
    /**
     * Records that [word] followed [previousWord], if both are things worth remembering.
     *
     * Kept apart from [record] because the two fail independently: the word is always worth
     * learning, the pair only when the word before it is one this dictionary would also hold.
     * A pair whose halves are not both learnable would name a word the native model cannot
     * resolve, and would be dropped at the next start anyway.
     */
    fun recordPair(previousWord: String, word: String, nowMillis: Long): Boolean {
        if (!enabled || previousWord.isEmpty() || word.isEmpty()) {
            return false
        }
        if (previousWord.length > MAX_WORD_LENGTH || word.length > MAX_WORD_LENGTH) {
            return false
        }
        if (previousWord in blocked || word in blocked || previousWord == word) {
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
        if (previousWord2 in blocked || previousWord1 in blocked || word in blocked) {
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

    fun record(word: String, locale: String, nowMillis: Long): Boolean {
        if (!enabled || word.isEmpty() || word.length > MAX_WORD_LENGTH) {
            return false
        }
        if (word in blocked) {
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
            return true
        }
        if (pending.size >= maxEntries) {
            // Full before the debounce elapsed, which means the user is typing fast. Drop the
            // least recently added rather than growing: the flush is about to happen anyway, and
            // an unbounded buffer in the IME process is a memory leak with a nice name.
            val oldest = pending.keys.first()
            pending.remove(oldest)
        }
        pending[key] = Entry(delta = 1, lastUsedAt = nowMillis)
        return true
    }

    /** True when the buffer should be written out. */
    fun isDue(nowMillis: Long): Boolean {
        if (pending.isEmpty()) {
            // The pairs ride along with the words. A pair is only ever recorded next to a word,
            // so an empty word buffer means there is nothing to write either.
            return false
        }
        return pending.size >= maxEntries || nowMillis - oldestRecordedAt >= debounceMillis
    }

    /**
     * Empties the buffer and returns what it held.
     *
     * Returns an empty list rather than null when there is nothing, so the caller has one path.
     */
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
            )
        }
        pending.clear()
        oldestRecordedAt = 0L
        return drained
    }

    /** Empties the pair buffer and returns what it held. Drained in the same flush as the words. */
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

    /** Empties the triple buffer. Drained in the same flush as the words and the pairs. */
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

    /** Discards everything without writing it. Used when entering private mode mid-session. */
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

    private class Entry(var delta: Int, var lastUsedAt: Long)

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 4_000L
        const val DEFAULT_MAX_ENTRIES = 64
        const val MAX_WORD_LENGTH = 64
    }
}
