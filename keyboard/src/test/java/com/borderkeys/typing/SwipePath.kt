// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.predict.Pipeline

/** A finger's samples through the centres of a word's keys, on the harness's layout. */
internal class SwipePath(val xs: FloatArray, val ys: FloatArray, val timestamps: LongArray) {

    val count: Int get() = xs.size

    companion object {
        /**
         * The samples through [word]'s keys in order, [STEPS_PER_KEY] to each next key and
         * [SAMPLE_MILLIS] apart; the swipe takes that long on [clock].
         */
        fun through(word: String, clock: ManualClock): SwipePath {
            val centres = word.map { Pipeline.keyCentre(it) }
            val xs = ArrayList<Float>()
            val ys = ArrayList<Float>()
            xs += centres.first().first
            ys += centres.first().second
            for (index in 1 until centres.size) {
                val (fromX, fromY) = centres[index - 1]
                val (toX, toY) = centres[index]
                for (step in 1..STEPS_PER_KEY) {
                    val along = step.toFloat() / STEPS_PER_KEY
                    xs += fromX + (toX - fromX) * along
                    ys += fromY + (toY - fromY) * along
                }
            }
            val start = clock.now
            val timestamps = LongArray(xs.size) { start + it * SAMPLE_MILLIS }
            clock.advance(timestamps.last() - start)
            return SwipePath(xs.toFloatArray(), ys.toFloatArray(), timestamps)
        }

        private const val STEPS_PER_KEY = 12
        private const val SAMPLE_MILLIS = 8L
    }
}
