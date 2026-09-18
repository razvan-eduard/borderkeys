// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.view.HapticFeedbackConstants
import com.borderkeys.data.theme.KeyboardPreferences

/**
 * The [HapticFeedbackConstants] class behind each [KeyboardPreferences.hapticStrength] level.
 *
 * Classes, not amplitudes: `performHapticFeedback` needs no permission and lets the phone
 * render each class the way its own keyboard would, where a raw amplitude would need the
 * VIBRATE permission this application does not ask for. A clock tick is the faintest a phone
 * offers, the keyboard tap is what every keyboard uses, and the long-press buzz is the firmest
 * short one.
 */
object HapticStrength {
    fun constantFor(level: Int): Int = when (level) {
        KeyboardPreferences.HAPTIC_LIGHT -> HapticFeedbackConstants.CLOCK_TICK
        KeyboardPreferences.HAPTIC_STRONG -> HapticFeedbackConstants.LONG_PRESS
        else -> HapticFeedbackConstants.KEYBOARD_TAP
    }
}
