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
        assertTrue(
            "a selection grows to whole words by default",
            defaults.composerSnapSelectionToWords,
        )
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
            listOf(ComposerAction.INSERT.id, ComposerAction.CORRECT.id),
            kept,
        )
    }

    @Test
    fun `the per-category assist models default to empty and are length-bounded`() {
        assertEquals("", KeyboardPreferences().assistTranslateModel)
        assertEquals("", KeyboardPreferences().assistWriteModel)
        val long = "x".repeat(1000)
        val kept = KeyboardPreferences(assistTranslateModel = long, assistWriteModel = long)
            .sanitised()
        assertEquals(KeyboardPreferences.MAX_MODEL_FILE_NAME_CHARS, kept.assistTranslateModel.length)
        assertEquals(KeyboardPreferences.MAX_MODEL_FILE_NAME_CHARS, kept.assistWriteModel.length)
    }

    @Test
    fun `the layout and keys settings default sensibly and are repaired on read`() {
        val fresh = KeyboardPreferences()
        assertEquals(KeyboardPreferences.SYMBOLS_NUMBER_TOP, fresh.symbolsNumberPosition)
        assertEquals(true, fresh.accentedCharacters)
        assertEquals(true, fresh.longPressHints)
        assertEquals(false, fresh.largeKeyText)
        assertEquals(KeyboardPreferences.DEFAULT_LONG_PRESS_MILLIS, fresh.longPressMillis)

        // An out-of-range digit position is not a fourth arrangement.
        assertEquals(
            KeyboardPreferences.SYMBOLS_NUMBER_TOP,
            KeyboardPreferences(symbolsNumberPosition = 9).sanitised().symbolsNumberPosition,
        )
        assertEquals(
            KeyboardPreferences.SYMBOLS_NUMBER_RIGHT,
            KeyboardPreferences(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_RIGHT)
                .sanitised().symbolsNumberPosition,
        )
        // The long-press delay is clamped to a range a deliberate tap never trips and a hold
        // never feels stuck in.
        assertEquals(
            KeyboardPreferences.MIN_LONG_PRESS_MILLIS,
            KeyboardPreferences(longPressMillis = 1).sanitised().longPressMillis,
        )
        assertEquals(
            KeyboardPreferences.MAX_LONG_PRESS_MILLIS,
            KeyboardPreferences(longPressMillis = 100_000).sanitised().longPressMillis,
        )

        assertEquals(KeyboardPreferences.ENTER_KEY_AUTO, fresh.enterKeyBehavior)
        assertEquals(
            "an out-of-range mode is not a fourth behaviour",
            KeyboardPreferences.ENTER_KEY_AUTO,
            KeyboardPreferences(enterKeyBehavior = 9).sanitised().enterKeyBehavior,
        )
        assertEquals(
            KeyboardPreferences.ENTER_KEY_FORCE_NEWLINE,
            KeyboardPreferences(enterKeyBehavior = KeyboardPreferences.ENTER_KEY_FORCE_NEWLINE)
                .sanitised().enterKeyBehavior,
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
    fun `custom actions are bounded and drop the blank ones`() {
        val many = List(100) { CustomAction(name = "n$it", instruction = "t$it") } +
            CustomAction(name = " ", instruction = "something") +
            CustomAction(name = "something", instruction = "")
        val kept = KeyboardPreferences(customActions = many).sanitised().customActions
        assertEquals(CustomAction.MAX_CUSTOM_ACTIONS, kept.size)
        assertTrue("a custom action with no name or no instruction is not one", kept.all {
            it.name.isNotBlank() && it.instruction.isNotBlank()
        })
    }

    @Test
    fun `a custom action cannot carry an unbounded string out of a corrupt file`() {
        val huge = CustomAction(name = "n".repeat(9000), instruction = "t".repeat(9000))
        val kept = KeyboardPreferences(customActions = listOf(huge)).sanitised().customActions
        assertEquals(CustomAction.MAX_NAME_CHARS, kept[0].name.length)
        assertEquals(CustomAction.MAX_INSTRUCTION_CHARS, kept[0].instruction.length)
    }

    @Test
    fun `a custom action decoded with no id is backfilled with a stable one, not dropped`() {
        val legacy = CustomAction(name = "shorten it", instruction = "make this shorter")
        val first = KeyboardPreferences(customActions = listOf(legacy)).sanitised().customActions
        assertEquals(1, first.size)
        assertTrue("a backfilled id must not be the 0 sentinel", first[0].id != 0)

        // sanitised() must be idempotent: the SAME list, sanitised twice, gets the SAME id both
        // times -- a fresh random id on every call would silently unpin anything already on the
        // bar the moment preferences are re-saved.
        val second = KeyboardPreferences(customActions = first).sanitised().customActions
        assertEquals(first[0].id, second[0].id)
    }

    @Test
    fun `composerBar keeps a pinned custom action's id and drops an unknown one`() {
        val action = CustomAction(id = 5000, name = "pirate", instruction = "talk like a pirate")
        val preferences = KeyboardPreferences(
            customActions = listOf(action),
            composerBar = listOf(ComposerAction.CORRECT.id, action.id, 999_999),
        ).sanitised()
        assertEquals(listOf(ComposerAction.CORRECT.id, action.id), preferences.composerBar)
    }

    @Test
    fun `custom quick actions are bounded and drop the blank ones`() {
        val many = List(100) { CustomQuickAction(name = "n$it", steps = listOf(QuickAction.CUT.id)) } +
            CustomQuickAction(name = " ", steps = listOf(QuickAction.CUT.id))
        val kept = KeyboardPreferences(customQuickActions = many).sanitised().customQuickActions
        assertEquals(CustomQuickAction.MAX_CUSTOM_QUICK_ACTIONS, kept.size)
        assertTrue("a custom quick action with no name is not one", kept.all { it.name.isNotBlank() })
    }

    @Test
    fun `a custom quick action cannot carry an unbounded name out of a corrupt file`() {
        val huge = CustomQuickAction(id = 1000, name = "n".repeat(9000), steps = listOf(QuickAction.CUT.id))
        val kept = KeyboardPreferences(customQuickActions = listOf(huge)).sanitised().customQuickActions
        assertEquals(CustomQuickAction.MAX_NAME_CHARS, kept[0].name.length)
    }

    @Test
    fun `quickActions keeps a pinned custom action's id and drops an unknown one`() {
        val action = CustomQuickAction(id = 5000, name = "select and cut", steps = listOf(QuickAction.CUT.id))
        val preferences = KeyboardPreferences(
            customQuickActions = listOf(action),
            quickActions = listOf(QuickAction.SELECT_ALL.id, action.id, 999_999),
        ).sanitised()
        assertEquals(listOf(QuickAction.SELECT_ALL.id, action.id), preferences.quickActions)
    }

    @Test
    fun `a macro step naming an action that is not macro-eligible is dropped on read`() {
        val macro = CustomQuickAction(
            id = 1000,
            name = "cut then settings",
            steps = listOf(QuickAction.CUT.id, QuickAction.SETTINGS.id),
        )
        val kept = KeyboardPreferences(customQuickActions = listOf(macro)).sanitised().customQuickActions
        assertEquals(listOf(QuickAction.CUT.id), kept[0].steps)
    }

    @Test
    fun `a direct reference cycle between two custom quick actions is not left in the file`() {
        val a = CustomQuickAction(id = 1000, name = "a", steps = listOf(1001, QuickAction.CUT.id))
        val b = CustomQuickAction(id = 1001, name = "b", steps = listOf(1000, QuickAction.SELECT_ALL.id))
        val kept = KeyboardPreferences(customQuickActions = listOf(a, b)).sanitised().customQuickActions
        assertTrue(
            "a cyclic macro's own steps must be cleared rather than left to recurse forever",
            kept.all { it.steps.none { step -> step == 1000 || step == 1001 } },
        )
    }
}
