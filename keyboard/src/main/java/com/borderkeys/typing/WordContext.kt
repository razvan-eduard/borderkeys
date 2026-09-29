// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The two words before the word being written, nearest first; null where there is none. */
data class WordContext(val previous1: String?, val previous2: String?) {

    /** The context once [word] has been written after this one. */
    fun then(word: String): WordContext = WordContext(word, previous1)

    companion object {
        /** No word before: the start of a field, a sentence or a line. */
        val NONE = WordContext(null, null)
    }
}
