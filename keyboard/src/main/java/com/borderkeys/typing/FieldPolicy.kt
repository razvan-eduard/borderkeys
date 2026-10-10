// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.CustomQuickAction
import com.borderkeys.data.theme.Feature
import com.borderkeys.data.theme.FieldRequirement
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBar
import com.borderkeys.data.theme.QuickActionBarItem

/**
 * The keyboard's features a party allows, one flag each. The field's kind, the settings and the
 * user's hand each contribute one, and a feature runs only where all of them allow it: [and].
 */
data class TypingFeatures(
    /** Dictionary words are suggested, applied as corrections, and offered on the ring. */
    val suggestions: Boolean = true,
    /** The keys may be rewritten: a correction, an automatic space or capital, a text shortcut. */
    val corrections: Boolean = true,
    /** A swipe across the letters is decoded. */
    val swipe: Boolean = true,
    /** The personal dictionary is recorded and consulted. */
    val learning: Boolean = true,
    /** Where the taps land is learned, and the learned patterns count. */
    val heatmap: Boolean = true,
    /** The clipboard history is kept and its chips offered. */
    val clipboard: Boolean = true,
    /** The assistant and the draft box may take the field's text. */
    val assistant: Boolean = true,
) {
    /** The features both allow. */
    infix fun and(other: TypingFeatures): TypingFeatures = TypingFeatures(
        suggestions = suggestions && other.suggestions,
        corrections = corrections && other.corrections,
        swipe = swipe && other.swipe,
        learning = learning && other.learning,
        heatmap = heatmap && other.heatmap,
        clipboard = clipboard && other.clipboard,
        assistant = assistant && other.assistant,
    )

    /** Nothing about the user is read or written: no learning, no clipboard, no assistant. */
    val isPrivate: Boolean
        get() = !learning && !clipboard && !assistant

    companion object {
        val ALL = TypingFeatures()
        val NONE = TypingFeatures(
            suggestions = false, corrections = false, swipe = false, learning = false,
            heatmap = false, clipboard = false, assistant = false,
        )

        /** A field the keyboard must forget: nothing personal; dictionary words still offered. */
        val PRIVATE = TypingFeatures(learning = false, heatmap = false, clipboard = false, assistant = false)

        /** A field that takes the keys as typed: no rewrite and no swipe; dictionary words still offered. */
        val AS_TYPED = TypingFeatures(corrections = false, swipe = false)

        /** A password field: private, as typed, and no dictionary words. */
        val PASSWORD = NONE

        /** The Learning and Heatmap switches as a party. */
        fun switches(learning: Boolean, heatmap: Boolean) = TypingFeatures(learning = learning, heatmap = heatmap)
    }
}

/**
 * What the field being typed into allows: its kind, the settings and the user's hand each as
 * a [TypingFeatures], and the unlock since boot. The gates read the flags below.
 */
data class FieldPolicy(
    /** What the field's kind allows. */
    val byField: TypingFeatures,
    /** What the settings allow: the Learning and Heatmap switches. */
    val bySettings: TypingFeatures = TypingFeatures.ALL,
    /** What the user allows by hand, through the Enabled/Disabled quick action: everything or nothing. */
    val byHand: TypingFeatures = TypingFeatures.ALL,
    /**
     * The user has unlocked since boot, so the personal dictionary, the clipboard history and
     * the settings can be read and written.
     */
    val userUnlocked: Boolean = true,
) {
    /** What runs here: allowed by the field, the settings and the hand alike. */
    val features: TypingFeatures
        get() = byField and bySettings and byHand

    /** Dictionary words may be suggested, corrected, swiped or offered on the ring. */
    val suggestionsAllowed: Boolean
        get() = features.suggestions

    /** The keys go in exactly as typed: no correction or rewrite, no space added or removed. */
    val verbatim: Boolean
        get() = !features.corrections

    /** A swipe across the letters is decoded, and the ring may open on a pause. */
    val swipeAllowed: Boolean
        get() = features.swipe

    /** The personal dictionary is recorded and consulted. */
    val personalAllowed: Boolean
        get() = features.learning && userUnlocked

    /** Where the taps land is learned, and the learned patterns count. */
    val heatmapAllowed: Boolean
        get() = personalAllowed && features.heatmap

    /** The clipboard history is kept here and its chips offered. */
    val clipboardAllowed: Boolean
        get() = features.clipboard && userUnlocked

    /** The assistant and the draft box may take this field's text. */
    val assistantAllowed: Boolean
        get() = features.assistant && userUnlocked

    /**
     * Private mode: nothing about the user is read or written, by the field's kind or by hand.
     * The switches do not make a field private.
     */
    val privateField: Boolean
        get() = (byField and byHand).isPrivate

    /** Whether the field's kind alone is private: a password field, or one the app marks as no learning. */
    val fieldIsPrivate: Boolean
        get() = byField.isPrivate

    /** Whether this field allows what [requirement] asks for. */
    fun allows(requirement: FieldRequirement): Boolean = when (requirement) {
        FieldRequirement.NONE -> true
        FieldRequirement.CLIPBOARD -> clipboardAllowed
        FieldRequirement.ASSISTANT -> assistantAllowed
        FieldRequirement.SWIPE -> swipeAllowed
        FieldRequirement.PERSONAL_FIELD -> !fieldIsPrivate
        FieldRequirement.ON_BY_HAND -> !offByHand
    }

    /** Whether the user has switched the keyboard's features off by hand. */
    val offByHand: Boolean
        get() = byHand != TypingFeatures.ALL

    /**
     * Whether [feature] runs in this field: switched on in [preferences] with every feature it
     * sits under, and allowed here, its parents' needs included.
     */
    fun on(feature: Feature, preferences: KeyboardPreferences): Boolean {
        var current: Feature? = feature
        while (current != null) {
            if (!allows(current.needs)) {
                return false
            }
            current = current.parent
        }
        return feature.on(preferences)
    }

    /** Whether [action] may run in this field. */
    fun allows(action: QuickAction): Boolean = allows(action.requires)

    /** Whether [item] may run in this field: a macro only when every step it expands to may. */
    fun allows(
        item: QuickActionBarItem,
        customActions: List<CustomQuickAction>,
    ): Boolean = when (item) {
        is QuickActionBarItem.Builtin -> allows(item.action)
        is QuickActionBarItem.Custom ->
            QuickActionBar.flatten(item.action, customActions).all(::allows)
    }

    /** This policy with the Learning switch at [learning] and the Heatmap switch at [heatmap]. */
    fun withSwitches(learning: Boolean, heatmap: Boolean): FieldPolicy =
        copy(bySettings = TypingFeatures.switches(learning, heatmap))

    /** This policy once the user has unlocked, the switches at [learning] and [heatmap]. */
    fun unlocked(learning: Boolean, heatmap: Boolean): FieldPolicy =
        copy(userUnlocked = true).withSwitches(learning, heatmap)

    /** This policy with the user allowing [features] by hand. */
    fun byHand(features: TypingFeatures): FieldPolicy = copy(byHand = features)

    companion object {
        /** Before any field has started: nothing personal until a field says the user has unlocked. */
        val NONE = FieldPolicy(byField = TypingFeatures.ALL, userUnlocked = false)

        /**
         * The policy of a field: [passwordField] allows no dictionary words and takes the keys
         * verbatim, [verbatimField] (an e-mail, number or phone field) takes the keys verbatim
         * and no swipe but still offers dictionary words, [privateField] allows nothing personal,
         * the personal dictionary needs [learningEnabled] and [userUnlocked] as well, and the
         * heatmap needs [heatmapEnabled] on top.
         */
        fun of(
            passwordField: Boolean,
            privateField: Boolean,
            learningEnabled: Boolean,
            heatmapEnabled: Boolean,
            userUnlocked: Boolean = true,
            verbatimField: Boolean = false,
        ): FieldPolicy {
            var kind = TypingFeatures.ALL
            if (passwordField) kind = kind and TypingFeatures.PASSWORD
            if (privateField) kind = kind and TypingFeatures.PRIVATE
            if (verbatimField) kind = kind and TypingFeatures.AS_TYPED
            return FieldPolicy(
                byField = kind,
                bySettings = TypingFeatures.switches(learningEnabled, heatmapEnabled),
                userUnlocked = userUnlocked,
            )
        }
    }
}
