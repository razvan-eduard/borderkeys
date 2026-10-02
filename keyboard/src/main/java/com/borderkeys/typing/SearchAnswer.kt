// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.predict.CorrectionOffer

/** The engine's last answer about the word being written, as the commit decision reads it. */
data class SearchAnswer(
    /** The word the answer is about. */
    val query: String,
    /** [query] when the dictionaries spell it, else empty. */
    val knownWord: String,
    /** Whether a dictionary spells [query] exactly, case aside. */
    val knownWordExact: Boolean,
    /** Whether that exact spelling is a name. */
    val knownWordIsName: Boolean,
    /** Autocorrect's list for [query], best first. */
    val corrections: List<CorrectionOffer>,
    /** The possessive of a name missing its apostrophe, or null. */
    val possessive: String?,
) {
    companion object {
        /** No answer yet. */
        val NONE = SearchAnswer(
            query = "",
            knownWord = "",
            knownWordExact = false,
            knownWordIsName = false,
            corrections = emptyList(),
            possessive = null,
        )
    }
}
