// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * Everything a rendering of the keyboard needs to know, combined into one value.
 *
 * [ThemeRepository] stores [theme], [lightTheme] and [preferences] as three separate flows,
 * because they are three separate files and three separate reasons to write. Reading them is a
 * different question: anything that draws or previews the keyboard needs all three together, and
 * collecting them as three separate `collectAsStateWithLifecycle` calls at every call site is how
 * a preview ends up missing one -- `PlacementPreview` shipped without composing the layout
 * against [preferences] at all, simply because nothing forced the three to travel together. This
 * is that force: one flow ([ThemeRepository.appearance]), one parameter, one thing to remember to
 * pass.
 */
data class KeyboardAppearance(
    val theme: KeyboardTheme,
    val lightTheme: KeyboardTheme,
    val preferences: KeyboardPreferences,
) {
    companion object {
        /** A usable placeholder for the one frame before the real flow has emitted -- the same
         *  role `KeyboardTheme()` and `KeyboardPreferences()` already play alone. */
        fun defaults(): KeyboardAppearance =
            KeyboardAppearance(KeyboardTheme(), KeyboardTheme(), KeyboardPreferences())
    }
}
