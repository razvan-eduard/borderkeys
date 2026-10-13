// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.dao.PairStep
import com.borderkeys.data.dao.TripleStep
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram

/**
 * The prediction engine as the typing flow uses it. Every call returns at once; an answer to
 * [requestSuggestions] reaches [TypingOrchestrator.onSuggestions] through whoever owns the engine,
 * and the callbacks run on the typing flow's thread.
 */
interface EnginePort {
    /**
     * Asks about [composing] after [previous1] and [previous2]; [tapXs] and [tapYs] are where each
     * of its code points was tapped, in the keyboard view's pixels, NaN for none, or null for no
     * taps.
     */
    fun requestSuggestions(
        composing: String,
        previous1: String?,
        previous2: String?,
        tapXs: FloatArray?,
        tapYs: FloatArray?,
    )

    /**
     * Learns [updates] after [previous1] and [previous2], the pair and triple they make in the
     * store of [language], a primary language subtag, each under [com.borderkeys.data.PhraseBudget]
     * as it is recorded; [onLearned] receives what was recorded and dropped.
     */
    fun learn(
        updates: List<LearnedWord>,
        previous1: String?,
        previous2: String?,
        language: String,
        onLearned: (LearnOutcome) -> Unit,
    )

    /** Drops the pairs and triples the store gave up under its budget. */
    fun dropLinks(pairs: List<UserBigram>, triples: List<UserTrigram>)

    fun setPersonalModelEnabled(enabled: Boolean)

    /**
     * How many uses a word, pair or triple needs before it counts; see
     * [com.borderkeys.data.theme.KeyboardPreferences.learnAfter].
     */
    fun setLearnAfterUses(uses: Int)

    /**
     * How much evidence an edit needs before it outranks a word spelled as typed; see
     * [com.borderkeys.data.theme.KeyboardPreferences.correctionStrictness].
     */
    fun setCorrectionStrictness(scale: Float)

    /**
     * Whether the learned touch patterns count, how far they move a substitution's cost from the
     * default patterns' cost, and how many taps a key needs first. A request's taps are priced by
     * the default patterns either way.
     */
    fun setTouchModel(learned: Boolean, weight: Float, minTaps: Int)

    /** Replaces the learned touch patterns; empty ones clear them. */
    fun setTouchPatterns(patterns: TouchPatterns)

    fun dominantLanguageTag(onResult: (String?) -> Unit)

    fun dominantPack(onResult: (Int) -> Unit)

    /**
     * The answer about each of [words], with no words before it, as if [pack] were the language
     * being written, in the same order; each null when [pack] is not open and active.
     */
    fun answersAs(pack: Int, words: List<String>, onResult: (List<SearchAnswer?>) -> Unit)

    /** Whether the dictionaries hold each of [words], in any case or marks. */
    fun knownWords(words: List<String>, onResult: (List<Boolean>) -> Unit)

    fun cancelPending()

    /**
     * Decodes a finished swipe, [count] samples, after [previous1] and [previous2]; the answer
     * reaches [TypingOrchestrator.onGestureCandidates].
     */
    fun decodeGesture(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    )

    /**
     * Decodes a swipe still in progress, as [decodeGesture] does; the answer reaches
     * [TypingOrchestrator.onGesturePreviewCandidates].
     */
    fun decodeGesturePreview(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
    )

    /** Drops a swipe decode that has not answered yet. */
    fun cancelPendingGesture()

    /** Drops a preview decode that has not answered yet. */
    fun cancelPendingPreview()
}

/**
 * What the engine did with an [EnginePort.learn]: each pair and triple it recorded, with the links
 * its budget dropped right after, in order.
 */
class LearnOutcome(val pairs: List<PairStep>, val triples: List<TripleStep>)
