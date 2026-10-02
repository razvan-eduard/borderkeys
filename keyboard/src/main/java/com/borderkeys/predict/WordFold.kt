// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import java.text.Normalizer
import java.util.Locale

/** A word in lower case with its accents stripped. */
object WordFold {

    /** [word] folded; the same instance when it is already plain lower-case letters. */
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
            val decomposed = Normalizer.normalize(character.toString(), Normalizer.Form.NFD)
            for (piece in decomposed) {
                if (Character.getType(piece) != Character.NON_SPACING_MARK.toInt()) {
                    out.append(piece)
                }
            }
        }
        return out.toString()
    }

    /** Letters kept as they are rather than folded. */
    private const val KEPT = "ñß"
}
