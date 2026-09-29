// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Where taps land on each letter key, for drawing over the keys in a preview: per letter code,
 * the mean offset from the key's centre and the covariance, in key units, and how strongly the
 * glow shows, 0 for a key still read the default way.
 */
class TouchGlows(
    val codes: IntArray,
    val meanX: FloatArray,
    val meanY: FloatArray,
    val varianceX: FloatArray,
    val varianceY: FloatArray,
    val covariance: FloatArray,
    /** 0 to 1; 0 draws the faint default circle at the key's centre. */
    val strength: FloatArray,
    /** The glow's colour, ARGB. */
    val color: Int,
)

/** The ellipse of one standard deviation of a 2x2 covariance: its two radii and its tilt. */
class GlowEllipse(val major: Float, val minor: Float, val degrees: Float) {
    companion object {
        /**
         * The ellipse of the covariance with variances [varianceX] and [varianceY] and
         * covariance [covariance], its radii at least [floor].
         */
        fun of(varianceX: Float, varianceY: Float, covariance: Float, floor: Float): GlowEllipse {
            val half = (varianceX + varianceY) / 2f
            val spread = sqrt(max(half * half - (varianceX * varianceY - covariance * covariance), 0f))
            val degrees = Math.toDegrees(
                0.5 * atan2(2.0 * covariance, (varianceX - varianceY).toDouble()),
            ).toFloat()
            return GlowEllipse(
                major = max(sqrt(max(half + spread, 0f)), floor),
                minor = max(sqrt(max(half - spread, 0f)), floor),
                degrees = degrees,
            )
        }
    }
}
