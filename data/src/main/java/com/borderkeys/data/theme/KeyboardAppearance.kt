// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * Everything a rendering of the keyboard needs, as one value, from one flow
 * ([ThemeRepository.appearance]).
 */
data class KeyboardAppearance(
    val theme: KeyboardTheme,
    val lightTheme: KeyboardTheme,
    val preferences: KeyboardPreferences,
    val particleEffects: ParticleEffectsSettings,
) {
    companion object {
        /** The defaults, for the frame before the flow has emitted. */
        fun defaults(): KeyboardAppearance =
            KeyboardAppearance(KeyboardTheme(), KeyboardTheme(), KeyboardPreferences(), ParticleEffectsSettings())
    }
}
