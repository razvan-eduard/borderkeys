// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.predict.CorrectionOffer
import com.borderkeys.predict.DecodedCorrection
import com.borderkeys.predict.ListedCorrection
import java.text.Normalizer

/** Whether a delimiter should replace what was typed, and with what. */
internal object AutoCorrection {

    /**
     * What a word and the answer offered for it amount to. Exactly one holds, and only one that
     * [replaces] replaces anything.
     */
    enum class Situation(val replaces: Boolean = false) {
        /** Nothing was offered at all. */
        NothingOffered,

        /** The answer is for an earlier query, not for what was typed. */
        StaleAnswer,

        /** What was typed ends in an apostrophe or a hyphen. */
        TrailingMark,

        /** The answer is more edits from what was typed than the distance setting allows. */
        TooFar,

        /** A name offered for a word whose letters are not the name's. */
        NameMismatch,

        /** Once cased, the answer is what was typed. Nothing to do. */
        NoChange,

        /** Shorter than the minimum length, and not an accent being restored. */
        TooShort,

        /** The dictionaries spell what was typed, and it is not a name being capitalised. */
        KnownWord,

        /**
         * A regular inflection of a dictionary word ([WordStems]), offered a word that differs in
         * more than accents, case or marks.
         */
        Inflection,

        /**
         * Otherwise correctable, but another reading of the taps is clearly likelier
         * ([CorrectionOffer.confident]).
         */
        Uncertain,

        /** None of the above, for an entry of the engine's correction list. */
        Correctable(replaces = true),

        /** None of the above, for the tap decoder's word ([DecodedCorrection]). */
        Decoded(replaces = true),
    }

    /** [pick]'s answer: the [Situation] and, when it [Situation.replaces], the cased text. */
    class Pick(val situation: Situation, val text: String?)

    /**
     * What a delimiter does with [typed], given autocorrect's list [corrections] for
     * [suggestionQuery] and what the dictionaries say of the word: [knownWord] is [typed] when
     * they spell it, [knownWordExact] whether one spells it exactly, case aside, and
     * [knownWordIsName] whether that spelling is a name. The word itself is judged first
     * ([typedSituation]); then each offer in turn ([candidateSituation]), the first that is
     * admissible winning as its [CorrectionOffer.appliedAs], a [Situation.TooShort] offer stopping
     * the walk, and the first offer's situation standing when none wins. An offer may be as many
     * edits away as its [CorrectionOffer.editCeiling] allows under [maxEdits] and [maxSlipEdits],
     * and is passed over when not [CorrectionOffer.confident]. The tap decoder's word, last in the
     * list, is left out unless [decoderAllowed].
     */
    fun pick(
        typed: String,
        corrections: List<CorrectionOffer>,
        suggestionQuery: String,
        knownWord: String,
        knownWordExact: Boolean,
        knownWordIsName: Boolean,
        minimumLength: Int,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        maxSlipEdits: Int = maxEdits,
        decoderAllowed: Boolean = true,
    ): Pick {
        val offers = admitted(corrections, decoderAllowed)
        typedSituation(
            typed, offers, suggestionQuery, knownWord, knownWordExact, knownWordIsName,
            capitaliseNames,
        )?.let { return it }
        var first: Situation? = null
        for (offer in offers) {
            // [capitaliseNames] gates only a name's capital, not [Situation.NameMismatch].
            val cased = matchCase(typed, offer.text, offer.isName && capitaliseNames)
            val situation = candidateSituation(
                typed, offer, cased, minimumLength, offer.editCeiling(maxEdits, maxSlipEdits),
            )
            if (first == null) {
                first = situation
            }
            when (situation) {
                Situation.Correctable -> return Pick(offer.appliedAs, cased)
                Situation.TooShort -> return Pick(situation, null)
                else -> Unit
            }
        }
        return Pick(first ?: Situation.NothingOffered, null)
    }

    /** [corrections] without the tap decoder's word unless [decoderAllowed]. */
    private fun admitted(corrections: List<CorrectionOffer>, decoderAllowed: Boolean): List<CorrectionOffer> =
        if (decoderAllowed) corrections else corrections.filterNot { it is DecodedCorrection }

    /**
     * The verdict the typed word settles on its own, or null when the offers decide: nothing
     * offered and nothing known, an answer about another word, a trailing mark, or a word the
     * dictionaries spell, which is left alone unless it is a name to capitalise.
     */
    private fun typedSituation(
        typed: String,
        corrections: List<CorrectionOffer>,
        suggestionQuery: String,
        knownWord: String,
        knownWordExact: Boolean,
        knownWordIsName: Boolean,
        capitaliseNames: Boolean,
    ): Pick? = when {
        corrections.isEmpty() && knownWord.isEmpty() -> Pick(Situation.NothingOffered, null)
        typed != suggestionQuery -> Pick(Situation.StaleAnswer, null)
        typed.isNotEmpty() && typed.last() in TRAILING_MARKS -> Pick(Situation.TrailingMark, null)
        knownWord.isNotEmpty() && knownWord.equals(typed, ignoreCase = true) -> {
            val recased = if (knownWordExact && knownWordIsName && capitaliseNames) {
                matchCase(typed, typed, isProperNoun = true)
            } else {
                typed
            }
            when {
                recased != typed -> Pick(Situation.Correctable, recased)
                knownWordExact -> Pick(Situation.NoChange, null)
                else -> Pick(Situation.KnownWord, null)
            }
        }
        else -> null
    }

    /**
     * Which [Situation] one [offer] is for [typed]; [cased] is the offer after [matchCase].
     */
    private fun candidateSituation(
        typed: String,
        offer: CorrectionOffer,
        cased: String,
        minimumLength: Int,
        maxEdits: Int,
    ): Situation = when {
        editDistance(stripDiacritics(typed), stripDiacritics(offer.text)) > maxEdits -> Situation.TooFar
        offer.isName &&
            !stripDiacritics(typed).equals(stripDiacritics(offer.text), ignoreCase = true) ->
            Situation.NameMismatch
        cased == typed -> Situation.NoChange
        typed.length < minimumLength &&
            !(typed.length >= MIN_DIACRITIC_LENGTH && isDiacriticOnlyDifference(typed, offer.text)) ->
            Situation.TooShort
        offer.inflection && !isSameLetters(typed, offer.text) -> Situation.Inflection
        !offer.confident -> Situation.Uncertain
        else -> Situation.Correctable
    }

    /**
     * [pick] for one offer: the correction to apply, cased like [typed], or null to commit what
     * was typed. [isProperNoun] is the offer's, and the known spelling's when [knownWord] is set.
     */
    fun correctionFor(
        typed: String,
        suggestion: String?,
        suggestionQuery: String,
        knownWord: String,
        minimumLength: Int,
        isProperNoun: Boolean = false,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        inflection: Boolean = false,
    ): String? = pick(
        typed,
        if (suggestion.isNullOrEmpty()) emptyList() else listOf(ListedCorrection(suggestion, isProperNoun, inflection)),
        suggestionQuery,
        knownWord,
        knownWordExact = knownWord.isNotEmpty(),
        knownWordIsName = isProperNoun && knownWord.isNotEmpty(),
        minimumLength,
        maxEdits,
        capitaliseNames,
    ).text

    /** Whether [typed] and [suggestion] are the same letters once accents, case and marks --
     *  apostrophes and hyphens -- are set aside. */
    private fun isSameLetters(typed: String, suggestion: String): Boolean =
        stripDiacritics(typed).filter { it.isLetter() } ==
            stripDiacritics(suggestion).filter { it.isLetter() }

    /** Whether [typed] and [suggestion] differ only in accents and case. */
    private fun isDiacriticOnlyDifference(typed: String, suggestion: String): Boolean =
        stripDiacritics(typed) == stripDiacritics(suggestion)

    /**
     * The edit ceiling for a word of [typedLength] letters under the `correctionDistance` setting:
     * one when strict, two when loose, and by default two from [LONG_WORD_LETTERS] letters on.
     */
    fun maxEditsFor(typedLength: Int, distanceSetting: Int): Int = when (distanceSetting) {
        DISTANCE_STRICT -> 1
        DISTANCE_LOOSE -> 2
        else -> if (typedLength >= LONG_WORD_LETTERS) 2 else 1
    }

    /**
     * The edit ceiling for an offer reached by neighbouring keys alone
     * ([ListedCorrection.slipsOnly]): by default two from [MIN_SLIP_PAIR_LETTERS] letters on, else
     * as [maxEditsFor].
     */
    fun maxSlipEditsFor(typedLength: Int, distanceSetting: Int): Int = when (distanceSetting) {
        DISTANCE_STRICT, DISTANCE_LOOSE -> maxEditsFor(typedLength, distanceSetting)
        else -> if (typedLength >= MIN_SLIP_PAIR_LETTERS) 2 else maxEditsFor(typedLength, distanceSetting)
    }

    /** Whether the tap decoder's word may be applied under the `correctionDistance` setting. */
    fun decoderAllowedFor(distanceSetting: Int): Boolean = distanceSetting != DISTANCE_STRICT

    /**
     * Optimal string alignment distance: Levenshtein, with a swap of two adjacent letters as one
     * edit.
     */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var twoBack = IntArray(b.length + 1)
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    current[j] = minOf(current[j], twoBack[j - 2] + 1)
                }
            }
            val rotate = twoBack
            twoBack = previous
            previous = current
            current = rotate
        }
        return previous[b.length]
    }

    /** The shortest word an accent may be restored on below the minimum length. */
    const val MIN_DIACRITIC_LENGTH = 2

    /** Same as `KeyboardPreferences.CORRECTION_DISTANCE_STRICT` and `_LOOSE`. */
    const val DISTANCE_STRICT = 0
    const val DISTANCE_LOOSE = 2

    /** From this many letters on, the default setting allows a second edit. */
    const val LONG_WORD_LETTERS = 8

    /**
     * From this many letters on, the default setting allows a second edit when both are
     * neighbouring keys in place of typed ones: half the letters still in place.
     */
    const val MIN_SLIP_PAIR_LETTERS = 4

    /** The marks a word may end in while still being written: the apostrophes and the hyphen. */
    private const val TRAILING_MARKS = "'’‘ʼ-"

    private fun stripDiacritics(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    /**
     * Gives [correction] the capitalisation of [typed]: all capitals when [typed] has more than
     * one letter and no lower case, else the case of its first letter. [isProperNoun], already
     * combined with the "capitalise names" setting, capitalises the first letter unless [typed]
     * is all capitals.
     */
    fun matchCase(typed: String, correction: String, isProperNoun: Boolean = false): String {
        if (typed.isEmpty() || correction.isEmpty()) {
            return correction
        }
        if (typed.count { it.isLetter() } > 1 && typed.none { it.isLowerCase() }) {
            return correction.uppercase()
        }
        if (isProperNoun) {
            return correction.replaceFirstChar { it.uppercaseChar() }
        }
        return if (typed[0].isUpperCase()) {
            correction.replaceFirstChar { it.uppercaseChar() }
        } else {
            correction.replaceFirstChar { it.lowercaseChar() }
        }
    }
}
