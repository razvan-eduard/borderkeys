// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size and position settings, and the clamping that keeps a bad file from hiding the UI. */
class KeyboardPreferencesTest {

    @Test
    fun leavingTheDockNarrowsTheKeyboardOnce() {
        val docked = KeyboardPreferences()
        assertEquals(1f, docked.widthScale, 0f)

        val left = docked.withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_LEFT)
        assertEquals(KeyboardPreferences.MODE_ONE_HANDED_LEFT, left.positionMode)
        assertEquals(KeyboardPreferences.ONE_HANDED_WIDTH_SCALE, left.widthScale, 0f)
    }

    @Test
    fun aWidthTheUserChoseIsNotOverwritten() {
        val chosen = KeyboardPreferences(widthScale = 0.7f)
            .withPositionMode(KeyboardPreferences.MODE_FLOATING)
        assertEquals(0.7f, chosen.widthScale, 0f)

        // Including a deliberate return to full width, from a mode that is already undocked.
        val widened = chosen.copy(widthScale = 1f)
            .withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_RIGHT)
        assertEquals(1f, widened.widthScale, 0f)
    }

    @Test
    fun returningToTheDockKeepsTheWidthForNextTime() {
        val left = KeyboardPreferences().withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_LEFT)
        val narrowed = left.copy(widthScale = 0.6f)
        val docked = narrowed.withPositionMode(KeyboardPreferences.MODE_DOCKED)

        assertEquals(KeyboardPreferences.MODE_DOCKED, docked.positionMode)
        assertEquals("the chosen width was thrown away", 0.6f, docked.widthScale, 0f)

        val backToLeft = docked.withPositionMode(KeyboardPreferences.MODE_ONE_HANDED_LEFT)
        assertEquals(0.6f, backToLeft.widthScale, 0f)
    }

    @Test
    fun isOneHandedCoversBothSidesAndNothingElse() {
        assertTrue(
            KeyboardPreferences(positionMode = KeyboardPreferences.MODE_ONE_HANDED_LEFT)
                .isOneHanded,
        )
        assertTrue(
            KeyboardPreferences(positionMode = KeyboardPreferences.MODE_ONE_HANDED_RIGHT)
                .isOneHanded,
        )
        assertFalse(KeyboardPreferences().isOneHanded)
        assertFalse(
            KeyboardPreferences(positionMode = KeyboardPreferences.MODE_FLOATING).isOneHanded,
        )
    }

    /**
     * A hand-edited or corrupted file must not be able to produce a keyboard the user cannot
     * reach the settings through -- one scaled to nothing, or pushed off the screen.
     */
    @Test
    fun sanitisingClampsEverySizeAndPosition() {
        val absurd = KeyboardPreferences(
            heightScale = 40f,
            widthScale = 0f,
            positionMode = 99,
            bottomOffsetDp = 100000f,
            horizontalOffsetDp = -100000f,
        ).sanitised()

        assertEquals(KeyboardPreferences.MAX_HEIGHT_SCALE, absurd.heightScale, 0f)
        assertEquals(KeyboardPreferences.MIN_WIDTH_SCALE, absurd.widthScale, 0f)
        assertEquals(KeyboardPreferences.MODE_DOCKED, absurd.positionMode)
        assertEquals(KeyboardPreferences.MAX_BOTTOM_OFFSET_DP, absurd.bottomOffsetDp, 0f)
        assertEquals(-160f, absurd.horizontalOffsetDp, 0f)

        val negative = KeyboardPreferences(heightScale = -1f, positionMode = -3).sanitised()
        assertEquals(KeyboardPreferences.MIN_HEIGHT_SCALE, negative.heightScale, 0f)
        assertEquals(KeyboardPreferences.MODE_DOCKED, negative.positionMode)
    }

    @Test
    fun sanitisingLeavesAReasonableFileAlone() {
        val reasonable = KeyboardPreferences(
            heightScale = 1.1f,
            widthScale = 0.8f,
            positionMode = KeyboardPreferences.MODE_FLOATING,
            bottomOffsetDp = 48f,
            horizontalOffsetDp = -20f,
        )
        assertEquals(reasonable, reasonable.sanitised())
    }

    /**
     * Applying a suggestion on space is opt-in, and taking it back is not.
     *
     * The pair is the whole argument for offering autocorrect at all: the objection to it is
     * that undoing a wrong correction costs more than typing the word did. A build that shipped
     * the first switch on, or the second off, would be the thing this keyboard argues against.
     */
    @Test
    fun autoCorrectIsOffByDefaultAndItsUndoIsOn() {
        val defaults = KeyboardPreferences()
        assertFalse(defaults.autoCorrectOnSpace)
        assertTrue(defaults.revertCorrectionOnBackspace)
        assertTrue(defaults.showSuggestionStrip)
    }

    /** The defaults are what a first run gets, and a first run should get an ordinary keyboard. */
    @Test
    fun theDefaultIsAPlainDockedKeyboard() {
        val defaults = KeyboardPreferences()
        assertEquals(KeyboardPreferences.MODE_DOCKED, defaults.positionMode)
        assertEquals(1f, defaults.heightScale, 0f)
        assertEquals(1f, defaults.widthScale, 0f)
        assertEquals(0f, defaults.bottomOffsetDp, 0f)
        assertEquals(0f, defaults.horizontalOffsetDp, 0f)
        assertEquals(defaults, defaults.sanitised())
    }
}
