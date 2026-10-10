// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * The Kotlin side of the JNI boundary. Every declaration has a counterpart in `kMethods` in
 * `jni_bridge.cpp`, bound through `RegisterNatives` in `JNI_OnLoad`.
 *
 * Not thread safe: every call except [nativeCreate], [nativeDestroy] and [nativeInspectPack]
 * runs on the prediction thread.
 */
internal object NativePredictor {

    init {
        System.loadLibrary("borderkeys")
    }

    /** Returns an opaque handle, or 0 if the engine could not be created. */
    external fun nativeCreate(): Long

    /** Releases the engine and everything it mapped. No native call may be in flight or follow. */
    external fun nativeDestroy(handle: Long)

    /**
     * Maps and validates a `.bkd` language pack from a window of an open file descriptor, which
     * the caller keeps and closes. Returns 0, or a negative `BkdStatus` code.
     */
    external fun nativeLoadLanguage(
        handle: Long,
        tag: String,
        fd: Int,
        offset: Long,
        length: Long,
        weight: Float,
    ): Int

    /**
     * Validates a `.bkd` without loading it. Fills [out] with `{ status, formatVersion,
     * wordCount, knownWordCount }`, the last the words the pack only knows and never offers, and
     * returns the pack's language tag, or null when the pack was refused. Not for the UI thread.
     */
    external fun nativeInspectPack(fd: Int, offset: Long, length: Long, out: IntArray): String?

    /** Sets which loaded packs take part in scoring, and with what weight. */
    external fun nativeSetActiveLanguages(handle: Long, tags: Array<String>, weights: FloatArray)

    /**
     * Pushes the key centres and the key size, in the view's pixels, and the long-press letters
     * a swipe may reach: [aliasCodes] on the key of the same index in [aliasBases].
     */
    external fun nativeSetKeyGeometry(
        handle: Long,
        codes: IntArray,
        centersX: FloatArray,
        centersY: FloatArray,
        keyWidth: Float,
        keyHeight: Float,
        aliasCodes: IntArray,
        aliasBases: IntArray,
    )

    /**
     * Answers one request. Fills [outWords], [outScores] and [outProperNoun] with the best
     * candidates, best first, and returns how many were written; an empty [composing] asks for
     * the next word. For a typed word, [outTexts] receives [TEXT_KNOWN_SPELLING],
     * [TEXT_POSSESSIVE] and [TEXT_DECODED], a slot with none left as it is; [outCorrections]
     * receives autocorrect's list, best first, up to [CORRECTION_SLOTS] entries, the rest null,
     * [outCorrectionNames] whether each entry is a name, [outCorrectionSlips] whether each was
     * reached by neighbouring keys in place of the typed ones alone, [outCorrectionConfident]
     * whether each stands against the other readings of the taps, and
     * [outSpellingFlags] [SPELLING_EXACT] and [SPELLING_NAME].
     */
    external fun nativeAnswer(
        handle: Long,
        composing: String,
        prev1: String?,
        prev2: String?,
        tapXs: FloatArray?,
        tapYs: FloatArray?,
        outWords: Array<String?>,
        outScores: FloatArray,
        outProperNoun: BooleanArray,
        outTexts: Array<String?>,
        outCorrections: Array<String?>,
        outCorrectionNames: BooleanArray,
        outCorrectionSlips: BooleanArray,
        outCorrectionConfident: BooleanArray,
        outSpellingFlags: BooleanArray,
    ): Int

    /**
     * [nativeAnswer] for [composing] alone, with no words before it and no taps, as if the pack
     * at [packIndex] were the language being written; the detected language is left as it is.
     * Returns -1, filling nothing, when that pack is not open and active.
     */
    external fun nativeAnswerAs(
        handle: Long,
        packIndex: Int,
        composing: String,
        outWords: Array<String?>,
        outScores: FloatArray,
        outProperNoun: BooleanArray,
        outTexts: Array<String?>,
        outCorrections: Array<String?>,
        outCorrectionNames: BooleanArray,
        outCorrectionSlips: BooleanArray,
        outCorrectionConfident: BooleanArray,
        outSpellingFlags: BooleanArray,
    ): Int

    /** [nativeAnswer]'s text slot: how the dictionaries spell the typed word. */
    const val TEXT_KNOWN_SPELLING = 0

    /** [nativeAnswer]'s text slot: the possessive of a name missing its apostrophe. */
    const val TEXT_POSSESSIVE = 1

    /** [nativeAnswer]'s text slot: the tap decoder's word, when the engine accepted it. */
    const val TEXT_DECODED = 2

    /** How many text slots [nativeAnswer] fills. */
    const val TEXT_SLOTS = 3

    /** How many entries of autocorrect's list [nativeAnswer] writes at most. */
    const val CORRECTION_SLOTS = 5

    /** [nativeAnswer]'s spelling flag: a dictionary spells the typed letters exactly, case aside. */
    const val SPELLING_EXACT = 0

    /** [nativeAnswer]'s spelling flag: that exact spelling is a name. */
    const val SPELLING_NAME = 1

    /** How many spelling flags [nativeAnswer] fills. */
    const val SPELLING_FLAGS = 2

    /**
     * Records that the user committed [word] after [prev1] and [prev2]. [asserted] is whether it
     * was picked on the strip or put back after a correction; [deliberateCapital] is whether its
     * capital was typed with shift.
     */
    external fun nativeLearn(
        handle: Long,
        word: String,
        prev1: String?,
        prev2: String?,
        deliberateCapital: Boolean,
        asserted: Boolean,
    )

    /**
     * Decodes a swipe from its raw touch samples, in view pixels, into the output buffers, best
     * first, and returns how many were written.
     */
    external fun nativeDecodeGesture(
        handle: Long,
        xs: FloatArray,
        ys: FloatArray,
        timestamps: LongArray,
        count: Int,
        prev1: String?,
        prev2: String?,
        outWords: Array<String?>,
        outScores: FloatArray,
        outProperNoun: BooleanArray,
    ): Int

    /** Replaces the personal dictionary's words, with their per-word counts. */
    external fun nativeLoadUserWords(
        handle: Long,
        words: Array<String>,
        counts: IntArray,
        deliberateCapitals: IntArray,
        asserted: IntArray,
    )

    /**
     * Replaces the blocked words: spellings no search offers, corrects to or counts as known,
     * each matched exactly, case aside. An empty array clears them.
     */
    external fun nativeSetBlockedWords(handle: Long, words: Array<String>)

    /** How readily the user's words outrank the dictionaries. A multiplier; 1 is the default. */
    external fun nativeSetLearningSpeed(handle: Long, speed: Float)

    /**
     * How common a word a pack knows but never offers must be to count as spelled: its unigram
     * log-probability at least [minimumLogProb]. Positive infinity counts none.
     */
    external fun nativeSetKnownWordReach(handle: Long, minimumLogProb: Float)

    /**
     * How much evidence an edit needs before it outranks a word spelled as typed. A multiplier;
     * 1 is the default, below it corrects more readily, above it less.
     */
    external fun nativeSetCorrectionStrictness(handle: Long, scale: Float)

    /** How much evidence stops the other dictionaries being searched; zero or less never does. */
    external fun nativeSetLanguageLock(
        handle: Long,
        minimumEvidence: Float,
        strict: Boolean,
    )

    /** The language consulted before anything has been recognised. Null or empty means none. */
    external fun nativeSetPreferredLanguage(handle: Long, tag: String?)

    /** Forgets which language the conversation is in. */
    external fun nativeResetLanguageEvidence(handle: Long)

    /** The evidence gathered for the open pack with [tag], or zero when none has it. */
    external fun nativeLanguageEvidence(handle: Long, tag: String): Float

    /** Sets the evidence for the open pack with [tag] and decides the language again. */
    external fun nativeSetLanguageEvidence(handle: Long, tag: String, evidence: Float)

    /** Whether a suggestion may be two words. */
    external fun nativeSetPhraseSuggestions(handle: Long, enabled: Boolean)

    /** Loads tier B's weights for [script]; false in `core`. */
    external fun nativeLoadSwipeWeights(handle: Long, script: Int, weights: ByteArray): Boolean

    /** Which script's model the layout now set decodes with; one not loaded decodes with tier A. */
    external fun nativeSelectSwipeScript(handle: Long, script: Int)

    /** Whether a model for [script] is loaded. */
    external fun nativeHasSwipeModel(handle: Long, script: Int): Boolean

    /** Switches tier B on or off; off frees its weights. A no-op in `core`. */
    external fun nativeSetSwipeModelEnabled(handle: Long, enabled: Boolean)

    /** Whether the last gesture decode went through the neural decoder. */
    external fun nativeLastDecodeUsedNeural(handle: Long): Boolean

    /**
     * Decodes one synthetic gesture through tier B and discards it. False when there is nothing
     * to warm.
     */
    external fun nativeWarmSwipeModel(handle: Long): Boolean

    /**
     * Marks in [outKnown] which of [stems] may stand as the stem of a regular inflection in the
     * language [tag], whose endings made them, and returns how many. At most [MAX_STEMS_QUERY]
     * stems.
     */
    external fun nativeKnownStems(
        handle: Long,
        tag: String,
        stems: Array<String>,
        outKnown: BooleanArray,
    ): Int

    /** The bridge's cap on one [nativeKnownStems] call. */
    const val MAX_STEMS_QUERY = 64

    /**
     * The score of [candidate] for [typed], term by term, into [out] (see [ScoreExplanation]).
     * False when the word is not offered.
     */
    external fun nativeExplainScore(
        handle: Long,
        typed: String,
        candidate: String,
        out: FloatArray,
    ): Boolean

    /** Replaces the personal three-word sequences. */
    external fun nativeLoadUserTrigrams(
        handle: Long,
        previous2: Array<String>,
        previous1: Array<String>,
        next: Array<String>,
        counts: IntArray,
    )

    /** Replaces the personal word pairs. */
    external fun nativeLoadUserBigrams(
        handle: Long,
        previous: Array<String>,
        next: Array<String>,
        counts: IntArray,
    )

    /** Whether the personal dictionary is consulted; it stays loaded either way. */
    external fun nativeSetPersonalModelEnabled(handle: Long, enabled: Boolean)

    /**
     * Whether the learned touch patterns count, how far they move a substitution's cost from the
     * default patterns' cost, and how many taps a key needs first.
     */
    external fun nativeSetTouchModel(handle: Long, learned: Boolean, weight: Float, minTaps: Int)

    /**
     * Replaces the learned touch patterns: per letter code, the tap weight, the mean offset from
     * the key's centre and the covariance, in key units. Empty arrays clear them.
     */
    external fun nativeSetTouchPatterns(
        handle: Long,
        codes: IntArray,
        taps: FloatArray,
        meanX: FloatArray,
        meanY: FloatArray,
        varianceX: FloatArray,
        varianceY: FloatArray,
        covariance: FloatArray,
    )

    /** The language tag of [nativeDominantPack]'s pack, or null while the engine is undecided. */
    external fun nativeDominantLanguageTag(handle: Long): String?


    /** The spelling the dictionaries hold for [word], in any case or marks, or null for none. */
    external fun nativeKnownSpelling(handle: Long, word: String): String?

    /** The pack the conversation is currently considered written in, or -1 when undecided. */
    external fun nativeDominantPack(handle: Long): Int
}
