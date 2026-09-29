// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType
import com.borderkeys.data.theme.KeyboardPreferences

/**
 * A [TypingOrchestrator] on an in-memory field, the engine on the host bridge, a recording host,
 * an in-memory store and a clock the test moves. After each key the rig delivers what reaches the
 * input method before the next one: the field's selection reports, the engine's answers, and the
 * runnables that fell due.
 */
internal class TypingRig(val engine: QueuedEngine, settings: KeyboardPreferences) {
    val editor = FakeFieldEditor()
    val clock = ManualClock()
    val host = FakeTypingHost(clock)
    val store = MemoryLearningStore()
    val orchestrator = TypingOrchestrator({ editor }, engine, host, store, clock)

    init {
        engine.onSuggestions = orchestrator::onSuggestions
        orchestrator.applySettings(settings)
    }

    /** Opens a field holding [text], the caret at its end, and settles. */
    fun startField(
        text: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        addressField: Boolean = false,
        terminalField: Boolean = false,
        passwordField: Boolean = false,
        privateField: Boolean = false,
    ) {
        editor.reset(text)
        orchestrator.startField(
            FieldSession(
                generation = orchestrator.session.generation + 1,
                policy = FieldPolicy.of(
                    passwordField = passwordField,
                    privateField = privateField,
                    learningEnabled = orchestrator.preferences.learningEnabled,
                ),
                addressField = addressField,
                terminalField = terminalField,
                inputType = inputType,
                imeOptions = imeOptions,
                described = true,
            ),
        )
        settle()
    }

    /** Presses the key for each code point of [text]. */
    fun type(text: String) {
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            press(code)
        }
    }

    /** Presses the key [code], [KEY_INTERVAL_MILLIS] after the last one, and settles. */
    fun press(code: Int) {
        clock.advance(KEY_INTERVAL_MILLIS)
        orchestrator.onKey(code)
        settle()
    }

    /** Delivers the selection reports, the engine's answers and the due runnables, until none is left. */
    fun settle() {
        while (true) {
            val report = editor.takeReport()
            if (report != null) {
                orchestrator.onSelectionChanged(report.selectionStart, report.selectionEnd)
                continue
            }
            if (engine.serveNext() || host.runDue()) {
                continue
            }
            return
        }
    }

    companion object {
        /** The time between two key presses. */
        const val KEY_INTERVAL_MILLIS = 150L
    }
}

/** Both clocks at one reading, which only [advance] moves. */
internal class ManualClock : TypingClock {
    var now = START
        private set

    fun advance(millis: Long) {
        now += millis
    }

    override fun currentTimeMillis(): Long = now

    override fun uptimeMillis(): Long = now

    private companion object {
        /** A wall-clock time, far from the zero the timing fields start at. */
        const val START = 1_790_000_000_000L
    }
}

/** Every batch written, in order. */
internal class MemoryLearningStore : LearningStore {
    val batches = mutableListOf<LearningBatch>()

    override fun persist(batch: LearningBatch) {
        batches += batch
    }

    override fun persistBeforeShutdown(batch: LearningBatch?) {
        if (batch != null) {
            batches += batch
        }
    }
}
