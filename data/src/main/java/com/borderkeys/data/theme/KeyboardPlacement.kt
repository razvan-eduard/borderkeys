// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * Size and position as one value, one per orientation. Portrait's copy is kept as flat fields of
 * [KeyboardPreferences]; this class is landscape's, with a smaller default height.
 */
@Serializable
data class KeyboardPlacement(
    val heightScale: Float = 0.7f,
    val widthScale: Float = 1f,
    val positionMode: Int = KeyboardPreferences.MODE_DOCKED,
    val bottomOffsetDp: Float = 0f,
    val horizontalOffsetDp: Float = 0f,
) {
    /** Clamps every field; applied on read. */
    fun sanitised(): KeyboardPlacement = copy(
        heightScale = heightScale.coerceIn(
            KeyboardPreferences.MIN_HEIGHT_SCALE, KeyboardPreferences.MAX_HEIGHT_SCALE,
        ),
        widthScale = widthScale.coerceIn(KeyboardPreferences.MIN_WIDTH_SCALE, 1f),
        // Docked to floating; anything else reads as docked.
        positionMode = if (positionMode in KeyboardPreferences.MODE_DOCKED..KeyboardPreferences.MODE_FLOATING) {
            positionMode
        } else {
            KeyboardPreferences.MODE_DOCKED
        },
        bottomOffsetDp = bottomOffsetDp.coerceIn(0f, KeyboardPreferences.MAX_BOTTOM_OFFSET_DP),
        horizontalOffsetDp = horizontalOffsetDp.coerceIn(
            KeyboardPreferences.MIN_HORIZONTAL_OFFSET_DP, KeyboardPreferences.MAX_HORIZONTAL_OFFSET_DP,
        ),
    )
}
