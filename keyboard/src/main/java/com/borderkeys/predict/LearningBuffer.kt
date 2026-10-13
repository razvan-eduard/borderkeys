// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseKey
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep

/**
 * Holds committed words, and the pair and triple uses the engine recorded with the links it dropped
 * right after each, in order, in memory until they are flushed to the database, when the buffer is
 * old enough, full enough, or the input session ends.
 *
 * Not thread safe; written and drained on the main thread by BorderKeysService.
 */
class LearningBuffer(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val pending = LinkedHashMap<Key, Entry>()
    private val pendingPairs = ArrayDeque<PairStep>()
    private val pendingTriples = ArrayDeque<TripleStep>()
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

    /**
     * Whether the buffer takes a pair of [word] after [previousWord], the two as [PhraseKey.word]
     * keys them: each at most [MAX_WORD_LENGTH] long, neither empty nor refused, and the two
     * different.
     */
    fun acceptsPair(previousWord: String, word: String): Boolean {
        if (!enabled || previousWord.isEmpty() || previousWord.length > MAX_WORD_LENGTH || word.isEmpty() ||
            word.length > MAX_WORD_LENGTH
        ) {
            return false
        }
        return !refused(previousWord) && !refused(word) && PhraseKey.word(previousWord) != PhraseKey.word(word)
    }

    /** The same as [acceptsPair], one word further back. */
    fun acceptsTriple(previousWord2: String, previousWord1: String, word: String): Boolean {
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
        return PhraseKey.word(previousWord1) != PhraseKey.word(word)
    }

    /**
     * Records the pair and triple uses the engine recorded, each with the links it dropped right
     * after, in order. Each table holds at most [maxEntries] uses: past it the oldest use goes, and
     * the links the engine dropped up to it stay, in one step with no use at the front.
     */
    fun recordSteps(pairs: List<PairStep>, triples: List<TripleStep>, nowMillis: Long) {
        if (!enabled || (pairs.isEmpty() && triples.isEmpty())) {
            return
        }
        startClock(nowMillis)
        append(pendingPairs, pairs, PairStep::use, PairStep::dropped) { PairStep(null, it) }
        append(pendingTriples, triples, TripleStep::use, TripleStep::dropped) { TripleStep(null, it) }
    }

    /**
     * Adds [steps] to [table] as [recordSteps] says: [useOf] and [dropsOf] read a step, and
     * [dropsOnly] makes the step with no use.
     */
    private fun <T, D> append(
        table: ArrayDeque<T>,
        steps: List<T>,
        useOf: (T) -> Any?,
        dropsOf: (T) -> List<D>,
        dropsOnly: (List<D>) -> T,
    ) {
        for (step in steps) {
            if (table.count { useOf(it) != null } >= maxEntries) {
                val oldest = table.indexOfFirst { useOf(it) != null }
                val drops = (0..oldest).flatMap { dropsOf(table.removeFirst()) }
                if (drops.isNotEmpty()) {
                    table.addFirst(dropsOnly(drops))
                }
            }
            table.addLast(step)
        }
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
        if (!enabled || !learnable(word)) {
            return false
        }
        if (refused(word)) {
            return false
        }
        startClock(nowMillis)
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

    /**
     * True when the buffer should be written out: a table holds [maxEntries] words, or [maxEntries]
     * pair or triple uses, or its oldest entry has waited [debounceMillis].
     */
    fun isDue(nowMillis: Long): Boolean {
        if (isBlank()) {
            return false
        }
        val full = pending.size >= maxEntries || pendingPairs.count { it.use != null } >= maxEntries ||
            pendingTriples.count { it.use != null } >= maxEntries
        return full || nowMillis - oldestRecordedAt >= debounceMillis
    }

    /** Whether no word, pair or triple is waiting. */
    private fun isBlank(): Boolean =
        pending.isEmpty() && pendingPairs.isEmpty() && pendingTriples.isEmpty()

    /** Starts the debounce clock at [nowMillis] when nothing is waiting yet. */
    private fun startClock(nowMillis: Long) {
        if (isBlank()) {
            oldestRecordedAt = nowMillis
        }
    }

    /** Stops the debounce clock once nothing is waiting. */
    private fun stopClockIfBlank() {
        if (isBlank()) {
            oldestRecordedAt = 0L
        }
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
        stopClockIfBlank()
        return drained
    }

    /** Empties the pair buffer and returns what it held, in order. */
    fun drainPairs(): List<PairStep> {
        val drained = pendingPairs.toList()
        pendingPairs.clear()
        stopClockIfBlank()
        return drained
    }

    /** Empties the triple buffer and returns what it held, in order. */
    fun drainTriples(): List<TripleStep> {
        val drained = pendingTriples.toList()
        pendingTriples.clear()
        stopClockIfBlank()
        return drained
    }

    /**
     * Drops [word], in any case, with every pair and triple use it is part of; the links the
     * engine dropped stay to be written. Returns whether the word itself was waiting to be written.
     */
    fun forget(word: String): Boolean {
        fun matches(other: String) = other.equals(word, ignoreCase = true)
        val waiting = pending.keys.removeAll { matches(it.word) }
        replaceSteps(pendingPairs) { step ->
            val use = step.use
            if (use == null || !(matches(use.previousWord) || matches(use.word))) {
                step
            } else {
                step.copy(use = null).takeIf { it.dropped.isNotEmpty() }
            }
        }
        replaceSteps(pendingTriples) { step ->
            val use = step.use
            if (use == null || !(matches(use.previousWord2) || matches(use.previousWord1) || matches(use.word))) {
                step
            } else {
                step.copy(use = null).takeIf { it.dropped.isNotEmpty() }
            }
        }
        stopClockIfBlank()
        return waiting
    }

    /** Replaces each step of [table] with what [replace] gives for it, dropping those it gives none for. */
    private fun <T> replaceSteps(table: ArrayDeque<T>, replace: (T) -> T?) {
        val kept = table.mapNotNull(replace)
        table.clear()
        table.addAll(kept)
    }

    /** Discards everything without writing it. */
    fun discard() {
        pending.clear()
        pendingPairs.clear()
        pendingTriples.clear()
        oldestRecordedAt = 0L
    }

    private data class Key(val word: String, val locale: String)

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

        /** Whether [word] is one the buffer records: [MIN_WORD_LENGTH] to [MAX_WORD_LENGTH] long. */
        fun learnable(word: String): Boolean = word.length in MIN_WORD_LENGTH..MAX_WORD_LENGTH
    }
}
