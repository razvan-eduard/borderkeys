// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ShiftFlow]'s record of shift before a swiped word, and what ends it. */
class ShiftFlowTest {

    private val clock = ManualClock()
    private val host = FakeTypingHost(clock)
    private val flow = ShiftFlow({ null }, host, clock)

    private fun field(generation: Int) = FieldSession(
        generation = generation,
        policy = FieldPolicy.of(
            passwordField = false,
            privateField = false,
            learningEnabled = true,
            heatmapEnabled = true,
        ),
        addressField = false,
        terminalField = false,
        described = true,
    )

    /** A field with auto-capitalise off, shift pressed once, then a swiped word cased. */
    private fun swipedUnderShift() {
        flow.applySettings(KeyboardPreferences(autoCapitalise = false))
        flow.startField(field(1))
        flow.press()
        assertEquals(ShiftState.ON, flow.state)
        assertTrue(flow.heldByUser)
        flow.swipedWordCased()
    }

    private fun assertSpent() {
        assertEquals(ShiftState.OFF, flow.state)
        assertFalse(flow.heldByUser)
    }

    @Test
    fun `a swiped word spends the shift pressed for it, and taking the word back gives it back`() {
        swipedUnderShift()
        assertSpent()
        flow.restoreAfterSwipeTakenBack()
        assertEquals(ShiftState.ON, flow.state)
        assertTrue(flow.heldByUser)
    }

    @Test
    fun `a new field ends the record of shift before the swipe`() {
        swipedUnderShift()
        flow.startField(field(2))
        flow.restoreAfterSwipeTakenBack()
        assertSpent()
    }

    @Test
    fun `a caps lock set after the swipe ends the record`() {
        swipedUnderShift()
        flow.lock()
        flow.restoreAfterSwipeTakenBack()
        assertEquals(ShiftState.LOCKED, flow.state)
        assertTrue(flow.heldByUser)
    }

    @Test
    fun `a letter typed after the swipe ends the record`() {
        swipedUnderShift()
        flow.letterTyped()
        flow.restoreAfterSwipeTakenBack()
        assertSpent()
    }

    @Test
    fun `a pick after the swipe ends the record`() {
        swipedUnderShift()
        flow.clearHeld()
        flow.restoreAfterSwipeTakenBack()
        assertSpent()
    }

    @Test
    fun `a delimiter after the swipe ends the record`() {
        swipedUnderShift()
        flow.afterDelimiter(heldByUser = false, composingEmpty = true)
        flow.restoreAfterSwipeTakenBack()
        assertSpent()
    }
}
