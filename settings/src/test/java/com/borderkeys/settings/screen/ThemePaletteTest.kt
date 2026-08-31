// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.theme.KeyboardTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the presets and the palette together.
 *
 * A swatch row rings the entry equal to the current colour, so a preset colour that is not in
 * the palette leaves its row with no selection at all: the keyboard changes, the picker goes
 * blank, and the user cannot tell what the current colour is or step away from it by one shade.
 * That is exactly what happened when the light preset introduced two greys of its own, and it
 * is invisible in a screenshot of the default theme.
 */
class ThemePaletteTest {

    @Test
    fun everyOpaquePresetColourIsInThePalette() {
        val presets = mapOf(
            "dark" to KeyboardTheme(),
            "light" to LIGHT_THEME,
            "high contrast" to HIGH_CONTRAST,
        )
        for ((name, theme) in presets) {
            for ((field, colour) in opaqueColoursOf(theme)) {
                assertTrue(
                    "$name preset uses $field = ${hex(colour)}, which no swatch offers",
                    PALETTE.contains(colour),
                )
            }
        }
    }

    /**
     * The swipe trail is the one colour allowed to be translucent, and its row matches on RGB
     * alone. The RGB half still has to be a palette entry or that row goes blank too.
     */
    @Test
    fun everyPresetTrailColourMatchesAPaletteEntryIgnoringAlpha() {
        val trails = mapOf(
            "dark" to KeyboardTheme().swipeTrailColor,
            "light" to LIGHT_THEME.swipeTrailColor,
            "high contrast" to HIGH_CONTRAST.swipeTrailColor,
        )
        for ((name, trail) in trails) {
            assertTrue(
                "$name preset trail ${hex(trail)} matches no swatch",
                PALETTE.any { (it and 0x00FFFFFF) == (trail and 0x00FFFFFF) },
            )
        }
    }

    /** A duplicate swatch would draw two rings for one colour. */
    @Test
    fun thePaletteHasNoDuplicates() {
        assertTrue("the palette repeats a colour", PALETTE.size == PALETTE.toSet().size)
    }

    /** Anything drawn behind a key label has to be opaque or the key shows the wallpaper. */
    @Test
    fun everyPaletteEntryIsOpaque() {
        for (colour in PALETTE) {
            assertTrue(
                "${hex(colour)} is not fully opaque",
                (colour ushr 24) == 0xFF,
            )
        }
    }

    private fun opaqueColoursOf(theme: KeyboardTheme) = listOf(
        "backgroundColor" to theme.backgroundColor,
        "keyColor" to theme.keyColor,
        "keyPressedColor" to theme.keyPressedColor,
        "modifierKeyColor" to theme.modifierKeyColor,
        "textColor" to theme.textColor,
        "secondaryTextColor" to theme.secondaryTextColor,
        "accentColor" to theme.accentColor,
    )

    private fun hex(colour: Int) = "0x%08X".format(colour)
}
