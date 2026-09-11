// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * Size and position, as one value -- so the keyboard can hold two of them, one per orientation.
 *
 * [KeyboardPreferences]'s own `heightScale`/`widthScale`/`positionMode`/`bottomOffsetDp`/
 * `horizontalOffsetDp`/`splitGapDp` are portrait's copy of exactly these fields, kept flat
 * rather than moved in here, so an existing install's portrait sizing survives this change
 * without a migration: the stored file already has those keys at the top level, and
 * `ignoreUnknownKeys`/defaults mean a landscape object that was never there before just
 * appears, defaulted, the first time this build reads an older file.
 *
 * Landscape's own default height is smaller than portrait's 1f, not equal to it -- a landscape
 * screen is a third the height of a portrait one, and a keyboard sized as if it were not is
 * exactly the "unacceptable" size this exists to fix.
 */
@Serializable
data class KeyboardPlacement(
    val heightScale: Float = 0.7f,
    val widthScale: Float = 1f,
    val positionMode: Int = KeyboardPreferences.MODE_DOCKED,
    val bottomOffsetDp: Float = 0f,
    val horizontalOffsetDp: Float = 0f,
    /** Only meaningful at [KeyboardPreferences.MODE_SPLIT]. Wider default than portrait's: a
     *  landscape screen has far more spare width either side of a person's two thumbs. */
    val splitGapDp: Float = 140f,
) {
    /**
     * A file that parses is not a file that makes sense -- the same reasoning
     * [KeyboardPreferences.sanitised] and [KeyboardTheme.sanitised] apply to their own fields,
     * applied here so a corrupt or hand-edited landscape object cannot escape the repository
     * either.
     */
    fun sanitised(): KeyboardPlacement = copy(
        heightScale = heightScale.coerceIn(
            KeyboardPreferences.MIN_HEIGHT_SCALE, KeyboardPreferences.MAX_HEIGHT_SCALE,
        ),
        widthScale = widthScale.coerceIn(KeyboardPreferences.MIN_WIDTH_SCALE, 1f),
        positionMode = if (positionMode in KeyboardPreferences.MODE_DOCKED..KeyboardPreferences.MODE_SPLIT) {
            positionMode
        } else {
            KeyboardPreferences.MODE_DOCKED
        },
        bottomOffsetDp = bottomOffsetDp.coerceIn(0f, KeyboardPreferences.MAX_BOTTOM_OFFSET_DP),
        horizontalOffsetDp = horizontalOffsetDp.coerceIn(
            KeyboardPreferences.MIN_HORIZONTAL_OFFSET_DP, KeyboardPreferences.MAX_HORIZONTAL_OFFSET_DP,
        ),
        splitGapDp = splitGapDp.coerceIn(
            KeyboardPreferences.MIN_SPLIT_GAP_DP, KeyboardPreferences.MAX_SPLIT_GAP_DP,
        ),
    )
}
