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
        left: Boolean = FlickClassifier.leftKey(kotlin.math.hypot(dx, dy), width, height, max),
    ): Int = FlickClassifier.classify(500f, 500f, 500f + dx, 500f + dy, path, millis, width, height, min, left)

    @Test
    fun `a press that barely moves is a tap`() {
        assertEquals(FlickClassifier.TAP, classify(0f, 0f))
        assertEquals(FlickClassifier.TAP, classify(30f, 20f))
    }

    @Test
    fun `a drag past the minimum that stays within the maximum is a flick in its sector`() {
        assertEquals(KeyFlick.NORTH, classify(0f, -90f))
        assertEquals(KeyFlick.EAST, classify(90f, 0f))
        assertEquals(KeyFlick.SOUTH, classify(0f, 90f))
        assertEquals(KeyFlick.WEST, classify(-90f, 0f))
        assertEquals(KeyFlick.NORTH_EAST, classify(70f, -70f))
        assertEquals(KeyFlick.SOUTH_WEST, classify(-70f, 70f))
    }

    @Test
    fun `a slow drag that never leaves the key is still a flick`() {
        assertEquals(KeyFlick.EAST, classify(150f, 0f, path = 400f, millis = 900L))
    }

    @Test
    fun `a sector is forty-five degrees wide, centred on its direction`() {
        assertEquals(KeyFlick.NORTH, FlickClassifier.direction(20f, -100f))
        assertEquals(KeyFlick.NORTH_EAST, FlickClassifier.direction(45f, -100f))
        assertEquals(KeyFlick.EAST, FlickClassifier.direction(100f, -30f))
        assertEquals(KeyFlick.NORTH_WEST, FlickClassifier.direction(-100f, -100f))
    }

    @Test
    fun `once past the maximum, a press is a swipe when its path reaches half a key width or it lasts`() {
        assertEquals(FlickClassifier.SWIPE, classify(0f, -400f, millis = 60L))
        assertEquals(FlickClassifier.SWIPE, classify(300f, 0f, millis = 60L))
        assertEquals(FlickClassifier.SWIPE, classify(0f, 0f, path = 700f, millis = 400L, left = true))
    }

    @Test
    fun `a flick takes its own sector when set, else the neighbour a fine sector away on its side`() {
        val onlyNorthEast = { direction: Int -> direction == KeyFlick.NORTH_EAST }
        assertEquals(KeyFlick.NORTH_EAST, FlickClassifier.flickDirection(70f, -70f, onlyNorthEast))
        assertEquals(KeyFlick.NORTH_EAST, FlickClassifier.flickDirection(27f, -100f, onlyNorthEast))
        assertEquals(-1, FlickClassifier.flickDirection(-27f, -100f, onlyNorthEast))
        assertEquals(-1, FlickClassifier.flickDirection(100f, 0f, onlyNorthEast))
    }

    @Test
    fun `leaving the key is measured from where the finger went down`() {
        assertFalse(FlickClassifier.pastTap(0f, 0f, 20f, 20f, width, height, min))
        assertTrue(FlickClassifier.pastTap(0f, 0f, 60f, 60f, width, height, min))
        assertFalse(FlickClassifier.leftKey(200f, width, height, max))
        assertTrue(FlickClassifier.leftKey(300f, width, height, max))
    }
}
