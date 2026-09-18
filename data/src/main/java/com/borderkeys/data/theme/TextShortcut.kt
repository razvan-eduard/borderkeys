// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A word that stands for a longer text: type [trigger], then a space or a punctuation mark, and
 * [expansion] takes its place -- "omw" for "on my way", an address, a signature.
 *
 * In the preferences rather than the database, for the same reason [CustomAction] is: a few
 * dozen short strings, a setting rather than data, backed up and restored with the rest of
 * the preferences. Matched against the word just typed, case-insensitively, by
 * `TextShortcuts` in the keyboard module, which is also where the typed word's own case is
 * carried over onto the expansion.
 */
@Serializable
data class TextShortcut(
    val trigger: String,
    val expansion: String,
) {
    companion object {
        /** A trigger is one word, typed in full before the delimiter that expands it. */
        const val MAX_TRIGGER_CHARS = 24

        /** Long enough for an address or a sign-off, short enough that a corrupt file cannot
         *  turn one shortcut into a paragraph. */
        const val MAX_EXPANSION_CHARS = 200

        /** As many as anyone will remember the triggers of. */
        const val MAX_SHORTCUTS = 100

        /** One word, no whitespace: the keyboard only ever matches a trigger against the single
         *  word just typed, so a trigger with a space in it could never fire. */
        fun isValidTrigger(trigger: String): Boolean =
            trigger.isNotEmpty() && trigger.length <= MAX_TRIGGER_CHARS && trigger.none { it.isWhitespace() }
    }
}
