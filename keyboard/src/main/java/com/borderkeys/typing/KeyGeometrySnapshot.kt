// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/**
 * The letter keys as taps land on them: each letter's key centre, in the keyboard view's pixels,
 * the average key size, and the [bucket] the taps belong to.
 */
class KeyGeometrySnapshot(
    val bucket: Bucket,
    val keyWidth: Float,
    val keyHeight: Float,
    /** Each letter key's code, with its centre at the same index of [centreX] and [centreY]. */
    val codes: IntArray,
    val centreX: FloatArray,
    val centreY: FloatArray,
) {
    /** What a tap's position depends on besides its key: orientation, placement and layout. */
    data class Bucket(val landscape: Boolean, val positionMode: Int, val layoutId: String)
}
