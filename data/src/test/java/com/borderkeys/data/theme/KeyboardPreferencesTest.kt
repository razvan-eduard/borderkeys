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
    fun animationsFollowTheirModeAndAFileFromBeforeKeepsWhatItHad() {
        val fresh = EffectsSettings()
        assertEquals(EffectsSettings.MODE_SYSTEM, fresh.animationMode)
        assertTrue(fresh.plays(systemAnimated = true))
        assertFalse(fresh.plays(systemAnimated = false))
        assertEquals(EffectsSettings.MODE_OFF, EffectsSettings(enabled = false).animationMode)
        assertFalse(EffectsSettings(enabled = false).plays(systemAnimated = true))
        val always = EffectsSettings(enabled = false, mode = EffectsSettings.MODE_ON)
        assertTrue(always.plays(systemAnimated = false))
        assertTrue(always.anyOn)
        assertEquals(
            null,
            KeyboardPreferences(effects = EffectsSettings(mode = 7)).sanitised().effects.mode,
        )
        assertEquals(
            0.25f,
            KeyboardPreferences(effects = EffectsSettings(speed = 0.01f)).sanitised().effects.speed,
            0f,
        )
    }

    @Test
    fun theImageOfferWindowIsOneOfItsSteps() {
        assertEquals(5, KeyboardPreferences().imageOfferMinutes)
        assertEquals(0, KeyboardPreferences(imageOfferMinutes = 0).sanitised().imageOfferMinutes)
        assertEquals(60, KeyboardPreferences(imageOfferMinutes = 60).sanitised().imageOfferMinutes)
        assertEquals(5, KeyboardPreferences(imageOfferMinutes = 7).sanitised().imageOfferMinutes)
        assertEquals(5, KeyboardPreferences(imageOfferMinutes = -1).sanitised().imageOfferMinutes)
    }

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

    @Test
    fun autoCorrectIsOffByDefaultAndItsUndoIsOn() {
        val defaults = KeyboardPreferences()
        assertFalse(defaults.autoCorrectOnSpace)
        assertFalse(defaults.autoCorrectOnEnter)
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

    @Test
    fun `rare words default to the listed ones and stay on the slider`() {
        assertEquals(KeyboardPreferences.RARE_WORDS_LISTED, KeyboardPreferences().rareWords)
        assertEquals(
            KeyboardPreferences.RARE_WORDS_LISTED,
            KeyboardPreferences(rareWords = -2).sanitised().rareWords,
        )
        assertEquals(
            KeyboardPreferences.RARE_WORDS_ALL,
            KeyboardPreferences(rareWords = 9).sanitised().rareWords,
        )
    }

    @Test
    fun `each rare-words step counts more words than the one before`() {
        val reach = (KeyboardPreferences.RARE_WORDS_LISTED..KeyboardPreferences.RARE_WORDS_ALL)
            .map { KeyboardPreferences.rareWordsMinLogProb(it) }
        assertEquals(Float.POSITIVE_INFINITY, reach.first())
        assertEquals(Float.NEGATIVE_INFINITY, reach.last())
        assertEquals(reach.sortedDescending(), reach)
        assertEquals(reach.size, reach.distinct().size)
    }

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

    /** A file from before the uses setting keeps what its learning speed asked for. */
    @Test
    fun anOldLearningSpeedBecomesItsNumberOfUses() {
        fun usesFor(speed: Int) = KeyboardPreferences(learningSpeed = speed).sanitised().learnAfter
        assertEquals(10, usesFor(KeyboardPreferences.LEARNING_CAUTIOUS))
        assertEquals(3, usesFor(KeyboardPreferences.LEARNING_BALANCED))
        assertEquals(1, usesFor(KeyboardPreferences.LEARNING_IMMEDIATE))
        assertEquals(
            "a chosen number wins over the old speed",
            5,
            KeyboardPreferences(learningSpeed = KeyboardPreferences.LEARNING_CAUTIOUS, learnAfterUses = 5)
                .sanitised().learnAfter,
        )
    }

    @Test
    fun theUsesAndTheFadeLandOnTheirStops() {
        assertEquals(5, KeyboardPreferences(learnAfterUses = 7).sanitised().learnAfterUses)
        assertEquals(10, KeyboardPreferences(learnAfterUses = 99).sanitised().learnAfterUses)
        assertEquals(1, KeyboardPreferences(learnAfterUses = -2).sanitised().learnAfterUses)
        assertEquals(90, KeyboardPreferences(unlearnHalfLifeDays = 100).sanitised().unlearnHalfLifeDays)
        assertEquals(7, KeyboardPreferences(unlearnHalfLifeDays = 0).sanitised().unlearnHalfLifeDays)
        assertEquals(365, KeyboardPreferences(unlearnHalfLifeDays = 5000).sanitised().unlearnHalfLifeDays)
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
        assertEquals(3, defaults.learnAfter)
        assertEquals(90, defaults.unlearnHalfLifeDays)
        assertEquals(KeyboardPreferences.DEFAULT_SUGGESTIONS, defaults.suggestionCount)
        assertFalse(defaults.phraseSuggestions)
        assertTrue(defaults.edgeArrows)
        assertFalse(defaults.blurBehindKeyboard)
        assertEquals(defaults, defaults.sanitised())
    }
    @Test
    fun `the neural swipe model defaults on`() {
        assertTrue(KeyboardPreferences().experimentalSwipeModelEnabled)
    }

    @Test
    fun `the globe key defaults on`() {
        assertTrue(KeyboardPreferences().languageKey)
    }

    @Test
    fun `quick action labels default off`() {
        assertFalse(KeyboardPreferences().quickActionsLabels)
    }

    @Test
    fun `a picked suggestion gets its space by default`() {
        assertTrue(KeyboardPreferences().spaceAfterSuggestion)
    }

    @Test
    fun `the key popup defaults on and the vibration to the system's own`() {
        assertTrue(KeyboardPreferences().keyPopup)
        assertEquals(KeyboardPreferences.HAPTIC_SYSTEM, KeyboardPreferences().hapticStrength)
        assertEquals(KeyboardPreferences.HAPTIC_SYSTEM, KeyboardPreferences(hapticStrength = 9).sanitised().hapticStrength)
        assertEquals(KeyboardPreferences.HAPTIC_STRONG, KeyboardPreferences(hapticStrength = 2).sanitised().hapticStrength)
    }

    @Test
    fun `text shortcuts are one word each, once per trigger`() {
        val stored = KeyboardPreferences(
            textShortcuts = listOf(
                TextShortcut(" omw ", " on my way "),
                TextShortcut("OMW", "second"),
                TextShortcut("two words", "dropped"),
                TextShortcut("blank", "   "),
                TextShortcut("sig", "Best, R."),
            ),
        ).sanitised()
        assertEquals(
            listOf(TextShortcut("omw", "on my way"), TextShortcut("sig", "Best, R.")),
            stored.textShortcuts,
        )
    }

    @Test
    fun `the radial suggestion menu defaults off`() {
        assertFalse(KeyboardPreferences().radialMenuEnabled)
    }

    @Test
    fun `keeping the ring open on an inconclusive lift defaults off`() {
        assertFalse(KeyboardPreferences().radialLiftKeepsOpen)
    }

    @Test
    fun `a tap outside the ring only closes the ring by default, and the ring follows a moving field`() {
        assertFalse(KeyboardPreferences().radialOutsideTapHidesKeyboard)
        assertTrue(KeyboardPreferences().radialCloseOnEditorMove)
    }

    @Test
    fun `the habitual space after an automatic one is swallowed once by default, and the value is clamped`() {
        assertEquals(KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST, KeyboardPreferences().autoSpaceHabit)
        assertEquals(
            KeyboardPreferences.AUTO_SPACE_SWALLOW_FIRST,
            KeyboardPreferences(autoSpaceHabit = 7).sanitised().autoSpaceHabit,
        )
        assertEquals(
            KeyboardPreferences.AUTO_SPACE_KEEP,
            KeyboardPreferences(autoSpaceHabit = KeyboardPreferences.AUTO_SPACE_KEEP).sanitised().autoSpaceHabit,
        )
    }

    @Test
    fun `backspace after a swiped word takes one letter by default`() {
        assertFalse(KeyboardPreferences().swipeBackspaceDeletesWord)
    }

    @Test
    fun `the correction distance defaults to normal and rejects anything but its three values`() {
        assertEquals(KeyboardPreferences.CORRECTION_DISTANCE_NORMAL, KeyboardPreferences().correctionDistance)
        assertEquals(
            KeyboardPreferences.CORRECTION_DISTANCE_NORMAL,
            KeyboardPreferences(correctionDistance = -1).sanitised().correctionDistance,
        )
        assertEquals(
            KeyboardPreferences.CORRECTION_DISTANCE_LOOSE,
            KeyboardPreferences(correctionDistance = KeyboardPreferences.CORRECTION_DISTANCE_LOOSE)
                .sanitised().correctionDistance,
        )
    }

    @Test
    fun `blurring behind the ring defaults on`() {
        assertTrue(KeyboardPreferences().radialBlurBackground)
    }

    @Test
    fun `the radial suggestion count is clamped tighter than the strip`() {
        assertEquals(3, KeyboardPreferences.MIN_RADIAL_SUGGESTIONS)
        assertEquals(6, KeyboardPreferences.MAX_RADIAL_SUGGESTIONS)
        assertEquals(
            KeyboardPreferences.MAX_RADIAL_SUGGESTIONS,
            KeyboardPreferences(radialSuggestionCount = 99).sanitised().radialSuggestionCount,
        )
        assertEquals(
            KeyboardPreferences.MIN_RADIAL_SUGGESTIONS,
            KeyboardPreferences(radialSuggestionCount = 0).sanitised().radialSuggestionCount,
        )
        assertEquals(
            KeyboardPreferences.DEFAULT_RADIAL_SUGGESTIONS,
            KeyboardPreferences().radialSuggestionCount,
        )
    }

    @Test
    fun `the heatmap defaults on, and its three settings are clamped to their ranges`() {
        val defaults = KeyboardPreferences()
        assertTrue(defaults.heatmapEnabled)
        assertEquals(KeyboardPreferences.DEFAULT_HEATMAP_WEIGHT, defaults.heatmapWeight, 0f)
        assertEquals(
            KeyboardPreferences.MAX_HEATMAP_WEIGHT,
            KeyboardPreferences(heatmapWeight = 9f).sanitised().heatmapWeight,
            0f,
        )
        assertEquals(
            KeyboardPreferences.DEFAULT_HEATMAP_WEIGHT,
            KeyboardPreferences(heatmapWeight = Float.NaN).sanitised().heatmapWeight,
            0f,
        )
        assertEquals(
            KeyboardPreferences.MIN_HEATMAP_MIN_TAPS,
            KeyboardPreferences(heatmapMinTaps = -5).sanitised().heatmapMinTaps,
        )
        assertEquals(40, KeyboardPreferences(heatmapMinTaps = 47).sanitised().heatmapMinTaps)
        assertEquals(
            KeyboardPreferences.MAX_HEATMAP_HALF_LIFE_DAYS,
            KeyboardPreferences(heatmapHalfLifeDays = 1000).sanitised().heatmapHalfLifeDays,
        )
    }

    @Test
    fun `the pause dwell and pick timeout are each clamped to their own range`() {
        assertEquals(
            KeyboardPreferences.MAX_RADIAL_PAUSE_DWELL_MILLIS,
            KeyboardPreferences(radialPauseDwellMillis = 99999).sanitised().radialPauseDwellMillis,
        )
        assertEquals(
            KeyboardPreferences.MIN_RADIAL_PAUSE_DWELL_MILLIS,
            KeyboardPreferences(radialPauseDwellMillis = -1).sanitised().radialPauseDwellMillis,
        )
        // 0 survives sanitising.
        assertEquals(
            0,
            KeyboardPreferences(radialPauseDwellMillis = 0).sanitised().radialPauseDwellMillis,
        )
        assertEquals(
            KeyboardPreferences.MAX_RADIAL_PICK_TIMEOUT_MILLIS,
            KeyboardPreferences(radialPickTimeoutMillis = 99999).sanitised()
                .radialPickTimeoutMillis,
        )
        assertEquals(
            KeyboardPreferences.MIN_RADIAL_PICK_TIMEOUT_MILLIS,
            KeyboardPreferences(radialPickTimeoutMillis = 0).sanitised().radialPickTimeoutMillis,
        )
    }

    @Test
    fun `the minimum swipe length before a pause counts is clamped, and zero is a real value`() {
        assertEquals(
            KeyboardPreferences.DEFAULT_RADIAL_MIN_PATH_LETTERS,
            KeyboardPreferences().radialMinPathLetters, 0f,
        )
        assertEquals(
            KeyboardPreferences.MAX_RADIAL_MIN_PATH_LETTERS,
            KeyboardPreferences(radialMinPathLetters = 99999f).sanitised().radialMinPathLetters, 0f,
        )
        assertEquals(
            KeyboardPreferences.MIN_RADIAL_MIN_PATH_LETTERS,
            KeyboardPreferences(radialMinPathLetters = -1f).sanitised().radialMinPathLetters, 0f,
        )
        // 0 survives sanitising.
        assertEquals(
            0f, KeyboardPreferences(radialMinPathLetters = 0f).sanitised().radialMinPathLetters, 0f,
        )
    }

    @Test
    fun `the radial anchor defaults to the finger and repairs an unknown value`() {
        assertEquals(
            KeyboardPreferences.RADIAL_ANCHOR_FINGER,
            KeyboardPreferences().radialMenuAnchor,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_ANCHOR_FINGER,
            KeyboardPreferences(radialMenuAnchor = 99).sanitised().radialMenuAnchor,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_ANCHOR_TANGENT_RIGHT,
            KeyboardPreferences(radialMenuAnchor = KeyboardPreferences.RADIAL_ANCHOR_TANGENT_RIGHT)
                .sanitised().radialMenuAnchor,
        )
    }

    @Test
    fun `the radial size defaults to medium and repairs an unknown value`() {
        assertEquals(KeyboardPreferences.RADIAL_SIZE_MEDIUM, KeyboardPreferences().radialMenuSize)
        assertEquals(
            KeyboardPreferences.RADIAL_SIZE_MEDIUM,
            KeyboardPreferences(radialMenuSize = 99).sanitised().radialMenuSize,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_SIZE_LARGE,
            KeyboardPreferences(radialMenuSize = KeyboardPreferences.RADIAL_SIZE_LARGE)
                .sanitised().radialMenuSize,
        )
    }

    @Test
    fun `the radial timeout default applies the top suggestion and repairs an unknown value`() {
        assertEquals(
            KeyboardPreferences.RADIAL_TIMEOUT_APPLY_TOP,
            KeyboardPreferences().radialTimeoutDefault,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_TIMEOUT_APPLY_TOP,
            KeyboardPreferences(radialTimeoutDefault = 99).sanitised().radialTimeoutDefault,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_TIMEOUT_CANCEL,
            KeyboardPreferences(radialTimeoutDefault = KeyboardPreferences.RADIAL_TIMEOUT_CANCEL)
                .sanitised().radialTimeoutDefault,
        )
    }

    @Test
    fun `the trusted word goes in the ring by default and repairs an unknown value`() {
        assertEquals(
            KeyboardPreferences.RADIAL_TRUSTED_CHIP,
            KeyboardPreferences().radialTrustedWord,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_TRUSTED_CHIP,
            KeyboardPreferences(radialTrustedWord = 99).sanitised().radialTrustedWord,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_TRUSTED_CHIP,
            KeyboardPreferences(radialTrustedWord = -1).sanitised().radialTrustedWord,
        )
        assertEquals(
            KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY,
            KeyboardPreferences(radialTrustedWord = KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY)
                .sanitised().radialTrustedWord,
        )
    }

    @Test
    fun `radialSizeScale maps each named size to a distinct multiplier`() {
        assertEquals(1f, KeyboardPreferences.radialSizeScale(KeyboardPreferences.RADIAL_SIZE_MEDIUM), 0f)
        assertTrue(
            KeyboardPreferences.radialSizeScale(KeyboardPreferences.RADIAL_SIZE_SMALL) <
                KeyboardPreferences.radialSizeScale(KeyboardPreferences.RADIAL_SIZE_MEDIUM),
        )
        assertTrue(
            KeyboardPreferences.radialSizeScale(KeyboardPreferences.RADIAL_SIZE_LARGE) >
                KeyboardPreferences.radialSizeScale(KeyboardPreferences.RADIAL_SIZE_MEDIUM),
        )
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
    fun `no preferred language by default`() {
        assertEquals("", KeyboardPreferences().preferredLanguageTag)
        assertEquals("", KeyboardPreferences().sanitised().preferredLanguageTag)
    }

    @Test
    fun `a preferred language is kept, and bounded like any other tag`() {
        assertEquals(
            "ro-RO",
            KeyboardPreferences(preferredLanguageTag = "ro-RO").sanitised().preferredLanguageTag,
        )
        val long = "x".repeat(KeyboardPreferences.MAX_LANGUAGE_TAG * 4)
        assertEquals(
            KeyboardPreferences.MAX_LANGUAGE_TAG,
            KeyboardPreferences(preferredLanguageTag = long).sanitised().preferredLanguageTag.length,
        )
    }

    @Test
    fun `twenty thousand learned words are kept by default, and the limit is clamped to its steps`() {
        assertEquals(20_000, KeyboardPreferences().learnedWordLimit)
        assertEquals(
            KeyboardPreferences.MIN_LEARNED_WORD_LIMIT,
            KeyboardPreferences(learnedWordLimit = 0).sanitised().learnedWordLimit,
        )
        assertEquals(
            KeyboardPreferences.MAX_LEARNED_WORD_LIMIT,
            KeyboardPreferences(learnedWordLimit = Int.MAX_VALUE).sanitised().learnedWordLimit,
        )
        assertEquals(5_000, KeyboardPreferences(learnedWordLimit = 5_600).sanitised().learnedWordLimit)
    }

    @Test
    fun `the language written carries over by default`() {
        assertTrue(KeyboardPreferences().rememberDetectedLanguage)
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
    fun `flicks keep one per key and direction, drop the invalid, and the thresholds are clamped`() {
        val flicks = listOf(
            KeyFlick('a'.code, KeyFlick.NORTH, KeyFlick.TEXT, "@"),
            KeyFlick('a'.code, KeyFlick.NORTH, KeyFlick.TEXT, "#"),
            KeyFlick('a'.code, 9, KeyFlick.TEXT, "x"),
            KeyFlick('b'.code, KeyFlick.EAST, KeyFlick.COMMAND, "999999"),
            KeyFlick('b'.code, KeyFlick.EAST, KeyFlick.COMMAND, QuickAction.DELETE_WORD.id.toString()),
            KeyFlick('c'.code, KeyFlick.SOUTH, KeyFlick.KEY, "not a key name"),
            KeyFlick('c'.code, KeyFlick.SOUTH, KeyFlick.KEY, "left", label = "toolong"),
            KeyFlick('d'.code, KeyFlick.WEST, KeyFlick.TEXT, ""),
            KeyFlick('e'.code, KeyFlick.WEST, 7, "x"),
        )
        val kept = KeyboardPreferences(keyFlicks = flicks, flickMinFraction = 0.01f, flickMaxFraction = 9f).sanitised()
        assertEquals(
            listOf(
                KeyFlick('a'.code, KeyFlick.NORTH, KeyFlick.TEXT, "#"),
                KeyFlick('b'.code, KeyFlick.EAST, KeyFlick.COMMAND, QuickAction.DELETE_WORD.id.toString()),
            ),
            kept.keyFlicks,
        )
        assertEquals(KeyboardPreferences.MIN_FLICK_MIN_FRACTION, kept.flickMinFraction)
        assertEquals(KeyboardPreferences.MAX_FLICK_MAX_FRACTION, kept.flickMaxFraction)
        assertEquals("@", KeyFlick.shownLabel(KeyFlick('a'.code, 0, KeyFlick.TEXT, "@")))
        assertEquals("", KeyFlick.shownLabel(KeyFlick('a'.code, 0, KeyFlick.COMMAND, "9")))
        assertEquals("bye", KeyFlick.shownLabel(KeyFlick('a'.code, 0, KeyFlick.TEXT, "goodbye", label = "bye")))
    }

    @Test
    fun `custom layouts keep one per id and the choices only well-formed pairs`() {
        val layouts = listOf(
            CustomLayout("custom-1", " Mine ", "ro-RO", "{}"),
            CustomLayout("custom-1", "Newer", "und", "{\"rows\":[]}"),
            CustomLayout("mine", "Bad id", "und", "{}"),
            CustomLayout("custom-2", "", "und", "{}"),
            CustomLayout("custom-3", "Empty", "und", "  "),
        )
        val kept = KeyboardPreferences(
            customLayouts = layouts,
            subtypeLayouts = mapOf("qwerty" to "custom-1", "azerty" to "azerty", "Bad Id" to "custom-1", "colemak" to "dvorak"),
        ).sanitised()
        assertEquals(listOf(CustomLayout("custom-1", "Newer", "und", "{\"rows\":[]}")), kept.customLayouts)
        assertEquals(mapOf("qwerty" to "custom-1", "colemak" to "dvorak"), kept.subtypeLayouts)
        assertEquals("custom-2", CustomLayout.nextId(kept.customLayouts))
        assertEquals("custom-1", CustomLayout.nextId(emptyList()))
    }

    @Test
    fun `accents from other languages and the accent modifiers default off, and the languages are tags once each`() {
        val defaults = KeyboardPreferences()
        assertFalse(defaults.extraAccents)
        assertEquals(emptyList<String>(), defaults.extraAccentLanguages)
        assertFalse(defaults.deadKeys)
        val kept = KeyboardPreferences(
            extraAccentLanguages = listOf("fr-FR", "ar", "fr-FR", "French", "../x", "ro-RO"),
        ).sanitised()
        assertEquals(listOf("fr-FR", "ar", "ro-RO"), kept.extraAccentLanguages)
        val many = KeyboardPreferences(extraAccentLanguages = List(100) { "a${'a' + it % 26}" }).sanitised()
        assertTrue(many.extraAccentLanguages.size <= KeyboardPreferences.MAX_EXTRA_ACCENT_LANGUAGES)
    }

    @Test
    fun `custom layouts and the subtypes' choices survive a write and a read`() {
        val original = KeyboardPreferences(
            customLayouts = listOf(
                CustomLayout("custom-1", "Mine", "ro-RO", "{\"rows\":[{\"keys\":[{\"c\":\"a\"}]}]}"),
                CustomLayout("custom-2", "Other", "und", "{\"rows\":[]}"),
            ),
            subtypeLayouts = mapOf("qwerty" to "custom-2", "azerty" to "dvorak"),
            subtypeLayoutsLandscape = mapOf("qwerty" to "qwerty", "azerty" to "custom-1"),
        )
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            kotlinx.coroutines.runBlocking { KeyboardPreferencesSerializer.writeTo(original, output) }
        }.toByteArray()
        val read = kotlinx.coroutines.runBlocking {
            KeyboardPreferencesSerializer.readFrom(java.io.ByteArrayInputStream(bytes))
        }
        assertEquals(original.customLayouts, read.customLayouts)
        assertEquals(original.subtypeLayouts, read.subtypeLayouts)
        assertEquals(original.subtypeLayoutsLandscape, read.subtypeLayoutsLandscape)
    }

    @Test
    fun `the layout list's order puts the named ids first and the rest after, custom ones first`() {
        assertEquals(
            listOf("azerty", "custom-2", "custom-1", "qwerty", "dvorak"),
            CustomLayout.ordered(listOf("custom-1", "qwerty", "custom-2", "azerty", "dvorak"), listOf("azerty", "gone", "custom-2")),
        )
        assertEquals(listOf("qwerty", "custom-1"), KeyboardPreferences(layoutOrder = listOf("qwerty", "Bad Id", "custom-1", "qwerty")).sanitised().layoutOrder)
    }

    @Test
    fun `a landscape choice may name the subtype's own layout, a portrait one may not`() {
        val kept = KeyboardPreferences(
            subtypeLayouts = mapOf("qwerty" to "qwerty", "azerty" to "custom-1"),
            subtypeLayoutsLandscape = mapOf("qwerty" to "qwerty", "Bad Id" to "custom-1"),
        ).sanitised()
        assertEquals(mapOf("azerty" to "custom-1"), kept.subtypeLayouts)
        assertEquals(mapOf("qwerty" to "qwerty"), kept.subtypeLayoutsLandscape)
    }

    @Test
    fun `the remembered voice keyboard and its set are bounded`() {
        val long = KeyboardPreferences(voiceKeyboardId = "x".repeat(1_000), voiceKeyboardSet = "y".repeat(10_000)).sanitised()
        assertEquals(KeyboardPreferences.MAX_INPUT_METHOD_ID_CHARS, long.voiceKeyboardId.length)
        assertEquals(KeyboardPreferences.MAX_INPUT_METHOD_SET_CHARS, long.voiceKeyboardSet.length)
        assertEquals("com.a/.Voice", KeyboardPreferences(voiceKeyboardId = "com.a/.Voice").sanitised().voiceKeyboardId)
    }

    @Test
    fun `every image size step survives sanitising, and the ends are clamped`() {
        for (megabytes in KeyboardPreferences.IMAGE_SIZE_STEPS) {
            assertEquals(
                megabytes,
                KeyboardPreferences(clipboardImageMaxMb = megabytes).sanitised().clipboardImageMaxMb,
            )
        }
        assertEquals(1, KeyboardPreferences(clipboardImageMaxMb = 0).sanitised().clipboardImageMaxMb)
        assertEquals(50, KeyboardPreferences(clipboardImageMaxMb = 500).sanitised().clipboardImageMaxMb)
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

    @Test
    fun `images are off even though the history is on`() {
        val defaults = KeyboardPreferences()
        assertTrue("the history should still be on", defaults.clipboardEnabled)
        assertFalse("images should be asked for", defaults.clipboardImages)
    }

    @Test
    fun `typing helps are on and the sound is not`() {
        val defaults = KeyboardPreferences()
        assertTrue("capitalisation should be on", defaults.autoCapitalise)
        assertTrue("names should capitalise themselves by default", defaults.capitaliseNames)
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
        assertFalse("insert is not a bar entry", bar.contains(ComposerAction.INSERT))
        assertTrue(
            "the sanitised default is the default",
            defaults.sanitised().composerBar == defaults.composerBar,
        )
        assertFalse(
            "a button that opens an empty list should not be there on a new install",
            bar.contains(ComposerAction.SAVED_PROMPTS),
        )
    }

    @Test
    fun `a bar written by a later build opens rather than failing`() {
        // 8 is Insert's id.
        val fromLater = KeyboardPreferences(composerBar = listOf(8, 9999, 1, 1))
        val kept = fromLater.sanitised().composerBar
        assertEquals(
            "an unknown id and Insert should be dropped, and a repeat not drawn twice",
            listOf(ComposerAction.CORRECT.id),
            kept,
        )
    }

    @Test
    fun `the bar holds eight and sanitising cuts a longer one`() {
        val ids = ComposerAction.entries.filter { it != ComposerAction.INSERT }.map { it.id }
        assertEquals(9, ids.size)
        val kept = KeyboardPreferences(composerBar = ids).sanitised().composerBar
        assertEquals(ComposerBar.MAX_ITEMS, kept.size)
        assertEquals(ids.take(ComposerBar.MAX_ITEMS), kept)
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
        assertEquals(KeyboardPreferences.SYMBOLS_NUMBER_RIGHT, fresh.symbolsNumberPosition)
        assertEquals(true, fresh.accentedCharacters)
        assertEquals(true, fresh.longPressHints)
        assertEquals(KeyboardPreferences.DEFAULT_LONG_PRESS_MILLIS, fresh.longPressMillis)

        // An out-of-range digit position is not a fourth arrangement.
        assertEquals(
            KeyboardPreferences.SYMBOLS_NUMBER_RIGHT,
            KeyboardPreferences(symbolsNumberPosition = 9).sanitised().symbolsNumberPosition,
        )
        assertEquals(
            KeyboardPreferences.SYMBOLS_NUMBER_TOP,
            KeyboardPreferences(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_TOP)
                .sanitised().symbolsNumberPosition,
        )
        assertEquals(
            KeyboardPreferences.SYMBOLS_NUMBER_RIGHT,
            KeyboardPreferences(symbolsNumberPosition = KeyboardPreferences.SYMBOLS_NUMBER_RIGHT)
                .sanitised().symbolsNumberPosition,
        )
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

        // The same list sanitised twice gets the same id.
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
