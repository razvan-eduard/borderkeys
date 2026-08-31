// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture buffer, checked against what a touch driver actually delivers.
 *
 * The batching is the point. A driver sampling at 120 Hz on a panel that reports at 60 Hz hands
 * the view one `ACTION_MOVE` holding two samples, and on a slow frame five or six. Reading only
 * the newest one is the classic mistake, and its symptom is not a visible bug -- the trail is
 * drawn from whatever was captured, so it stays smooth -- but a decoder fed a path with the
 * curvature sampled out of it.
 */
class GestureCaptureTest {

    /**
     * A batched move event: [count] samples, the last of which is the "current" one.
     *
     * `MotionEvent` cannot be constructed on the JVM without an emulator, so the capture buffer
     * reads through [MotionSamples] and this stands in for the real thing. It counts its own
     * reads so a test can prove nothing was skipped rather than infer it from the totals.
     */
    private class Batch(
        private val xs: FloatArray,
        private val ys: FloatArray,
        private val ts: LongArray,
    ) : MotionSamples {
        var reads = 0
            private set

        override val sampleCount: Int get() = xs.size

        override fun xAt(index: Int): Float {
            reads++
            return xs[index]
        }

        override fun yAt(index: Int) = ys[index]

        override fun timeAt(index: Int) = ts[index]
    }

    private fun batch(from: Int, count: Int) = Batch(
        FloatArray(count) { (from + it).toFloat() },
        FloatArray(count) { (from + it).toFloat() * 2f },
        LongArray(count) { (from + it).toLong() * 8L },
    )

    @Test
    fun everyHistoricalSampleIsKept() {
        val capture = GestureCapture()
        capture.begin(0f, 0f, 0L)

        // Three events carrying five, one and eight samples: a fast stroke, a frame where the
        // finger barely moved, and a frame the system was late delivering.
        val sizes = intArrayOf(5, 1, 8)
        var next = 1
        for (size in sizes) {
            val b = batch(next, size)
            capture.capture(b)
            assertEquals("the buffer skipped a sample", size, b.reads)
            next += size
        }

        val expected = 1 + sizes.sum()
        assertEquals(expected, capture.count)

        // The start point, then every sample in the order it was taken.
        assertEquals(0f, capture.xs[0], 0f)
        for (i in 1 until expected) {
            assertEquals("sample $i is out of order", i.toFloat(), capture.xs[i], 0f)
            assertEquals(i.toFloat() * 2f, capture.ys[i], 0f)
            assertEquals(i.toLong() * 8L, capture.times[i])
        }
    }

    @Test
    fun theBoundingBoxCoversEverySample() {
        val capture = GestureCapture()
        capture.begin(100f, 100f, 0L)
        capture.capture(Batch(
            floatArrayOf(40f, 260f, 90f),
            floatArrayOf(310f, 55f, 120f),
            longArrayOf(8L, 16L, 24L),
        ))

        assertEquals(40f, capture.minX, 0f)
        assertEquals(260f, capture.maxX, 0f)
        assertEquals(55f, capture.minY, 0f)
        assertEquals(310f, capture.maxY, 0f)
    }

    /**
     * The one invariant the whole class exists for: the arrays handed to the decoder are the
     * same objects the buffer started with, no matter how long the swipe ran.
     */
    @Test
    fun theBuffersAreNeverReplaced() {
        val capture = GestureCapture()
        val xs = capture.xs
        val ys = capture.ys
        val times = capture.times

        capture.begin(0f, 0f, 0L)
        // Four times the capacity, delivered in ragged batches the way a driver delivers them.
        var next = 1
        var size = 1
        while (next < capture.capacity * 4) {
            capture.capture(batch(next, size))
            next += size
            size = size % 7 + 1
        }

        assertSame("the x buffer was reallocated", xs, capture.xs)
        assertSame("the y buffer was reallocated", ys, capture.ys)
        assertSame("the timestamp buffer was reallocated", times, capture.times)
        assertTrue("the buffer overflowed", capture.count <= capture.capacity)
        assertTrue("the buffer never decimated", capture.decimations >= 2)
    }

    /**
     * Decimation has to keep the shape of the stroke, not just its length. Halving a monotone
     * ramp must leave a monotone ramp that still starts where the finger went down and still
     * reaches near where it is now.
     */
    @Test
    fun decimationKeepsTheShapeOfTheStroke() {
        val capture = GestureCapture(capacity = 16)
        capture.begin(0f, 0f, 0L)
        for (i in 1..64) {
            capture.append(i.toFloat(), i.toFloat(), i.toLong())
        }

        assertTrue(capture.count in 2..16)
        assertEquals("the first point was dropped", 0f, capture.xs[0], 0f)
        for (i in 1 until capture.count) {
            assertTrue(
                "decimation reordered the stroke",
                capture.xs[i] > capture.xs[i - 1],
            )
        }
        // Four halvings of a 64-sample ramp leave a stride of 16, so the last kept point is
        // within one stride of the newest sample rather than somewhere in the middle.
        val last = capture.xs[capture.count - 1]
        assertTrue("decimation lost the end of the stroke: last was $last", last >= 64f - 16f)
    }

    @Test
    fun resetKeepsTheStorageAndDropsThePoints() {
        val capture = GestureCapture()
        val xs = capture.xs
        capture.begin(5f, 5f, 0L)
        capture.capture(batch(1, 20))
        assertNotEquals(0, capture.count)

        capture.reset()

        assertEquals(0, capture.count)
        assertSame(xs, capture.xs)
    }

    /**
     * A new gesture must not inherit the previous one's bounding box, or the first trail
     * invalidation covers the rectangle between the two swipes and repaints most of the
     * keyboard for nothing.
     */
    @Test
    fun beginResetsTheBoundingBox() {
        val capture = GestureCapture()
        capture.begin(0f, 0f, 0L)
        capture.append(500f, 500f, 8L)

        capture.begin(300f, 200f, 0L)

        assertEquals(300f, capture.minX, 0f)
        assertEquals(300f, capture.maxX, 0f)
        assertEquals(200f, capture.minY, 0f)
        assertEquals(200f, capture.maxY, 0f)
        assertEquals(1, capture.count)
        assertEquals(0, capture.decimations)
    }
}
