// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.view.HapticFeedbackConstants
import com.borderkeys.data.theme.KeyboardPreferences

/** The [HapticFeedbackConstants] class behind each [KeyboardPreferences.hapticStrength] level. */
object HapticStrength {
    fun constantFor(level: Int): Int = when (level) {
        KeyboardPreferences.HAPTIC_LIGHT -> HapticFeedbackConstants.CLOCK_TICK
        KeyboardPreferences.HAPTIC_MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
        KeyboardPreferences.HAPTIC_STRONG -> HapticFeedbackConstants.LONG_PRESS
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    }
}
