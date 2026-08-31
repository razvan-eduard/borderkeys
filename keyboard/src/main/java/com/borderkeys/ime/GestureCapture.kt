// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * The samples one motion event carries, flattened into a single indexable sequence.
 *
 * `MotionEvent` splits its samples in two: the historical ones behind `getHistoricalX(pointer,
 * h)` and the newest one behind `getX(pointer)`. Every caller has to remember to read both, and
 * forgetting the historical half is silent -- the trail still looks right and only the decode
 * gets worse. Flattening the two into one sequence puts that arithmetic in one place instead of
 * at every call site, and gives the capture buffer something a test can implement.
 */
interface MotionSamples {
    /** Historical samples plus the current one. Always at least one. */
    val sampleCount: Int

    fun xAt(index: Int): Float

    fun yAt(index: Int): Float

    /** Uptime in milliseconds, the same clock `MotionEvent.getEventTime` uses. */
    fun timeAt(index: Int): Long
}

/**
 * A fixed-size buffer of the points of one swipe.
 *
 * Split out of the view for two reasons. It is the only piece of gesture handling with
 * invariants worth asserting -- every sample kept, the bounding box correct, the arrays never
 * replaced -- and none of those need a `Canvas`, a `Context` or a real `MotionEvent` to check.
 * The view keeps the drawing and the touch dispatch; this keeps the numbers.
 *
 * Nothing here allocates after construction. The buffer runs inside the `MotionEvent` handler,
 * which shares the 2 ms touch-to-commit budget with the whole rest of the input path, and a
 * garbage collection triggered mid-swipe shows up as a visible stutter in the trail.
 */
class GestureCapture(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 2) { "a gesture needs room for at least two points" }
    }

    /**
     * The captured points, valid up to [count].
     *
     * Exposed directly rather than copied out: the decoder reads them on another thread while
     * this thread has already moved on, and copying 512 floats per gesture to defend against a
     * race that the caller's own handoff already prevents would be paying twice.
     */
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

    /**
     * How many times the buffer has halved itself since [begin].
     *
     * Kept for the tests, which have no other way to tell a buffer that decimated correctly from
     * one that quietly dropped the tail, and cheap enough to leave in: one increment per 512
     * samples.
     */
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

    /**
     * Appends every sample in [samples], oldest first.
     *
     * A touch driver batches: one `ACTION_MOVE` typically holds several samples taken between
     * frames. Ignoring the historical ones throws away most of a fast swipe and with it exactly
     * the curvature that separates "than" from "thin".
     */
    fun capture(samples: MotionSamples) {
        val n = samples.sampleCount
        for (i in 0 until n) {
            append(samples.xAt(i), samples.yAt(i), samples.timeAt(i))
        }
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

    /**
     * Halves the sample rate in place when the buffer fills.
     *
     * Growing the array would allocate during the gesture, which is the one thing this path may
     * not do. Dropping every other sample loses nothing that matters: by the time five hundred
     * samples have arrived the path is oversampled several times over relative to the sixty-four
     * points the decoder resamples it to. The first and last points are both kept, because an
     * odd count keeps index zero and index `count - 1`, and an even one keeps index zero and
     * `count - 2` -- one sample from the end of a gesture that is still being drawn.
     */
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
        /**
         * 512 points is about four seconds of swiping at the 120 Hz a fast panel reports, which
         * is far longer than any real word takes. Past that the buffer halves rather than grows.
         */
        const val DEFAULT_CAPACITY = 512
    }
}
