// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Whether a delimiter should replace what was typed, and with what.
 *
 * Its own object rather than two methods on the service because this is the decision the whole
 * feature is: a keyboard that rewrites correct words is the failure this project was written
 * against, and the rules for not doing that are worth being able to test without an editor, an
 * input connection and a dictionary.
 */
internal object AutoCorrection {

    /**
     * The correction to apply, or null to commit what was typed.
     *
     * Null in every case where applying one would be an argument rather than a correction:
     *
     *  - the word is shorter than [minimumLength], where every guess is a coin toss;
     *  - the dictionaries spell the word, so it is a word, and a keyboard does not correct
     *    words -- the engine ranks by likelihood, so a real but uncommon word loses to a longer
     *    common one and was being replaced by it;
     *  - the suggestion is what was typed;
     *  - the suggestion is what was typed in a different case, which changes nothing but the
     *    capital the user chose.
     *
     * Otherwise the correction, carrying the capitalisation of the word it replaces.
     */
    fun correctionFor(
        typed: String,
        suggestion: String?,
        knownWord: String,
        minimumLength: Int,
    ): String? {
        if (typed.length < minimumLength) {
            return null
        }
        if (typed == knownWord) {
            return null
        }
        if (suggestion.isNullOrEmpty() || suggestion == typed) {
            return null
        }
        if (suggestion.equals(typed, ignoreCase = true)) {
            return null
        }
        return matchCase(typed, suggestion)
    }

    /**
     * Gives a correction the capitalisation of the word it replaces.
     *
     * The dictionaries store lower-case spellings, so a correction arrives lower case whatever
     * was typed. Committing it as it comes turns the first word of a sentence into a lower-case
     * one, which is a second thing to fix for every one thing that was fixed.
     */
    fun matchCase(typed: String, correction: String): String {
        if (typed.isEmpty() || correction.isEmpty()) {
            return correction
        }
        // Shouted, and more than one letter: two capitals are a decision, one is the start of a
        // sentence. A single "I" stays "I" rather than becoming a shout.
        if (typed.length > 1 && typed.any { it.isLetter() } && typed.none { it.isLowerCase() }) {
            return correction.uppercase()
        }
        if (!typed[0].isUpperCase() || correction[0].isUpperCase()) {
            return correction
        }
        return correction.replaceFirstChar { it.uppercaseChar() }
    }
}
