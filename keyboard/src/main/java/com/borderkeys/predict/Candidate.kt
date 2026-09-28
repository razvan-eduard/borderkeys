// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/** One word the engine offers, with its flags. */
data class Candidate(
    val text: String,

    /** A name from the dictionary, always shown capitalised. */
    val isProperNoun: Boolean = false,

    /**
     * This candidate's share of a swipe decode, per mille; the shares of one result sum to 1000.
     * Zero for typed suggestions.
     */
    val share: Float = 0f,

    /**
     * The word autocorrect would put in place of what was typed. At most one candidate in a
     * result carries it.
     */
    val isCorrection: Boolean = false,
)
