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
        isProperNoun: Boolean = false,
        maxEdits: Int = Int.MAX_VALUE,
        capitaliseNames: Boolean = true,
    ): String? {
        if (suggestion.isNullOrEmpty() || typed != suggestionQuery) {
            return null
        }
        // How far the candidate is from what was typed, once case and accents are set aside --
        // a ceiling on top of the engine's own ranking, which only ever decides *which*
        // candidate comes first, never whether it is close enough to be a correction at all. A
        // correct word the dictionaries simply do not know ("snobul") used to be replaced by
        // whatever ranked first, however far away ("noul", two edits on six letters).
        if (editDistance(stripDiacritics(typed), stripDiacritics(suggestion)) > maxEdits) {
            return null
        }
        // A name corrects only its own letters: "maria" may become "Maria" and "laurentiu"
        // "Laurențiu", but "everyone" must never become "Everton" nor "thanks" "Hanks". A
        // proper noun the dictionaries carry says nothing about what an ordinary word was meant
        // to be, and a typo two edits from somebody's name is still a typo, not that person --
        // the trade the dictionaries' own names list was never meant to make.
        if (isProperNoun && !stripDiacritics(typed).equals(stripDiacritics(suggestion), ignoreCase = true)) {
            return null
        }
        // Cased once, up front, rather than compared raw and separately case-insensitively:
        // "would this actually change anything once matchCase has had its say" is the one
        // question both of the old separate checks (exact match, and match but for case) were
        // really asking, and asking it this way is also what lets a name exactly matching what
        // was typed -- "ana" against the dictionary's own "ana" -- still become a correction
        // when isProperNoun says the only thing wrong with it is the case, instead of being
        // waved through as "identical" before matchCase ever got to capitalise it.
        // [capitaliseNames] gates only the capital, never the guard above: with the setting
        // off a name is cased like any other word, but it still may not correct anything but
        // its own letters. The two uses of the flag are separate questions, and only the first
        // is a preference -- "everyone" must not become "Everton" whatever the user chose.
        val cased = matchCase(typed, suggestion, isProperNoun && capitaliseNames)
        if (cased == typed) {
            return null
        }
        if (typed.length < minimumLength && !isDiacriticOnlyDifference(typed, suggestion)) {
            return null
        }
        // Not for a name: "the dictionaries know this exact word" is the whole reason to leave
        // an ordinary word alone, but for a name it is the opposite -- it is *why* knownWord
        // equals typed at all (the dictionary is not offering a different word, only a
        // different case for the same one), and that must not be read as "nothing to do" the
        // way it is for every word that is not a name.
        if (typed == knownWord && !(isProperNoun && capitaliseNames)) {
            return null
        }
        return cased
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

    /**
     * The edit ceiling [correctionFor] applies for a word of [typedLength] letters under the
     * user's `correctionDistance` setting (`KeyboardPreferences.CORRECTION_DISTANCE_*`, passed
     * as a plain int because `:keyboard` cannot reference `:data`'s constants):
     * strict is one edit, loose is two, and the default allows the second edit only once a
     * word is long enough (eight letters) for two slips to be likelier than a different word.
     */
    fun maxEditsFor(typedLength: Int, distanceSetting: Int): Int = when (distanceSetting) {
        DISTANCE_STRICT -> 1
        DISTANCE_LOOSE -> 2
        else -> if (typedLength >= LONG_WORD_LETTERS) 2 else 1
    }

    /**
     * Optimal string alignment distance -- Levenshtein plus a swap of two adjacent letters as a
     * single edit, since "teh" for "the" is one slip, not two. Both inputs are already folded by
     * the caller. Two short rows, allocated per call; this runs once per delimiter, never per
     * frame.
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

    /** Mirrors `KeyboardPreferences.CORRECTION_DISTANCE_STRICT/LOOSE`; `:keyboard` cannot
     *  reference `:data`'s constants directly, the same reasoning `forSetting` gives elsewhere. */
    const val DISTANCE_STRICT = 0
    const val DISTANCE_LOOSE = 2

    /** From this many letters on, the default setting allows a second edit. */
    const val LONG_WORD_LETTERS = 8

    private fun stripDiacritics(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    /**
     * Gives a correction the capitalisation of the word it replaces.
     *
     * The built-in dictionaries store lower-case spellings, so a correction from one of those
     * arrives lower case whatever was typed. The personal dictionary is not so tidy: it keeps the
     * literal spelling last committed (see `UserModel::learn`), which is capitalised whenever
     * that commit happened to be -- a genuine sentence start, a host app misreporting its caps
     * state, or a stray shift press -- and that capital survives in storage regardless of where
     * the word is typed next. Left unchecked that reads as a personal word "randomly" showing up
     * capitalised mid-sentence, so both branches below fully decide the correction's case rather
     * than only ever adding a capital never seen -- restoring one just as readily as removing one
     * a dictionary should not have offered.
     *
     * [isProperNoun] reaches here already combined with the "capitalise names" preference by
     * every caller -- see correctionFor's own [capitaliseNames]. It means the dictionary flagged
     * [correction] a name (see
     * PackedTrie::isProperNoun) -- capitalised regardless of what [typed] looked like, the one
     * override this function makes that is not about [typed] at all, because a name is not a
     * guess about which key the user meant to reach the way the rest of this function is. The
     * personal dictionary never sets this (there is no name classifier for a freshly learned
     * word), so a learned name only reads capitalised here when [typed] itself was.
     * Checked after the all-caps branch, not before: caps lock is a deliberate, stronger
     * instruction than "capitalise this one word", so "ANA" typed in full caps still shouts,
     * exactly as any other word would.
     */
    fun matchCase(typed: String, correction: String, isProperNoun: Boolean = false): String {
        if (typed.isEmpty() || correction.isEmpty()) {
            return correction
        }
        // Shouted, and more than one *letter*: two capitals are a decision, one is the start of a
        // sentence. A single "I" stays "I" rather than becoming a shout -- and so does "I'" or
        // "A-", where the second character is an apostrophe or hyphen (word characters to the
        // keyboard, but not capitals anyone chose).
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
