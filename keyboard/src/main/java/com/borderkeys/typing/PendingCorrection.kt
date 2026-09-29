// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/**
 * A correction just written, settled by the next key: backspace can take it back, and any other
 * key confirms it.
 */
data class PendingCorrection(
    val typed: String,
    val corrected: String,
    val delimiter: String,
    /** The word before it. */
    val contextWord: String?,
    /** The word before [contextWord]. */
    val grandContextWord: String?,
    /** [ComposingWord.capitalisedByUser] when [typed] was finished. */
    val deliberateCapital: Boolean,
    /** Whether confirming it learns [corrected]; false for a text shortcut's expansion. */
    val learn: Boolean = true,
    /** [typed] with where each of its letters was tapped. */
    val taps: TypedTaps? = null,
)
