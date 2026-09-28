// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/** One theme someone saved, under a name they chose. */
@Serializable
data class CustomThemeEntry(
    val id: String,
    val name: String,
    val theme: KeyboardTheme,
    val createdAt: Long = 0L,
) {
    /** Clamps every field; applied on read. */
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
    /** Clamped to [MAX_CUSTOM_THEMES] on read as well as on write. */
    fun sanitised(): CustomThemeLibrary = copy(
        themes = themes.map { it.sanitised() }.take(MAX_CUSTOM_THEMES),
    )

    companion object {
        /** The most saved themes kept. */
        const val MAX_CUSTOM_THEMES = 50
    }
}
