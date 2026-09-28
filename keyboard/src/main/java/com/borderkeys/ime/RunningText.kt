// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Whether the word being written is part of a sentence, read from the character in front of it:
 * a word right after one of [MARKS], or after a digit, is not.
 */
object RunningText {

    /** Characters that, in front of a word, make it part of something other than a sentence. */
    private const val MARKS = "./:@#?&=%~\\_+*$|<>"

    /**
     * True when the word may be corrected. [characterBeforeWord] gives the character in front of
     * the word, null at the start of a field; it is asked only once the word itself passes.
     */
    inline fun admits(word: CharSequence, characterBeforeWord: () -> Char?): Boolean {
        for (element in word) {
            if (element.isDigit()) {
                return false
            }
        }
        val before = characterBeforeWord() ?: return true
        return !isMark(before)
    }

    /** Whether [character] is one of [MARKS]; public for the inline [admits]. */
    fun isMark(character: Char): Boolean =
        character.isDigit() || MARKS.indexOf(character) >= 0
}
