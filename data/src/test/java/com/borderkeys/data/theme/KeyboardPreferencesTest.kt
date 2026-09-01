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

    /**
     * The learning speed is one number applied to two curves, so the three settings have to be
     * ordered and the middle one has to be the identity.
     */
    @Test
    fun theLearningSpeedsAreOrderedAroundTheDefault() {
        val cautious = KeyboardPreferences.learningSpeedFactor(KeyboardPreferences.LEARNING_CAUTIOUS)
        val balanced = KeyboardPreferences.learningSpeedFactor(KeyboardPreferences.LEARNING_BALANCED)
        val immediate =
            KeyboardPreferences.learningSpeedFactor(KeyboardPreferences.LEARNING_IMMEDIATE)

        assertTrue("cautious must be slower than the default", cautious < balanced)
        assertTrue("immediate must be faster than the default", immediate > balanced)
        assertEquals("the default must not scale anything", 1f, balanced, 0f)
        assertTrue("a speed must never be zero or negative", cautious > 0f)

        // An unknown value, from a hand-edited file or a future version, is the default rather
        // than nothing: a keyboard that silently stopped learning would be hard to diagnose.
        assertEquals(1f, KeyboardPreferences.learningSpeedFactor(99), 0f)
        assertEquals(1f, KeyboardPreferences.learningSpeedFactor(-1), 0f)
    }

    @Test
    fun anOutOfRangeLearningSpeedIsClampedToTheDefault() {
        assertEquals(
            KeyboardPreferences.LEARNING_BALANCED,
            KeyboardPreferences(learningSpeed = 42).sanitised().learningSpeed,
        )
        assertEquals(
            KeyboardPreferences.LEARNING_CAUTIOUS,
            KeyboardPreferences(learningSpeed = KeyboardPreferences.LEARNING_CAUTIOUS)
                .sanitised().learningSpeed,
        )
    }

    /**
     * The strip's buffers are sized for the maximum, so the range has to stay inside it or a
     * stored value would index past the end of an array on the keyboard's draw path.
     */
    @Test
    fun theSuggestionCountIsClampedToWhatTheStripCanDraw() {
        assertEquals(3, KeyboardPreferences.MIN_SUGGESTIONS)
        assertEquals(8, KeyboardPreferences.MAX_SUGGESTIONS)
        assertEquals(
            KeyboardPreferences.MAX_SUGGESTIONS,
            KeyboardPreferences(suggestionCount = 99).sanitised().suggestionCount,
        )
        assertEquals(
            KeyboardPreferences.MIN_SUGGESTIONS,
            KeyboardPreferences(suggestionCount = 0).sanitised().suggestionCount,
        )
        assertEquals(
            KeyboardPreferences.MIN_SUGGESTIONS,
            KeyboardPreferences(suggestionCount = -4).sanitised().suggestionCount,
        )
        assertEquals(5, KeyboardPreferences(suggestionCount = 5).sanitised().suggestionCount)
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
        assertEquals(KeyboardPreferences.LEARNING_BALANCED, defaults.learningSpeed)
        assertEquals(KeyboardPreferences.DEFAULT_SUGGESTIONS, defaults.suggestionCount)
        // Two-word suggestions are the one prediction feature that can be confidently wrong
        // about something the user did not write, so they are opt-in like autocorrect.
        assertFalse(defaults.phraseSuggestions)
        // The arrow costs nothing and answers a problem the settings screen answers badly, so
        // it is on. The blur costs a compositor pass per frame, so it is not.
        assertTrue(defaults.edgeArrows)
        assertFalse(defaults.blurBehindKeyboard)
        assertEquals(defaults, defaults.sanitised())
    }
    @Test
    fun `language lock is on and balanced by default`() {
        assertEquals(
            KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
            KeyboardPreferences().languageLock,
        )
    }

    /** Zero is the engine's "never lock", so off has to map to it exactly. */
    @Test
    fun `off asks the engine for no lock at all`() {
        assertEquals(
            0f,
            KeyboardPreferences.languageLockEvidence(KeyboardPreferences.LANGUAGE_LOCK_OFF),
        )
    }

    @Test
    fun `quick waits for less evidence than patient`() {
        val quick = KeyboardPreferences.languageLockEvidence(KeyboardPreferences.LANGUAGE_LOCK_QUICK)
        val balanced =
            KeyboardPreferences.languageLockEvidence(KeyboardPreferences.LANGUAGE_LOCK_BALANCED)
        val patient =
            KeyboardPreferences.languageLockEvidence(KeyboardPreferences.LANGUAGE_LOCK_PATIENT)
        assertTrue("quick should be the smallest threshold", quick < balanced)
        assertTrue("patient should be the largest threshold", balanced < patient)
    }

    @Test
    fun `an out-of-range lock is repaired rather than trusted`() {
        assertEquals(
            KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
            KeyboardPreferences(languageLock = 99).sanitised().languageLock,
        )
    }

}
