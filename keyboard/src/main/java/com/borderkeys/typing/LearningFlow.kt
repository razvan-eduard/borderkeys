// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.language.LanguageTags
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
        answerLanguage = null
        learnedIn = null
        wordsSinceChange = null
        applyGate()
        if (field.policy.privateField) {
            learning.discard()
        }
    }

    /** Pairs and triples go under the subtype's language until an answer names one. */
    override fun onSubtypeChanged() {
        answerLanguage = null
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

    /** Switched off by hand mid-field: what was gathered in it is dropped, and the gate follows. */
    override fun onPolicyChanged() {
        if (session.policy.privateField) {
            learning.discard()
        }
        if (host.viewAttached) {
            applyGate()
        }
    }

    /** The gate opens for what the unlock allows; the bucket's stored totals are read now. */
    override fun onUserUnlocked() {
        if (host.viewAttached) {
            applyGate()
        }
        reloadTouches()
    }

    /** Writes what is left and waits, a bounded time, for the writes in flight. */
    override fun onShutdown() = store.persistBeforeShutdown(drain())

    /** Sets the words never learned. */
    fun setRefusedWords(words: RefusedWords) {
        learning.setRefusedWords(words)
    }

    /**
     * The primary language subtag of the last answer in this field and subtype; pairs and triples
     * are learned under it.
     */
    private var answerLanguage: String? = null

    /**
     * The language the last word in this field was learned under, and how many words have been
     * learned under it since the language last changed, null while it has not changed in this
     * field: a pair spans no change of language, nor a triple.
     */
    private var learnedIn: String? = null
    private var wordsSinceChange: Int? = null

    /** The engine answered reading [language]'s pairs and triples; an unknown language is ignored. */
    fun answeredIn(language: String) {
        if (language != LanguageTags.UNKNOWN) {
            answerLanguage = language
        }
    }

    /**
     * Drops [word], in any case, from what is waiting to be written, with its pairs and triples.
     * Returns whether the word itself was waiting.
     */
    fun forget(word: String): Boolean = learning.forget(word)

    /** Learns nothing until the gate is next set, at a field start or a settings change. */
    fun stop() {
        learning.enabled = false
    }

    /**
     * Records a confirmed word, and the pair and triple it makes with [contextWord] and
     * [grandContextWord], read before the commit; a word of one letter is not counted, but its
     * pair and triple are learned. [deliberateCapital] is whether its first letter is a capital the
     * user chose ([ComposingWord.capitalisedByUser]); a word written in capitals throughout is
     * learned with none. [asserted] is whether the user chose it on purpose. [taps] is the word as
     * it was typed, with where each letter was tapped, lined up with [word], or with its first
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
        val nameCapital = deliberateCapital && !writtenInCapitals(word)
        var learned = false
        if (learning.enabled) {
            // The input-method subtype's tag, recorded with the word.
            val locale = host.subtypeTag()
            // The pair and triple go under the last answer's language, else the subtype's.
            val language = answerLanguage ?: LanguageTags.primary(locale)
            val now = clock.currentTimeMillis()
            if (learnedIn != null && language != learnedIn) {
                wordsSinceChange = 0
            }
            val since = wordsSinceChange
            learnedIn = language
            wordsSinceChange = since?.plus(1)
            // A word with nothing before it is paired with the sentence start.
            val pairContext = contextWord ?: UserBigram.SENTENCE_START
            val pairKept = (since == null || since >= 1) && learning.acceptsPair(pairContext, word)
            val tripleKept = (since == null || since >= 2) && contextWord != null && grandContextWord != null &&
                learning.acceptsTriple(grandContextWord, contextWord, word)
            val counted = learning.record(word, locale, now, nameCapital, asserted)
            if (counted) {
                host.playEffect(EffectEvent.LearnedWord, word)
            }
            learned = counted || pairKept
            if (learned) {
                // The engine learns the pair and the triple the buffer takes; the buffer keeps what
                // the engine recorded.
                engine.learn(
                    listOf(LearnedWord(word, locale, if (counted) 1 else 0, now, nameCapital, asserted)),
                    pairContext.takeIf { pairKept }, grandContextWord.takeIf { tripleKept }, language,
                ) { outcome ->
                    if (outcome.pairs.isNotEmpty() || outcome.triples.isNotEmpty()) {
                        learning.recordSteps(outcome.pairs, outcome.triples, clock.currentTimeMillis())
                        scheduleFlush()
                    }
                }
            }
        }
        if (learned || touched) {
            scheduleFlush()
        }
    }

    /** Writes the buffer now when it is due, else after the debounce, while the views are up. */
    private fun scheduleFlush() {
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

    /** Reads the current bucket's stored totals again, as after an edit on the settings screen. */
    fun reloadTouches() {
        if (!session.policy.userUnlocked) {
            return
        }
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
     * Whether anything is learned, whether the personal dictionary is consulted and from how many
     * uses, and whether the learned touch patterns count: off with their switches or in a private
     * field.
     */
    private fun applyGate() {
        learning.enabled = session.policy.personalAllowed
        engine.setPersonalModelEnabled(learning.enabled)
        engine.setLearnAfterUses(settings.learnAfter)
        engine.setTouchModel(
            session.policy.heatmapAllowed, settings.heatmapWeight, settings.heatmapMinTaps,
        )
    }

    /** Writes the buffered learning to the [store]; what the store gives up, the engine drops. */
    private fun flush() {
        val batch = drain() ?: return
        store.persist(batch) { pairs, triples -> engine.dropLinks(pairs, triples) }
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
}

/** Whether [word] holds two letters or more, each a capital. */
private fun writtenInCapitals(word: String): Boolean {
    var letters = 0
    for (character in word) {
        if (character.isLetter()) {
            if (!character.isUpperCase()) {
                return false
            }
            letters++
        }
    }
    return letters >= MIN_CAPITALS_WORD
}

/** The fewest letters a word written in capitals throughout has. */
private const val MIN_CAPITALS_WORD = 2
