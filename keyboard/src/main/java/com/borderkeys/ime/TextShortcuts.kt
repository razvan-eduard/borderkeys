// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut

/**
 * Matches the word just typed against the user's [TextShortcut]s ignoring case, and carries the
 * typed word's case onto the expansion as a correction's would be.
 */
object TextShortcuts {

    /** The expansion for [typed], cased after it, or null when no trigger matches. */
    fun expansionFor(typed: String, shortcuts: List<TextShortcut>): String? {
        if (typed.isEmpty() || shortcuts.isEmpty()) {
            return null
        }
        val shortcut = shortcuts.firstOrNull { it.trigger.equals(typed, ignoreCase = true) } ?: return null
        val expansion = shortcut.expansion
        val letters = typed.filter { it.isLetter() }
        return when {
            letters.length > 1 && letters.all { it.isUpperCase() } -> expansion.uppercase()
            typed.first().isUpperCase() -> expansion.replaceFirstChar { it.titlecase() }
            else -> expansion
        }
    }
}
