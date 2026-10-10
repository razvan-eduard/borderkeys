// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.CustomQuickAction
import com.borderkeys.data.theme.Feature
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBarItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldPolicyTest {

    private fun policy(
        password: Boolean = false,
        private: Boolean = false,
        learning: Boolean = true,
        heatmap: Boolean = true,
        verbatim: Boolean = false,
    ) = FieldPolicy.of(
        passwordField = password,
        privateField = private,
        learningEnabled = learning,
        heatmapEnabled = heatmap,
        verbatimField = verbatim,
    )

    @Test
    fun `a password field allows no dictionary words and nothing personal, and takes the keys verbatim`() {
        val policy = policy(password = true, private = true)
        assertFalse(policy.suggestionsAllowed)
        assertFalse(policy.swipeAllowed)
        assertTrue(policy.privateField)
        assertFalse(policy.personalAllowed)
        assertTrue(policy.verbatim)
    }

    @Test
    fun `a private field that is not a password field still allows dictionary words`() {
        val policy = policy(private = true)
        assertTrue(policy.suggestionsAllowed)
        assertTrue(policy.swipeAllowed)
        assertFalse(policy.personalAllowed)
        assertFalse(policy.verbatim)
    }

    @Test
    fun `an e-mail, number or phone field takes the keys verbatim and no swipe, and still offers dictionary words`() {
        val policy = policy(verbatim = true)
        assertTrue(policy.verbatim)
        assertFalse(policy.swipeAllowed)
        assertTrue(policy.suggestionsAllowed)
        assertFalse(policy.privateField)
        assertTrue(policy.personalAllowed)
    }

    @Test
    fun `a free-form text field allows everything`() {
        val policy = policy()
        assertTrue(policy.suggestionsAllowed)
        assertTrue(policy.swipeAllowed)
        assertFalse(policy.verbatim)
        assertTrue(policy.clipboardAllowed)
        assertTrue(policy.assistantAllowed)
        assertFalse(policy.privateField)
    }

    private val prefsOn = KeyboardPreferences(
        clipboardEnabled = true,
        clipboardSuggestion = true,
        clipboardImages = true,
        swipeEnabled = true,
        showSuggestionStrip = true,
    )

    @Test
    fun `before any field the policy is locked, so nothing personal runs`() {
        assertFalse(FieldPolicy.NONE.userUnlocked)
        assertFalse(FieldPolicy.NONE.clipboardAllowed)
        assertFalse(FieldPolicy.NONE.personalAllowed)
        assertFalse(FieldPolicy.NONE.on(Feature.CLIPBOARD_HISTORY, prefsOn))
        assertTrue("the keys still type", FieldPolicy.NONE.on(Feature.SWIPE, prefsOn))
    }

    @Test
    fun `a feature runs only where the settings switch it on and the field allows it`() {
        assertTrue(policy().on(Feature.CLIPBOARD_OFFER, prefsOn))
        assertFalse("its own switch", policy().on(Feature.CLIPBOARD_OFFER, prefsOn.copy(clipboardSuggestion = false)))
        assertFalse("its parent's switch", policy().on(Feature.CLIPBOARD_OFFER, prefsOn.copy(clipboardEnabled = false)))
        assertFalse("a password field", policy(password = true, private = true).on(Feature.CLIPBOARD_OFFER, prefsOn))
        assertFalse("a field kept out of learning", policy(private = true).on(Feature.CLIPBOARD_HISTORY, prefsOn))
    }

    @Test
    fun `swipe needs its switch and a field that reads swipes`() {
        assertTrue(policy().on(Feature.SWIPE, prefsOn))
        assertFalse(policy().on(Feature.SWIPE, prefsOn.copy(swipeEnabled = false)))
        assertFalse(policy(verbatim = true).on(Feature.SWIPE, prefsOn))
        assertFalse(policy(password = true, private = true).on(Feature.SWIPE, prefsOn))
    }

    @Test
    fun `the strip shows in every field, a password field included, until switched off by hand`() {
        assertTrue(policy().on(Feature.STRIP, prefsOn))
        assertTrue(policy(password = true, private = true).on(Feature.STRIP, prefsOn))
        assertFalse(policy().byHand(TypingFeatures.NONE).on(Feature.STRIP, prefsOn))
        assertFalse(policy().on(Feature.STRIP, prefsOn.copy(showSuggestionStrip = false)))
    }

    @Test
    fun `off by hand is the hand switch alone, not a private field`() {
        assertFalse(policy().offByHand)
        assertTrue(policy().byHand(TypingFeatures.NONE).offByHand)
        assertFalse(policy(password = true, private = true).offByHand)
    }

    @Test
    fun `a plain field allows every quick action`() {
        for (action in QuickAction.entries) {
            assertTrue(action.name, policy().allows(action))
        }
    }

    @Test
    fun `a password field allows Paste and the plain edits, and nothing that reads, keeps or hands on its text`() {
        val password = policy(password = true, private = true)
        assertTrue(password.allows(QuickAction.PASTE))
        assertTrue(password.allows(QuickAction.SELECT_ALL))
        assertTrue(password.allows(QuickAction.UNDO))
        for (action in listOf(
            QuickAction.COPY_ALL, QuickAction.CUT,
            QuickAction.CLIPBOARD_HISTORY, QuickAction.PRIVATE_COPY,
            QuickAction.COMPOSE, QuickAction.FEATURES_SWITCH,
        )) {
            assertFalse(action.name, password.allows(action))
        }
    }

    @Test
    fun `a field the app keeps out of learning refuses the same as a password field`() {
        val noLearning = policy(private = true)
        assertTrue(noLearning.allows(QuickAction.PASTE))
        assertFalse(noLearning.allows(QuickAction.COPY_ALL))
        assertFalse(noLearning.allows(QuickAction.COMPOSE))
        assertFalse(noLearning.allows(QuickAction.FEATURES_SWITCH))
    }

    @Test
    fun `everything off by hand refuses the clipboard and the assistant, and keeps its own switch`() {
        val muted = policy().byHand(TypingFeatures.NONE)
        assertFalse(muted.allows(QuickAction.COPY_ALL))
        assertFalse(muted.allows(QuickAction.COMPOSE))
        assertTrue("the way back on stays reachable", muted.allows(QuickAction.FEATURES_SWITCH))
        assertTrue(muted.allows(QuickAction.PASTE))
    }

    @Test
    fun `a macro is allowed only when every step it expands to is`() {
        val safe = CustomQuickAction(
            id = 1000, name = "a", icon = 0,
            steps = listOf(QuickAction.SELECT_ALL.id, QuickAction.PASTE.id),
        )
        val copying = CustomQuickAction(
            id = 1001, name = "b", icon = 0,
            steps = listOf(QuickAction.SELECT_ALL.id, 1000, QuickAction.COPY_ALL.id),
        )
        val all = listOf(safe, copying)
        val password = policy(password = true, private = true)
        assertTrue(password.allows(QuickActionBarItem.Custom(safe), all))
        assertFalse(password.allows(QuickActionBarItem.Custom(copying), all))
        assertTrue(policy().allows(QuickActionBarItem.Custom(copying), all))
    }

    @Test
    fun `features combine with and, a feature on only where every party allows it`() {
        val both = TypingFeatures.PRIVATE and TypingFeatures.AS_TYPED
        assertTrue(both.suggestions)
        assertFalse(both.corrections)
        assertFalse(both.swipe)
        assertFalse(both.learning)
        assertFalse(both.clipboard)
        assertTrue(both.isPrivate)
        assertFalse(TypingFeatures.AS_TYPED.isPrivate)
        assertEquals(TypingFeatures.NONE, TypingFeatures.ALL and TypingFeatures.NONE)
        assertEquals(TypingFeatures.PRIVATE, TypingFeatures.ALL and TypingFeatures.PRIVATE)
    }

    @Test
    fun `everything off by hand is private mode with the keys as typed, in a plain text field`() {
        val muted = policy().byHand(TypingFeatures.NONE)
        assertFalse(muted.suggestionsAllowed)
        assertTrue(muted.verbatim)
        assertFalse(muted.swipeAllowed)
        assertFalse(muted.personalAllowed)
        assertFalse(muted.heatmapAllowed)
        assertFalse(muted.clipboardAllowed)
        assertFalse(muted.assistantAllowed)
        assertTrue(muted.privateField)
        assertFalse("the field itself is not private", muted.fieldIsPrivate)
        val back = muted.byHand(TypingFeatures.ALL)
        assertEquals(policy(), back)
    }

    @Test
    fun `the hand's choice survives the switches and the unlock`() {
        val muted = policy().byHand(TypingFeatures.NONE)
        assertFalse(muted.withSwitches(learning = true, heatmap = true).personalAllowed)
        assertFalse(muted.unlocked(learning = true, heatmap = true).suggestionsAllowed)
        assertTrue(muted.unlocked(learning = true, heatmap = true).privateField)
    }

    @Test
    fun `a password field is private by its kind, so the quick actions bar stays away`() {
        assertTrue(policy(password = true, private = true).fieldIsPrivate)
        assertTrue(policy(private = true).fieldIsPrivate)
        assertFalse(policy(verbatim = true).fieldIsPrivate)
    }

    @Test
    fun `the personal dictionary needs the Learning switch`() {
        val off = policy(learning = false)
        assertFalse(off.personalAllowed)
        assertTrue(off.withSwitches(true, true).personalAllowed)
        assertFalse(off.withSwitches(true, true).withSwitches(false, true).personalAllowed)
    }

    @Test
    fun `the heatmap needs Learning and its own switch`() {
        val both = policy()
        assertTrue(both.heatmapAllowed)
        assertFalse(both.withSwitches(learning = false, heatmap = true).heatmapAllowed)
        assertFalse(both.withSwitches(learning = true, heatmap = false).heatmapAllowed)
        assertTrue(both.withSwitches(learning = true, heatmap = false).personalAllowed)
    }

    @Test
    fun `a private field never records the heatmap`() {
        val private = policy(private = true)
        assertFalse(private.heatmapAllowed)
        assertFalse(private.withSwitches(learning = true, heatmap = true).heatmapAllowed)
    }

    @Test
    fun `switching Learning on does not open a private field`() {
        val private = policy(private = true, learning = false)
        assertFalse(private.withSwitches(true, true).personalAllowed)
    }

    @Test
    fun `before the first unlock nothing personal is allowed, and the switches do not open it`() {
        val locked = FieldPolicy.of(
            passwordField = false, privateField = false, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertTrue(locked.suggestionsAllowed)
        assertFalse(locked.personalAllowed)
        assertFalse(locked.heatmapAllowed)
        assertFalse(locked.withSwitches(learning = true, heatmap = true).personalAllowed)
    }

    @Test
    fun `the unlock opens what the switches allow, and no more`() {
        val locked = FieldPolicy.of(
            passwordField = false, privateField = false, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertTrue(locked.unlocked(learning = true, heatmap = true).personalAllowed)
        assertTrue(locked.unlocked(learning = true, heatmap = true).heatmapAllowed)
        assertFalse(locked.unlocked(learning = false, heatmap = true).personalAllowed)
        assertFalse(locked.unlocked(learning = true, heatmap = false).heatmapAllowed)
        val private = FieldPolicy.of(
            passwordField = false, privateField = true, learningEnabled = true, heatmapEnabled = true,
            userUnlocked = false,
        )
        assertFalse(private.unlocked(learning = true, heatmap = true).personalAllowed)
    }
}
