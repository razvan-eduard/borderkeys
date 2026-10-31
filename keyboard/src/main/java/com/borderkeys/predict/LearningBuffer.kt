// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.data.PhraseKey
import com.borderkeys.data.WordShape
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.LinkWrites

/**
 * Holds committed words, and the link rows the commits changed ([LinkWrites]), in memory until
 * they are flushed to the database, when the buffer is old enough, full enough, or the input
 * session ends.
 *
 * Not thread safe; written and drained on the main thread by BorderKeysService.
 */
class LearningBuffer(
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val pending = LinkedHashMap<Key, Entry>()
    private var pendingLinks = LinkWrites()
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
     * keys them: each at most [WordShape.MAX_LENGTH] long, neither empty nor refused, and the two
     * different.
     */
    fun acceptsPair(previousWord: String, word: String): Boolean {
        if (!enabled || previousWord.isEmpty() || previousWord.length > WordShape.MAX_LENGTH || word.isEmpty() ||
            word.length > WordShape.MAX_LENGTH
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
        if (previousWord2.length > WordShape.MAX_LENGTH || previousWord1.length > WordShape.MAX_LENGTH ||
            word.length > WordShape.MAX_LENGTH
        ) {
            return false
        }
        if (refused(previousWord2) || refused(previousWord1) || refused(word)) {
            return false
        }
        return PhraseKey.word(previousWord1) != PhraseKey.word(word)
    }

    /**
     * Records the link rows a commit or an edit changed, each replacing the one of its key the
     * buffer holds ([LinkWrites.then]), whether or not the buffer records anything new: a commit's
     * rows come from a word recorded before.
     */
    fun recordLinks(writes: LinkWrites, nowMillis: Long) {
        if (writes.isEmpty) {
            return
        }
        startClock(nowMillis)
        pendingLinks = pendingLinks.then(writes)
    }

    /**
     * Records one committed word in [language]. Returns true if it was accepted. [deliberateCapital]
     * is kept for the word once seen; [asserted], a choice, adds one to the word's choices.
     */
    fun record(
        word: String,
        language: String,
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
        val key = Key(word, language)
        val existing = pending[key]
        if (existing != null) {
            existing.delta++
            existing.lastUsedAt = nowMillis
            existing.deliberateCapital = existing.deliberateCapital || deliberateCapital
            existing.asserted += if (asserted) 1 else 0
            return true
        }
        if (pending.size >= maxEntries) {
            val oldest = pending.keys.first()
            pending.remove(oldest)
        }
        pending[key] = Entry(
            delta = 1, lastUsedAt = nowMillis, deliberateCapital = deliberateCapital,
            asserted = if (asserted) 1 else 0,
        )
        return true
    }

    /**
     * True when the buffer should be written out: it holds [maxEntries] words, or [maxEntries]
     * links to write or give up, or its oldest entry has waited [debounceMillis].
     */
    fun isDue(nowMillis: Long): Boolean {
        if (isBlank()) {
            return false
        }
        val full = pending.size >= maxEntries || pendingLinks.links >= maxEntries
        return full || nowMillis - oldestRecordedAt >= debounceMillis
    }

    /** Whether no word or link row is waiting. */
    private fun isBlank(): Boolean = pending.isEmpty() && pendingLinks.isEmpty

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
                language = key.language,
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

    /** Empties the link rows and returns what they held. */
    fun drainLinks(): LinkWrites {
        val drained = pendingLinks
        pendingLinks = LinkWrites()
        stopClockIfBlank()
        return drained
    }

    /**
     * Drops [word], in any case, with the link rows it takes part in and the contexts they follow
     * ([LinkWrites.without]); the links given up stay to be written. Returns whether the word
     * itself was waiting to be written.
     */
    fun forget(word: String): Boolean {
        val waiting = pending.keys.removeAll { it.word.equals(word, ignoreCase = true) }
        pendingLinks = pendingLinks.without(word)
        stopClockIfBlank()
        return waiting
    }

    /** Discards everything without writing it; returns whether anything was waiting. */
    fun discard(): Boolean {
        val waiting = !isBlank()
        pending.clear()
        pendingLinks = LinkWrites()
        oldestRecordedAt = 0L
        return waiting
    }

    private data class Key(val word: String, val language: String)

    private class Entry(
        var delta: Int,
        var lastUsedAt: Long,
        var deliberateCapital: Boolean = false,
        var asserted: Int = 0,
    )

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 4_000L
        const val DEFAULT_MAX_ENTRIES = 64

        /** Whether [word] is one the buffer records: of a word's length ([WordShape.isWordLength]). */
        fun learnable(word: String): Boolean = WordShape.isWordLength(word)
    }
}
