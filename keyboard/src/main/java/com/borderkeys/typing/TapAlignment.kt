// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** A word as it was typed, with where each of its code points was tapped, NaN for no point. */
class TypedTaps(val typed: String, val xs: FloatArray, val ys: FloatArray) {
    companion object {
        /** [word] with its taps from [trail], or null for an empty word. */
        fun of(word: CharSequence, trail: TapTrail): TypedTaps? =
            if (word.isEmpty()) null else TypedTaps(word.toString(), trail.copyXs(), trail.copyYs())
    }
}

/** One tap's offset from the centre of the key it was meant for, in key units. */
class TouchSample(val code: Int, val dx: Float, val dy: Float)

/**
 * Lines a word's taps up with the word the user kept. A tap on the key of the letter kept, or on
 * a ring neighbour of it, gives that letter one sample: the tap's offset from that key's centre,
 * dropped beyond [MAX_OFFSET]. A word with any code point untapped gives none.
 */
object TapAlignment {

    /** The ring's radius in key units, KeyGeometry::kNeighbourRadius in proximity.hpp. */
    const val NEIGHBOUR_RADIUS = 1.45f

    /** The farthest a sample may lie from its key's centre, in key units. */
    const val MAX_OFFSET = 1.0f

    /**
     * The samples [taps] gives for [kept]. The two must have as many code points; with
     * [completion], [kept] may be longer and only its first letters are lined up.
     */
    fun samples(
        taps: TypedTaps,
        kept: String,
        completion: Boolean,
        geometry: KeyGeometrySnapshot,
    ): List<TouchSample> {
        val typed = taps.typed.codePoints().toArray()
        val meant = kept.codePoints().toArray()
        val lengthsMatch = if (completion) meant.size >= typed.size else meant.size == typed.size
        if (!lengthsMatch || typed.size != taps.xs.size || typed.size != taps.ys.size) {
            return emptyList()
        }
        if (taps.xs.any { it.isNaN() } || taps.ys.any { it.isNaN() }) {
            return emptyList()
        }
        val samples = ArrayList<TouchSample>(typed.size)
        for (i in typed.indices) {
            val hit = geometry.indexOf(Character.toLowerCase(typed[i]))
            val code = Character.toLowerCase(meant[i])
            val key = geometry.indexOf(code)
            if (hit < 0 || key < 0 || (hit != key && apart(geometry, hit, key) > NEIGHBOUR_RADIUS)) {
                continue
            }
            val dx = (taps.xs[i] - geometry.centreX[key]) / geometry.keyWidth
            val dy = (taps.ys[i] - geometry.centreY[key]) / geometry.keyHeight
            if (dx * dx + dy * dy <= MAX_OFFSET * MAX_OFFSET) {
                samples += TouchSample(code, dx, dy)
            }
        }
        return samples
    }

    /** How far apart the centres of keys [a] and [b] are, in key units. */
    private fun apart(geometry: KeyGeometrySnapshot, a: Int, b: Int): Float {
        val dx = (geometry.centreX[a] - geometry.centreX[b]) / geometry.keyWidth
        val dy = (geometry.centreY[a] - geometry.centreY[b]) / geometry.keyHeight
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
