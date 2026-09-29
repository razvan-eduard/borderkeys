// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.ime.TouchGlows

/** The heatmap's stored totals as the preview draws them. */
object HeatmapGlows {

    /** A bucket key, "portrait/0/qwerty+dig", read back. */
    data class Bucket(val landscape: Boolean, val layoutId: String) {
        /** The layout without its number-row and key variants. */
        val baseLayoutId: String get() = layoutId.substringBefore('+')

        companion object {
            fun parse(key: String): Bucket =
                Bucket(key.startsWith("landscape/"), key.split('/', limit = 3).getOrElse(2) { "" })
        }
    }

    /**
     * The glows of [bucket]'s rows among [rows], each weighed down to [now]. A key with fewer
     * than [minTaps] taps gets strength 0, the default circle; from there the glow grows with
     * the taps to full strength at [FULL_AT] times the minimum.
     */
    fun of(
        rows: List<KeyTouch>,
        bucket: String?,
        now: Long,
        halfLifeMillis: Long,
        minTaps: Int,
        color: Int,
    ): TouchGlows {
        val keys = rows.filter { it.bucket == bucket }
            .map { KeyTouches.decayed(it, now, halfLifeMillis) }
            .filter { it.taps > 0.0 }
        return TouchGlows(
            codes = IntArray(keys.size) { keys[it].code },
            meanX = FloatArray(keys.size) { (keys[it].sumX / keys[it].taps).toFloat() },
            meanY = FloatArray(keys.size) { (keys[it].sumY / keys[it].taps).toFloat() },
            varianceX = FloatArray(keys.size) { spread(keys[it].sumXX, keys[it].sumX, keys[it].sumX, keys[it].taps) },
            varianceY = FloatArray(keys.size) { spread(keys[it].sumYY, keys[it].sumY, keys[it].sumY, keys[it].taps) },
            covariance = FloatArray(keys.size) { spread(keys[it].sumXY, keys[it].sumX, keys[it].sumY, keys[it].taps) },
            strength = FloatArray(keys.size) { strength(keys[it].taps, minTaps) },
            color = color,
        )
    }

    /** 0 below [minTaps]; from [MIN_STRENGTH] there, rising to 1 at [FULL_AT] times it. */
    fun strength(taps: Double, minTaps: Int): Float {
        if (taps < minTaps) {
            return 0f
        }
        val grown = ((taps - minTaps) / (minTaps * (FULL_AT - 1))).coerceIn(0.0, 1.0)
        return (MIN_STRENGTH + (1f - MIN_STRENGTH) * grown).toFloat()
    }

    /** E[ab] − E[a]·E[b] from the weighted sums of a·b, a and b. */
    private fun spread(sumAB: Double, sumA: Double, sumB: Double, weight: Double): Float =
        (sumAB / weight - (sumA / weight) * (sumB / weight)).toFloat()

    /** The glows' colour, orange, ARGB. */
    const val COLOR = 0xFFFF9800.toInt()

    /** Full strength at this many times the minimum. */
    private const val FULL_AT = 4.0

    /** A key's strength at the minimum. */
    private const val MIN_STRENGTH = 0.35f
}
