// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.WordStems
import com.borderkeys.typing.SearchAnswer

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

    /** What the commit decision reads of this answer, with [refused] words dropped. */
    fun searchAnswer(refused: RefusedWords): SearchAnswer = SearchAnswer(
        query = query,
        knownWord = knownWord,
        knownWordExact = knownWordExact,
        knownWordIsName = knownWordIsName,
        corrections = offers(refused),
        possessive = possessive,
    )

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
    val correctionNames = BooleanArray(NativePredictor.CORRECTION_SLOTS)
    val correctionSlips = BooleanArray(NativePredictor.CORRECTION_SLOTS)
    val correctionConfident = BooleanArray(NativePredictor.CORRECTION_SLOTS)
    val spellingFlags = BooleanArray(NativePredictor.SPELLING_FLAGS)

    /** Empties the slots the engine fills only when it has something for them. */
    fun clear() {
        texts.fill(null)
        corrections.fill(null)
        correctionNames.fill(false)
        correctionSlips.fill(false)
        correctionConfident.fill(true)
        spellingFlags.fill(false)
    }
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
    scratch.clear()
    val count = NativePredictor.nativeAnswer(
        handle, composing, previous1, previous2, tapXs, tapYs, scratch.words, scratch.scores,
        scratch.properNoun, scratch.texts, scratch.corrections, scratch.correctionNames,
        scratch.correctionSlips, scratch.correctionConfident, scratch.spellingFlags,
    )
    return readAnswer(handle, composing, languages, scratch, count)
}

/**
 * [answerRequest] for [composing] alone, with no words before it and no taps, as if the pack at
 * [packIndex] were the language being written; null when that pack is not open and active.
 */
internal fun answerRequestAs(
    handle: Long,
    packIndex: Int,
    composing: String,
    languages: List<String>,
    scratch: AnswerScratch,
): PredictionAnswer? {
    scratch.clear()
    val count = NativePredictor.nativeAnswerAs(
        handle, packIndex, composing, scratch.words, scratch.scores, scratch.properNoun,
        scratch.texts, scratch.corrections, scratch.correctionNames, scratch.correctionSlips,
        scratch.correctionConfident, scratch.spellingFlags,
    )
    return if (count < 0) null else readAnswer(handle, composing, languages, scratch, count)
}

/** The answer [scratch] holds for [composing], [count] ranked words long. */
private fun readAnswer(
    handle: Long,
    composing: String,
    languages: List<String>,
    scratch: AnswerScratch,
    count: Int,
): PredictionAnswer {
    val spelling = scratch.texts[NativePredictor.TEXT_KNOWN_SPELLING]
    val knownStems = if (composing.isNotEmpty()) knownStemsOf(handle, composing, languages) else emptySet()
    fun shielded(text: String) =
        knownStems.isNotEmpty() && WordStems.shields(composing, text, knownStems, languages)
    val corrections = ArrayList<CorrectionOffer>(NativePredictor.CORRECTION_SLOTS + 1)
    for (index in 0 until NativePredictor.CORRECTION_SLOTS) {
        val text = scratch.corrections[index] ?: break
        corrections.add(
            ListedCorrection(
                text, scratch.correctionNames[index], shielded(text), scratch.correctionSlips[index],
                scratch.correctionConfident[index],
            ),
        )
    }
    scratch.texts[NativePredictor.TEXT_DECODED]?.let { decoded ->
        corrections.add(DecodedCorrection(decoded, shielded(decoded)))
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
        knownWordExact = scratch.spellingFlags[NativePredictor.SPELLING_EXACT],
        knownWordIsName = scratch.spellingFlags[NativePredictor.SPELLING_NAME],
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
