// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import java.text.Normalizer

/**
 * The stems a word would have if it were a regular inflection, per language.
 *
 * A stem is what is left after one listed ending comes off, in every spelling the ending
 * attaches to: the bare stem, the stem with a doubled final consonant undone, and -- before an
 * ending that begins with a vowel -- the stem with its final e restored. Words are folded to
 * lower case without accents first, the way the dictionaries key them. A stem shorter than
 * [MIN_STEM_LETTERS] is not returned, and an ending whose first letter only repeats the stem's
 * last letter is a doubled letter, not an ending.
 *
 * Pure. The engine answers which stems it holds; [shields] then says whether the correction
 * offered for the word is built on one of them.
 */
internal object WordStems {

    /** The shortest stem worth looking up. */
    const val MIN_STEM_LETTERS = 4

    /** An ending and what replaces it: "ies" comes off as "y". [restoresE] is whether the
     *  ending may have taken a final e off its stem: "hiking" from "hike". */
    private class Ending(
        val suffix: String,
        val replacement: String = "",
        val restoresE: Boolean = false,
    )

    private val ENGLISH = listOf(
        Ending("s"), Ending("es"), Ending("ies", "y"),
        Ending("ed", restoresE = true), Ending("ied", "y"),
        Ending("ing", restoresE = true),
        Ending("er", restoresE = true), Ending("ier", "y"),
        Ending("est", restoresE = true), Ending("iest", "y"),
        Ending("ly"), Ending("ily", "y"), Ending("ally"),
        Ending("y"),
        Ending("en"),
        Ending("ness"), Ending("iness", "y"),
        Ending("ment"),
    )

    /** Plural, case and article endings. */
    private val ROMANIAN = listOf(
        Ending("i"), Ending("e"), Ending("a"), Ending("u"),
        Ending("ul"), Ending("ului"), Ending("lui"),
        Ending("le"), Ending("lor"), Ending("ilor"), Ending("elor"),
        Ending("ii"), Ending("ele"), Ending("ile"), Ending("iile"), Ending("iilor"),
        Ending("uri"), Ending("urile"), Ending("urilor"),
        Ending("ei"), Ending("ea"),
    )

    private const val VOWELS = "aeiou"

    private fun endingsFor(languageTag: String): List<Ending> =
        when (languageTag.substringBefore('-').lowercase()) {
            "en" -> ENGLISH
            "ro" -> ROMANIAN
            else -> emptyList()
        }

    /** Every stem [word] has under the endings of [languages], folded. Empty when it has none. */
    fun candidates(word: String, languages: Collection<String>): Set<String> =
        stemsOf(word, languages, MIN_STEM_LETTERS, everyE = false)

    /**
     * Whether [typed], whose known stems are [knownStems], is shielded from [suggestion]: the
     * suggestion is one of those stems, or is not built on any of them. False when the word has
     * no known stem, false when the suggestion carries the typed letters on, and false when the
     * suggestion is another inflection of the same stem -- for which every spelling of every
     * ending is tried, however short the stem.
     */
    fun shields(
        typed: String,
        suggestion: String,
        knownStems: Set<String>,
        languages: Collection<String>,
    ): Boolean {
        if (knownStems.isEmpty()) {
            return false
        }
        val folded = fold(suggestion)
        if (folded in knownStems) {
            return true
        }
        if (folded.startsWith(fold(typed))) {
            return false
        }
        return stemsOf(suggestion, languages, minimum = 2, everyE = true).none { it in knownStems }
    }

    private fun stemsOf(
        word: String,
        languages: Collection<String>,
        minimum: Int,
        everyE: Boolean,
    ): Set<String> {
        val folded = fold(word)
        if (folded.length <= minimum || folded.any { !it.isLetter() }) {
            return emptySet()
        }
        val stems = LinkedHashSet<String>()
        for (language in languages) {
            for (ending in endingsFor(language)) {
                if (!folded.endsWith(ending.suffix)) {
                    continue
                }
                val bare = folded.dropLast(ending.suffix.length)
                if (bare.isEmpty()) {
                    continue
                }
                if (ending.replacement.isEmpty() && bare.last() == ending.suffix.first()) {
                    continue
                }
                val stem = bare + ending.replacement
                if (stem.length >= minimum) {
                    stems += stem
                }
                if (ending.replacement.isNotEmpty()) {
                    continue
                }
                val last = bare.last()
                if ((everyE || ending.restoresE) && last != 'e' && bare.length + 1 >= minimum) {
                    stems += bare + "e"
                }
                if (bare.length - 1 >= minimum && bare[bare.length - 2] == last &&
                    last !in VOWELS
                ) {
                    stems += bare.dropLast(1)
                }
            }
        }
        return stems
    }

    private fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD)
            .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            .lowercase()
}
