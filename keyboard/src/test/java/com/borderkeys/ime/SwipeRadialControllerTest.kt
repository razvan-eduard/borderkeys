// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause/lift/pick state machine kept separate from the view and the input connection it
 * drives -- the same reason [LanguageSwitchCorrectorTest] tests its own class this way.
 */
class SwipeRadialControllerTest {

    @Test
    fun `starts idle`() {
        assertEquals(SwipeRadialController.State.IDLE, SwipeRadialController().state)
    }

    @Test
    fun `a pause with candidates moves idle to preview and says show it`() {
        val controller = SwipeRadialController()
        assertTrue(controller.onPauseDetected(listOf("the")))
        assertEquals(SwipeRadialController.State.PREVIEW, controller.state)
    }

    @Test
    fun `a pause with no candidates does nothing`() {
        val controller = SwipeRadialController()
        assertFalse(controller.onPauseDetected(emptyList()))
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `a pause while already previewing is not shown again`() {
        val controller = SwipeRadialController()
        controller.onPauseDetected(listOf("the"))
        assertFalse(controller.onPauseDetected(listOf("the")))
        assertEquals(SwipeRadialController.State.PREVIEW, controller.state)
    }

    @Test
    fun `a pause while the real menu is up is refused`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        assertFalse(controller.onPauseDetected(listOf("the")))
        assertEquals(SwipeRadialController.State.AWAITING_PICK, controller.state)
    }

    @Test
    fun `resuming closes a showing preview`() {
        val controller = SwipeRadialController()
        controller.onPauseDetected(listOf("the"))
        controller.onResumed()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `resuming from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onResumed()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `lifting shows the real menu from idle`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        assertEquals(SwipeRadialController.State.AWAITING_PICK, controller.state)
    }

    @Test
    fun `lifting shows the real menu from a preview in progress`() {
        val controller = SwipeRadialController()
        controller.onPauseDetected(listOf("the"))
        controller.onGestureLifted()
        assertEquals(SwipeRadialController.State.AWAITING_PICK, controller.state)
    }

    @Test
    fun `picking closes the menu`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        controller.onPicked()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `picking from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onPicked()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `timing out closes the menu`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        controller.onTimedOut()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `timing out from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onTimedOut()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `backspace closes the menu`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        controller.onBackspace()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `backspace from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onBackspace()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `any other key or action closes the menu`() {
        val controller = SwipeRadialController()
        controller.onGestureLifted()
        controller.onOtherKeyOrAction()
        assertEquals(SwipeRadialController.State.IDLE, controller.state)
    }

    @Test
    fun `any other key or action from idle is a no-op`() {
        val controller = SwipeRadialController()
        controller.onOtherKeyOrAction()
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
