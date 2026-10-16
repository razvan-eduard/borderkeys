// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** What a word of the personal dictionary looks like: a letter first, then letters, apostrophes and hyphens. */
object WordShape {
    /** The shortest word recorded or added. */
    const val MIN_LENGTH = 2

    /** The longest word recorded or added. */
    const val MAX_LENGTH = 64

    private val APOSTROPHE = '\''.code
    private val HYPHEN = '-'.code

    /** A letter, an apostrophe or a hyphen: what continues a word once a letter began it. */
    fun isWordCharacter(code: Int): Boolean = Character.isLetter(code) || code == APOSTROPHE || code == HYPHEN

    /** Whether [text] is [MIN_LENGTH] to [MAX_LENGTH] long. */
    fun isWordLength(text: String): Boolean = text.length in MIN_LENGTH..MAX_LENGTH

    /** Whether [text] is a word: of a word's length, a letter first, then word characters only. */
    fun isWord(text: String): Boolean {
        if (!isWordLength(text) || !Character.isLetter(text.codePointAt(0))) {
            return false
        }
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            if (!isWordCharacter(code)) {
                return false
            }
            index += Character.charCount(code)
        }
        return true
    }

    /** Whether [text] begins with an upper-case letter. */
    fun startsWithCapital(text: String): Boolean = text.isNotEmpty() && Character.isUpperCase(text.codePointAt(0))
}
