// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.theme.KeyboardPreferences

/**
 * A [TypingOrchestrator] on an in-memory field, the engine on the host bridge, a recording host
 * and ring, an in-memory store and a clock the test moves. After each key the rig delivers what
 * reaches the input method before the next one: the field's selection reports, the engine's
 * answers, and the runnables that fell due.
 */
internal class TypingRig(val engine: QueuedEngine, settings: KeyboardPreferences) {
    val editor = FakeFieldEditor()
    val clock = ManualClock()
    val host = FakeTypingHost(clock)
    val ring = FakeRingUi()
    val store = MemoryLearningStore()
    val orchestrator = TypingOrchestrator({ editor }, engine, host, ring, store, clock)

    init {
        engine.onSuggestions = orchestrator::onSuggestions
        engine.onGestureCandidates = orchestrator::onGestureCandidates
        engine.onGesturePreviewCandidates = orchestrator::onGesturePreviewCandidates
        orchestrator.applySettings(settings)
    }

    /** Swipes through the keys of [word] on the harness's layout, lifts, and settles. */
    fun swipe(word: String) {
        val path = SwipePath.through(word, clock)
        orchestrator.onGesture(path.xs, path.ys, path.timestamps, path.count)
        settle()
    }

    /** Swipes through the keys of [word] and pauses there, the finger still down, and settles. */
    fun swipeAndPause(word: String) {
        val path = SwipePath.through(word, clock)
        orchestrator.onGesturePaused(path.xs, path.ys, path.timestamps, path.count)
        settle()
    }

    /** Opens a field holding [text], the caret at its end, and settles unless [settle] is false. */
    fun startField(
        text: String = "",
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        imeOptions: Int = 0,
        addressField: Boolean = false,
        terminalField: Boolean = false,
        passwordField: Boolean = false,
        privateField: Boolean = false,
        userUnlocked: Boolean = true,
        settle: Boolean = true,
    ) {
        editor.reset(text)
        orchestrator.startField(
            FieldSession(
                generation = orchestrator.session.generation + 1,
                policy = FieldPolicy.of(
                    passwordField = passwordField,
                    privateField = privateField,
                    learningEnabled = orchestrator.preferences.learningEnabled,
                    heatmapEnabled = orchestrator.preferences.heatmapEnabled,
                    userUnlocked = userUnlocked,
                ),
                addressField = addressField,
                terminalField = terminalField,
                inputType = inputType,
                imeOptions = imeOptions,
                described = true,
            ),
        )
        if (settle) {
            settle()
        }
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

    /** Presses the key [code], the one at [keyIndex], chosen at ([x], [y]), and settles. */
    fun tap(code: Int, keyIndex: Int, x: Float, y: Float) {
        clock.advance(KEY_INTERVAL_MILLIS)
        orchestrator.onKey(code, keyIndex, x, y)
        settle()
    }

    /** Holds the key [code] down until its long press fires, and settles. */
    fun longPress(code: Int) {
        clock.advance(KEY_INTERVAL_MILLIS)
        orchestrator.onKeyLongPress(code)
        settle()
    }

    /** Picks [word] from slot [index] of the strip, and settles. */
    fun pick(index: Int, word: String) {
        orchestrator.onPick(index, word)
        settle()
    }

    /** Slides along the space bar by [steps] characters, and settles. */
    fun slide(steps: Int) {
        orchestrator.onCursorNudge(steps)
        settle()
    }

    /** Slides the space bar up or down by [lines], and settles. */
    fun slideLines(lines: Int) {
        orchestrator.onCursorNudgeLines(lines)
        settle()
    }

    /** The application moves the caret to [position], as a tap in the field or an arrow key does. */
    fun moveCaret(position: Int) {
        editor.setSelection(position, position)
        settle()
    }

    /** Lets [millis] pass with no key pressed, and settles. */
    fun pause(millis: Long) {
        clock.advance(millis)
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
            break
        }
        val word = orchestrator.composingText
        check(orchestrator.taps.size == word.codePointCount(0, word.length)) {
            "'$word' has ${orchestrator.taps.size} taps"
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

/** Every batch written, in order, and the heatmap totals it serves by bucket. */
internal class MemoryLearningStore : LearningStore {
    val batches = mutableListOf<LearningBatch>()

    /** What [loadTouches] answers, by bucket. */
    val storedTouches = mutableMapOf<String, List<KeyTouch>>()

    /** Every heatmap total written, in order. */
    val touches: List<KeyTouch> get() = batches.flatMap { it.touches }

    override fun persist(batch: LearningBatch) {
        batches += batch
    }

    override fun persistBeforeShutdown(batch: LearningBatch?) {
        if (batch != null) {
            batches += batch
        }
    }

    override fun loadTouches(bucket: String, onLoaded: (List<KeyTouch>) -> Unit) {
        onLoaded(storedTouches[bucket].orEmpty())
    }
}

/** The harness's QWERTY letters as the keyboard view would snapshot them, in [bucket]. */
internal fun harnessGeometry(bucket: KeyGeometrySnapshot.Bucket = HARNESS_BUCKET): KeyGeometrySnapshot {
    val letters = "qwertyuiopasdfghjklzxcvbnm"
    val centres = letters.map { com.borderkeys.predict.Pipeline.keyCentre(it) }
    return KeyGeometrySnapshot(
        bucket = bucket,
        keyWidth = HARNESS_KEY_WIDTH,
        keyHeight = HARNESS_KEY_HEIGHT,
        density = 2.75f,
        codes = IntArray(letters.length) { letters[it].code },
        centreX = FloatArray(letters.length) { centres[it].first },
        centreY = FloatArray(letters.length) { centres[it].second },
    )
}

internal val HARNESS_BUCKET = KeyGeometrySnapshot.Bucket(landscape = false, positionMode = 0, layoutId = "qwerty")
internal const val HARNESS_KEY_WIDTH = 108f
internal const val HARNESS_KEY_HEIGHT = 160f
