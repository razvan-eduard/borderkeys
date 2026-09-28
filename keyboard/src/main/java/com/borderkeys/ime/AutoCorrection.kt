// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

/** Whether a delimiter should replace what was typed, and with what. */
internal object AutoCorrection {

    /**
     * What a word and the answer offered for it amount to. Exactly one holds, and only
     * [Correctable] replaces anything.
     */
    enum class Situation {
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

        /** None of the above. */
        Correctable,
    }

    /**
     * Which [Situation] this is, checked in order. [cased] is the answer after [matchCase];
     * [inflection] is [WordStems.shields]'s answer for [typed] against [suggestion].
     */
    fun situationOf(
        typed: String,
        suggestion: String?,
        suggestionQuery: String,
        knownWord: String,
        cased: String,
        minimumLength: Int,
        isProperNoun: Boolean = false,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
        inflection: Boolean = false,
    ): Situation = when {
        suggestion.isNullOrEmpty() -> Situation.NothingOffered
        typed != suggestionQuery -> Situation.StaleAnswer
        typed.isNotEmpty() && typed.last() in TRAILING_MARKS -> Situation.TrailingMark
        editDistance(stripDiacritics(typed), stripDiacritics(suggestion)) > maxEdits ->
            Situation.TooFar
        isProperNoun &&
            !stripDiacritics(typed).equals(stripDiacritics(suggestion), ignoreCase = true) ->
            Situation.NameMismatch
        cased == typed -> Situation.NoChange
        typed.length < minimumLength &&
            !(typed.length >= MIN_DIACRITIC_LENGTH && isDiacriticOnlyDifference(typed, suggestion)) ->
            Situation.TooShort
        typed == knownWord && !(isProperNoun && capitaliseNames) -> Situation.KnownWord
        inflection && !isSameLetters(typed, suggestion) -> Situation.Inflection
        else -> Situation.Correctable
    }

    /** The correction to apply, cased like [typed], or null to commit what was typed. */
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
    ): String? {
        // [capitaliseNames] gates only a name's capital, not [Situation.NameMismatch].
        val cased = matchCase(typed, suggestion.orEmpty(), isProperNoun && capitaliseNames)
        val situation = situationOf(typed, suggestion, suggestionQuery, knownWord, cased,
                                    minimumLength, isProperNoun, maxEdits, capitaliseNames,
                                    inflection)
        return if (situation == Situation.Correctable) cased else null
    }

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
