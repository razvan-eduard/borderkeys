// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/**
 * The letter keys as taps land on them: each letter's key centre, in the keyboard view's pixels,
 * the average key size, the display's density, and the [bucket] the taps belong to.
 */
class KeyGeometrySnapshot(
    val bucket: Bucket,
    val keyWidth: Float,
    val keyHeight: Float,
    /** Pixels per dp. */
    val density: Float,
    /** Each letter key's code, with its centre at the same index of [centreX] and [centreY]. */
    val codes: IntArray,
    val centreX: FloatArray,
    val centreY: FloatArray,
) {
    /** The index of [code]'s key, or -1. */
    fun indexOf(code: Int): Int = codes.indexOf(code)

    /** What a tap's position depends on besides its key: orientation, placement and layout. */
    data class Bucket(val landscape: Boolean, val positionMode: Int, val layoutId: String) {
        /** The bucket as the heatmap stores it. */
        val key: String
            get() = "${if (landscape) "landscape" else "portrait"}/$positionMode/$layoutId"
    }
}
