// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.RefusedWords

/**
 * What is learned from typing: each confirmed word, with the pair and the triple it makes,
 * buffered and written to the [store]. Where the field's policy allows nothing personal, nothing
 * is learned and the personal dictionary is not consulted.
 */
class LearningFlow(
    private val engine: EnginePort,
    private val host: TypingHost,
    private val store: LearningStore,
    private val clock: TypingClock,
) : TypingFlow() {

    /** What is learned, until it is written to the [store]. */
    private val learning = LearningBuffer()

    private val flushRunnable = Runnable { flush() }

    override fun onFieldStarted(field: FieldSession) {
        applyGate()
        if (field.policy.privateField) {
            learning.discard()
        }
    }

    /** What was learned in the field is written. */
    override fun onFieldFinished() = flush()

    /** With the views up, the gate follows the settings at once; otherwise at the next field. */
    override fun onSettingsChanged(settings: KeyboardPreferences) {
        if (host.viewAttached) {
            applyGate()
        }
    }

    /** Writes what is left and waits, a bounded time, for the writes in flight. */
    override fun onShutdown() = store.persistBeforeShutdown(drain())

    /** Sets the words never learned. */
    fun setRefusedWords(words: RefusedWords) {
        learning.setRefusedWords(words)
    }

    /** Learns nothing until the gate is next set, at a field start or a settings change. */
    fun stop() {
        learning.enabled = false
    }

    /**
     * Records a confirmed word, and the pair and triple it makes with [contextWord] and
     * [grandContextWord], read before the commit. [deliberateCapital] is whether its capital was
     * typed with shift; [asserted] whether the user chose it on purpose.
     */
    fun record(
        word: String,
        contextWord: String?,
        grandContextWord: String?,
        deliberateCapital: Boolean = false,
        asserted: Boolean = false,
    ) {
        if (!learning.enabled || word.length < MIN_LEARNED_LENGTH) {
            return
        }
        // The input-method subtype's tag, recorded with the word.
        val locale = host.subtypeTag()
        val now = clock.currentTimeMillis()
        // A word with nothing before it is paired with the sentence start.
        val pairContext = contextWord ?: UserBigram.SENTENCE_START
        learning.recordPair(pairContext, word, now)
        if (contextWord != null && grandContextWord != null) {
            learning.recordTriple(grandContextWord, contextWord, word, now)
        }
        if (learning.record(word, locale, now, deliberateCapital, asserted)) {
            host.playEffect(EffectEvent.LearnedWord, word)
            engine.learn(
                listOf(LearnedWord(word, locale, 1, now, deliberateCapital, asserted)),
                pairContext, grandContextWord,
            )
        }
        if (!host.viewAttached) {
            return
        }
        host.removeCallbacks(flushRunnable)
        if (learning.isDue(clock.currentTimeMillis())) {
            flush()
        } else {
            host.postDelayed(flushRunnable, LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        }
    }

    /**
     * Whether anything is learned, whether the personal dictionary is consulted, and whether the
     * learned touch patterns count: off with their switches or in a private field.
     */
    private fun applyGate() {
        learning.enabled = session.policy.personalAllowed
        engine.setPersonalModelEnabled(learning.enabled)
        engine.setTouchModel(
            session.policy.heatmapAllowed, settings.heatmapWeight, settings.heatmapMinTaps,
        )
    }

    /** Writes the buffered learning to the [store]. */
    private fun flush() {
        val batch = drain() ?: return
        store.persist(batch)
    }

    /** Empties [learning], or returns null when there was nothing in it. */
    private fun drain(): LearningBatch? {
        val updates = learning.drain()
        val pairs = learning.drainPairs()
        val triples = learning.drainTriples()
        if (updates.isEmpty() && pairs.isEmpty() && triples.isEmpty()) {
            return null
        }
        return LearningBatch(updates, pairs, triples)
    }

    private companion object {
        const val MIN_LEARNED_LENGTH = 2
    }
}
