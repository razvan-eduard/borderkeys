// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A word that stands for a longer text: type [trigger], then a space or a punctuation mark, and
 * [expansion] takes its place -- "omw" for "on my way", an address, a signature.
 * Stored in the preferences; matched case-insensitively by `TextShortcuts` in the keyboard module.
 */
@Serializable
data class TextShortcut(
    val trigger: String,
    val expansion: String,
) {
    companion object {
        /** A trigger is one word, typed in full before the delimiter that expands it. */
        const val MAX_TRIGGER_CHARS = 24

        /** The longest expansion accepted. */
        const val MAX_EXPANSION_CHARS = 200

        /** As many as anyone will remember the triggers of. */
        const val MAX_SHORTCUTS = 100

        /** Whether [trigger] is one word, with no whitespace. */
        fun isValidTrigger(trigger: String): Boolean =
            trigger.isNotEmpty() && trigger.length <= MAX_TRIGGER_CHARS && trigger.none { it.isWhitespace() }
    }
}
