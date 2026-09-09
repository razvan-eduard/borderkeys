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

    /** Three by default, and never outside the slider's own range once written back out. */
    @Test
    fun `the shortest word corrected defaults to three and stays on the slider`() {
        assertEquals(3, KeyboardPreferences().minCorrectionLength)
        assertEquals(
            KeyboardPreferences.MIN_CORRECTION_LENGTH,
            KeyboardPreferences(minCorrectionLength = -4).sanitised().minCorrectionLength,
        )
        assertEquals(
            KeyboardPreferences.MAX_CORRECTION_LENGTH,
            KeyboardPreferences(minCorrectionLength = 99).sanitised().minCorrectionLength,
        )
    }

    /** Manual by default, and an unrecognised stored value falls back to manual rather than
     *  being carried through -- there are only ever two valid modes. */
    @Test
    fun `theme mode defaults to manual and rejects anything but the two real modes`() {
        assertEquals(KeyboardPreferences.THEME_MODE_MANUAL, KeyboardPreferences().themeMode)
        assertEquals(
            KeyboardPreferences.THEME_MODE_AUTO_SYSTEM,
            KeyboardPreferences(themeMode = KeyboardPreferences.THEME_MODE_AUTO_SYSTEM)
                .sanitised().themeMode,
        )
        assertEquals(
            KeyboardPreferences.THEME_MODE_MANUAL,
            KeyboardPreferences(themeMode = 99).sanitised().themeMode,
        )
        assertEquals(
            KeyboardPreferences.THEME_MODE_MANUAL,
            KeyboardPreferences(themeMode = -1).sanitised().themeMode,
        )
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

    /** Strict is the only setting that stops consulting the others before it has decided. */
    @Test
    fun `only strict refuses to guess`() {
        assertTrue(
            KeyboardPreferences.languageLockStrict(KeyboardPreferences.LANGUAGE_LOCK_STRICT),
        )
        for (lock in listOf(
            KeyboardPreferences.LANGUAGE_LOCK_OFF,
            KeyboardPreferences.LANGUAGE_LOCK_PATIENT,
            KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
            KeyboardPreferences.LANGUAGE_LOCK_QUICK,
        )) {
            assertFalse("$lock should still consult every dictionary",
                KeyboardPreferences.languageLockStrict(lock))
        }
    }

    @Test
    fun `strict is a value the store keeps`() {
        assertEquals(
            KeyboardPreferences.LANGUAGE_LOCK_STRICT,
            KeyboardPreferences(languageLock = KeyboardPreferences.LANGUAGE_LOCK_STRICT)
                .sanitised().languageLock,
        )
    }

    @Test
    fun `an out-of-range lock is repaired rather than trusted`() {
        assertEquals(
            KeyboardPreferences.LANGUAGE_LOCK_BALANCED,
            KeyboardPreferences(languageLock = 99).sanitised().languageLock,
        )
    }

    /** Both step tables have to stay inside what sanitised() will accept, or a slider lies. */
    @Test
    fun `every retention step survives sanitising`() {
        for (minutes in KeyboardPreferences.RETENTION_STEPS) {
            assertEquals(
                "$minutes was clamped",
                minutes,
                KeyboardPreferences(clipboardRetentionMinutes = minutes)
                    .sanitised().clipboardRetentionMinutes,
            )
        }
    }

    @Test
    fun `every history size step survives sanitising`() {
        for (count in KeyboardPreferences.HISTORY_SIZE_STEPS) {
            assertEquals(
                "$count was clamped",
                count,
                KeyboardPreferences(clipboardMaxEntries = count).sanitised().clipboardMaxEntries,
            )
        }
    }

    @Test
    fun `the steps are ordered, so a slider moves one way`() {
        assertEquals(
            KeyboardPreferences.RETENTION_STEPS.sorted(),
            KeyboardPreferences.RETENTION_STEPS,
        )
        assertEquals(
            KeyboardPreferences.HISTORY_SIZE_STEPS.sorted(),
            KeyboardPreferences.HISTORY_SIZE_STEPS,
        )
    }

    /** A stored value between two steps has to land on one of them, not fall off the slider. */
    @Test
    fun `a value between steps snaps to the nearer one`() {
        val steps = KeyboardPreferences.RETENTION_STEPS
        assertEquals(0, KeyboardPreferences.nearestStep(steps, 1))
        assertEquals(steps.lastIndex, KeyboardPreferences.nearestStep(steps, 999_999))
        // 50 minutes is nearer 60 than 30
        assertEquals(60, steps[KeyboardPreferences.nearestStep(steps, 50)])
    }

    /**
     * The history is on by default and images are not, which is the whole point of the switch:
     * a copied word and a picture of someone's screen are not the same thing to remember.
     */
    @Test
    fun `images are off even though the history is on`() {
        val defaults = KeyboardPreferences()
        assertTrue("the history should still be on", defaults.clipboardEnabled)
        assertFalse("images should be asked for", defaults.clipboardImages)
    }

    /**
     * The typing conveniences default on and the noisy one defaults off.
     *
     * Held together in one test because the difference is the rule: something that changes what
     * you get is on, something that makes the phone do a thing it was not doing is off.
     */
    @Test
    fun `typing helps are on and the sound is not`() {
        val defaults = KeyboardPreferences()
        assertTrue("capitalisation should be on", defaults.autoCapitalise)
        assertTrue("two spaces should end a sentence", defaults.doubleSpacePeriod)
        assertTrue("the space bar should move the cursor", defaults.spaceCursorControl)
        assertFalse("a keypress should not make a sound unasked", defaults.keySound)
    }

    @Test
    fun `emoji recents are bounded and drop empties`() {
        val many = List(100) { "e$it" } + ""
        val kept = KeyboardPreferences(emojiRecents = many).sanitised().emojiRecents
        assertEquals(KeyboardPreferences.MAX_EMOJI_RECENTS, kept.size)
        assertFalse("an empty entry is not an emoji", kept.contains(""))
    }


    @Test
    fun `the draft box is on and its bar has the buttons that work without a model`() {
        val defaults = KeyboardPreferences()
        assertTrue("the draft box should be available", defaults.composerEnabled)
        val bar = ComposerAction.fromIds(defaults.composerBar)
        assertTrue("insert has to be on the bar", bar.contains(ComposerAction.INSERT))
        assertFalse(
            "a button that opens an empty list should not be there on a new install",
            bar.contains(ComposerAction.SAVED_PROMPTS),
        )
    }

    @Test
    fun `a bar written by a later build opens rather than failing`() {
        val fromLater = KeyboardPreferences(composerBar = listOf(8, 9999, 1, 1))
        val kept = fromLater.sanitised().composerBar
        assertEquals(
            "an unknown id should be dropped and a repeat should not be drawn twice",
            listOf(ComposerAction.INSERT.id, ComposerAction.GRAMMAR.id),
            kept,
        )
    }

    @Test
    fun `the draft box's text size defaults to medium and rejects anything but its three steps`() {
        assertEquals(
            KeyboardPreferences.COMPOSER_TEXT_SIZE_MEDIUM,
            KeyboardPreferences().composerTextSize,
        )
        assertEquals(
            KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL,
            KeyboardPreferences(composerTextSize = KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL)
                .sanitised().composerTextSize,
        )
        assertEquals(
            KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE,
            KeyboardPreferences(composerTextSize = KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE)
                .sanitised().composerTextSize,
        )
        assertEquals(
            "an unrecognised step is not a fourth size",
            KeyboardPreferences.COMPOSER_TEXT_SIZE_MEDIUM,
            KeyboardPreferences(composerTextSize = 99).sanitised().composerTextSize,
        )
        assertEquals(
            KeyboardPreferences.COMPOSER_TEXT_SIZE_MEDIUM,
            KeyboardPreferences(composerTextSize = -1).sanitised().composerTextSize,
        )
    }

    @Test
    fun `saved prompts are bounded and drop the blank ones`() {
        val many = List(100) { SavedPrompt(name = "n$it", text = "t$it") } +
            SavedPrompt(name = " ", text = "something") +
            SavedPrompt(name = "something", text = "")
        val kept = KeyboardPreferences(savedPrompts = many).sanitised().savedPrompts
        assertEquals(SavedPrompt.MAX_SAVED, kept.size)
        assertTrue("a prompt with no name or no text is not a prompt", kept.all {
            it.name.isNotBlank() && it.text.isNotBlank()
        })
    }

    @Test
    fun `a saved prompt cannot carry an unbounded string out of a corrupt file`() {
        val huge = SavedPrompt(name = "n".repeat(9000), text = "t".repeat(9000))
        val kept = KeyboardPreferences(savedPrompts = listOf(huge)).sanitised().savedPrompts
        assertEquals(SavedPrompt.MAX_NAME_CHARS, kept[0].name.length)
        assertEquals(SavedPrompt.MAX_TEXT_CHARS, kept[0].text.length)
    }
}
