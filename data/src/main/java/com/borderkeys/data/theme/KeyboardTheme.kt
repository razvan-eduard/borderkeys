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
    )
}
