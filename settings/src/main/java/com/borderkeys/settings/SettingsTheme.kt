// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The settings UI's own palette.
 *
 * Deliberately unrelated to [com.borderkeys.data.theme.KeyboardTheme]. The keyboard's colours
 * are a user preference stored in a DataStore and compiled into Paint objects; these are the
 * chrome of an ordinary Android screen and follow the system's light/dark setting. Tying them
 * together would mean a user who picked a dark keyboard got a dark settings screen whether or
 * not their phone is in dark mode.
 *
 * No dynamic colour. It reads the wallpaper, which is one more thing this application would be
 * looking at for no reason the user asked for.
 */
private val Accent = Color(0xFF6EA8FE)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04203F),
    surface = Color(0xFF14141A),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2A2A34),
    onSurfaceVariant = Color(0xFFC6C6D0),
    background = Color(0xFF0F0F14),
    onBackground = Color(0xFFF2F2F7),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F5FBF),
    onPrimary = Color.White,
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF14141A),
    surfaceVariant = Color(0xFFE6E6EE),
    onSurfaceVariant = Color(0xFF44444E),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF14141A),
)

@Composable
fun BorderKeysSettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
