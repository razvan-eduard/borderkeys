// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackpointTest {

    private val deadZone = 15f
    private val halfDiagonal = 90f

    @Test
    fun `inside the dead zone nothing moves and the next tick is the slowest`() {
        val tick = Trackpoint.tick(10f, -10f, deadZone, halfDiagonal, 100)
        assertEquals(0, tick.xSteps)
        assertEquals(0, tick.ySteps)
        assertEquals(Trackpoint.MAX_DELAY_MILLIS, tick.delayMillis)
    }

    @Test
    fun `each axis steps on its own once past the dead zone`() {
        assertEquals(1, Trackpoint.tick(30f, 0f, deadZone, halfDiagonal, 100).xSteps)
        assertEquals(-1, Trackpoint.tick(-30f, 0f, deadZone, halfDiagonal, 100).xSteps)
        val both = Trackpoint.tick(-30f, 40f, deadZone, halfDiagonal, 100)
        assertEquals(-1, both.xSteps)
        assertEquals(1, both.ySteps)
        assertEquals(0, Trackpoint.tick(30f, 5f, deadZone, halfDiagonal, 100).ySteps)
    }

    @Test
    fun `the further the finger, the sooner the next tick, down to the floor at half the diagonal`() {
        val near = Trackpoint.tick(20f, 0f, deadZone, halfDiagonal, 100).delayMillis
        val mid = Trackpoint.tick(50f, 0f, deadZone, halfDiagonal, 100).delayMillis
        val far = Trackpoint.tick(90f, 0f, deadZone, halfDiagonal, 100).delayMillis
        assertTrue(near > mid && mid > far)
        assertEquals(Trackpoint.MIN_DELAY_MILLIS, far)
        assertEquals(Trackpoint.MIN_DELAY_MILLIS, Trackpoint.tick(400f, 0f, deadZone, halfDiagonal, 100).delayMillis)
    }

    @Test
    fun `the speed setting scales the delay, within the floor and twice the ceiling`() {
        // Halfway across: 115 ms, which halves and doubles without a remainder.
        val normal = Trackpoint.tick(52.5f, 0f, deadZone, halfDiagonal, 100).delayMillis
        assertEquals(115L, normal)
        assertEquals(normal / 2, Trackpoint.tick(52.5f, 0f, deadZone, halfDiagonal, 200).delayMillis)
        assertEquals(normal * 2, Trackpoint.tick(52.5f, 0f, deadZone, halfDiagonal, 50).delayMillis)
        assertEquals(Trackpoint.MAX_DELAY_MILLIS * 2, Trackpoint.tick(0f, 0f, deadZone, halfDiagonal, 10).delayMillis)
    }
}
