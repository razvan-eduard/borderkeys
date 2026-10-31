// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.text.InputType
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.MemoryDictionary
import com.borderkeys.data.SteppedExecutor
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserWord
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.KeyboardPersonalModel
import com.borderkeys.ime.PersonalModelReload
import com.borderkeys.predict.PersonalModelLoad
import com.borderkeys.predict.RefusedWords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlin.coroutines.resume

/**
 * A [TypingOrchestrator] on an in-memory field, the engine on the host bridge, a recording host
 * and ring, the production store ([DictionaryLearningStore]) on the personal dictionary's
 * repository over tables in memory, its worker on a hand-stepped thread, and a clock the test
 * moves. After each key the rig delivers what reaches the input method before the next one: the
 * field's selection reports, the engine's answers, the runnables that fell due and the personal
 * dictionary's reads and writes.
 */
internal class TypingRig(
    val engine: QueuedEngine,
    settings: KeyboardPreferences,
    /** The offensive words a load leaves out, and the learning refuses, while their switch is on. */
    private val offensiveWords: Set<String> = emptySet(),
) {
    val editor = FakeFieldEditor()
    val clock = ManualClock()
    val host = FakeTypingHost(clock)
    val ring = FakeRingUi()

    /** The personal dictionary's thread, stepped as the rig settles. */
    val personalThread = SteppedExecutor()

    /** The personal dictionary, on tables in memory. */
    val dictionary = MemoryDictionary(personalThread)
    val store = DictionaryLearningStore(
        dictionary.worker, { dictionary.repository }, { task -> host.postDelayed(task, AT_ONCE_MILLIS) },
    )
    val orchestrator = TypingOrchestrator({ editor }, engine, host, ring, store, clock)

    /** What an edit or a reload of the personal model threw, rethrown by [settle]. */
    private var reloadFailure: Throwable? = null

    /** Where the edits and reloads of the personal model run, as the service runs them: at once, on the test's thread. */
    private val personalScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> reloadFailure = error },
    )

    /**
     * The personal model as the personal dictionary's edits reach it, the production host on the
     * rig's engine and orchestrator, attached to [dictionary]: the service's reload, the dictionary's
     * rows loaded as the service loads them, and the blocked words refused by the learning.
     */
    val personalModel = KeyboardPersonalModel(
        personalScope,
        Mutex(),
        engine,
        orchestrator,
        reloadModel = { between -> reloadSteps().reload(between) },
        refreshBlocked = {
            orchestrator.setRefusedWords(RefusedWords.of(dictionary.repository.blockedWordSet(), offensiveWhileSwitchedOn()))
        },
    )

    init {
        engine.onSuggestions = orchestrator::onSuggestions
        engine.onGestureCandidates = orchestrator::onGestureCandidates
        engine.onGesturePreviewCandidates = orchestrator::onGesturePreviewCandidates
        host.onReloadPersonalModel = { reloadPersonalModel() }
        dictionary.repository.attach(personalModel)
        orchestrator.applySettings(settings)
    }

    /**
     * Swipes through the keys of [word] on the harness's layout and lifts, then settles; with
     * [reports] false, delivers the reports the swipe's own edits queued, serves the engine's
     * answers and holds the reports those queue.
     */
    fun swipe(word: String, reports: Boolean = true) {
        val path = SwipePath.through(word, clock)
        orchestrator.onGesture(path.xs, path.ys, path.timestamps, path.count)
        if (reports) {
            settle()
            return
        }
        while (true) {
            val report = editor.takeReport() ?: break
            orchestrator.onSelectionChanged(report.selectionStart, report.selectionEnd)
        }
        while (engine.serveNext()) continue
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
        verbatimField: Boolean = false,
        userUnlocked: Boolean = true,
        contentMimeTypes: List<String> = emptyList(),
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
                    verbatimField = verbatimField,
                ),
                addressField = addressField,
                terminalField = terminalField,
                inputType = inputType,
                imeOptions = imeOptions,
                described = true,
                contentMimeTypes = contentMimeTypes,
            ),
        )
        // The platform reports where the caret sits in a field that opens with text.
        if (text.isNotEmpty()) {
            orchestrator.onSelectionChanged(editor.selectionStart, editor.selectionEnd)
        }
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

    /** Flicks a key: [flick]'s effect through the orchestrator, and settles. */
    fun flick(flick: KeyFlick): Boolean {
        clock.advance(KEY_INTERVAL_MILLIS)
        val taken = orchestrator.onFlick(flick)
        settle()
        return taken
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

    /** Drags along backspace by [steps] characters, negative leftwards, and settles. */
    fun backspaceSelect(steps: Int) {
        orchestrator.onBackspaceSelect(steps)
        settle()
    }

    /** Drags along backspace by [lines] lines, and settles. */
    fun backspaceSelectLines(lines: Int) {
        orchestrator.onBackspaceSelectLines(lines)
        settle()
    }

    /** Lifts after a drag along backspace, and settles. */
    fun backspaceLift() {
        orchestrator.onBackspaceSelectionLift()
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

    /** Makes the host forget each word it is asked to forget, as the service does. */
    fun reloadOnForget() {
        host.onForgetWord = { word ->
            personalScope.launch {
                orchestrator.forgetUnsaved(word)
                dictionary.repository.forgetEveryCase(word)
            }
        }
    }

    /**
     * The service's reload of the personal model, started at once and carried on as the engine's
     * settle and the personal dictionary's reads and writes are served ([KeyboardPersonalModel.reload]).
     */
    fun reloadPersonalModel() {
        personalScope.launch { personalModel.reload {} }
    }

    /** Loads the personal dictionary's rows into the engine as the service does at its start, and settles. */
    fun loadPersonalModel() {
        personalScope.launch { reloadSteps().reload() }
        settle()
    }

    /**
     * One reload as the service runs it: the engine settled, the links marked stale, the learning
     * waiting written, the dictionary's rows loaded as the service loads them, and the links marked
     * fresh once the engine settles again.
     */
    private fun reloadSteps() = PersonalModelReload(
        settle = { suspendCancellableCoroutine { continuation -> engine.settle { continuation.resume(Unit) } } },
        flush = { orchestrator.flushLearningNow() },
        load = {
            engine.loadPersonal(
                PersonalModelLoad.read(dictionary.repository, orchestrator.preferences, offensiveWords, clock.now),
            )
        },
        stale = { orchestrator.personalModelStale() },
        fresh = { orchestrator.personalModelFresh() },
    )

    /** [offensiveWords] while the offensive-word switch is on, else none. */
    private fun offensiveWhileSwitchedOn(): Set<String> =
        if (orchestrator.preferences.blockOffensiveWords) offensiveWords else emptySet()

    /** Runs [block] on the personal dictionary, an edit as the pages make it, and settles; returns what it returned. */
    fun <T> edit(block: suspend (DictionaryRepository) -> T): T = read(block)

    /** What [block] reads from the personal dictionary, asked for now and answered as the rig settles. */
    fun <T> read(block: suspend (DictionaryRepository) -> T): T {
        val answer = CompletableDeferred<T>()
        CoroutineScope(Dispatchers.Unconfined).launch {
            answer.completeWith(runCatching { block(dictionary.repository) })
        }
        settle()
        return runBlocking { answer.await() }
    }

    /** The word rows written to the personal dictionary, by spelling in the order each was first written. */
    fun writtenWords(): List<UserWord> =
        runBlocking { dictionary.words.allWords().flatMap { dictionary.words.rows(it) } }

    /** The pairs written, in the order each was first written. */
    fun writtenPairs(): List<UserBigram> = dictionary.pairs.rows.values.toList()

    /** The triples written, in the order each was first written. */
    fun writtenTriples(): List<UserTrigram> = dictionary.triples.rows.values.toList()

    /** The heatmap's totals written, in the order each was first written. */
    fun writtenTouches(): List<KeyTouch> = runBlocking { dictionary.touches.observeAll().first() }

    /** Whether nothing at all was written to the personal dictionary. */
    fun nothingWritten(): Boolean =
        writtenWords().isEmpty() && writtenPairs().isEmpty() && writtenTriples().isEmpty() && writtenTouches().isEmpty()

    /**
     * Delivers the selection reports, the engine's answers, the due runnables and the personal
     * dictionary's reads and writes, until none is left.
     */
    fun settle() {
        while (true) {
            val report = editor.takeReport()
            if (report != null) {
                orchestrator.onSelectionChanged(report.selectionStart, report.selectionEnd)
                continue
            }
            if (engine.serveNext() || host.runDue() || personalThread.runNext()) {
                continue
            }
            break
        }
        reloadFailure?.let { throw it }
        val word = orchestrator.composingText
        check(orchestrator.taps.size == word.codePointCount(0, word.length)) {
            "'$word' has ${orchestrator.taps.size} taps"
        }
    }

    companion object {
        /** The time between two key presses. */
        const val KEY_INTERVAL_MILLIS = 150L

        /** The delay of a runnable posted to run at once, as the main thread's handler runs it. */
        private const val AT_ONCE_MILLIS = 0L
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
