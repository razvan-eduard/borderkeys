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
 * The settings UI's own palette, independent of [com.borderkeys.data.theme.KeyboardTheme]; it
 * follows the system's light or dark setting. No dynamic colour.
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
