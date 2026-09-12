// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.data.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a preset has to be true of, and what the palette has to be true of.
 *
 * Presets used to be required to draw only palette colours, because a swatch row rings the entry
 * equal to the current colour and a colour missing from the row left it with no selection at
 * all -- the keyboard changed and the picker went blank. That stopped being true when the rows
 * gained the custom wheel, which is ringed exactly when the colour is not one of the swatches,
 * so a preset can now use any colour it likes and the row still shows where the colour is.
 *
 * What replaced it is the check that matters more anyway: a preset nobody can read is a preset
 * that should not ship, and ten of them is more than anyone will check by eye.
 */
class ThemePaletteTest {

    /** WCAG AA for body text, and the lower bar it sets for large or secondary text. */
    private val readable = 4.5
    private val readableSecondary = 3.0

    @Test
    fun `every preset can be read`() {
        val offences = mutableListOf<String>()
        for (preset in PRESETS) {
            val theme = preset.theme
            fun check(what: String, ink: Int, under: Int, floor: Double) {
                val ratio = contrast(ink, under)
                if (ratio < floor) {
                    offences += "${preset.nameKey}: $what is ${"%.2f".format(ratio)} to 1"
                }
            }
            check("a label on a key", theme.textColor, theme.keyColor, readable)
            check("a label on a modifier key", theme.textColor, theme.modifierKeyColor, readable)
            check("a label on a pressed key", theme.textColor, theme.keyPressedColor, readable)
            // The accent is text on the suggestion strip, which sits on the background.
            check("the accent on the background", theme.accentColor, theme.backgroundColor, readable)
            check(
                "a hint on a key", theme.secondaryTextColor, theme.keyColor, readableSecondary,
            )
        }
        assertEquals("a preset is not readable", emptyList<String>(), offences)
    }

    /** Two presets that look the same are one preset and a mistake. */
    @Test
    fun `no two presets are the same`() {
        val themes = PRESETS.map { it.theme }
        assertEquals("two presets are identical", themes.size, themes.toSet().size)
        val names = PRESETS.map { it.nameKey }
        assertEquals("two presets share a name", names.size, names.toSet().size)
    }

    /** Fifteen, as offered. A row that quietly loses one is a row nobody notices has lost one. */
    @Test
    fun `the row offers fifteen presets`() {
        assertEquals(15, PRESETS.size)
    }

    /** Every preset belongs to a category the row actually groups by, and every category shows. */
    @Test
    fun `every category has at least one preset`() {
        for (category in ThemeCategory.entries) {
            assertTrue(
                "${category.name} has no presets",
                PRESETS.any { it.category == category },
            )
        }
    }

    /** A duplicate swatch would draw two rings for one colour. */
    @Test
    fun `the palette has no duplicates`() {
        val palette = ThemePalette.COLOURS
        assertTrue("the palette repeats a colour", palette.size == palette.toSet().size)
    }

    /** Anything drawn behind a key label has to be opaque or the key shows the wallpaper. */
    @Test
    fun `every palette entry is opaque`() {
        for (colour in ThemePalette.COLOURS) {
            assertTrue("${hex(colour)} is not fully opaque", (colour ushr 24) == 0xFF)
        }
    }

    /**
     * The contrast ratio of two opaque colours, as WCAG defines it.
     *
     * Written out rather than pulled from a library: it is eight lines, and a keyboard that
     * takes no permissions is not going to add a dependency to divide two numbers.
     */
    private fun contrast(a: Int, b: Int): Double {
        val first = luminance(a)
        val second = luminance(b)
        val lighter = maxOf(first, second)
        val darker = minOf(first, second)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(colour: Int): Double {
        fun channel(value: Int): Double {
            val v = value / 255.0
            return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel((colour shr 16) and 0xFF) +
            0.7152 * channel((colour shr 8) and 0xFF) +
            0.0722 * channel(colour and 0xFF)
    }

    private fun hex(colour: Int) = "0x%08X".format(colour)
}
