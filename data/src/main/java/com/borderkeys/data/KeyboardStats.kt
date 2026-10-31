// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import kotlin.math.floor
import kotlin.math.log2

/**
 * What the keyboard measures about itself while it runs, for the settings application to
 * show: how long a search or a swipe decode takes, how long a swiped word takes to reach the
 * field, how the packs loaded. Written from the keyboard's threads, read from a settings
 * screen in the same process, so every quantity is a [Series] whose readings are taken as one
 * [Snapshot].
 */
object KeyboardStats {

    /**
     * One measured quantity: its last value, how many there were, their mean and their maximum,
     * and its median and 95th percentile ([Series]).
     */
    class Snapshot(
        val last: Double,
        val count: Int,
        val mean: Double,
        val max: Double,
        val p50: Double,
        val p95: Double,
    )

    /** How a figure stands against its baseline. */
    enum class Rating { GOOD, FAIR, POOR }

    /**
     * Rates [value] against a baseline: [GOOD][Rating.GOOD] up to [good], [FAIR][Rating.FAIR]
     * up to [fair], [POOR][Rating.POOR] past it; with [higherIsBetter] the comparisons turn
     * around, [good] being the floor of good and [fair] the floor of fair.
     */
    fun rate(value: Double, good: Double, fair: Double, higherIsBetter: Boolean = false): Rating =
        if (higherIsBetter) {
            when {
                value >= good -> Rating.GOOD
                value >= fair -> Rating.FAIR
                else -> Rating.POOR
            }
        } else {
            when {
                value <= good -> Rating.GOOD
                value <= fair -> Rating.FAIR
                else -> Rating.POOR
            }
        }

    /**
     * A quantity's values as they are added: the last, the count, the mean and the maximum, and a
     * fixed histogram of [BUCKETS] buckets from [LOWEST] up, each a [PER_DOUBLING]th of a doubling
     * wide, a value under [LOWEST] in the first and one past the last bucket in the last. A
     * percentile is read by nearest rank as the largest value of the bucket that holds it, so at
     * most a bucket's width above it. Adding a value allocates nothing.
     */
    class Series {
        private var last = 0.0
        private var count = 0
        private var mean = 0.0
        private var max = 0.0

        // How many values each bucket holds, and the largest of them.
        private val held = IntArray(BUCKETS)
        private val largest = DoubleArray(BUCKETS)

        /** Adds [value]; a value that is not a number is left out. */
        @Synchronized
        fun add(value: Double) {
            if (value.isNaN()) {
                return
            }
            last = value
            count++
            mean += (value - mean) / count
            if (value > max) {
                max = value
            }
            val bucket = bucketOf(value)
            held[bucket]++
            if (value > largest[bucket]) {
                largest[bucket] = value
            }
        }

        @Synchronized
        fun reset() {
            last = 0.0
            count = 0
            mean = 0.0
            max = 0.0
            held.fill(0)
            largest.fill(0.0)
        }

        @Synchronized
        fun snapshot(): Snapshot = Snapshot(last, count, mean, max, percentile(P50), percentile(P95))

        /** The [percent]th percentile by nearest rank, as the histogram reads it; 0 with none. */
        private fun percentile(percent: Int): Double {
            val rank = maxOf(1, (percent * count + PERCENT - 1) / PERCENT)
            var seen = 0
            for (bucket in 0 until BUCKETS) {
                seen += held[bucket]
                if (seen >= rank) {
                    return largest[bucket]
                }
            }
            return 0.0
        }

        private companion object {
            /** The top of the first bucket, in the quantity's own unit. */
            const val LOWEST = 0.01

            /** Buckets per doubling of the value. */
            const val PER_DOUBLING = 16

            /** The doublings above [LOWEST] the buckets reach. */
            const val DOUBLINGS = 24

            const val BUCKETS = DOUBLINGS * PER_DOUBLING

            const val PERCENT = 100
            const val P50 = 50
            const val P95 = 95

            /**
             * The bucket of [value]: 0 under [LOWEST], else one more for each [PER_DOUBLING]th of a
             * doubling above it, up to the last, infinity included.
             */
            fun bucketOf(value: Double): Int =
                if (value < LOWEST) {
                    0
                } else {
                    val steps = floor(log2(value / LOWEST) * PER_DOUBLING)
                    minOf((BUCKETS - 1).toDouble(), 1 + steps).toInt()
                }
        }
    }

    /** The native search behind one keystroke, in milliseconds. */
    val searchMillis = Series()

    /** From the keystroke to the strip showing its answer, in milliseconds. */
    val suggestionMillis = Series()

    /** The native decode of one swipe, in milliseconds. */
    val decodeMillis = Series()

    /** From the finger lifting to the swiped word standing in the field, in milliseconds. */
    val liftToTextMillis = Series()

    /** How many candidates a swipe decoded to. */
    val swipeCandidates = Series()

    /** A swipe's path, in key widths. */
    val swipePathKeys = Series()

    /** A swipe's duration from first to last sample, in milliseconds. */
    val swipeMillis = Series()

    /** How many touch samples a swipe delivered. */
    val swipeSamples = Series()

    /** Keys pressed and words finished since the last reset, for the speed line. */
    @Volatile
    var keystrokes = 0

    @Volatile
    var words = 0

    /** Uptime of the first and the latest keystroke or swipe since the last reset. */
    @Volatile
    var firstInputAt = 0L

    @Volatile
    var lastInputAt = 0L

    /** Counts one key press or swipe at [uptimeMillis]. */
    fun input(uptimeMillis: Long) {
        if (firstInputAt == 0L) {
            firstInputAt = uptimeMillis
        }
        lastInputAt = uptimeMillis
    }

    /** Whether the last swipe went through the neural decoder. */
    @Volatile
    var neuralDecoder = false

    /** How long the last activation of the language packs took, in milliseconds; -1 before one. */
    @Volatile
    var packLoadMillis = -1L

    fun reset() {
        searchMillis.reset()
        suggestionMillis.reset()
        decodeMillis.reset()
        liftToTextMillis.reset()
        swipeCandidates.reset()
        swipePathKeys.reset()
        swipeMillis.reset()
        swipeSamples.reset()
        keystrokes = 0
        words = 0
        firstInputAt = 0L
        lastInputAt = 0L
    }
}
