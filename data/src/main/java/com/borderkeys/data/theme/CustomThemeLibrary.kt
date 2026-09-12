// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * One theme someone saved themselves, under a name they chose.
 *
 * A preset is a starting point picked by the application; this is the same idea for a theme
 * nobody but the user has ever seen -- built by hand in the colour rows below, then kept, so it
 * does not have to be rebuilt from scratch the next time they want it back.
 */
@Serializable
data class CustomThemeEntry(
    val id: String,
    val name: String,
    val theme: KeyboardTheme,
    val createdAt: Long = 0L,
) {
    /** Read-time repair, the same reasoning as [KeyboardTheme.sanitised]: a stored entry is not
     *  a trusted entry, whether it came from this device's own DataStore or an imported file. */
    fun sanitised(): CustomThemeEntry = copy(
        name = name.take(MAX_NAME_LENGTH),
        theme = theme.sanitised(),
    )

    companion object {
        /** Long enough for a real name, short enough that a card never has to wrap it. */
        const val MAX_NAME_LENGTH = 40
    }
}

/** The whole saved collection, as one DataStore file. */
@Serializable
data class CustomThemeLibrary(val themes: List<CustomThemeEntry> = emptyList()) {
    /**
     * Clamped to [MAX_CUSTOM_THEMES] on read as well as on write: the write path already refuses
     * to grow the list past the limit, but a file edited by hand or restored from a future build
     * with a higher limit is not a file that has already been through that check.
     */
    fun sanitised(): CustomThemeLibrary = copy(
        themes = themes.map { it.sanitised() }.take(MAX_CUSTOM_THEMES),
    )

    companion object {
        /** High enough that nobody building a real collection of looks hits it, low enough that
         *  a corrupted or hostile file cannot make this device hold an unbounded list of them. */
        const val MAX_CUSTOM_THEMES = 50
    }
}
