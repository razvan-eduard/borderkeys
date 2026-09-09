// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.content.Context
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme

/**
 * Which of the two stored themes is showing right now.
 *
 * [KeyboardPreferences.themeMode] is the setting; this is where it is actually applied -- the
 * same shape [DynamicColors.apply] is, a pure function from what is stored to what to draw,
 * called fresh every time a theme might have changed and never written back. Composes with
 * [DynamicColors]: resolve which theme is showing first, then hand the result to
 * [DynamicColors.apply] for the wallpaper tint, the same order [KeyboardPreferences]'s own doc
 * comment on `themeMode` describes.
 */
object ThemeMode {
    fun resolve(
        theme: KeyboardTheme,
        lightTheme: KeyboardTheme,
        preferences: KeyboardPreferences,
        context: Context,
    ): KeyboardTheme {
        if (preferences.themeMode != KeyboardPreferences.THEME_MODE_AUTO_SYSTEM) {
            return theme
        }
        return if (SystemDarkMode.isDark(context)) theme else lightTheme
    }
}
