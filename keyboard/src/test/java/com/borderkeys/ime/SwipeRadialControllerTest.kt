// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-state pause/resolve state machine kept separate from the view and the input
 * connection it drives -- the same reason [LanguageSwitchCorrectorTest] tests its own class
 * this way.
 */
class SwipeRadialControllerTest {

    @Test
    fun `starts idle`() {
        assertEquals(SwipeRadialController.State.IDLE, SwipeRadialController().state)
    }

    @Test
    fun `opening with candidates moves idle to open and says so`() {
        val controller = SwipeRadialController()
        assertTrue(controller.onRingOpened(listOf("tidy", "toasty")))
        assertEquals(SwipeRadialController.State.OPEN, controller.state)
    }

    @Test
    fun `opening with no candidates does nothing`() {
        val controller = SwipeRadialController()
        assertFalse(controller.onRingOpened(emptyList()))
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `opening twice for one gesture is refused`() {
        val controller = SwipeRadialController()
        controller.onRingOpened(listOf("tidy"))
        assertFalse(controller.onRingOpened(listOf("toasty")))
        assertEquals(SwipeRadialController.State.OPEN, controller.state)
    }

    @Test
    fun `resolving closes an open ring`() {
        val controller = SwipeRadialController()
        controller.onRingOpened(listOf("tidy"))
        controller.onResolved()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `resolving from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onResolved()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `eligible only past both the point count and the path length thresholds`() {
        assertFalse(SwipeRadialController.isEligibleForPreview(
            gestureCount = 3, pathLengthPx = 200f, minPoints = 6, minPathPx = 96f,
        ))
        assertFalse(SwipeRadialController.isEligibleForPreview(
            gestureCount = 10, pathLengthPx = 40f, minPoints = 6, minPathPx = 96f,
        ))
        assertFalse(SwipeRadialController.isEligibleForPreview(
            gestureCount = 3, pathLengthPx = 40f, minPoints = 6, minPathPx = 96f,
        ))
        assertTrue(SwipeRadialController.isEligibleForPreview(
            gestureCount = 10, pathLengthPx = 200f, minPoints = 6, minPathPx = 96f,
        ))
    }

    @Test
    fun `eligibility is inclusive at exactly the thresholds`() {
        assertTrue(SwipeRadialController.isEligibleForPreview(
            gestureCount = 6, pathLengthPx = 96f, minPoints = 6, minPathPx = 96f,
        ))
    }
}
