// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Whether the word being written is part of a sentence, or a piece of something that has to
 * come out character-exact.
 *
 * Autocorrect is for sentences. To a dictionary, `example` in `user@example.com`, `main` in
 * `~/src/main.kt` and `keys` in `border_keys_2` are all ordinary misspellings with obvious
 * fixes, and every one of those fixes is wrong. They are also wrong where it hurts most: an
 * address or a filename is useless if a letter moves, and it is the last thing anyone rereads
 * before pressing send.
 *
 * What betrays such a word is never the word. This keyboard composes only letters, apostrophes
 * and hyphens (see [BorderKeysService.isWordCharacter]), so the dot, the slash and the
 * underscore were each committed a keystroke earlier and had left the composing region before
 * autocorrect was asked anything. By then `example` is indistinguishable from a word in a
 * sentence. The evidence is the character standing immediately in front of it, so that is what
 * this reads.
 *
 * Pure, and outside the service, for the reason [AutoCorrection], [AutoShift] and [HabitSpace]
 * are: the rule deserves JVM tests, and the service has an editor and a connection in the way.
 */
object RunningText {

    /**
     * Characters which, standing in front of a word, mean the word and whatever precedes them
     * are one thing -- and that thing is not a sentence.
     *
     * Two are pointedly missing. The apostrophe and the hyphen live *inside* ordinary words --
     * "don't", "aşa-zis", "l'homme" -- and refusing to correct after either would switch
     * autocorrect off across most of French and Italian. Digits qualify without being listed:
     * `v2beta` and `sha256sum` belong here by the same argument as `main.kt`.
     *
     * A full stop, a colon and a question mark are the uncomfortable ones, since each also ends
     * a sentence. They are included anyway. A word following "example." or "8080:" is far more
     * often part of an address than the opening of a new sentence, and guessing wrong in that
     * direction costs only a correction that was not offered.
     */
    private const val MARKS = "./:@#?&=%~\\_+*$|<>"

    /**
     * True when the word may be corrected.
     *
     * [characterBeforeWord] answers with the character standing in front of the word, or null
     * at the very start of a field. It is a lambda, and this is `inline`, so a caller holding
     * the answer already pays nothing, and a caller that would need an editor round trip makes
     * it only once the cheap test on the word itself has passed.
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

    /** The membership test itself, written once and shared. Public only because [admits] is
     *  `inline` and its body has to be able to reach it. */
    fun isMark(character: Char): Boolean =
        character.isDigit() || MARKS.indexOf(character) >= 0
}
