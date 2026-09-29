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

    /** Takes [settings], the field's policy following the Learning switch first. */
    fun applySettings(settings: KeyboardPreferences) {
        session = session.copy(policy = session.policy.withLearning(settings.learningEnabled))
        onSettingsChanged(settings)
    }

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

    protected open fun onShutdown() {}
}
