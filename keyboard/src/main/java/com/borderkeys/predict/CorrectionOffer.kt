// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import com.borderkeys.ime.WordStems

/** One entry of autocorrect's list for a typed word, in the engine's own case. */
data class CorrectionOffer(
    val text: String,
    /** Whether [text] is a name. */
    val isName: Boolean,
    /** [WordStems.shields]'s answer for the typed word against [text]. */
    val inflection: Boolean,
    /**
     * Whether the engine reached [text] by neighbouring keys in place of typed ones alone: no
     * letter added, dropped, swapped or run on.
     */
    val slipsOnly: Boolean = false,
)
