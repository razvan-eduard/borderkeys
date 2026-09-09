// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

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
     *  - the word is shorter than [minimumLength] -- unless the only difference from what was
     *    typed is a diacritic, which is not a guess at what the user meant, only at which key
     *    they didn't reach for. "in" reaching "în" is exactly this: two real, unrelated words
     *    that happen to be the same letters without their accents, not a coin toss;
     *  - the dictionaries spell the word, so it is a word, and a keyboard does not correct
     *    words -- the engine ranks by likelihood, so a real but uncommon word loses to a longer
     *    common one and was being replaced by it;
     *  - the suggestion is what was typed;
     *  - the suggestion is what was typed in a different case, which changes nothing but the
     *    capital the user chose;
     *  - [suggestionQuery] -- the word the engine's answer is actually about -- is not [typed].
     *    The engine has one thread and answers by posting back rather than blocking, so a
     *    delimiter can be typed before the answer for the word just finished has arrived at all.
     *    [suggestion] would then still be whatever an earlier, unrelated word last resolved to,
     *    and applying it would correct the word being committed to a word never asked about --
     *    not a bad ranking, an answer to a different question. "tinde" reaching "idependent" is
     *    this: no edit-distance budget this engine uses reaches "idependent" from "tinde", so it
     *    was never the engine's answer for "tinde" to begin with.
     *
     * Otherwise the correction, carrying the capitalisation of the word it replaces.
     */
    fun correctionFor(
        typed: String,
        suggestion: String?,
        suggestionQuery: String,
        knownWord: String,
        minimumLength: Int,
    ): String? {
        if (suggestion.isNullOrEmpty() || suggestion == typed || typed != suggestionQuery) {
            return null
        }
        if (suggestion.equals(typed, ignoreCase = true)) {
            return null
        }
        if (typed.length < minimumLength && !isDiacriticOnlyDifference(typed, suggestion)) {
            return null
        }
        if (typed == knownWord) {
            return null
        }
        return matchCase(typed, suggestion)
    }

    /**
     * Whether [typed] and [suggestion] are the same letters, differing only in accents and
     * case -- "in"/"în", "sa"/"să". This is a separate, looser fold than the dictionary's own
     * (`foldCodePoint` in proximity.cpp), which stays the single source of truth for what the
     * engine considers the same word. This one only has to tell "restoring an accent" apart from
     * "guessing a different word" for the [minimumLength] gate above, so Unicode's own canonical
     * decomposition is enough -- it does not need to agree with the native fold character for
     * character the way the Kotlin/C++ pair documented there does.
     */
    private fun isDiacriticOnlyDifference(typed: String, suggestion: String): Boolean =
        stripDiacritics(typed) == stripDiacritics(suggestion)

    private fun stripDiacritics(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()

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
