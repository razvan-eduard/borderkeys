// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.geometry

import org.junit.Assert.assertEquals
import org.junit.Test

class HalvesTest {

    @Test
    fun `a half is the length over its two sides, exactly`() {
        val length = 7f
        assertEquals(length / SIDES, half(length), 0f)
        assertEquals(length, half(length) + half(length), 0f)
        assertEquals(SIDES, halfDown(SIDES + SIDES))
        assertEquals("an odd length rounds down", 1, halfDown(SIDES + 1))
        assertEquals(SIDES, middleDown(1, SIDES + SIDES))
    }

    @Test
    fun `the middle of two points is as far from each`() {
        val start = 3f
        val end = 11f
        assertEquals(middle(start, end) - start, end - middle(start, end), 0f)
    }

    @Test
    fun `both sides double, a diameter is two radii, a perimeter two of each side`() {
        val inset = 5f
        assertEquals(inset + inset, bothSides(inset), 0f)
        assertEquals(SIDES + SIDES, bothSides(SIDES))
        assertEquals(inset + inset, diameter(inset), 0f)
        val width = 4f
        val height = 9f
        assertEquals(width + height + width + height, perimeter(width, height), 0f)
    }

    @Test
    fun `a slot's centre is half a slot past its start, and the turns are whole and quartered`() {
        val size = 6f
        assertEquals(size * SIDES + half(size), centreOfSlot(SIDES, size), 0f)
        assertEquals(FULL_TURN, QUARTER_TURN * SIDES * SIDES, 0f)
        assertEquals(half(SIDES.toDouble()), 1.0, 0.0)
        assertEquals(bothSides(1.0), SIDES.toDouble(), 0.0)
    }
}
