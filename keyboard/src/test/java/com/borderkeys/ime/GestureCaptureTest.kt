// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GestureCapture] against batched move events. */
class GestureCaptureTest {

    @Test
    fun `the path length sums the segments and is zero before a second point`() {
        val gesture = GestureCapture(8)
        gesture.begin(0f, 0f, 0L)
        assertEquals(0f, gesture.pathLength())
        gesture.append(3f, 4f, 1L)
        gesture.append(3f, 0f, 2L)
        assertEquals(9f, gesture.pathLength())
    }

    /** A batched move event, its last sample the current one; counts its x reads. */
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

        // Three events carrying five, one and eight samples.
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

    /** The arrays handed to the decoder stay the same objects however long the swipe runs. */
    @Test
    fun theBuffersAreNeverReplaced() {
        val capture = GestureCapture()
        val xs = capture.xs
        val ys = capture.ys
        val times = capture.times

        capture.begin(0f, 0f, 0L)
        // Four times the capacity, in ragged batches.
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

    /** Decimating a monotone ramp keeps it monotone, from its first point to near the newest. */
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
        // Four halvings of 64 samples leave a stride of 16.
        val last = capture.xs[capture.count - 1]
        assertTrue("decimation lost the end of the stroke: last was $last", last >= 64f - 16f)
    }

    @Test
    fun distanceFromPreviousIsZeroBeforeASecondPoint() {
        val capture = GestureCapture()
        assertEquals(0f, capture.distanceFromPrevious(), 0f)
        capture.begin(10f, 10f, 0L)
        assertEquals(0f, capture.distanceFromPrevious(), 0f)
    }

    @Test
    fun distanceFromPreviousIsTheLastStep() {
        val capture = GestureCapture()
        capture.begin(0f, 0f, 0L)
        capture.append(3f, 4f, 8L)
        assertEquals(5f, capture.distanceFromPrevious(), 0.001f)
        capture.append(3f, 4f, 16L)
        assertEquals(0f, capture.distanceFromPrevious(), 0.001f)
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
