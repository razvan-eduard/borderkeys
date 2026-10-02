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
    /** Autocorrect's list for [query], best first. */
    val corrections: List<CorrectionOffer>,
    /** [query] when the dictionaries spell it the same, ignoring case; else empty. */
    val knownWord: String,
    /** Whether a dictionary spells [query] exactly, case aside: [knownWord] in its own case. */
    val knownWordExact: Boolean,
    /** Whether that exact spelling is a name. */
    val knownWordIsName: Boolean,
    /** The possessive of a name missing its apostrophe, or null. */
    val possessive: String?,
) {
    /** This answer with its ranking dropped, as a cancelled request leaves it. */
    fun withoutRanking(): PredictionAnswer = PredictionAnswer(
        query, emptyList(), emptyList(), emptyList(), corrections, knownWord, knownWordExact,
        knownWordIsName, possessive,
    )

    /** The candidates the listener receives: the ranking, with [refused] words dropped. */
    fun candidates(refused: RefusedWords): List<Candidate> {
        val out = ArrayList<Candidate>(words.size)
        for (index in words.indices) {
            val word = words[index] ?: continue
            out.add(Candidate(word, properNoun[index]))
        }
        if (!refused.isEmpty) {
            out.removeAll { refused.refuses(it.text) }
        }
        return out
    }

    /** Autocorrect's list with [refused] words dropped. */
    fun offers(refused: RefusedWords): List<CorrectionOffer> =
        if (refused.isEmpty) corrections else corrections.filterNot { refused.refuses(it.text) }

    companion object {
        /** The answer for [query] when no engine is there to ask. */
        fun empty(query: String) = PredictionAnswer(
            query, emptyList(), emptyList(), emptyList(), emptyList(), "", false, false, null,
        )
    }
}

/** The buffers [answerRequest] fills through JNI, reused from one request to the next. */
internal class AnswerScratch {
    val words = arrayOfNulls<String>(PredictionEngine.MAX_RESULTS)
    val scores = FloatArray(PredictionEngine.MAX_RESULTS)
    val properNoun = BooleanArray(PredictionEngine.MAX_RESULTS)
    val texts = arrayOfNulls<String>(NativePredictor.TEXT_SLOTS)
    val corrections = arrayOfNulls<String>(NativePredictor.CORRECTION_SLOTS)
    val edits = IntArray(NativePredictor.CORRECTION_SLOTS)
    val flags = BooleanArray(NativePredictor.FLAG_SLOTS)
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
    scratch.texts.fill(null)
    scratch.corrections.fill(null)
    scratch.edits.fill(0)
    scratch.flags.fill(false)
    val count = NativePredictor.nativeAnswer(
        handle, composing, previous1, previous2, tapXs, tapYs, scratch.words, scratch.scores,
        scratch.properNoun, scratch.texts, scratch.corrections, scratch.edits, scratch.flags,
    )
    val spelling = scratch.texts[NativePredictor.TEXT_KNOWN_SPELLING]
    val knownStems = if (composing.isNotEmpty()) knownStemsOf(handle, composing, languages) else emptySet()
    val corrections = ArrayList<CorrectionOffer>(NativePredictor.CORRECTION_SLOTS)
    for (index in 0 until NativePredictor.CORRECTION_SLOTS) {
        val text = scratch.corrections[index] ?: break
        val inflection =
            knownStems.isNotEmpty() && WordStems.shields(composing, text, knownStems, languages)
        corrections.add(CorrectionOffer(text, scratch.flags[index], inflection, scratch.edits[index]))
    }
    return PredictionAnswer(
        query = composing,
        words = List(count) { scratch.words[it] },
        scores = List(count) { scratch.scores[it] },
        properNoun = List(count) { scratch.properNoun[it] },
        corrections = corrections,
        knownWord = if (spelling != null && spelling.equals(composing, ignoreCase = true)) {
            composing
        } else {
            ""
        },
        knownWordExact = scratch.flags[NativePredictor.FLAG_EXACT_SPELLING],
        knownWordIsName = scratch.flags[NativePredictor.FLAG_EXACT_SPELLING_NAME],
        possessive = scratch.texts[NativePredictor.TEXT_POSSESSIVE],
    )
}

/**
 * The stems of [query] that the engine at [handle] vouches for, each under the endings of the
 * language whose dictionary holds it; empty when [query] is no regular inflection of any.
 */
internal fun knownStemsOf(handle: Long, query: String, languages: List<String>): Set<String> {
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
    return knownStems
}
