// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapTrailTest {

    @Test
    fun `each code point keeps its key and point, and the last one can be taken back`() {
        val trail = TapTrail()
        trail.add(3, 10f, 20f)
        trail.add(7, 30f, 40f)
        assertEquals(2, trail.size)
        assertEquals(7, trail.keyIndexAt(1))
        assertEquals(30f, trail.xAt(1))
        assertEquals(40f, trail.yAt(1))
        trail.removeLast()
        assertEquals(1, trail.size)
        assertEquals(3, trail.keyIndexAt(0))
    }

    @Test
    fun `code points no tap chose have no point`() {
        val trail = TapTrail()
        trail.add(1, 5f, 5f)
        trail.addUntapped(2)
        assertEquals(3, trail.size)
        assertTrue(trail.isTapped(0))
        assertFalse(trail.isTapped(1))
        assertEquals(TapTrail.NO_KEY, trail.keyIndexAt(2))
    }

    @Test
    fun `a long word grows the trail`() {
        val trail = TapTrail()
        repeat(100) { trail.add(it, it.toFloat(), 0f) }
        assertEquals(100, trail.size)
        assertEquals(99f, trail.xAt(99))
        trail.clear()
        assertEquals(0, trail.size)
    }
}
