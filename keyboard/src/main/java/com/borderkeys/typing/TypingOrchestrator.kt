// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.CaretNudge
import com.borderkeys.ime.Composer
import com.borderkeys.ime.FieldRestore
import com.borderkeys.ime.KeyCodes
import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.ime.PhysicalKeys
import com.borderkeys.ime.RunningText
import com.borderkeys.ime.ShiftState
import com.borderkeys.ime.WordCommit
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.RefusedWords
import com.borderkeys.predict.WordFold

/**
 * Owns each word from its first key to what is learned from it: the keys, the composing word,
 * the engine's answer, the commit decision, the pending correction, spacing, shift and the
 * field's history. The field, the engine, the views, the swipe ring, the database and the clocks
 * are reached through [FieldEditor], [EnginePort], [TypingHost], [RingUi], [LearningStore] and
 * [TypingClock].
 */
class TypingOrchestrator(
    /** The field being typed into, or null when none is bound. */
    private val currentEditor: () -> FieldEditor?,
    private val engine: EnginePort,
    private val host: TypingHost,
    private val ring: RingUi,
    store: LearningStore,
    private val clock: TypingClock,
) {
    /** What is learned from typing, and the gate on it. */
    private val learningFlow = LearningFlow(engine, host, store, clock)

    /** Shift, caps lock and auto-shift, and the casing they give words. */
    private val shiftFlow = ShiftFlow(currentEditor, host, clock)

    /** The spaces the keyboard writes or takes back on its own. */
    private val spacingFlow = SpacingFlow(clock)

    /** Typing into a terminal, key event by key event. */
    private val terminalWriter = TerminalWriter(host)

    /** The commit decision, the pending correction, and the language switch's corrections. */
    private val commitFlow = CommitFlow(currentEditor, engine)

    /** What the strip asks the engine, the answer a delimiter applies, and the strip's row. */
    private val suggestionFlow = SuggestionFlow(engine, host, clock)

    /** A swipe between its decode and its word, and the words its ring offers. */
    private val swipeFlow = SwipeFlow()

    /** The flows, in the order each hears of a field and of the settings. */
    private val flows: List<TypingFlow> = listOf(
        terminalWriter, learningFlow, shiftFlow, spacingFlow, commitFlow, suggestionFlow, swipeFlow,
    )

    var preferences = KeyboardPreferences()
        private set

    /** The field being typed into: what it is and what it allows. */
    var session = FieldSession.NONE
        private set

    /** The tags of the packs the engine consults, heaviest first. */
    var languageTags: List<String>
        get() = spacingFlow.languageTags
        set(value) {
            spacingFlow.languageTags = value
        }

    /** Apostrophe spellings for the languages switched on; see [com.borderkeys.ime.Contractions]. */
    var contractions: Map<String, String>
        get() = commitFlow.contractions
        set(value) {
            commitFlow.contractions = value
        }

    /** The word being written. */
    private val composingWord = ComposingWord()

    /** [composingWord]'s text. */
    private val composing: StringBuilder get() = composingWord.text

    /** The word being written. */
    val composingText: String get() = composing.toString()

    /** Where each code point of the word being written was typed. */
    internal val taps: TapTrail get() = composingWord.taps

    /** The letter keys as the taps land on them, for the current field and layout. */
    var keyGeometry: KeyGeometrySnapshot? = null
        set(value) {
            field = value
            learningFlow.geometry = value
        }

    /** The editor's selection, as of the last report, [selectionStart] never after [selectionEnd]. */
    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set

    /** What the last slide along the space bar selected, so one drag keeps one anchor. */
    private var lastNudge: CaretNudge.Selection? = null

    /** The two words before the word being written. */
    var wordContext = WordContext.NONE
        private set

    /**
     * Set right before a commit of this class's own and spent by the next selection report,
     * which is then not treated as a caret move. Cleared by any key press.
     */
    private var ownEditPending = false

    /** Whether [adoptWordAtCaret] is running; selection reports meanwhile are its own edits. */
    private var adoptingWordAtCaret = false

    /** The word characters right after the caret, read where the caret last moved to. */
    private var caretTail = ""

    /** The engine's last answer from [askCaretSplit]. */
    private var caretSplit: CaretSplit? = null

    /** The field's undo and redo history for this input session. */
    private val fieldHistory = Composer()

    /** The word the engine was last asked about. */
    val lastQuery: String get() = suggestionFlow.lastQuery

    // ---- the field ---------------------------------------------------------------------------

    /**
     * Starts [field]: the learning gate is set for it, and the word, shift, the modifiers and the
     * field's history start over.
     */
    fun startField(field: FieldSession) {
        session = field
        session = session.copy(
            policy = session.policy.withSwitches(preferences.learningEnabled, preferences.heatmapEnabled),
        )
        for (flow in flows) {
            flow.startField(session)
        }
        host.releaseModifiers()
        ownEditPending = false
        caretTail = currentEditor()?.let(::wordAfterCaret).orEmpty()
        caretSplit = null
        resetComposing()
        resetFieldHistory()
        applyAutoShift()
        updateEditorEmpty(currentEditor()?.textBeforeCursor(1)?.isNotEmpty() == true)
    }

    /** The field closed: what was learned is written, and the pending requests and the word go. */
    fun finishField() {
        for (flow in flows) {
            flow.finishField()
        }
        engine.cancelPending()
        resetComposing()
    }

    /** Takes [settings]; with the views up, the learning gate and shift follow them at once. */
    fun applySettings(settings: KeyboardPreferences) {
        preferences = settings
        session = session.copy(
            policy = session.policy.withSwitches(settings.learningEnabled, settings.heatmapEnabled),
        )
        for (flow in flows) {
            flow.applySettings(settings)
        }
        if (host.viewAttached) {
            applyAutoShift()
        }
    }

    /** The last flush: writes what is left and waits, a bounded time, for the writes in flight. */
    fun shutdown() {
        for (flow in flows) {
            flow.shutdown()
        }
    }

    /** Sets the words never learned. */
    fun setRefusedWords(words: RefusedWords) {
        learningFlow.setRefusedWords(words)
    }

    /** Learns nothing until the learning gate is next set, at a field start or a settings change. */
    fun stopLearning() {
        learningFlow.stop()
    }

    /**
     * The field reported its selection as [start] to [end]; a selection made leftwards comes
     * with [start] after [end].
     */
    fun onSelectionChanged(start: Int, end: Int) {
        selectionStart = minOf(start, end)
        selectionEnd = maxOf(start, end)
        host.refreshPrivateReveal()
        updateEditorEmpty(selectionEnd > 0)
        if (!host.viewAttached) {
            return
        }
        val hasSelection = selectionEnd > selectionStart
        host.clearStripActions()
        if (!hasSelection) {
            // A caret that still ends the composing text asks for suggestions; the echo of this
            // class's own commit is spent; any other move closes the ring and adopts the word
            // under the caret.
            val caretMatches = composingMatchesCaret(selectionEnd)
            if (caretMatches) {
                if (lastQuery != composing.toString()) {
                    requestSuggestions()
                }
            } else if (ownEditPending) {
                ownEditPending = false
            } else if (session.terminalField) {
                dismissRing()
            } else {
                dismissRing()
                adoptWordAtCaret()
            }
            applyAutoShift()
        } else {
            dismissRing()
        }
    }

    // ---- keys --------------------------------------------------------------------------------

    /**
     * A key press, the key at [keyIndex] chosen at ([x], [y]) in the keyboard view's pixels, NaN
     * when no tap chose it. Every key but backspace confirms a pending correction and closes the
     * ring first. Returns false for a key left to the caller: pages, panels, the globe and the
     * modifier keys.
     */
    fun onKey(
        code: Int,
        keyIndex: Int = TapTrail.NO_KEY,
        x: Float = Float.NaN,
        y: Float = Float.NaN,
    ): Boolean {
        if (code != KeyCodes.DELETE) {
            confirmPendingCorrection()
            dismissRing()
        }
        when (code) {
            KeyCodes.SHIFT -> handleShift()
            KeyCodes.DELETE -> handleDelete()
            KeyCodes.ENTER -> handleEnter()
            KeyCodes.ESCAPE -> handleHardwareKey(KeyEvent.KEYCODE_ESCAPE)
            KeyCodes.TAB -> handleHardwareKey(KeyEvent.KEYCODE_TAB)
            KeyCodes.ARROW_LEFT, KeyCodes.ARROW_RIGHT, KeyCodes.ARROW_UP, KeyCodes.ARROW_DOWN,
            KeyCodes.HOME, KeyCodes.END, KeyCodes.PAGE_UP, KeyCodes.PAGE_DOWN ->
                handleNavigationKey(code)
            KeyCodes.FORWARD_DELETE -> handleHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)
            KeyCodes.INSERT -> handleHardwareKey(KeyEvent.KEYCODE_INSERT)
            else -> if (KeyCodes.isCharacter(code)) {
                handleCharacter(code, keyIndex, x, y)
            } else {
                return false
            }
        }
        return true
    }

    /** A held key repeating: backspace, an arrow, or forward delete. */
    fun onKeyRepeat(code: Int) {
        if (code == KeyCodes.DELETE) {
            handleDelete()
        } else if (KeyCodes.isArrow(code)) {
            handleNavigationKey(code)
        } else if (code == KeyCodes.FORWARD_DELETE) {
            handleHardwareKey(KeyEvent.KEYCODE_FORWARD_DEL)
        }
    }

    /**
     * A key held down: shift locks, and backspace reverts a pending correction or deletes the
     * word before the cursor. Returns false for any other key.
     */
    fun onKeyLongPress(code: Int): Boolean {
        if (code == KeyCodes.SHIFT) {
            lockShift()
            return true
        }
        if (code != KeyCodes.DELETE) {
            return false
        }
        val editor = currentEditor()
        if (editor != null && !revertCorrection(editor)) {
            deleteWordBeforeCursor(editor)
        }
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
        return true
    }

    /** A key that writes more than one character: the word is finished, then [text] written. */
    fun onText(text: CharSequence) {
        dismissRing()
        val editor = currentEditor() ?: return
        confirmPendingCorrection()
        editor.beginBatchEdit()
        finishComposing(editor)
        editor.commitText(text, 1)
        editor.endBatchEdit()
        checkpointField()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    /** Moves the caret by [steps] characters through setSelection, from a space-bar slide. */
    fun onCursorNudge(steps: Int) {
        dismissRing()
        val editor = currentEditor() ?: return
        val extracted = editor.extractedText(0) ?: return
        val length = extracted.text?.length ?: return
        val next = CaretNudge.slide(
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            steps = steps,
            length = length,
            selecting = shiftFlow.state != ShiftState.OFF,
        )
        applyNudge(editor, next)
    }

    /**
     * Finishes the composing word and moves the caret, or the selection, to [next] in one batch
     * edit.
     */
    private fun applyNudge(editor: FieldEditor, next: CaretNudge.Selection) {
        lastNudge = next
        if (next.start == selectionStart && next.end == selectionEnd) {
            return
        }
        editor.beginBatchEdit()
        if (composing.isNotEmpty()) {
            finishComposing(editor)
        }
        selectionStart = next.start
        selectionEnd = next.end
        editor.setSelection(next.anchor, next.caret)
        editor.endBatchEdit()
    }

    /** Moves the caret up or down by [lines], keeping its column; with shift held it selects. */
    fun onCursorNudgeLines(lines: Int) {
        dismissRing()
        val editor = currentEditor() ?: return
        val text = editor.extractedText(0)?.text ?: return
        val next = CaretNudge.slideLines(
            text = text,
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            lines = lines,
            selecting = shiftFlow.state != ShiftState.OFF,
        )
        applyNudge(editor, next)
    }

    /**
     * A caret key from the modifier row, sent as the hardware key, selecting when shift is held.
     * The word being typed is finished first.
     */
    private fun handleNavigationKey(code: Int) {
        if (currentEditor() == null) {
            return
        }
        val keyCode = when (code) {
            KeyCodes.ARROW_LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            KeyCodes.ARROW_RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            KeyCodes.ARROW_UP -> KeyEvent.KEYCODE_DPAD_UP
            KeyCodes.ARROW_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            KeyCodes.HOME -> KeyEvent.KEYCODE_MOVE_HOME
            KeyCodes.END -> KeyEvent.KEYCODE_MOVE_END
            KeyCodes.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
            else -> KeyEvent.KEYCODE_PAGE_DOWN
        }
        val meta = shiftFlow.heldMeta(spend = false)
        ownEditPending = composing.isNotEmpty()
        resetComposing()
        host.sendPhysicalKey(keyCode, meta)
        refreshContextFromEditor()
        applyAutoShift()
    }

    /**
     * Escape, tab, or a character under control or alt: commits the word being typed, then sends
     * the key.
     */
    private fun handleHardwareKey(keyCode: Int) {
        val editor = currentEditor() ?: return
        val meta = shiftFlow.heldMeta(spend = true)
        ownEditPending = composing.isNotEmpty()
        editor.beginBatchEdit()
        finishComposing(editor)
        host.sendPhysicalKey(keyCode, meta)
        editor.endBatchEdit()
        checkpointField()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    private fun handleCharacter(code: Int, keyIndex: Int, x: Float, y: Float) {
        val editor = currentEditor() ?: return
        KeyboardStats.keystrokes++
        KeyboardStats.input(clock.uptimeMillis())
        // Under an armed control or alt, a character goes out as its hardware key; one with no
        // hardware key releases the modifiers and is typed.
        if (host.modifiersArmed) {
            val keyCode = PhysicalKeys.keyCodeFor(code)
            if (keyCode != 0) {
                handleHardwareKey(keyCode)
                return
            }
            host.releaseModifiers()
        }
        ownEditPending = false
        val shifted = shiftFlow.shifted(code)
        if (session.terminalField) {
            typeIntoTerminal(editor, shifted)
            return
        }
        // A word starts with a letter; after that, [isWordCharacter] continues it.
        val letter = if (composing.isEmpty()) {
            Character.isLetter(shifted)
        } else {
            isWordCharacter(shifted)
        }
        // A one-shot shift is spent only by a letter.
        if (letter) {
            shiftFlow.spendOneShot()
        }
        val heldByUser = shiftFlow.heldByUser
        if (composing.isEmpty() && letter) {
            composingWord.capitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            val ahead = editor.textBeforeCursor(1)
            composingWord.runningText = ahead.isNullOrEmpty() || !RunningText.isMark(ahead[0])
        }
        if (letter) {
            shiftFlow.letterTyped()
        }

        if (letter) {
            spacingFlow.dropAutoSpace()
            if (composingWord.fromGesture) {
                // A letter after a swiped word finishes and learns it, adds a space, and starts
                // the next word.
                composingWord.fromGesture = false
                val contextWord = wordContext.previous1
                val grandContextWord = wordContext.previous2
                editor.beginBatchEdit()
                val finished = finishComposing(editor)
                editor.commitText(" ", 1)
                editor.endBatchEdit()
                if (finished != null) {
                    learningFlow.record(finished, contextWord, grandContextWord, composingWord.capitalisedByUser)
                }
                checkpointField()
                composingWord.capitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            }
            composingWord.append(shifted, keyIndex, x, y)
            editor.setComposingText(composing, 1)
            requestSuggestions()
            return
        }

        // A delimiter ends the word. What replaces the typed word, if anything, is decided by
        // [commitOutcome]; a rewrite is committed in its place and can be reverted.
        val typed = composing.toString()
        val typedTaps = TypedTaps.of(composing, composingWord.taps)
        val outcome = commitOutcome(typed, shifted)
        val rewrite = outcome.isRewrite
        val correction = outcome.text
        // Read before anything commits.
        val contextWord = wordContext.previous1
        val grandContextWord = wordContext.previous2

        // A space typed right after one this keyboard added is handled per
        // KeyboardPreferences.autoSpaceHabit.
        if (shifted == ' '.code && spacingFlow.swallowsTypedSpace(typed.isEmpty(), editor)) {
            shiftFlow.afterDelimiter(heldByUser, composing.isEmpty())
            return
        }

        // Two spaces in quick succession after a word character become ". ".
        if (shifted == ' '.code && spacingFlow.doubleSpaceMakesPeriod(typed.isEmpty(), editor)) {
            ownEditPending = true
            spacingFlow.writePeriod(editor)
            commitFlow.dropPending()
            checkpointField()
            refreshContextFromEditor()
            applyAutoShift(justCommitted = ". ")
            requestSuggestions()
            return
        }
        spacingFlow.delimiterTyped(shifted)

        ownEditPending = true
        editor.beginBatchEdit()
        spacingFlow.removeSpaceBeforeMark(shifted, typed.isEmpty(), editor)
        val delimiter = String(Character.toChars(shifted)) + spacingFlow.spaceAfterMark(shifted, editor)
        if (correction != null) {
            // commitText replaces the composing region with the correction.
            composingWord.clear()
            editor.commitText(correction + delimiter, 1)
            host.playEffect(EffectEvent.AutocorrectApplied, correction)
        } else {
            finishComposing(editor)
            editor.commitText(delimiter, 1)
        }
        editor.endBatchEdit()

        if (correction != null) {
            // An expansion's last word is the context the next word follows.
            wordContext = wordContext.then(
                if (rewrite) correction.substringAfterLast(' ') else correction,
            )
            // Learned once the correction survives the next keystroke.
            commitFlow.setPending(
                PendingCorrection(
                    typed, correction, delimiter, contextWord, grandContextWord,
                    composingWord.capitalisedByUser, learn = !rewrite, taps = typedTaps,
                ),
            )
            if (!rewrite) {
                commitFlow.recordLanguageSwitchFlag(editor, typed, correction, delimiter)
            }
        } else {
            if (typed.isNotEmpty()) {
                learningFlow.record(
                    typed, contextWord, grandContextWord, composingWord.capitalisedByUser,
                    taps = typedTaps,
                )
            }
            commitFlow.dropPending()
        }
        // A sentence mark clears the context for the next word, after this word was learned.
        if (isSentenceEndingPunctuation(shifted)) {
            wordContext = WordContext.NONE
        }
        checkpointField()
        shiftFlow.afterDelimiter(heldByUser, composing.isEmpty(), justCommitted = delimiter)
        requestSuggestions()
        engine.dominantLanguageTag { tag -> spacingFlow.dominantLanguageTag = tag }
        commitFlow.checkLanguageSwitch(::onLanguageSwitchReplacements)
    }

    private fun handleDelete() {
        dismissRing()
        val editor = currentEditor() ?: return
        if (session.terminalField) {
            deleteInTerminal()
            return
        }
        val hasSelection = selectionEnd > selectionStart
        // A selection is deleted whole, by committing empty text over it.
        if (hasSelection) {
            composingWord.clear()
            composingWord.fromGesture = false
            confirmPendingCorrection()
            editor.commitText("", 1)
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return
        }
        // A swiped word is deleted whole when KeyboardPreferences.swipeBackspaceDeletesWord is on.
        if (composingWord.fromGesture && preferences.swipeBackspaceDeletesWord && composing.isNotEmpty()) {
            cancelSwipedWord()
            applyAutoShift()
            return
        }
        if (spacingFlow.takeBackPeriod(editor)) {
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (revertCorrection(editor)) {
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            // After a backspace, typed letters extend a swiped word.
            composingWord.fromGesture = false
            composingWord.deleteLast()
            editor.setComposingText(composing, 1)
            if (composing.isEmpty()) {
                applyAutoShift()
            }
            requestSuggestions()
            return
        }
        editor.beginBatchEdit()
        val before = editor.textBeforeCursor(2)
        val toDelete = if (before != null && before.length == 2 &&
            Character.isSurrogatePair(before[0], before[1])
        ) {
            2
        } else {
            1
        }
        editor.deleteSurroundingText(toDelete, 0)
        editor.endBatchEdit()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    private fun handleEnter() {
        val editor = currentEditor() ?: return
        if (session.terminalField) {
            enterInTerminal()
            return
        }
        val contextWord = wordContext.previous1
        val grandContextWord = wordContext.previous2
        val typedTaps = TypedTaps.of(composing, composingWord.taps)
        editor.beginBatchEdit()
        val finished = finishComposing(editor)
        val imeOptions = session.imeOptions
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
        // ENTER_KEY_AUTO honours IME_FLAG_NO_ENTER_ACTION, ENTER_KEY_FORCE_ACTION performs any
        // declared action, and ENTER_KEY_FORCE_NEWLINE never performs one.
        val noEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val performAction = when (preferences.enterKeyBehavior) {
            KeyboardPreferences.ENTER_KEY_FORCE_ACTION -> hasAction
            KeyboardPreferences.ENTER_KEY_FORCE_NEWLINE -> false
            else -> hasAction && !noEnterAction
        }
        if (performAction) {
            editor.endBatchEdit()
            editor.performEditorAction(action)
        } else {
            editor.commitText("\n", 1)
            editor.endBatchEdit()
            afterNewlineCommitted()
        }
        if (finished != null) {
            learningFlow.record(
                finished, contextWord, grandContextWord, composingWord.capitalisedByUser,
                taps = typedTaps,
            )
        }
        // Enter clears the context for the next word.
        wordContext = WordContext.NONE
        requestSuggestions()
    }

    /**
     * After a committed newline, from Enter or the quick-action bar: records the step, resets
     * the spacing state, and re-derives the context and shift.
     */
    fun afterNewlineCommitted() {
        checkpointField()
        spacingFlow.lineStarted()
        refreshContextFromEditor()
        applyAutoShift()
    }

    private fun handleShift() {
        shiftFlow.press()
        // The strip's words are re-cased for the new shift state.
        requestSuggestions()
    }

    /** Locks shift, from holding it. */
    private fun lockShift() {
        shiftFlow.lock()
        requestSuggestions()
    }

    // ---- swipes and the ring -------------------------------------------------------------------

    /**
     * A finished swipe of [count] samples: an open ring closes, the pause's guess is taken back or
     * the word in progress finished, and the path is decoded.
     */
    fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.swipeEnabled || !session.policy.suggestionsAllowed) {
            return
        }
        swipeFlow.forgetTopWord()
        // Closes a ring and drops a pause-time decode still in flight.
        dismissRing()
        engine.cancelPendingPreview()
        if (swipeFlow.takePreviewComposed()) {
            // The preview's guess is taken back; this decode replaces it.
            cancelSwipedWord()
        } else {
            finishWordBeforeSwipe()
        }
        host.onSwipeLifted(xs, ys, timestamps, count)
        engine.decodeGesture(
            xs, ys, timestamps, count, wordContext.previous1, wordContext.previous2,
        )
    }

    /** The finger paused mid-swipe: the path so far is decoded for the ring, if it is on. */
    fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.radialMenuEnabled || !session.policy.suggestionsAllowed) {
            ring.resumeGestureCapture()
            return
        }
        swipeFlow.forgetTopWord()
        if (swipeFlow.takePreviewComposed()) {
            // A second pause: the first preview's guess is taken back.
            cancelSwipedWord()
        } else {
            finishWordBeforeSwipe()
        }
        engine.decodeGesturePreview(
            xs, ys, timestamps, count, wordContext.previous1, wordContext.previous2,
        )
    }

    /**
     * The pause-time decode's answer: the top candidate composes at once, and the ring opens on
     * [SwipeFlow.wedges].
     */
    fun onGesturePreviewCandidates(candidates: List<Candidate>) {
        if (!host.viewAttached || currentEditor() == null) {
            return
        }
        if (candidates.isEmpty() || session.terminalField) {
            // No ring: the stroke goes back to plain capture.
            ring.resumeGestureCapture()
            return
        }
        val cased = caseSwipedWords(candidates)
        swipeFlow.rememberTopWord(cased.first().text)
        composeSwipedWord(cased)
        host.onSwipeDecoded(candidates.size)
        host.traceSwipeDecode(candidates.size)
        swipeFlow.previewComposed()
        val pickTimeout = if (preferences.radialLiftKeepsOpen) {
            null
        } else {
            preferences.radialPickTimeoutMillis.toLong()
        }
        if (!ring.open(swipeFlow.wedges(cased), SwipeFlow.TRUSTED_WEDGE, false, pickTimeout)) {
            // Too few words for a ring: the preview stays composing and the stroke goes back to
            // plain capture.
            swipeFlow.forgetTopWord()
            ring.resumeGestureCapture()
        }
    }

    /**
     * A decoded swipe that did not pause: the first candidate composes at once and all of them go
     * to the strip. With [KeyboardPreferences.radialLiftKeepsOpen] on, a ring opens for a tap,
     * unless the decode was decisive and [KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY] is set.
     */
    fun onGestureCandidates(candidates: List<Candidate>) {
        host.onSwipeAnswered()
        if (candidates.isEmpty()) {
            host.clearStrip()
            requestSuggestions()
            return
        }
        if (currentEditor() == null) {
            return
        }
        val cased = caseSwipedWords(candidates)
        val best = cased.first().text
        if (session.terminalField) {
            swipeIntoTerminal(cased)
            host.onSwipeDecoded(cased.size)
            return
        }
        composeSwipedWord(cased)
        host.onSwipeDecoded(candidates.size)
        host.traceSwipeDecode(candidates.size)
        // The strip shows the swipe's alternatives, with no typed chip and no correction.
        host.showSuggestions(cased, -1, -1)
        if (host.viewAttached && preferences.radialMenuEnabled && preferences.radialLiftKeepsOpen) {
            swipeFlow.rememberTopWord(best)
            if (preferences.radialTrustedWord == KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY &&
                swipeFlow.decisive(cased)
            ) {
                host.playEffect(EffectEvent.SwipeAccepted, best)
                return
            }
            ring.open(swipeFlow.wedges(cased), SwipeFlow.TRUSTED_WEDGE, true, null)
        }
    }

    /**
     * The finger lifted while the ring was open: its selection resolves it. With no wedge or
     * Cancel under the finger and [KeyboardPreferences.radialLiftKeepsOpen] on, the ring stays open
     * for a tap.
     */
    fun onRingLifted() {
        if (!host.viewAttached) {
            return
        }
        swipeFlow.forgetPreview()
        val selection = ring.selection()
        if (selection == RingUi.Selection.None && preferences.radialLiftKeepsOpen && ring.isOpen) {
            ring.keepOpenForTap()
            return
        }
        closeRing((selection as? RingUi.Selection.Word)?.index)
        resolveRingSelection(selection)
    }

    /** The touch stream was interrupted while the ring was open: the swipe is discarded. */
    fun onRingCancelled() {
        closeRing()
        cancelSwipedWord()
    }

    /**
     * A tap on a ring kept open after a lift: a wedge applies its word, the centre cancels, and
     * anything else only closes the ring.
     */
    fun onRingTapped(selection: RingUi.Selection) {
        when (selection) {
            is RingUi.Selection.Word -> {
                closeRing(selection.index)
                resolveRingSelection(selection)
            }
            RingUi.Selection.Cancel -> {
                closeRing()
                cancelSwipedWord()
            }
            RingUi.Selection.None -> dismissRing()
        }
    }

    /** The ring's pick timeout ran out: its current selection resolves it. */
    fun onRingTimedOut() {
        val selection = ring.selection()
        closeRing((selection as? RingUi.Selection.Word)?.index)
        resolveRingSelection(selection)
    }

    /** Closes the swipe's ring, when one is open, and forgets the pause's guess with it. */
    fun dismissRing() {
        if (ring.dismiss()) {
            swipeFlow.forgetPreview()
        }
    }

    /** Closes the ring; [celebrateIndex] is the picked wedge, or null. */
    private fun closeRing(celebrateIndex: Int? = null) {
        swipeFlow.forgetPreview()
        ring.close(celebrateIndex)
    }

    /**
     * A wedge applies its word and Cancel discards the swipe; with neither,
     * [KeyboardPreferences.radialTimeoutDefault] applies the top word or cancels.
     */
    private fun resolveRingSelection(selection: RingUi.Selection) {
        when (selection) {
            is RingUi.Selection.Word -> {
                onPick(selection.index, selection.word)
                host.playEffect(EffectEvent.SwipeAccepted, selection.word)
            }
            RingUi.Selection.Cancel -> cancelSwipedWord()
            RingUi.Selection.None -> {
                val word = swipeFlow.topWord
                if (preferences.radialTimeoutDefault == KeyboardPreferences.RADIAL_TIMEOUT_CANCEL) {
                    cancelSwipedWord()
                } else if (word != null) {
                    onPick(0, word)
                    host.playEffect(EffectEvent.SwipeAccepted, word)
                } else {
                    cancelSwipedWord()
                }
            }
        }
    }

    // ---- swiped words --------------------------------------------------------------------------

    /** Finishes and learns the word in progress before a swipe. */
    fun finishWordBeforeSwipe() {
        val editor = currentEditor() ?: return
        if (composing.isEmpty()) {
            return
        }
        val contextWord = wordContext.previous1
        val grandContextWord = wordContext.previous2
        val typedTaps = TypedTaps.of(composing, composingWord.taps)
        // The caret report this edit causes is not a caret move.
        ownEditPending = true
        editor.beginBatchEdit()
        val finished = finishComposing(editor)
        editor.endBatchEdit()
        if (finished != null) {
            learningFlow.record(
                finished, contextWord, grandContextWord, composingWord.capitalisedByUser,
                taps = typedTaps,
            )
        }
    }

    /**
     * Cases the swipe candidates as typed letters would come out under the current shift, names
     * capitalised, then spends a one-shot shift. Never lower-cases. Drops candidates that become
     * the same text.
     */
    fun caseSwipedWords(candidates: List<Candidate>): List<Candidate> {
        if (!preferences.capitaliseNames) {
            return candidates
        }
        composingWord.capitalisedByUser = shiftFlow.heldByUser && shiftFlow.state != ShiftState.OFF
        return shiftFlow.caseSwiped(candidates)
    }

    /**
     * Composes the first of [cased], from [caseSwipedWords], after a space where one is needed,
     * and makes it the answer a delimiter applies.
     */
    fun composeSwipedWord(cased: List<Candidate>) {
        val editor = currentEditor() ?: return
        val best = cased.first().text
        editor.beginBatchEdit()
        spaceBeforeSwipedWord(editor)
        composingWord.clear()
        composingWord.appendUntapped(best)
        editor.setComposingText(composing, 1)
        editor.endBatchEdit()
        composingWord.fromGesture = true
        suggestionFlow.answerSwiped(best, cased.first().isProperNoun)
    }

    /**
     * Discards the swiped word, with no commit and no learning: empties the composing region, or
     * deletes the word before the caret when it is no longer composing, and the space inserted
     * before it.
     */
    fun cancelSwipedWord() {
        val editor = currentEditor()
        if (editor != null) {
            editor.beginBatchEdit()
            if (composing.isNotEmpty()) {
                editor.setComposingText("", 1)
                editor.finishComposingText()
            } else {
                editor.finishComposingText()
                deleteWordBeforeCaret(editor)
            }
            // The space inserted before the swiped word, when it is still there.
            if (composingWord.autoSpaceBefore) {
                val before = editor.textBeforeCursor(1)
                if (before != null && before.length == 1 && before[0] == ' ') {
                    editor.deleteSurroundingText(1, 0)
                }
            }
            editor.endBatchEdit()
        }
        composingWord.clear()
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        host.clearStrip()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    /** Deletes the run of [isWordCharacter] characters before the caret. */
    private fun deleteWordBeforeCaret(editor: FieldEditor) {
        val before = editor.textBeforeCursor(CONTEXT_WINDOW_CHARS)
        if (before.isNullOrEmpty()) {
            return
        }
        var length = 0
        while (length < before.length && isWordCharacter(before[before.length - 1 - length].code)) {
            length++
        }
        if (length > 0) {
            editor.deleteSurroundingText(length, 0)
        }
    }

    /**
     * Inserts a space before a swiped word unless the caret follows whitespace, nothing, a
     * character in [SWIPE_NO_SPACE_AFTER], or is in an address field. Records it in
     * [ComposingWord.autoSpaceBefore].
     */
    private fun spaceBeforeSwipedWord(editor: FieldEditor) {
        composingWord.autoSpaceBefore = false
        if (session.addressField) {
            return
        }
        val before = editor.textBeforeCursor(1)
        if (before.isNullOrEmpty()) {
            return
        }
        val previous = before[0]
        if (previous.isWhitespace() || previous in SWIPE_NO_SPACE_AFTER) {
            return
        }
        editor.commitText(" ", 1)
        composingWord.autoSpaceBefore = true
    }

    /**
     * Writes a swiped word whole into a terminal, after a space when letters were typed just
     * before it, and offers the rest of [cased] on the strip. The word stays the terminal's word,
     * so a pick from the strip replaces it the way it replaces typed letters.
     */
    fun swipeIntoTerminal(cased: List<Candidate>) {
        val editor = currentEditor() ?: return
        val best = cased.first().text
        ownEditPending = true
        terminalWriter.writeSwiped(editor, best)
        suggestionFlow.answerSwiped(best, cased.first().isProperNoun)
        host.showSuggestions(cased, -1, -1)
        host.playEffect(EffectEvent.SwipeAccepted, best)
    }

    // ---- the language switch ------------------------------------------------------------------

    /** `Ask` shows the revert panel; `Auto-apply` edits the field itself, right away. */
    private fun onLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        if (preferences.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY) {
            applyLanguageSwitchReplacements(replacements)
        } else {
            host.offerLanguageReplacements(replacements)
        }
    }

    /**
     * Applies the replacements through [CommitFlow.replace] in one batch edit, with one
     * [checkpointField] for the batch.
     */
    fun applyLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        val editor = currentEditor() ?: return
        editor.beginBatchEdit()
        finishComposing(editor)
        val changed = commitFlow.replace(editor, replacements)
        editor.endBatchEdit()
        if (changed) {
            checkpointField()
            refreshContextFromEditor()
            requestSuggestions()
        }
    }

    // ---- the commit decision -------------------------------------------------------------------

    /** Confirms the pending correction and learns it. */
    private fun confirmPendingCorrection() {
        val pending = commitFlow.takePending() ?: return
        if (!pending.learn) {
            return
        }
        learningFlow.record(
            pending.corrected, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital, taps = pending.taps,
        )
    }

    /** Drops the pending correction without learning it, after an edit that rewrote the field. */
    fun dropPendingCorrection() {
        commitFlow.dropPending()
    }

    /** What the key [endedBy] would write in place of [typed]; see [WordCommit]. Reads no editor. */
    internal fun commitOutcome(typed: String, endedBy: Int): WordCommit.Outcome =
        commitFlow.decide(
            typed, endedBy, composingWord.fromGesture, composingWord.runningText,
            suggestionFlow.answer,
        )

    /**
     * Takes the pending correction back through [CommitFlow.revert], and learns what that leaves.
     * Returns whether it was taken back.
     */
    private fun revertCorrection(editor: FieldEditor, viaBackspace: Boolean = true): Boolean {
        val revert = commitFlow.revert(editor, viaBackspace) ?: return false
        val pending = revert.pending
        if (revert.learnCorrected) {
            learningFlow.record(
                pending.corrected, pending.contextWord, pending.grandContextWord,
                pending.deliberateCapital, taps = pending.taps,
            )
        }
        if (!revert.reverted) {
            return false
        }
        wordContext = wordContext.copy(previous1 = pending.typed)
        // The typed word is learned as asserted; the rejected correction is forgotten from the
        // personal dictionary, never blocked.
        learningFlow.record(
            pending.typed, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital, asserted = true, taps = pending.taps,
        )
        host.forgetWord(pending.corrected, blockWhenNotPersonal = false)
        host.playEffect(EffectEvent.CorrectionReverted, pending.typed)
        refreshContextFromEditor()
        return true
    }

    // ---- the field's history -------------------------------------------------------------------

    /** Records the field's text as a step in [fieldHistory] when it changed. */
    fun checkpointField() {
        val editor = currentEditor() ?: return
        val text = editor.extractedText(FIELD_HISTORY_CHARS)?.text?.toString() ?: return
        if (text == fieldHistory.current()) {
            return
        }
        fieldHistory.addResult(text)
    }

    /** Starts a new undo and redo history for a field just opened, seeded with its text. */
    private fun resetFieldHistory() {
        fieldHistory.clear()
        checkpointField()
    }

    /** Puts the field back one step; what was typed since the last step is a step of its own. */
    fun undo() {
        checkpointField()
        restoreFieldVersion(fieldHistory.back())
    }

    /** Puts the field forward one step, after [undo]; text typed since the undo stays. */
    fun redo() {
        checkpointField()
        restoreFieldVersion(fieldHistory.forward())
    }

    /**
     * Puts the field back to [target], editing only the span where the live text differs; see
     * [FieldRestore.diff].
     */
    private fun restoreFieldVersion(target: String?) {
        if (target == null) {
            return
        }
        val editor = currentEditor() ?: return
        val extracted = editor.extractedText(FIELD_HISTORY_CHARS) ?: return
        val current = extracted.text?.toString() ?: return
        if (current == target) {
            return
        }
        val span = FieldRestore.diff(current, target)
        editor.beginBatchEdit()
        finishComposing(editor)
        // The diff's offsets are into the extracted window, which starts at startOffset.
        val boundary = extracted.startOffset + span.deleteFrom + span.deleteCount
        editor.setSelection(boundary, boundary)
        if (span.deleteCount > 0) {
            editor.deleteSurroundingText(span.deleteCount, 0)
        }
        if (span.insert.isNotEmpty()) {
            editor.commitText(span.insert, 1)
        }
        editor.endBatchEdit()
        commitFlow.dropPending()
        refreshContextFromEditor()
        requestSuggestions()
    }

    // ---- picks ---------------------------------------------------------------------------------

    /** A word picked from the strip or the ring, [index] being its slot. */
    fun onPick(index: Int, word: String) {
        val editor = currentEditor() ?: return
        dismissRing()
        if (session.terminalField) {
            pickIntoTerminal(editor, word)
            return
        }
        // While a correction is pending, the typed chip reverts it.
        val pendingTyped = commitFlow.pendingTypedWord
        if (pendingTyped != null && index == host.stripTypedIndex() &&
            word == pendingTyped && composing.isEmpty()
        ) {
            revertCorrection(editor, viaBackspace = false)
            host.clearStrip()
            requestSuggestions()
            return
        }
        host.playEffect(EffectEvent.SuggestionPicked, word)
        confirmPendingCorrection()
        // Read before the commit.
        val contextWord = wordContext.previous1
        val grandContextWord = wordContext.previous2
        val typedTaps = TypedTaps.of(composing, composingWord.taps)
        editor.beginBatchEdit()
        // A pick replaces the word being typed or the word the caret sits in; with neither, it
        // is a prediction inserted at the caret. An adopted word is deleted first, when it is
        // still the text before the caret.
        val replacesWord = composing.isNotEmpty() || lastQuery.isNotEmpty()
        val replaced = if (composing.isNotEmpty()) composing.toString() else lastQuery
        if (composing.isEmpty() && lastQuery.isNotEmpty()) {
            val before = editor.textBeforeCursor(lastQuery.length)
            if (before != null && before.toString() == lastQuery) {
                editor.deleteSurroundingText(lastQuery.length, 0)
            }
        }
        composingWord.clear()
        composingWord.appendUntapped(word)
        val after = editor.textAfterCursor(CONTEXT_WINDOW_CHARS)
        var tail = 0
        if (after != null && replacesWord) {
            while (tail < after.length && isWordCharacter(after[tail].code)) {
                tail++
            }
        }
        val tailText = after?.substring(0, tail).orEmpty()
        // The word characters after the caret go with a replacing pick, unless the pick is the
        // text it replaces as it stands, or the caret splits two words and the pick does not end
        // with them.
        val unchanged = tailText.isNotEmpty() && word == replaced
        val keepsTail = unchanged ||
            (tailText.isNotEmpty() && splitsAtCaret(replaced, tailText) &&
                !WordFold.fold(word).endsWith(WordFold.fold(tailText)))
        if (tail > 0 && !keepsTail) {
            editor.deleteSurroundingText(0, tail)
        }
        // A space follows the pick unless one is already next, the setting is off, the field
        // holds an address, or the pick leaves the text as it stands.
        val nextChar = after?.getOrNull(if (keepsTail) 0 else tail)
        val space = if (unchanged || !preferences.spaceAfterSuggestion || session.addressField ||
            nextChar == ' '
        ) {
            ""
        } else {
            " "
        }
        ownEditPending = true
        editor.commitText(word + space, 1)
        editor.endBatchEdit()
        caretTail = if (keepsTail) tailText else ""
        caretSplit = null
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        spacingFlow.spaceAddedAfterPick(space.isNotEmpty())
        // A pick spends a one-shot shift.
        shiftFlow.spendOneShot()
        shiftFlow.clearHeld()

        // Each word of the pick is learned as asserted.
        val words = word.split(' ').filter { it.isNotEmpty() }
        var previous = contextWord
        var grandPrevious = grandContextWord
        for ((index, part) in words.withIndex()) {
            // The letters typed before the pick line up with the first word's.
            learningFlow.record(
                part, previous, grandPrevious, asserted = true,
                taps = if (index == 0) typedTaps else null, completion = true,
            )
            grandPrevious = previous
            previous = part
        }
        // The picked words become the context for the next word.
        wordContext = WordContext(
            previous1 = words.lastOrNull() ?: word,
            previous2 = if (words.size >= 2) words[words.size - 2] else wordContext.previous1,
        )
        composingWord.clear()
        host.clearStrip()
        checkpointField()
        applyAutoShift()
        requestSuggestions()
    }

    // ---- terminals -----------------------------------------------------------------------------

    /**
     * Types [code] into a terminal, written at once. A one-shot shift is spent by the letter it
     * capitalised, as in an ordinary field.
     */
    private fun typeIntoTerminal(editor: FieldEditor, code: Int) {
        val letter = terminalWriter.continuesWord(code)
        if (letter) {
            shiftFlow.spendOneShot()
        }
        shiftFlow.clearHeld()
        ownEditPending = true
        terminalWriter.type(editor, code, letter)
        requestTerminalSuggestions()
    }

    /** Backspace in a terminal. */
    private fun deleteInTerminal() {
        ownEditPending = true
        terminalWriter.deleteBack()
        requestTerminalSuggestions()
    }

    /** Replaces the letters typed into a terminal with [word], and a space when set to. */
    private fun pickIntoTerminal(editor: FieldEditor, word: String) {
        host.playEffect(EffectEvent.SuggestionPicked, word)
        val space = if (preferences.spaceAfterSuggestion) " " else ""
        ownEditPending = true
        terminalWriter.pick(editor, word, space)
        shiftFlow.spendOneShot()
        shiftFlow.clearHeld()
        host.clearStrip()
        requestTerminalSuggestions()
    }

    /** Enter in a terminal. */
    private fun enterInTerminal() {
        ownEditPending = true
        terminalWriter.enter()
        requestTerminalSuggestions()
    }

    /** The strip's completions of the terminal's word; a terminal has no words before it to read. */
    private fun requestTerminalSuggestions() {
        wordContext = WordContext.NONE
        suggestionFlow.requestForTerminal(terminalWriter.word)
    }

    // ---- suggestions ---------------------------------------------------------------------------

    /**
     * The engine's answer about [query]: kept for the delimiter, then cased and arranged on the
     * strip. An answer about an older query is dropped.
     */
    fun onSuggestions(
        candidates: List<Candidate>,
        knownWord: String,
        query: String,
        possessive: String?,
        inflection: Boolean,
    ) {
        if (!suggestionFlow.accept(query)) {
            return
        }
        if (composing.isNotEmpty()) {
            host.setEditorEmpty(false)
        }
        suggestionFlow.keep(candidates, knownWord, query, possessive, inflection)
        val cased = shiftFlow.caseForStrip(candidates, lastQuery)
        val slots = suggestionFlow.wordSlots() ?: return
        // Arranged for the slots that hold words, outlining what a delimiter would commit.
        suggestionFlow.show(
            cased, slots,
            correction = commitFlow.outline(
                lastQuery, composingWord.fromGesture, composingWord.runningText,
                suggestionFlow.answer,
            ),
            revertable = commitFlow.revertableWord(),
        )
    }

    /** Reads the heatmap's stored totals again, after an edit on the settings screen. */
    fun reloadTouches() = learningFlow.reloadTouches()

    /** Asks the engine about the composing word; never for a password field. */
    fun requestSuggestions() {
        val query = composing.toString()
        askCaretSplit(query)
        suggestionFlow.request(query, wordContext, composingWord.taps)
    }

    /** The run of word characters right after the caret. */
    private fun wordAfterCaret(editor: FieldEditor): String {
        val after = editor.textAfterCursor(CONTEXT_WINDOW_CHARS) ?: return ""
        var end = 0
        while (end < after.length && isWordCharacter(after[end].code)) {
            end++
        }
        return after.substring(0, end)
    }

    /**
     * Asks whether [before], the text before the caret, and [caretTail] are two words: the
     * dictionaries do not hold the run and do hold the tail.
     */
    private fun askCaretSplit(before: String) {
        if (before.isEmpty() || caretTail.isEmpty() || !session.policy.suggestionsAllowed) {
            return
        }
        val tail = caretTail
        engine.knownWords(listOf(before + tail, tail)) { known ->
            caretSplit = CaretSplit(before, tail, splits = !known[0] && known[1])
        }
    }

    /**
     * Tells the strip whether the field has anything in it; with no text before the caret, the
     * text after it is read.
     */
    private fun updateEditorEmpty(hasTextBeforeCaret: Boolean) {
        if (!host.viewAttached) {
            return
        }
        host.setEditorEmpty(
            if (hasTextBeforeCaret) {
                false
            } else {
                currentEditor()?.textAfterCursor(1).isNullOrEmpty()
            },
        )
    }

    // ---- the composing word --------------------------------------------------------------------

    /** Ends the composing region and returns the word that was committed, if any. */
    private fun finishComposing(editor: FieldEditor): String? {
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        if (composing.isEmpty()) {
            editor.finishComposingText()
            return null
        }
        val word = composing.toString()
        editor.finishComposingText()
        composingWord.clear()
        wordContext = wordContext.then(word)
        KeyboardStats.words++
        return word
    }

    /** Ends the composing region, before an edit of the caller's own; returns the word, if any. */
    fun finishWord(): String? {
        val editor = currentEditor() ?: return null
        return finishComposing(editor)
    }

    /** Drops the word, its answer and the pending correction, and asks about the caret afresh. */
    fun resetComposing() {
        dismissRing()
        engine.cancelPendingPreview()
        engine.cancelPendingGesture()
        swipeFlow.forgetPreview()
        host.onWordReset()
        commitFlow.dropPending()
        suggestionFlow.clearAnswer()
        composingWord.clear()
        terminalWriter.clearWord()
        composingWord.runningText = true
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        spacingFlow.dropAutoSpace()
        currentEditor()?.finishComposingText()
        refreshContextFromEditor()
        host.clearStrip()
        requestSuggestions()
    }

    /** True when the composing word is the text immediately before the application's caret. */
    private fun composingMatchesCaret(caret: Int): Boolean {
        if (composing.isEmpty()) {
            return false
        }
        val editor = currentEditor() ?: return false
        val before = editor.textBeforeCursor(composing.length) ?: return false
        return before.length == composing.length && before.contentEquals(composing)
    }

    /**
     * Makes the word the caret sits in the one the strip is about, and marks it as the composing
     * region, without changing the text, so typing continues it. A caret after a delimiter or in
     * an empty field marks nothing.
     */
    private fun adoptWordAtCaret() {
        if (adoptingWordAtCaret) {
            return
        }
        adoptingWordAtCaret = true
        try {
            adoptWordAtCaretNow()
        } finally {
            adoptingWordAtCaret = false
        }
    }

    private fun adoptWordAtCaretNow() {
        composingWord.clear()
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        composingWord.capitalisedByUser = false
        // The pending correction stays.
        val editor = currentEditor()
        editor?.finishComposingText()
        caretTail = editor?.let(::wordAfterCaret).orEmpty()
        caretSplit = null

        val before = editor?.textBeforeCursor(CONTEXT_WINDOW_CHARS)
        if (editor == null || before.isNullOrEmpty()) {
            wordContext = WordContext.NONE
            suggestionFlow.requestAdopted("", WordContext.NONE)
            return
        }
        // The run of [isWordCharacter] characters before the caret, starting at its first
        // letter, is the word; the words before it are its context.
        var start = before.length
        while (start > 0 && isWordCharacter(before[start - 1].code)) {
            start--
        }
        while (start < before.length && !Character.isLetter(before[start].code)) {
            start++
        }
        val partial = before.substring(start)
        val (context1, context2) = contextWordsBefore(before, start)
        wordContext = WordContext(context1, context2)
        if (partial.isNotEmpty()) {
            composingWord.appendUntapped(partial)
            val caret = selectionEnd
            editor.setComposingRegion(caret - partial.length, caret)
        }
        askCaretSplit(partial)
        suggestionFlow.requestAdopted(partial, wordContext)
    }

    /** Whether [askCaretSplit]'s last answer has [before] and [tail] as two words. */
    private fun splitsAtCaret(before: String, tail: String): Boolean =
        caretSplit?.let { it.before == before && it.tail == tail && it.splits } == true

    /** Reads the two words before the cursor back from the editor. */
    fun refreshContextFromEditor() {
        val before = currentEditor()?.textBeforeCursor(CONTEXT_WINDOW_CHARS)
        if (before.isNullOrEmpty()) {
            wordContext = WordContext.NONE
            return
        }
        val (context1, context2) = contextWordsBefore(before, before.length)
        wordContext = WordContext(context1, context2)
    }

    /** No words before the next one: after a line or a stamp written by the caller. */
    fun clearContext() {
        wordContext = WordContext.NONE
    }

    /**
     * The one or two words ending at [end] in [before]; a sentence mark or a line break between
     * them stops the reading, leaving null.
     */
    private fun contextWordsBefore(before: CharSequence, end: Int): Pair<String?, String?> {
        fun wordEndingAt(limit: Int): Pair<String, Int>? {
            var index = limit
            while (index > 0 && !isWordCharacter(before[index - 1].code)) {
                if (isSentenceEndingPunctuation(before[index - 1].code) || before[index - 1] == '\n') {
                    return null
                }
                index--
            }
            if (index == 0) {
                return null
            }
            val wordEnd = index
            while (index > 0 && isWordCharacter(before[index - 1].code)) {
                index--
            }
            return before.substring(index, wordEnd) to index
        }
        val first = wordEndingAt(end) ?: return null to null
        val second = wordEndingAt(first.second)
        return first.first to second?.first
    }

    /** Deletes back to the start of the word before the cursor, in one press. */
    fun deleteWordBeforeCursor() {
        val editor = currentEditor() ?: return
        deleteWordBeforeCursor(editor)
    }

    private fun deleteWordBeforeCursor(editor: FieldEditor) {
        if (selectionEnd > selectionStart) {
            editor.commitText("", 1)
            checkpointField()
            return
        }
        composingWord.clear()
        editor.finishComposingText()
        val before = editor.textBeforeCursor(CONTEXT_WINDOW_CHARS)
        if (before.isNullOrEmpty()) {
            return
        }
        var count = 0
        while (count < before.length && !isWordCharacter(before[before.length - 1 - count].code)) {
            count++
        }
        while (count < before.length && isWordCharacter(before[before.length - 1 - count].code)) {
            count++
        }
        editor.deleteSurroundingText(count.coerceAtLeast(1), 0)
        checkpointField()
    }

    /** Marks that end a sentence. */
    private fun isSentenceEndingPunctuation(code: Int): Boolean =
        code == '.'.code || code == '!'.code || code == '?'.code

    /**
     * Sets shift from what the field asks for and the text before the caret, unless the user set
     * it. [justCommitted] is text just written, appended to what the editor reports.
     */
    private fun applyAutoShift(justCommitted: String = "") =
        shiftFlow.applyAuto(composing.isEmpty(), justCommitted)

    /** Whether [before] and [tail], the text on either side of the caret, are two words. */
    private data class CaretSplit(val before: String, val tail: String, val splits: Boolean)

    companion object {
        const val CONTEXT_WINDOW_CHARS = 64

        /** How much of the field [checkpointField] and the field's history read. */
        const val FIELD_HISTORY_CHARS = 20_000

        /** The characters a swiped word follows without a space; see [spaceBeforeSwipedWord]. */
        const val SWIPE_NO_SPACE_AFTER = "([{\"'/-_@#\n"

        /** A letter, an apostrophe or a hyphen: what continues a word once a letter began it. */
        fun isWordCharacter(code: Int): Boolean =
            Character.isLetter(code) || code == '\''.code || code == '-'.code
    }
}
