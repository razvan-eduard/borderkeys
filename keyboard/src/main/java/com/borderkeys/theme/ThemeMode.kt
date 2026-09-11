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

    /**
     * [resolve], with [DynamicColors.apply]'s wallpaper tint composed on top when the preference
     * for it is on -- the two steps every settings preview that draws a real keyboard needs,
     * always in this order and always together, so it was three call sites re-deriving the same
     * pair rather than one of them differing on purpose.
     */
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
