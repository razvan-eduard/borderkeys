// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.data.theme.KeyboardPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lifecycle every [TypingFlow] gets from its base class. */
class TypingFlowTest {

    private class Recorder : TypingFlow() {
        val events = mutableListOf<String>()
        val policy: FieldPolicy get() = session.policy

        fun current(generation: Int) = isCurrent(generation)

        override fun onFieldStarted(field: FieldSession) {
            events += "started ${field.generation}"
        }

        override fun onFieldFinished() {
            events += "finished"
        }

        override fun onSettingsChanged(settings: KeyboardPreferences) {
            events += "settings, personal ${policy.personalAllowed}"
        }

        override fun onShutdown() {
            events += "shutdown"
        }
    }

    private fun field(generation: Int, privateField: Boolean = false) = FieldSession(
        generation = generation,
        policy = FieldPolicy.of(passwordField = false, privateField = privateField, learningEnabled = true),
        addressField = false,
        terminalField = false,
    )

    @Test
    fun `a field finishes once, and not before it starts`() {
        val flow = Recorder()
        flow.finishField()
        flow.startField(field(1))
        flow.finishField()
        flow.finishField()
        assertEquals(listOf("started 1", "finished"), flow.events)
    }

    @Test
    fun `shutdown finishes the open field first`() {
        val flow = Recorder()
        flow.startField(field(1))
        flow.shutdown()
        assertEquals(listOf("started 1", "finished", "shutdown"), flow.events)
    }

    @Test
    fun `the policy follows the Learning switch before the flow hears of the settings`() {
        val flow = Recorder()
        flow.startField(field(1))
        flow.applySettings(KeyboardPreferences(learningEnabled = false))
        flow.applySettings(KeyboardPreferences(learningEnabled = true))
        assertEquals(
            listOf("started 1", "settings, personal false", "settings, personal true"),
            flow.events,
        )
    }

    @Test
    fun `a private field stays private whatever the Learning switch says`() {
        val flow = Recorder()
        flow.startField(field(1, privateField = true))
        flow.applySettings(KeyboardPreferences(learningEnabled = true))
        assertFalse(flow.policy.personalAllowed)
    }

    @Test
    fun `an answer about another field is not current`() {
        val flow = Recorder()
        flow.startField(field(2))
        assertTrue(flow.current(2))
        assertFalse(flow.current(1))
    }
}
