// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The sixteen colours BorderKeys treats as its palette, in one place.
 *
 * Two things read this list, in the two different senses of "one place" that matter here:
 *
 *  - `:settings`' own colour-picker row (`ColourRow`) draws these as the circles someone taps,
 *    and rings whichever one equals the field being edited.
 *  - [KeyboardTheme]'s own defaults -- [KeyboardTheme.patternColor] specifically -- pick a
 *    starting colour *from* this list, on purpose, so the picker above shows something ringed
 *    the first time anyone opens it rather than the "custom" wheel for a colour nobody chose.
 *
 * That second use is the reason this lives in `:data` and not in `:settings`, where a swatch
 * list would otherwise obviously belong: [KeyboardTheme]'s default has to be *computed from*
 * this list, not merely equal to one entry of it by two people typing the same hex twice and
 * promising each other not to let it drift -- and `:data` is the only direction a value both
 * `:data` and `:settings` can see is allowed to live in, since `:settings` depends on `:data`
 * and never the reverse.
 *
 * Every colour used by any preset in `ThemeScreen` (`:settings`) appears here too. That is a
 * requirement, not a coincidence: a swatch row draws a ring around the entry that matches the
 * current value, so a preset colour missing from the ramp left the row with nothing selected and
 * the user with no idea what the current colour was -- true before the row grew the custom
 * wheel, which rings whenever the colour is not a swatch, so it no longer strictly has to hold,
 * but every preset still draws from here regardless. `ThemePaletteTest`, in `:settings`, holds
 * the two lists together.
 */
object ThemePalette {
    val COLOURS: List<Int> = listOf(
        // Colours first. The row scrolls, and twelve greys before the first colour meant
        // scrolling past most of it to reach anything that was not grey -- while the greys
        // themselves were eight shades nobody could tell apart at thirty density-independent
        // pixels.
        0xFF6EA8FE.toInt(), 0xFF3B82F6.toInt(), 0xFF1D4ED8.toInt(), 0xFF14B8A6.toInt(),
        0xFF10B981.toInt(), 0xFF4ADE80.toInt(), 0xFFF59E0B.toInt(), 0xFFFF8A4C.toInt(),
        0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFFA855F7.toInt(), 0xFF7AA2F7.toInt(),
        // Then the neutrals, and only four: black, white, and one grey at each end of the
        // middle. Anything between them is a job for the wheel at the end of the row.
        0xFF000000.toInt(), 0xFF2A2A34.toInt(), 0xFFC6C6D0.toInt(), 0xFFFFFFFF.toInt(),
    )

    /** How many colours [KeyboardTheme.customColours] keeps per field before the oldest picks
     *  are dropped -- the row already scrolls, so this is a bound against unlimited growth,
     *  not a number anyone is expected to reach by hand. */
    const val MAX_CUSTOM_COLOURS_PER_FIELD = 24

    private const val RGB_MASK = 0x00FFFFFF

    /** [colours]' index of the entry matching [target], or -1. RGB-only when [preserveAlpha]
     *  is set, since the swipe trail is stored translucent while everything compared against it
     *  here is opaque. */
    fun indexOf(colours: List<Int>, target: Int, preserveAlpha: Boolean = false): Int =
        colours.indexOfFirst { entry ->
            if (preserveAlpha) (entry and RGB_MASK) == (target and RGB_MASK) else entry == target
        }

    fun contains(colours: List<Int>, target: Int, preserveAlpha: Boolean = false): Boolean =
        indexOf(colours, target, preserveAlpha) >= 0

    /** Where a newly-picked colour belongs -- named so a call site never hardcodes
     *  `colours.size` to mean "the end of the row". */
    fun appendIndex(colours: List<Int>): Int = colours.size

    /**
     * [colours] with [colour] inserted at [index], unless it is already there -- as one of
     * [COLOURS] or already in [colours] itself -- in which case the list comes back unchanged:
     * picking a colour the row already offers never duplicates it or moves it.
     *
     * Growing past [MAX_CUSTOM_COLOURS_PER_FIELD] drops from the front: the oldest pick is the
     * one most likely already forgotten.
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
