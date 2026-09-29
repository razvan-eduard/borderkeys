// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** One field, from the moment it starts until the next one does. */
data class FieldSession(
    /** Counts the fields started; an answer about an older field is dropped. */
    val generation: Int,
    val policy: FieldPolicy,
    /** The field holds an e-mail address or a URI. */
    val addressField: Boolean,
    /** The field is a terminal: commits show at once and deletions go as key events. */
    val terminalField: Boolean,
) {
    companion object {
        /** Before any field has started. */
        val NONE = FieldSession(
            generation = 0,
            policy = FieldPolicy.NONE,
            addressField = false,
            terminalField = false,
        )
    }
}
