// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * How the keyboard looks, as data read from a typed DataStore and compiled into `Paint` objects.
 * Colours are packed ARGB ints. The defaults are a dark theme.
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

    /** The corner hint's size: what holding a key would type, or an accent. */
    val accentTextSizeSp: Float = 15.5f,
    val showKeyBorders: Boolean = false,
    val pressedElevation: Float = 2f,
    val swipeTrailColor: Int = 0xCC6EA8FE.toInt(),
    val swipeTrailWidthDp: Float = 4f,

    /**
     * A second background colour, for a vertical gradient from the top of the keyboard to its
     * bottom edge. Zero means the background is flat.
     */
    val backgroundGradientColor: Int = 0,

    /**
     * The patterns drawn over the background, as PATTERN_ constants, in the order given; empty
     * for a plain surface. The serializer reads the older single `backgroundPattern` key into it.
     */
    val backgroundPatterns: List<Int> = emptyList(),

    // [ThemePalette.COLOURS]'s first entry, at a low alpha.
    val patternColor: Int = (ThemePalette.COLOURS.first() and 0x00FFFFFF) or (0x1F shl 24),

    /** The repeat of the pattern, edge to edge of one tile. */
    val patternScaleDp: Float = 24f,

    /**
     * A picture behind the keys: the name, not the path, of a file in this application's own
     * directory, or empty.
     */
    val backgroundImage: String = "",

    /** How far the picture is darkened before anything is drawn on it, from 0 to 1. */
    val backgroundImageDim: Float = 0.55f,

    /** Whether the background reaches the edges of the screen beside a narrowed keyboard. */
    val fullWidthBackground: Boolean = true,

    /**
     * Whether the background reaches into the strip kept clear along the bottom edge for the
     * system's navigation bar.
     */
    val navigationBarBackground: Boolean = true,

    /**
     * How much of the keyboard's surface shows: 1 is a solid keyboard, and anything less lets
     * the application behind show through keys, labels and strip alike, down to
     * [MIN_OPACITY]. Clamped on read.
     */
    val opacity: Float = 1f,

    /**
     * How the suggestion strip marks the word a delimiter would apply: [APPLIED_HIGHLIGHT_OUTLINE]
     * (a traced border) or [APPLIED_HIGHLIGHT_BACKGROUND] (a filled chip). Clamped on read.
     */
    val appliedHighlightStyle: Int = APPLIED_HIGHLIGHT_OUTLINE,

    /** The colour of that mark; zero means unset, see [appliedHighlightColorOrDefault]. */
    val appliedHighlightColor: Int = 0,

    /**
     * Colours the user picked through the wheel that are not in [ThemePalette.COLOURS]: one list
     * per editable colour field, keyed by the `KEY_*` constants below, in the order picked.
     */
    val customColours: Map<String, List<Int>> = emptyMap(),
) {
    /** Clamps every dimension into a range that can be drawn; applied on read. */
    fun sanitised(): KeyboardTheme = copy(
        keyCornerRadiusDp = keyCornerRadiusDp.coerceIn(0f, 32f),
        keyGapDp = keyGapDp.coerceIn(0f, 16f),
        rowHeightDp = rowHeightDp.coerceIn(28f, 96f),
        labelTextSizeSp = labelTextSizeSp.coerceIn(8f, 40f),
        accentTextSizeSp = accentTextSizeSp.coerceIn(6f, 32f),
        pressedElevation = pressedElevation.coerceIn(0f, 16f),
        swipeTrailWidthDp = swipeTrailWidthDp.coerceIn(1f, 24f),
        // Known patterns only, each once.
        backgroundPatterns = backgroundPatterns
            .filter { it in PATTERN_DOTS until PATTERN_COUNT }
            .distinct(),
        patternScaleDp = patternScaleDp.coerceIn(8f, 64f),
        // A file name, never a path.
        backgroundImage = if (backgroundImage.contains('/') || backgroundImage.contains('\\')) {
            ""
        } else {
            backgroundImage.take(MAX_IMAGE_NAME)
        },
        backgroundImageDim = backgroundImageDim.coerceIn(0f, 1f),
        opacity = opacity.coerceIn(MIN_OPACITY, 1f),
        appliedHighlightStyle = if (appliedHighlightStyle in
            APPLIED_HIGHLIGHT_OUTLINE..APPLIED_HIGHLIGHT_BACKGROUND
        ) {
            appliedHighlightStyle
        } else {
            APPLIED_HIGHLIGHT_OUTLINE
        },
        // Exact duplicates dropped; the newest kept on overflow.
        customColours = customColours
            .mapValues { (_, colours) -> colours.distinct().takeLast(ThemePalette.MAX_CUSTOM_COLOURS_PER_FIELD) }
            .filterValues { it.isNotEmpty() },
    )

    /** The colour the background fades to, which is its own colour when it fades to nothing. */
    fun gradientEnd(): Int =
        if (backgroundGradientColor == 0) backgroundColor else backgroundGradientColor

    /** [appliedHighlightColor], or the accent when it is unset, whatever the style. */
    fun appliedHighlightColorOrDefault(): Int =
        if (appliedHighlightColor != 0) appliedHighlightColor else accentColor

    companion object {
        /** The patterns, as indices; [sanitised] drops one out of range. NONE is never stored. */
        const val PATTERN_NONE = 0
        const val PATTERN_DOTS = 1
        const val PATTERN_GRID = 2
        const val PATTERN_DIAGONAL = 3
        const val PATTERN_CHECKS = 4
        const val PATTERN_STRIPES = 5
        const val PATTERN_COUNT = 6

        /** The longest background image name accepted. */
        const val MAX_IMAGE_NAME = 64

        /** The least [opacity]. */
        const val MIN_OPACITY = 0.3f

        /** A traced border, nothing painted behind the word. */
        const val APPLIED_HIGHLIGHT_OUTLINE = 0

        /** A filled chip behind the word. */
        const val APPLIED_HIGHLIGHT_BACKGROUND = 1

        // Keys into [customColours].
        const val KEY_BACKGROUND = "background"
        const val KEY_KEY = "key"
        const val KEY_KEY_PRESSED = "keyPressed"
        const val KEY_MODIFIER_KEY = "modifierKey"
        const val KEY_TEXT = "text"
        const val KEY_SECONDARY_TEXT = "secondaryText"
        const val KEY_ACCENT = "accent"
        const val KEY_SWIPE_TRAIL = "swipeTrail"
        const val KEY_APPLIED_HIGHLIGHT = "appliedHighlight"
        const val KEY_PATTERN = "pattern"
        const val KEY_GRADIENT_END = "gradientEnd"
    }
}
