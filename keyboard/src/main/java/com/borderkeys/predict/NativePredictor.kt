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
     * wordCount }` and returns the pack's language tag, or null when the pack was refused.
     * Not for the UI thread.
     */
    external fun nativeInspectPack(fd: Int, offset: Long, length: Long, out: IntArray): String?

    /** Sets which loaded packs take part in scoring, and with what weight. */
    external fun nativeSetActiveLanguages(handle: Long, tags: Array<String>, weights: FloatArray)

    /** Pushes the key centres and the key size, in the view's pixels. */
    external fun nativeSetKeyGeometry(
        handle: Long,
        codes: IntArray,
        centersX: FloatArray,
        centersY: FloatArray,
        keyWidth: Float,
        keyHeight: Float,
    )

    /**
     * Fills [outWords], [outScores] and [outProperNoun] with the best candidates, best first, and
     * returns how many were written. An empty [composing] asks for the next word.
     * [outCorrectionIndex] receives the entry the corrections heap settled on, or -1.
     */
    external fun nativeSuggest(
        handle: Long,
        composing: String,
        prev1: String?,
        prev2: String?,
        outWords: Array<String?>,
        outScores: FloatArray,
        outProperNoun: BooleanArray,
        outCorrectionIndex: IntArray,
    ): Int

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

    /** Whether a suggestion may be two words. */
    external fun nativeSetPhraseSuggestions(handle: Long, enabled: Boolean)

    /** Loads tier B's weights from a `.bkw` file's bytes. False when invalid or in `core`. */
    external fun nativeLoadSwipeWeights(handle: Long, weights: ByteArray): Boolean

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
     * The best word the last [nativeSuggest] reached by an edit, and in [nameOut][0] whether it
     * is a name. Valid only right after [nativeSuggest].
     */
    external fun nativeBestCorrection(handle: Long, nameOut: BooleanArray): String?

    /** The possessive of a name missing its apostrophe, or null. */
    external fun nativePossessive(handle: Long, word: String): String?

    /** How the dictionaries spell [word], or null when none holds it. */
    external fun nativeKnownSpelling(handle: Long, word: String): String?

    /**
     * Marks in [outKnown] which of [stems] may stand as the stem of a regular inflection, and
     * returns how many. At most [MAX_STEMS_QUERY] stems.
     */
    external fun nativeKnownStems(handle: Long, stems: Array<String>, outKnown: BooleanArray): Int

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

    /** The language tag of [nativeDominantPack]'s pack, or null while the engine is undecided. */
    external fun nativeDominantLanguageTag(handle: Long): String?

    /**
     * What [packIndex] alone would spell [word] as, or null when that pack has nothing better
     * than [word].
     */
    external fun nativeCandidateForPack(handle: Long, packIndex: Int, word: String): String?

    /** The pack the conversation is currently considered written in, or -1 when undecided. */
    external fun nativeDominantPack(handle: Long): Int
}
