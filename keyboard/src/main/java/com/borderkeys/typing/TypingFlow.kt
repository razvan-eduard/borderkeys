// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * One part of the typing pipeline that [TypingOrchestrator] drives. The lifecycle is fixed here;
 * a flow supplies only what it does when a field starts or ends, when the settings change, and
 * when the keyboard goes.
 */
abstract class TypingFlow {

    /** The field being typed into, [FieldSession.NONE] before the first. */
    protected var session: FieldSession = FieldSession.NONE
        private set

    /** The settings, as of the last [applySettings]. */
    protected var settings: KeyboardPreferences = KeyboardPreferences()
        private set

    /** Whether a field has started and not yet finished. */
    private var fieldOpen = false

    fun startField(field: FieldSession) {
        session = field
        fieldOpen = true
        onFieldStarted(field)
    }

    /** Finishes the open field; with none open, does nothing. */
    fun finishField() {
        if (!fieldOpen) {
            return
        }
        fieldOpen = false
        onFieldFinished()
    }

    /** Takes [settings], the field's policy following the Learning and Heatmap switches first. */
    fun applySettings(settings: KeyboardPreferences) {
        this.settings = settings
        session = session.copy(
            policy = session.policy.withSwitches(settings.learningEnabled, settings.heatmapEnabled),
        )
        onSettingsChanged(settings)
    }

    /** The user unlocked: the field's policy allows what it withheld, the switches still applying. */
    fun userUnlocked() {
        session = session.copy(
            policy = session.policy.unlocked(settings.learningEnabled, settings.heatmapEnabled),
        )
        onUserUnlocked()
    }

    /** The user allows [features] by hand: the field's policy follows, in the open field too. */
    fun applyByHand(features: TypingFeatures) {
        session = session.copy(policy = session.policy.byHand(features))
        onPolicyChanged()
    }

    /** The field's policy changed while it is open; a flow with a gate re-reads it here. */
    protected open fun onPolicyChanged() {}

    /** The keyboard is going; the open field is finished first. */
    fun shutdown() {
        finishField()
        onShutdown()
    }

    /** Whether [generation] numbers the field this flow is in. */
    protected fun isCurrent(generation: Int): Boolean = generation == session.generation

    protected abstract fun onFieldStarted(field: FieldSession)

    protected open fun onFieldFinished() {}

    protected open fun onSettingsChanged(settings: KeyboardPreferences) {}

    protected open fun onUserUnlocked() {}

    protected open fun onShutdown() {}
}
