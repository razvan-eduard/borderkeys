// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The sixteen colours of the palette: the swatches `:settings`' `ColourRow` draws, the source of
 * [KeyboardTheme.patternColor]'s default, and of every `ThemeScreen` preset colour
 * (`ThemePaletteTest` checks).
 */
object ThemePalette {
    val COLOURS: List<Int> = listOf(
        // Colours first.
        0xFF6EA8FE.toInt(), 0xFF3B82F6.toInt(), 0xFF1D4ED8.toInt(), 0xFF14B8A6.toInt(),
        0xFF10B981.toInt(), 0xFF4ADE80.toInt(), 0xFFF59E0B.toInt(), 0xFFFF8A4C.toInt(),
        0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFFA855F7.toInt(), 0xFF7AA2F7.toInt(),
        // Then four neutrals: black, two greys, white.
        0xFF000000.toInt(), 0xFF2A2A34.toInt(), 0xFFC6C6D0.toInt(), 0xFFFFFFFF.toInt(),
    )

    /** How many colours [KeyboardTheme.customColours] keeps per field; the oldest go first. */
    const val MAX_CUSTOM_COLOURS_PER_FIELD = 24

    private const val RGB_MASK = 0x00FFFFFF

    /** [colours]' index of the entry matching [target], or -1; RGB only when [preserveAlpha]. */
    fun indexOf(colours: List<Int>, target: Int, preserveAlpha: Boolean = false): Int =
        colours.indexOfFirst { entry ->
            if (preserveAlpha) (entry and RGB_MASK) == (target and RGB_MASK) else entry == target
        }

    fun contains(colours: List<Int>, target: Int, preserveAlpha: Boolean = false): Boolean =
        indexOf(colours, target, preserveAlpha) >= 0

    /** Where a newly picked colour goes: the end of the row. */
    fun appendIndex(colours: List<Int>): Int = colours.size

    /**
     * [colours] with [colour] inserted at [index], unchanged when [COLOURS] or [colours] already
     * hold it. Past [MAX_CUSTOM_COLOURS_PER_FIELD], the front is dropped.
     */
    fun inserted(colours: List<Int>, colour: Int, index: Int, preserveAlpha: Boolean = false): List<Int> {
        if (contains(COLOURS, colour, preserveAlpha) || contains(colours, colour, preserveAlpha)) {
            return colours
        }
        val updated = colours.toMutableList().apply { add(index.coerceIn(0, size), colour) }
        return if (updated.size > MAX_CUSTOM_COLOURS_PER_FIELD) {
            updated.subList(updated.size - MAX_CUSTOM_COLOURS_PER_FIELD, updated.size)
        } else {
            updated
        }
    }

    /** [colours] with the entry at [index] removed. */
    fun removedAt(colours: List<Int>, index: Int): List<Int> =
        colours.toMutableList().apply { removeAt(index) }
}
