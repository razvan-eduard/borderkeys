// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyFlick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A press on a 100 by 150 key, its diagonal about 180, under the default thresholds. */
class FlickClassifierTest {

    private val width = 100f
    private val height = 150f
    private val min = 0.28f
    private val max = 1.41f

    private fun classify(
        dx: Float,
        dy: Float,
        path: Float = kotlin.math.hypot(dx, dy),
        millis: Long = 80L,
        left: Boolean = FlickClassifier.leftKey(path, width, height, max),
    ): Int = FlickClassifier.classify(500f, 500f, 500f + dx, 500f + dy, path, millis, width, height, min, left)

    @Test
    fun `a press that barely moves is a tap`() {
        assertEquals(FlickClassifier.TAP, classify(0f, 0f))
        assertEquals(FlickClassifier.TAP, classify(30f, 20f))
    }

    @Test
    fun `a drag past the minimum and within the maximum is a flick in its sector`() {
        assertEquals(KeyFlick.NORTH, classify(0f, -90f))
        assertEquals(KeyFlick.EAST, classify(90f, 0f))
        assertEquals(KeyFlick.SOUTH, classify(0f, 90f))
        assertEquals(KeyFlick.WEST, classify(-90f, 0f))
        assertEquals(KeyFlick.NORTH_EAST, classify(70f, -70f))
        assertEquals(KeyFlick.SOUTH_WEST, classify(-70f, 70f))
    }

    @Test
    fun `a sector is forty-five degrees wide, centred on its direction`() {
        assertEquals(KeyFlick.NORTH, FlickClassifier.direction(20f, -100f))
        assertEquals(KeyFlick.NORTH_EAST, FlickClassifier.direction(45f, -100f))
        assertEquals(KeyFlick.EAST, FlickClassifier.direction(100f, -30f))
        assertEquals(KeyFlick.NORTH_WEST, FlickClassifier.direction(-100f, -100f))
    }

    @Test
    fun `past the key, a slow or bent path is a swipe`() {
        assertEquals(FlickClassifier.SWIPE, classify(0f, -400f, millis = 400L))
        assertEquals(FlickClassifier.SWIPE, classify(300f, 0f, path = 500f, millis = 80L))
    }

    @Test
    fun `past the key, one fast straight stroke is still a flick`() {
        assertEquals(KeyFlick.EAST, classify(300f, 0f, millis = 60L))
        assertEquals(KeyFlick.NORTH, classify(0f, -400f, millis = 100L))
    }

    @Test
    fun `a swipe that comes back near where it began is a swipe once it has left the key`() {
        assertEquals(FlickClassifier.SWIPE, classify(-60f, 0f, path = 700f, millis = 400L, left = true))
        assertEquals(FlickClassifier.SWIPE, classify(0f, 0f, path = 700f, millis = 400L, left = true))
    }

    @Test
    fun `leaving the key and passing the tap are the two thresholds`() {
        assertFalse(FlickClassifier.pastTap(0f, 0f, 20f, 20f, width, height, min))
        assertTrue(FlickClassifier.pastTap(0f, 0f, 60f, 60f, width, height, min))
        assertFalse(FlickClassifier.leftKey(200f, width, height, max))
        assertTrue(FlickClassifier.leftKey(300f, width, height, max))
    }
}
