// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.TextShortcut

/**
 * Matches the word just typed against the user's [TextShortcut]s, on text alone.
 *
 * The match ignores case and the typed word's case is carried onto the expansion the way a
 * correction's would be: "omw" gives the expansion as written, "Omw" capitalises its first
 * letter, "OMW" shouts it -- so a shortcut at the start of a sentence needs no second entry.
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
