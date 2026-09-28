// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.runtime.staticCompositionLocalOf
import com.borderkeys.i18n.LanguageManager

/** The loaded catalogue, provided once at the root of the settings tree. */
val LocalStrings = staticCompositionLocalOf<LanguageManager> {
    error("LocalStrings was read outside SettingsRoot; provide it at the top of the tree")
}
