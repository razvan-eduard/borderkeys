// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.KeyTouches
import com.borderkeys.data.entity.KeyTouch

/** Per-key patterns as the engine takes them: tap weight, mean offset and covariance, in key units. */
class TouchPatterns(
    val codes: IntArray,
    val taps: FloatArray,
    val meanX: FloatArray,
    val meanY: FloatArray,
    val varianceX: FloatArray,
    val varianceY: FloatArray,
    val covariance: FloatArray,
) {
    val size: Int get() = codes.size
}

/**
 * The heatmap as the keyboard holds it: the stored totals of the current bucket, and the samples
 * taken since the last write, by bucket and letter. The engine is given the two added together.
 */
class TouchLearning {

    /** The bucket [stored] belongs to, or null before the first load. */
    var bucket: String? = null
        private set

    private val stored = HashMap<Int, KeyTouch>()
    private val pending = LinkedHashMap<Pair<String, Int>, KeyTouch>()

    /** Takes [rows], [bucket]'s stored totals, each weighed down to [now]. */
    fun load(bucket: String, rows: List<KeyTouch>, now: Long, halfLifeMillis: Long) {
        this.bucket = bucket
        stored.clear()
        for (row in rows) {
            stored[row.code] = KeyTouches.decayed(row, now, halfLifeMillis)
        }
    }

    /** Adds [samples], taken on [geometry] at [now]. */
    fun add(samples: List<TouchSample>, geometry: KeyGeometrySnapshot, now: Long) {
        val key = geometry.bucket.key
        for (sample in samples) {
            val slot = key to sample.code
            val sum = pending[slot]
            val dx = sample.dx.toDouble()
            val dy = sample.dy.toDouble()
            pending[slot] = KeyTouch(
                bucket = key,
                code = sample.code,
                taps = (sum?.taps ?: 0.0) + 1.0,
                sumX = (sum?.sumX ?: 0.0) + dx,
                sumY = (sum?.sumY ?: 0.0) + dy,
                sumXX = (sum?.sumXX ?: 0.0) + dx * dx,
                sumYY = (sum?.sumYY ?: 0.0) + dy * dy,
                sumXY = (sum?.sumXY ?: 0.0) + dx * dy,
                keyWidthPx = geometry.keyWidth,
                keyHeightPx = geometry.keyHeight,
                density = geometry.density,
                lastUsedAt = now,
            )
        }
    }

    fun isEmpty(): Boolean = pending.isEmpty()

    /** Whether nothing is held, stored or pending. */
    val isBlank: Boolean get() = stored.isEmpty() && pending.isEmpty()

    /** The samples taken since the last drain, as totals by bucket and letter, now counted as stored. */
    fun drain(halfLifeMillis: Long): List<KeyTouch> {
        val drained = pending.values.toList()
        for (touch in drained) {
            if (touch.bucket == bucket) {
                stored[touch.code] = KeyTouches.merged(stored[touch.code], touch, halfLifeMillis)
            }
        }
        pending.clear()
        return drained
    }

    /** Forgets the stored totals and the samples not yet written. */
    fun discard() {
        stored.clear()
        pending.clear()
    }

    /** The current bucket's patterns, by letter code: what is stored and what is pending, added. */
    fun patterns(halfLifeMillis: Long): TouchPatterns {
        val current = bucket
        val totals = HashMap<Int, KeyTouch>(stored)
        if (current != null) {
            for ((slot, touch) in pending) {
                if (slot.first == current) {
                    totals[touch.code] = KeyTouches.merged(totals[touch.code], touch, halfLifeMillis)
                }
            }
        }
        val keys = totals.values.filter { it.taps > 0.0 }.sortedBy { it.code }
        return TouchPatterns(
            codes = IntArray(keys.size) { keys[it].code },
            taps = FloatArray(keys.size) { keys[it].taps.toFloat() },
            meanX = FloatArray(keys.size) { (keys[it].sumX / keys[it].taps).toFloat() },
            meanY = FloatArray(keys.size) { (keys[it].sumY / keys[it].taps).toFloat() },
            varianceX = FloatArray(keys.size) { spread(keys[it].sumXX, keys[it].sumX, keys[it].sumX, keys[it].taps) },
            varianceY = FloatArray(keys.size) { spread(keys[it].sumYY, keys[it].sumY, keys[it].sumY, keys[it].taps) },
            covariance = FloatArray(keys.size) { spread(keys[it].sumXY, keys[it].sumX, keys[it].sumY, keys[it].taps) },
        )
    }

    /** E[ab] − E[a]·E[b] from the weighted sums of a·b, a and b. */
    private fun spread(sumAB: Double, sumA: Double, sumB: Double, weight: Double): Float =
        (sumAB / weight - (sumA / weight) * (sumB / weight)).toFloat()
}
