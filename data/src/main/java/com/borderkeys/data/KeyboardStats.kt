// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * What the keyboard measures about itself while it runs, for the settings application to
 * show: how long a search or a swipe decode takes, how long a swiped word takes to reach the
 * field, how the packs loaded. Written from the keyboard's threads, read from a settings
 * screen in the same process, so every quantity is a [Series] whose readings are taken as one
 * [Snapshot].
 */
object KeyboardStats {

    /** One measured quantity: its last value, how many there were, their mean and their maximum. */
    class Snapshot(val last: Double, val count: Int, val mean: Double, val max: Double)

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

    class Series {
        private var last = 0.0
        private var count = 0
        private var mean = 0.0
        private var max = 0.0

        @Synchronized
        fun add(value: Double) {
            last = value
            count++
            mean += (value - mean) / count
            if (value > max) {
                max = value
            }
        }

        @Synchronized
        fun reset() {
            last = 0.0
            count = 0
            mean = 0.0
            max = 0.0
        }

        @Synchronized
        fun snapshot(): Snapshot = Snapshot(last, count, mean, max)
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
