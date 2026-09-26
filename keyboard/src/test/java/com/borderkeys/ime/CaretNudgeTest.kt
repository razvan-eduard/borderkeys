// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaretNudgeTest {

    @Test
    fun `a plain slide moves the caret and selects nothing`() {
        val moved = CaretNudge.slide(start = 3, end = 3, previous = null, steps = 2, length = 10, selecting = false)
        assertEquals(CaretNudge.Selection(5, 5), moved)
        assertTrue(moved.empty)
    }

    @Test
    fun `a plain slide out of a selection leaves from its end and drops it`() {
        assertEquals(
            CaretNudge.Selection(6, 6),
            CaretNudge.slide(start = 2, end = 5, previous = null, steps = 1, length = 10, selecting = false),
        )
    }

    @Test
    fun `the caret stops at the ends of the field`() {
        assertEquals(0, CaretNudge.slide(2, 2, null, -5, 10, selecting = false).caret)
        assertEquals(10, CaretNudge.slide(8, 8, null, 5, 10, selecting = false).caret)
        assertEquals(0, CaretNudge.slide(2, 2, null, -5, 10, selecting = true).caret)
    }

    @Test
    fun `with shift held the slide selects from where the caret was`() {
        val selected = CaretNudge.slide(start = 3, end = 3, previous = null, steps = -2, length = 10, selecting = true)
        assertEquals(CaretNudge.Selection(anchor = 3, caret = 1), selected)
        assertEquals(1, selected.start)
        assertEquals(3, selected.end)
    }

    @Test
    fun `one drag keeps one anchor across its steps, in both directions`() {
        val first = CaretNudge.slide(3, 3, null, -2, 10, selecting = true)
        val second = CaretNudge.slide(first.start, first.end, first, -1, 10, selecting = true)
        assertEquals(CaretNudge.Selection(3, 0), second)
        val back = CaretNudge.slide(second.start, second.end, second, 4, 10, selecting = true)
        assertEquals(CaretNudge.Selection(3, 4), back)
    }

    @Test
    fun `a selection made some other way is extended from its end`() {
        val extended = CaretNudge.slide(start = 2, end = 5, previous = null, steps = 2, length = 10, selecting = true)
        assertEquals(CaretNudge.Selection(anchor = 2, caret = 7), extended)
    }

    @Test
    fun `a selection changed since the last slide is not continued from it`() {
        val earlier = CaretNudge.Selection(3, 1)
        val fresh = CaretNudge.slide(start = 4, end = 6, previous = earlier, steps = 1, length = 10, selecting = true)
        assertEquals(CaretNudge.Selection(4, 7), fresh)
        val grown = CaretNudge.slide(start = 1, end = 5, previous = earlier, steps = 1, length = 10, selecting = true)
        assertEquals(CaretNudge.Selection(1, 6), grown)
    }

    @Test
    fun `a line up or down keeps the column`() {
        val text = "abcd\nefgh\nij"
        assertEquals(2, CaretNudge.lineTarget(text, 7, -1))
        assertEquals(7, CaretNudge.lineTarget(text, 2, 1))
        assertEquals(2, CaretNudge.lineTarget(text, 12, -2))
    }

    @Test
    fun `a shorter line stops the caret at its end, and the field's ends stop it too`() {
        val text = "abcd\nefgh\nij"
        assertEquals(12, CaretNudge.lineTarget(text, 8, 1))
        assertEquals(3, CaretNudge.lineTarget(text, 3, -1))
        assertEquals(11, CaretNudge.lineTarget(text, 11, 3))
        assertEquals(0, CaretNudge.lineTarget("", 0, -1))
    }

    @Test
    fun `a line slide moves, or selects with shift held, from the moving end`() {
        val text = "ab\ncd"
        assertEquals(CaretNudge.Selection(2, 2), CaretNudge.slideLines(text, 5, 5, null, -1, selecting = false))
        val selected = CaretNudge.slideLines(text, 5, 5, null, -1, selecting = true)
        assertEquals(CaretNudge.Selection(5, 2), selected)
        val onward = CaretNudge.slideLines(text, selected.start, selected.end, selected, 1, selecting = true)
        assertEquals(CaretNudge.Selection(5, 5), onward)
    }
}
