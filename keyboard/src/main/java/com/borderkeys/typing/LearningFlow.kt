// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.RefusedWords

/**
 * What is learned from typing: each confirmed word, with the pair and the triple it makes, and
 * where its letters were tapped, buffered and written to the [store]. Where the field's policy
 * allows nothing personal, nothing is learned and the personal dictionary is not consulted; taps
 * are learned only where it allows the heatmap.
 */
class LearningFlow(
    private val engine: EnginePort,
    private val host: TypingHost,
    private val store: LearningStore,
    private val clock: TypingClock,
) : TypingFlow() {

    /** What is learned, until it is written to the [store]. */
    private val learning = LearningBuffer()

    /** The heatmap: the current bucket's stored totals and the samples not yet written. */
    private val touches = TouchLearning()

    private val flushRunnable = Runnable { flush() }

    /** The letter keys taps are lined up against; a new bucket loads its stored totals. */
    var geometry: KeyGeometrySnapshot? = null
        set(value) {
            val bucketChanged = value?.bucket?.key != field?.bucket?.key
            field = value
            if (bucketChanged) {
                reloadTouches()
            }
        }

    private val halfLifeMillis: Long
        get() = KeyTouches.halfLifeMillis(settings.heatmapHalfLifeDays)

    override fun onFieldStarted(field: FieldSession) {
        applyGate()
        if (field.policy.privateField) {
            learning.discard()
        }
    }

    /** What was learned in the field is written. */
    override fun onFieldFinished() = flush()

    /**
     * Learning switched off drops the words and taps not yet written, and the Heatmap switched off
     * drops the taps, at once. With the views up, the gate follows the settings at once too;
     * otherwise at the next field.
     */
    override fun onSettingsChanged(settings: KeyboardPreferences) {
        if (!settings.learningEnabled) {
            learning.discard()
        }
        if ((!settings.learningEnabled || !settings.heatmapEnabled) && !touches.isBlank) {
            touches.discard()
            pushTouches()
        }
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
     * typed with shift; [asserted] whether the user chose it on purpose. [taps] is the word as it
     * was typed, with where each letter was tapped, lined up with [word], or with its first
     * letters when [completion].
     */
    fun record(
        word: String,
        contextWord: String?,
        grandContextWord: String?,
        deliberateCapital: Boolean = false,
        asserted: Boolean = false,
        taps: TypedTaps? = null,
        completion: Boolean = false,
    ) {
        val touched = taps != null && recordTouches(taps, word, completion)
        val learned = learning.enabled && word.length >= MIN_LEARNED_LENGTH
        if (learned) {
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
        }
        if (!(learned || touched) || !host.viewAttached) {
            return
        }
        host.removeCallbacks(flushRunnable)
        if (learning.isDue(clock.currentTimeMillis())) {
            flush()
        } else {
            host.postDelayed(flushRunnable, LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        }
    }

    /** Reads the current bucket's stored totals again, as after an edit on the settings screen. */
    fun reloadTouches() {
        val bucket = geometry?.bucket?.key ?: return
        store.loadTouches(bucket) { rows ->
            if (geometry?.bucket?.key == bucket) {
                touches.load(bucket, rows, clock.currentTimeMillis(), halfLifeMillis)
                pushTouches()
            }
        }
    }

    /**
     * Lines [taps] up with [kept] and adds the samples, where the field allows the heatmap.
     * Returns whether any were added.
     */
    private fun recordTouches(taps: TypedTaps, kept: String, completion: Boolean): Boolean {
        val geometry = geometry ?: return false
        if (!session.policy.heatmapAllowed) {
            return false
        }
        val samples = TapAlignment.samples(taps, kept, completion, geometry)
        if (samples.isEmpty()) {
            return false
        }
        touches.add(samples, geometry, clock.currentTimeMillis())
        pushTouches()
        return true
    }

    /** Hands the engine the current bucket's patterns. */
    private fun pushTouches() {
        engine.setTouchPatterns(touches.patterns(halfLifeMillis))
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

    /** Empties [learning] and [touches], or returns null when there was nothing in them. */
    private fun drain(): LearningBatch? {
        val updates = learning.drain()
        val pairs = learning.drainPairs()
        val triples = learning.drainTriples()
        val taps = touches.drain(halfLifeMillis)
        if (updates.isEmpty() && pairs.isEmpty() && triples.isEmpty() && taps.isEmpty()) {
            return null
        }
        return LearningBatch(updates, pairs, triples, taps, halfLifeMillis)
    }

    private companion object {
        const val MIN_LEARNED_LENGTH = 2
    }
}
