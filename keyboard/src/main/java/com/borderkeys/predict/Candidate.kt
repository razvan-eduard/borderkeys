// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * One word the engine offers, with everything a caller needs to rank, apply or draw it.
 *
 * The result callbacks used to carry a word array beside a `BooleanArray` of proper-noun bits
 * and a `FloatArray` of decode shares, each read at the same index. Every producer had to filter
 * and compact all three in lockstep, and nothing checked that they still lined up: a word wearing
 * the flags of a word two places along is the bug that shape invites, and it is silent. One
 * object per word makes it unrepresentable.
 *
 * Immutable and allocated per result rather than pooled. A result is assembled at human speed --
 * once per keystroke or once per swipe -- and never on the drawing path, which reads
 * [com.borderkeys.ime.SuggestionStripView]'s own char arrays and never touches this.
 */
data class Candidate(
    val text: String,

    /**
     * A name from the dictionary, which renders capitalised whatever was typed and whatever the
     * shift state says -- see `NativePredictor.nativeSuggest` for where the bit comes from.
     */
    val isProperNoun: Boolean = false,

    /**
     * This candidate's share of a decode, per mille, the softmax `Engine::normaliseGestureScores`
     * leaves behind: the shares of one result sum to 1000. Rank one holding more than half of it
     * is a swipe with nothing left to ask about.
     *
     * Zero for typed suggestions, which are ranked but never normalised into a distribution.
     */
    val share: Float = 0f,
)
