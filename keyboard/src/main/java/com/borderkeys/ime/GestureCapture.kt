// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/** The samples one motion event carries, historical ones first, as one indexable sequence. */
interface MotionSamples {
    /** Historical samples plus the current one. Always at least one. */
    val sampleCount: Int

    fun xAt(index: Int): Float

    fun yAt(index: Int): Float

    /** Uptime in milliseconds, the same clock `MotionEvent.getEventTime` uses. */
    fun timeAt(index: Int): Long
}

/** A fixed-size buffer of the points of one swipe; allocates nothing after construction. */
class GestureCapture(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 2) { "a gesture needs room for at least two points" }
    }

    /** The captured points, valid up to [count]; the decoder reads them on its own thread. */
    val xs = FloatArray(capacity)
    val ys = FloatArray(capacity)
    val times = LongArray(capacity)

    var count = 0
        private set

    var minX = 0f
        private set
    var minY = 0f
        private set
    var maxX = 0f
        private set
    var maxY = 0f
        private set

    /** How many times the buffer has halved itself since [begin]. */
    var decimations = 0
        private set

    /** Starts a gesture at the point the finger went down. */
    fun begin(x: Float, y: Float, time: Long) {
        count = 0
        decimations = 0
        minX = x
        maxX = x
        minY = y
        maxY = y
        append(x, y, time)
    }

    /** Forgets the gesture. The arrays keep their storage for the next one. */
    fun reset() {
        count = 0
        decimations = 0
    }

    /** Appends every sample in [samples], oldest first. */
    fun capture(samples: MotionSamples) {
        val n = samples.sampleCount
        for (i in 0 until n) {
            append(samples.xAt(i), samples.yAt(i), samples.timeAt(i))
        }
    }

    /** The distance between the two newest points, in the units of [xs]; zero before a second. */
    fun distanceFromPrevious(): Float {
        if (count < 2) {
            return 0f
        }
        val dx = xs[count - 1] - xs[count - 2]
        val dy = ys[count - 1] - ys[count - 2]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Appends one point, halving the buffer first if it is full. */
    fun append(x: Float, y: Float, time: Long) {
        if (count >= capacity) {
            decimate()
        }
        xs[count] = x
        ys[count] = y
        times[count] = time
        count++
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    /** Keeps every other sample from the first, in place, when the buffer fills. */
    private fun decimate() {
        var write = 0
        var read = 0
        while (read < count) {
            xs[write] = xs[read]
            ys[write] = ys[read]
            times[write] = times[read]
            write++
            read += 2
        }
        count = write
        decimations++
    }

    companion object {
        /** The buffer's size in points. */
        const val DEFAULT_CAPACITY = 512
    }
}
