// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.borderkeys.i18n.LanguageManager

/**
 * The loaded catalogue, provided once at the root of the settings tree so that no screen has to
 * be handed one through every composable above it.
 *
 * `static` rather than `compositionLocalOf`: the manager is replaced only when the language
 * changes, and when it does every screen has to recompose anyway, so tracking reads individually
 * would cost on every recomposition to save nothing.
 */
val LocalStrings = staticCompositionLocalOf<LanguageManager> {
    error("LocalStrings was read outside SettingsRoot; provide it at the top of the tree")
}
