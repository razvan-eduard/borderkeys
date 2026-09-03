// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * How the keyboard looks, as data.
 *
 * Deliberately not an Android theme. There are no XML style resources for the keyboard, no
 * `?attr/` lookups and no `TypedArray` reads at runtime, because every one of those is a
 * resource-table lookup and the draw path has four milliseconds for the whole invalidated
 * region. This is read once from a typed DataStore, compiled into `Paint` objects, and the draw
 * path only ever uses those.
 *
 * Colours are packed ARGB ints, the form `Paint.setColor` wants, so nothing is converted while
 * drawing.
 *
 * Defaults are a dark theme, because a keyboard is on screen next to whatever the user is
 * reading and a slab of white at the bottom of a dark app is the thing people complain about
 * first.
 */
@Serializable
data class KeyboardTheme(
    val backgroundColor: Int = 0xFF14141A.toInt(),
    val keyColor: Int = 0xFF2A2A34.toInt(),
    val keyPressedColor: Int = 0xFF3D3D4C.toInt(),
    val modifierKeyColor: Int = 0xFF1E1E26.toInt(),
    val textColor: Int = 0xFFF2F2F7.toInt(),
    val secondaryTextColor: Int = 0xFF9A9AAA.toInt(),
    val accentColor: Int = 0xFF6EA8FE.toInt(),
    val keyCornerRadiusDp: Float = 8f,
    val keyGapDp: Float = 4f,
    val rowHeightDp: Float = 52f,
    val labelTextSizeSp: Float = 20f,
    val showKeyBorders: Boolean = false,
    val pressedElevation: Float = 2f,
    val swipeTrailColor: Int = 0xCC6EA8FE.toInt(),
    val swipeTrailWidthDp: Float = 4f,

    /**
     * A second background colour: a vertical gradient from the top of the keyboard to its
     * bottom edge. Zero means there is no second colour and the background is flat.
     *
     * Zero rather than "the same as the background" as the off state, so that a preset which
     * changes only [backgroundColor] -- every preset does -- cannot accidentally leave a
     * gradient running from its new colour to the old one.
     */
    val backgroundGradientColor: Int = 0,

    /**
     * The patterns drawn over the background, as PATTERN_ constants, in the order given.
     *
     * A list rather than one choice, because they layer: dots over a grid is a third thing, and
     * there is no reason for the keyboard to be the one deciding that two of them together are
     * not allowed. Empty for a plain surface.
     *
     * This replaced a single `backgroundPattern` field; the serializer reads that older key and
     * carries whichever pattern it named in as the one member of this list.
     */
    val backgroundPatterns: List<Int> = emptyList(),
    val patternColor: Int = 0x1FFFFFFF,

    /** The repeat of the pattern, edge to edge of one tile. */
    val patternScaleDp: Float = 24f,

    /**
     * A picture behind the keys: the name of a file in this application's own directory, or
     * empty.
     *
     * A name and not a path, because the two builds have different directories and a path
     * copied from one would point at nothing in the other. Whoever draws it resolves the name.
     */
    val backgroundImage: String = "",

    /**
     * How far the picture is darkened before anything is drawn on it, from 0 to 1.
     *
     * Not decoration. Key labels are one colour and a photograph is every colour, so without
     * this the letters disappear over whichever part of the picture happens to be behind them.
     * The default is heavy on purpose: a background that competes with the labels is a
     * background that makes the keyboard worse.
     */
    val backgroundImageDim: Float = 0.55f,

    /**
     * Whether the background reaches the edges of the screen.
     *
     * On by default. A one-handed keyboard is narrower than the window, and with this off the
     * space beside it is a hole showing the application underneath -- which is what the space
     * used to be. With it on the keyboard reads as a surface that the keys sit on, and the
     * pattern is worth having because there is somewhere for it to show.
     */
    val fullWidthBackground: Boolean = true,
) {
    /**
     * Clamps every dimension into a range that can actually be drawn.
     *
     * A theme file that parses is not the same as a theme file that makes sense. It can be hand
     * edited, restored from an older version, or simply written by a future build with different
     * bounds -- and a `rowHeightDp` of 40000 does not throw, it produces a keyboard taller than
     * the screen with no way to reach the settings that would fix it. Clamping is applied on
     * read, so a bad value cannot escape the repository.
     */
    fun sanitised(): KeyboardTheme = copy(
        keyCornerRadiusDp = keyCornerRadiusDp.coerceIn(0f, 32f),
        keyGapDp = keyGapDp.coerceIn(0f, 16f),
        rowHeightDp = rowHeightDp.coerceIn(28f, 96f),
        labelTextSizeSp = labelTextSizeSp.coerceIn(8f, 40f),
        pressedElevation = pressedElevation.coerceIn(0f, 16f),
        swipeTrailWidthDp = swipeTrailWidthDp.coerceIn(1f, 24f),
        // Read through the known list and de-duplicated: a stored file is not a trusted file,
        // and the same pattern drawn twice is the same pattern drawn once, more slowly.
        backgroundPatterns = backgroundPatterns
            .filter { it in PATTERN_DOTS until PATTERN_COUNT }
            .distinct(),
        patternScaleDp = patternScaleDp.coerceIn(8f, 64f),
        // A file name, never a path: anything with a separator in it is a way out of the
        // directory this is supposed to name a file in.
        backgroundImage = if (backgroundImage.contains('/') || backgroundImage.contains('\\')) {
            ""
        } else {
            backgroundImage.take(MAX_IMAGE_NAME)
        },
        backgroundImageDim = backgroundImageDim.coerceIn(0f, 1f),
    )

    /** The colour the background fades to, which is its own colour when it fades to nothing. */
    fun gradientEnd(): Int =
        if (backgroundGradientColor == 0) backgroundColor else backgroundGradientColor

    companion object {
        /**
         * The patterns, as indices rather than an enum.
         *
         * The theme is serialised to disk and read back by whatever version comes next; an int
         * that falls outside the range is clamped to none by [sanitised], where an unknown enum
         * name would be a parse failure that loses the whole theme.
         */
        /** No longer a value anything stores; kept because "none" is still a thing to say. */
        const val PATTERN_NONE = 0
        const val PATTERN_DOTS = 1
        const val PATTERN_GRID = 2
        const val PATTERN_DIAGONAL = 3
        const val PATTERN_CHECKS = 4
        const val PATTERN_STRIPES = 5
        const val PATTERN_COUNT = 6

        /** Long enough for a generated name, short enough not to be a path in disguise. */
        const val MAX_IMAGE_NAME = 64
    }
}
