// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.WordStems

/** One request's answer, as the engine gave it, before refused words are dropped. */
internal class PredictionAnswer(
    /** The composing text asked about. */
    val query: String,
    /** The ranking, best first. */
    val words: List<String?>,
    /** The engine's score of each of [words]. */
    val scores: List<Float>,
    /** Whether each of [words] is a name. */
    val properNoun: List<Boolean>,
    /** Which of [words] the corrections heap settled on, or -1. */
    val correctionAt: Int,
    /** Autocorrect's answer, or null. */
    val correction: String?,
    /** Whether [correction] is a name. */
    val correctionIsName: Boolean,
    /** [query] when the dictionaries spell it the same, ignoring case; else empty. */
    val knownWord: String,
    /** The possessive of a name missing its apostrophe, or null. */
    val possessive: String?,
    /** [WordStems.shields]'s answer for [query] and [correction]. */
    val inflection: Boolean,
) {
    /** This answer with its ranking dropped, as a cancelled request leaves it. */
    fun withoutRanking(): PredictionAnswer = PredictionAnswer(
        query, emptyList(), emptyList(), emptyList(), correctionAt, correction, correctionIsName,
        knownWord, possessive, inflection,
    )

    /**
     * The candidates the listener receives: the ranking with the correction marked, the
     * correction added when the ranking lacks it, and [refused] words dropped.
     */
    fun candidates(refused: RefusedWords): List<Candidate> {
        val out = ArrayList<Candidate>(words.size + 1)
        for (index in words.indices) {
            val word = words[index] ?: continue
            out.add(Candidate(word, properNoun[index], isCorrection = index == correctionAt))
        }
        if (correctionAt < 0 && correction != null) {
            out.add(Candidate(correction, correctionIsName, isCorrection = true))
        }
        if (!refused.isEmpty) {
            out.removeAll { refused.refuses(it.text) }
        }
        return out
    }

    companion object {
        /** The answer for [query] when no engine is there to ask. */
        fun empty(query: String) = PredictionAnswer(
            query, emptyList(), emptyList(), emptyList(), -1, null, false, "", null, false,
        )
    }
}

/** The buffers [answerRequest] fills through JNI, reused from one request to the next. */
internal class AnswerScratch {
    val words = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
    val scores = FloatArray(PredictionEngine.MAX_RESULTS)
    val properNoun = BooleanArray(PredictionEngine.MAX_RESULTS)
    val correctionIndex = IntArray(1)
    val texts = arrayOfNulls<String>(NativePredictor.TEXT_SLOTS)
    val correctionName = BooleanArray(1)
}

/**
 * Asks the engine at [handle] about [composing] after [previous1] and [previous2], through
 * [scratch], with [languages] the active tags. [tapXs] and [tapYs] are where each code point of
 * [composing] was tapped, in the keyboard view's pixels, NaN for none; null for no taps.
 */
internal fun answerRequest(
    handle: Long,
    composing: String,
    previous1: String?,
    previous2: String?,
    languages: List<String>,
    scratch: AnswerScratch,
    tapXs: FloatArray? = null,
    tapYs: FloatArray? = null,
): PredictionAnswer {
    scratch.correctionIndex[0] = -1
    scratch.texts.fill(null)
    scratch.correctionName[0] = false
    val count = NativePredictor.nativeAnswer(
        handle, composing, previous1, previous2, tapXs, tapYs, scratch.words, scratch.scores,
        scratch.properNoun, scratch.correctionIndex, scratch.texts, scratch.correctionName,
    )
    val spelling = scratch.texts[NativePredictor.TEXT_KNOWN_SPELLING]
    val correction = scratch.texts[NativePredictor.TEXT_CORRECTION]
    return PredictionAnswer(
        query = composing,
        words = List(count) { scratch.words[it] },
        scores = List(count) { scratch.scores[it] },
        properNoun = List(count) { scratch.properNoun[it] },
        correctionAt = scratch.correctionIndex[0],
        correction = correction,
        correctionIsName = scratch.correctionName[0],
        knownWord = if (spelling != null && spelling.equals(composing, ignoreCase = true)) {
            composing
        } else {
            ""
        },
        possessive = scratch.texts[NativePredictor.TEXT_POSSESSIVE],
        inflection = composing.isNotEmpty() && inflectionOf(handle, composing, correction, languages),
    )
}

/**
 * Whether [query] is a regular inflection of a word the engine at [handle] holds that
 * [correction] is not built on, under the endings of [languages].
 */
internal fun inflectionOf(
    handle: Long,
    query: String,
    correction: String?,
    languages: List<String>,
): Boolean {
    if (correction == null) {
        return false
    }
    // Each language's endings are checked against that language's dictionary alone.
    val knownStems = HashSet<String>()
    for (language in languages) {
        val stems =
            WordStems.candidates(query, listOf(language)).take(NativePredictor.MAX_STEMS_QUERY)
        if (stems.isEmpty()) {
            continue
        }
        val known = BooleanArray(stems.size)
        if (NativePredictor.nativeKnownStems(handle, language, stems.toTypedArray(), known) > 0) {
            stems.filterIndexedTo(knownStems) { index, _ -> known[index] }
        }
    }
    if (knownStems.isEmpty()) {
        return false
    }
    return WordStems.shields(query, correction, knownStems, languages)
}
