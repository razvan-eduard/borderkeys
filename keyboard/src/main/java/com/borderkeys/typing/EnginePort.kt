// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.personal.LinkWrites
import com.borderkeys.data.personal.WorkingCopy

/**
 * The prediction engine as the typing flow uses it. Every call returns at once; an answer to
 * [requestSuggestions] reaches [TypingOrchestrator.onSuggestions] through whoever owns the engine,
 * and the callbacks run on the typing flow's thread.
 */
interface EnginePort {
    /**
     * Asks about [composing] after [previous1] and [previous2], the learned pairs and triples read
     * in the store of [language], a primary language subtag; [tapXs] and [tapYs] are where each of
     * its code points was tapped, in the keyboard view's pixels, NaN for none, or null for no taps.
     * The answer names the pack it leaned on.
     */
    fun requestSuggestions(
        composing: String,
        previous1: String?,
        previous2: String?,
        language: String,
        tapXs: FloatArray?,
        tapYs: FloatArray?,
    )

    /**
     * Learns [commit]: its word, and the pair and the triple it makes, each under
     * [com.borderkeys.data.PhraseBudget] as it is recorded; [onCommitted] receives the link rows it
     * changed, for the flush to write.
     */
    fun commit(commit: Commit, onCommitted: (LinkWrites) -> Unit)

    /**
     * Runs [change] on the personal model once every commit posted before it is learned, and hands
     * [onEdited] the link rows it returned, or null when no personal model is loaded.
     */
    fun editPersonal(change: (WorkingCopy) -> LinkWrites, onEdited: (LinkWrites?) -> Unit)

    /** Calls [onSettled] on the UI thread once every commit posted before it has reported its rows. */
    fun settle(onSettled: () -> Unit)

    fun setPersonalModelEnabled(enabled: Boolean)

    /**
     * How many uses a word, pair or triple needs before it counts; see
     * [com.borderkeys.data.theme.KeyboardPreferences.learnAfter].
     */
    fun setLearnAfterUses(uses: Int)

    /**
     * Whether learned phrases are offered after their context; see
     * [com.borderkeys.data.theme.KeyboardPreferences.phraseSuggestions].
     */
    fun setPhraseSuggestions(enabled: Boolean)

    /**
     * How much evidence an edit needs before it outranks a word spelled as typed; see
     * [com.borderkeys.data.theme.KeyboardPreferences.correctionStrictness].
     */
    fun setCorrectionStrictness(scale: Float)

    /**
     * How much one-sided evidence decides the language being written, zero never, and whether an
     * undecided one searches the heaviest pack alone; see
     * [com.borderkeys.data.theme.KeyboardPreferences.languageLock].
     */
    fun setLanguageLock(minimumEvidence: Float, strict: Boolean)

    /**
     * The language consulted before one is recognised, empty for none; see
     * [com.borderkeys.data.theme.KeyboardPreferences.preferredLanguageTag].
     */
    fun setPreferredLanguage(tag: String)

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
     * Decodes a finished swipe, [count] samples, after [previous1] and [previous2], the learned
     * pairs and triples read in [language]'s store; the answer reaches
     * [TypingOrchestrator.onGestureCandidates].
     */
    fun decodeGesture(
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        previous1: String?,
        previous2: String?,
        language: String,
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
        language: String,
    )

    /** Drops a swipe decode that has not answered yet. */
    fun cancelPendingGesture()

    /** Drops a preview decode that has not answered yet. */
    fun cancelPendingPreview()
}

/**
 * A committed word as the engine learns it: its uses ([word]), the words before it, [previous1]
 * null for no pair and [previous2] null for no triple, the language its pair and triple are learned
 * in, a primary language subtag, and whether the pair and the triple were chosen on purpose.
 */
class Commit(
    val word: LearnedWord,
    val previous1: String?,
    val previous2: String?,
    val language: String,
    val pairAsserted: Boolean = false,
    val tripleAsserted: Boolean = false,
)
