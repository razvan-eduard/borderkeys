// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The engine's last answer about the word being written, as the commit decision reads it. */
data class SearchAnswer(
    /** The word the answer is about. */
    val query: String,
    /** [query] when the dictionaries spell it, else empty. */
    val knownWord: String,
    /** Autocorrect's answer, or null. */
    val correction: String?,
    /** Whether [correction] is a name. */
    val correctionIsName: Boolean,
    /** The possessive of a name missing its apostrophe, or null. */
    val possessive: String?,
    /** Whether [query] is a regular inflection of a known word that [correction] is not built on. */
    val inflection: Boolean,
) {
    companion object {
        /** No answer yet. */
        val NONE = SearchAnswer(
            query = "",
            knownWord = "",
            correction = null,
            correctionIsName = false,
            possessive = null,
            inflection = false,
        )
    }
}
