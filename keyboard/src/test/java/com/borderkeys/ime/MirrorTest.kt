// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class MirrorTest {

    @Test
    fun `left to right, slots keep their order`() {
        assertEquals(listOf(0, 1, 2), (0 until 3).map { Mirror.slot(it, 3, rightToLeft = false) })
    }

    @Test
    fun `right to left, the first item takes the rightmost slot`() {
        assertEquals(listOf(2, 1, 0), (0 until 3).map { Mirror.slot(it, 3, rightToLeft = true) })
        for (index in 0 until 5) {
            assertEquals(index, Mirror.slot(Mirror.slot(index, 5, true), 5, true))
        }
    }

    @Test
    fun `right to left, the first cell sits against the right edge and the spare room is at the left`() {
        assertEquals(0f, Mirror.cellLeft(0, 100f, 350f, rightToLeft = false))
        assertEquals(250f, Mirror.cellLeft(0, 100f, 350f, rightToLeft = true))
        assertEquals(50f, Mirror.cellLeft(2, 100f, 350f, rightToLeft = true))
    }

    @Test
    fun `the cell under a point is the cell drawn there, in both directions`() {
        for (rightToLeft in listOf(false, true)) {
            for (index in 0 until 3) {
                val centre = Mirror.cellLeft(index, 100f, 350f, rightToLeft) + 50f
                assertEquals("cell $index, rightToLeft $rightToLeft", index, Mirror.cellAt(centre, 100f, 350f, rightToLeft))
            }
        }
        // The spare room past the last whole cell is a fourth cell the caller does not have.
        assertEquals(3, Mirror.cellAt(20f, 100f, 350f, rightToLeft = true))
        assertEquals(3, Mirror.cellAt(330f, 100f, 350f, rightToLeft = false))
    }

    @Test
    fun `a point outside the row is no cell`() {
        assertEquals(-1, Mirror.cellAt(-1f, 100f, 350f, rightToLeft = false))
        assertEquals(-1, Mirror.cellAt(351f, 100f, 350f, rightToLeft = true))
        assertEquals(-1, Mirror.cellAt(350f, 100f, 350f, rightToLeft = false))
    }
}
