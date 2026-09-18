// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import java.text.Normalizer
import java.util.Locale

/**
 * One spelling for every way a word can be written: lower case, accents stripped.
 *
 * The engine keys its trie the same way (`foldCodePoint` in proximity.cpp), which is what lets
 * "masina" find "mașina". This is the Kotlin-side counterpart, for the one comparison that
 * happens outside the engine: a candidate against the words the user refused, and against the
 * offensive-word list while its switch is on. "Shit" at a sentence start, "SHIT" under caps
 * lock and "căcat" with its accents all have to meet their plain spellings there. The two folds
 * need not agree letter for letter: nothing folded here is ever compared with something folded
 * in the engine, only with other strings folded here.
 *
 * Returns its argument, the same instance, when nothing needs changing -- a lower-case word of
 * plain letters, which is nearly every candidate -- so the filter that publishes an answer pays
 * for the fold only on the rare word that needs it.
 */
object WordFold {

    fun fold(word: String): String {
        var plain = true
        for (character in word) {
            if (character !in 'a'..'z' && character != '-' && character != '\'') {
                plain = false
                break
            }
        }
        if (plain) {
            return word
        }
        val lower = word.lowercase(Locale.ROOT)
        val out = StringBuilder(lower.length)
        for (character in lower) {
            if (character in 'a'..'z' || character == '-' || character == '\'' || character in KEPT) {
                out.append(character)
                continue
            }
            // One character at a time, so a letter that stays a letter (KEPT) never has its
            // own mark stripped, only the accents that are spelling variants are.
            val decomposed = Normalizer.normalize(character.toString(), Normalizer.Form.NFD)
            for (piece in decomposed) {
                if (Character.getType(piece) != Character.NON_SPACING_MARK.toInt()) {
                    out.append(piece)
                }
            }
        }
        return out.toString()
    }

    /**
     * Letters of their own rather than accented spellings. ñ is not an n to a Spanish speaker:
     * the engine does fold it for its lookups, but the candidate it shows carries whichever
     * spelling the pack kept, and only the one written with ñ is the word a blocked-word list
     * names -- "cono", a cone, must not go with "coño". ß has no decomposition to begin with
     * and is listed so the fast path above and this one agree about it.
     */
    private const val KEPT = "ñß"
}
