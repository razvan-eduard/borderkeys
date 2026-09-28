// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.borderkeys.data.theme.KeyboardTheme

/**
 * The keyboard's theme recoloured from the system's wallpaper palette, read from the
 * `android.R.color.system_accent1_*`/`system_neutral1_*`/`system_neutral2_*` resources.
 * Android 12 and later.
 */
object DynamicColors {

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * [theme] with every colour replaced by the system palette's, or [theme] unchanged when any
     * of them cannot be read. Shape and sizing are untouched.
     */
    fun apply(theme: KeyboardTheme, context: Context): KeyboardTheme {
        if (!available) {
            return theme
        }
        val stops = if (SystemDarkMode.isDark(context)) DARK_STOPS else LIGHT_STOPS
        val background = colorOf(context, stops.background) ?: return theme
        val key = colorOf(context, stops.key) ?: return theme
        val keyPressed = colorOf(context, stops.keyPressed) ?: return theme
        val modifierKey = colorOf(context, stops.modifierKey) ?: return theme
        val text = colorOf(context, stops.text) ?: return theme
        val secondaryText = colorOf(context, stops.secondaryText) ?: return theme
        val accent = colorOf(context, stops.accent) ?: return theme
        return theme.copy(
            backgroundColor = background,
            keyColor = key,
            keyPressedColor = keyPressed,
            modifierKeyColor = modifierKey,
            textColor = text,
            secondaryTextColor = secondaryText,
            accentColor = accent,
            // The accent at the theme trail's alpha.
            swipeTrailColor = (accent and 0x00FFFFFF) or (theme.swipeTrailColor and 0xFF000000.toInt()),
        )
    }

    private fun colorOf(context: Context, resId: Int): Int? =
        runCatching { ContextCompat.getColor(context, resId) }.getOrNull()

    /** Which tonal stop fills which role, one set per UI mode. */
    private class Stops(
        val background: Int,
        val key: Int,
        val keyPressed: Int,
        val modifierKey: Int,
        val text: Int,
        val secondaryText: Int,
        val accent: Int,
    )

    private val DARK_STOPS = Stops(
        background = android.R.color.system_neutral1_900,
        key = android.R.color.system_neutral2_700,
        keyPressed = android.R.color.system_neutral2_600,
        modifierKey = android.R.color.system_neutral1_800,
        text = android.R.color.system_neutral1_50,
        secondaryText = android.R.color.system_neutral2_300,
        accent = android.R.color.system_accent1_200,
    )

    private val LIGHT_STOPS = Stops(
        background = android.R.color.system_neutral1_50,
        key = android.R.color.system_neutral1_100,
        keyPressed = android.R.color.system_neutral1_200,
        modifierKey = android.R.color.system_neutral2_50,
        text = android.R.color.system_neutral1_900,
        secondaryText = android.R.color.system_neutral2_600,
        accent = android.R.color.system_accent1_600,
    )
}
