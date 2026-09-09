// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.content.Context
import android.content.res.Configuration

/**
 * Whether the phone is currently in dark mode.
 *
 * One fact, read one way, shared by [DynamicColors] (which tonal stops to read) and [ThemeMode]
 * (which of the two stored themes to show) rather than each computing it themselves and risking
 * disagreeing about what "dark" means on some Android version neither was tested against.
 */
object SystemDarkMode {
    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
