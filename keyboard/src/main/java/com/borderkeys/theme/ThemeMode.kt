// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.content.Context
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme

/** Which of the two stored themes is showing, by [KeyboardPreferences.themeMode]. */
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

    /** [resolve], then [DynamicColors.apply] when the preference for it is on. */
    fun effective(
        theme: KeyboardTheme,
        lightTheme: KeyboardTheme,
        preferences: KeyboardPreferences,
        context: Context,
    ): KeyboardTheme {
        val resolved = resolve(theme, lightTheme, preferences, context)
        return if (preferences.followSystemColors) DynamicColors.apply(resolved, context) else resolved
    }
}
