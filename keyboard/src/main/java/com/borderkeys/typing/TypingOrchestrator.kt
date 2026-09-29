// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.AutoCorrection
import com.borderkeys.ime.AutoShift
import com.borderkeys.ime.CaretNudge
import com.borderkeys.ime.Composer
import com.borderkeys.ime.FieldRestore
import com.borderkeys.ime.HabitSpace
import com.borderkeys.ime.KeyCodes
import com.borderkeys.ime.LanguageSwitchCorrector
import com.borderkeys.ime.PhysicalKeys
import com.borderkeys.ime.PunctuationSpace
import com.borderkeys.ime.RunningText
import com.borderkeys.ime.ShiftState
import com.borderkeys.ime.SuggestionRow
import com.borderkeys.ime.WordCommit
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.RefusedWords

/**
 * Owns each word from its first key to what is learned from it: the keys, the composing word,
 * the engine's answer, the commit decision, the pending correction, spacing, shift and the
 * field's history. The field, the engine, the views, the database and the clocks are reached
 * through [FieldEditor], [EnginePort], [TypingHost], [LearningStore] and [TypingClock].
 */
class TypingOrchestrator(
    /** The field being typed into, or null when none is bound. */
    private val currentEditor: () -> FieldEditor?,
    private val engine: EnginePort,
    private val host: TypingHost,
    private val store: LearningStore,
    private val clock: TypingClock,
) {
    var preferences = KeyboardPreferences()
        private set

    /** The field being typed into: what it is and what it allows. */
    var session = FieldSession.NONE
        private set

    /** The tags of the packs the engine consults, heaviest first. */
    @Volatile
    var languageTags: List<String> = emptyList()

    /** Apostrophe spellings for the languages switched on; see [com.borderkeys.ime.Contractions]. */
    var contractions: Map<String, String> = emptyMap()

    /** The word being written. */
    private val composingWord = ComposingWord()

    /** [composingWord]'s text. */
    private val composing: StringBuilder get() = composingWord.text

    /** The word being written. */
    val composingText: String get() = composing.toString()

    /** The editor's selection, as of the last report, [selectionStart] never after [selectionEnd]. */
    var selectionStart = 0
        private set
    var selectionEnd = 0
        private set

    /** What the last slide along the space bar selected, so one drag keeps one anchor. */
    private var lastNudge: CaretNudge.Selection? = null

    /** The letters typed into a terminal since the last delimiter. */
    private val terminalWord = StringBuilder()

    /** The two words before the word being written. */
    var wordContext = WordContext.NONE
        private set

    /** The language the conversation is considered written in, or null. */
    private var dominantLanguageTag: String? = null

    private var shiftState = ShiftState.OFF

    /** Set when the user pressed shift, cleared by the character it applied to. */
    private var shiftHeldByUser = false

    /** When the last space was committed, for the two-spaces-make-a-full-stop window. */
    private var lastSpaceAt = 0L

    /** Set for one keystroke after two spaces became a full stop; backspace then undoes it. */
    private var pendingSpacePeriod = false

    /** Set when a space was added after a sentence mark; the next typed space is swallowed. */
    private var pendingAutoSpace = false

    /** Set when the user released a caps lock that auto-shift applied, until the next letter. */
    private var userReleasedAutoLock = false

    /**
     * Set right before a commit of this class's own and spent by the next selection report,
     * which is then not treated as a caret move. Cleared by any key press.
     */
    private var ownEditPending = false

    /** Whether [adoptWordAtCaret] is running; selection reports meanwhile are its own edits. */
    private var adoptingWordAtCaret = false

    /** Whether the current lock came from the field asking for capitals rather than from shift. */
    private var autoLockedShift = false
    private var lastShiftPressAt = 0L

    private val flushLearningRunnable = Runnable { flushLearning() }

    /** Uptime of the keystroke the engine was last asked about, for the strip latency figure. */
    private var suggestionsRequestedAt = 0L

    /** The engine's last answer, kept so the delimiter path can apply it. */
    private var searchAnswer = SearchAnswer.NONE

    /**
     * A correction that has been applied and can still be taken back, for one keystroke:
     * backspace reverts it, any other key confirms it, and a cursor move drops it.
     */
    private data class PendingCorrection(
        val typed: String,
        val corrected: String,
        val delimiter: String,
        /** The word before it. */
        val contextWord: String?,
        /** The word before [contextWord]. */
        val grandContextWord: String?,
        /** [ComposingWord.capitalisedByUser] when [typed] was finished. */
        val deliberateCapital: Boolean,
        /** Whether confirming it learns [corrected]; false for a text shortcut's expansion. */
        val learn: Boolean = true,
    )

    private var pendingCorrection: PendingCorrection? = null

    /** The field's undo and redo history for this input session. */
    private val fieldHistory = Composer()

    /** Finds the words that look wrong once the conversation's language has changed. */
    private val languageSwitchCorrector = LanguageSwitchCorrector()

    /** What is learned, until it is written to the [store]. */
    private val learning = LearningBuffer()

    /** Where the typed word and the word a delimiter would apply end up on the strip. */
    private val suggestionRow = SuggestionRow()

    /** The word the engine was last asked about. */
    var lastQuery: String = ""
        private set

    // ---- the field ---------------------------------------------------------------------------

    /**
     * Starts [field]: the learning gate is set for it, and the word, shift, the modifiers and the
     * field's history start over.
     */
    fun startField(field: FieldSession) {
        session = field
        terminalWord.setLength(0)
        applyLearningGate()
        if (session.policy.privateField) {
            learning.discard()
        }
        // Each field starts with shift and caps lock off.
        shiftHeldByUser = false
        userReleasedAutoLock = false
        autoLockedShift = false
        shiftState = ShiftState.OFF
        host.showShiftState(shiftState)
        host.releaseModifiers()
        ownEditPending = false
        resetComposing()
        resetFieldHistory()
        applyAutoShift()
        updateEditorEmpty(currentEditor()?.textBeforeCursor(1)?.isNotEmpty() == true)
    }

    /** The field closed: what was learned is written, and the pending requests and the word go. */
    fun finishField() {
        flushLearning()
        engine.cancelPending()
        resetComposing()
    }

    /** Takes [settings]; with the views up, the learning gate and shift follow them at once. */
    fun applySettings(settings: KeyboardPreferences) {
        preferences = settings
        if (host.viewAttached) {
            applyLearningGate(settings)
            applyAutoShift()
        }
    }

    /** The last flush: writes what is left and waits, a bounded time, for the writes in flight. */
    fun shutdown() {
        store.persistBeforeShutdown(drainLearning())
    }

    /** Sets the words never learned. */
    fun setRefusedWords(words: RefusedWords) {
        learning.setRefusedWords(words)
    }

    /** Learns nothing until the learning gate is next set, at a field start or a settings change. */
    fun stopLearning() {
        learning.enabled = false
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
                host.dismissRing()
            } else {
                host.dismissRing()
                adoptWordAtCaret()
            }
            applyAutoShift()
        } else {
            host.dismissRing()
        }
    }

    // ---- keys --------------------------------------------------------------------------------

    /**
     * A key press. Every key but backspace confirms a pending correction and closes the ring
     * first. Returns false for a key left to the caller: pages, panels, the globe and the
     * modifier keys.
     */
    fun onKey(code: Int): Boolean {
        if (code != KeyCodes.DELETE) {
            confirmPendingCorrection()
            host.dismissRing()
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
            else -> if (KeyCodes.isCharacter(code)) handleCharacter(code) else return false
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
        host.dismissRing()
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
        host.dismissRing()
        val editor = currentEditor() ?: return
        val extracted = editor.extractedText(0) ?: return
        val length = extracted.text?.length ?: return
        val next = CaretNudge.slide(
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            steps = steps,
            length = length,
            selecting = shiftState != ShiftState.OFF,
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
        host.dismissRing()
        val editor = currentEditor() ?: return
        val text = editor.extractedText(0)?.text ?: return
        val next = CaretNudge.slideLines(
            text = text,
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            lines = lines,
            selecting = shiftState != ShiftState.OFF,
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
        val meta = heldShiftMeta(spend = false)
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
        val meta = heldShiftMeta(spend = true)
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

    /**
     * The shift bits for a hardware key: set only while the user holds shift. With [spend], a
     * one-shot shift is spent by the key.
     */
    private fun heldShiftMeta(spend: Boolean): Int {
        if (!shiftHeldByUser || shiftState == ShiftState.OFF) {
            return 0
        }
        if (spend && shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        return SHIFT_META
    }

    private fun handleCharacter(code: Int) {
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
        val shifted = if (shiftState != ShiftState.OFF) {
            Character.toUpperCase(code)
        } else {
            code
        }
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
        if (letter && shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        val heldByUser = shiftHeldByUser
        if (composing.isEmpty() && letter) {
            composingWord.capitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            val ahead = editor.textBeforeCursor(1)
            composingWord.runningText = ahead.isNullOrEmpty() || !RunningText.isMark(ahead[0])
        }
        if (letter) {
            shiftHeldByUser = false
            userReleasedAutoLock = false
        }

        if (letter) {
            pendingAutoSpace = false
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
                    recordLearned(finished, contextWord, grandContextWord, composingWord.capitalisedByUser)
                }
                checkpointField()
                composingWord.capitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            }
            composing.appendCodePoint(shifted)
            editor.setComposingText(composing, 1)
            requestSuggestions()
            return
        }

        // A delimiter ends the word. What replaces the typed word, if anything, is decided by
        // [commitOutcome]; a rewrite is committed in its place and can be reverted.
        val typed = composing.toString()
        val outcome = commitOutcome(typed, shifted)
        val rewrite = outcome.isRewrite
        val correction = outcome.text
        // Read before anything commits.
        val contextWord = wordContext.previous1
        val grandContextWord = wordContext.previous2

        // A space typed right after one this keyboard added is handled per
        // KeyboardPreferences.autoSpaceHabit; see [HabitSpace].
        if (shifted == ' '.code &&
            HabitSpace.swallows(
                composingEmpty = typed.isEmpty(),
                pendingAutoSpace = pendingAutoSpace,
                habit = preferences.autoSpaceHabit,
                characterBeforeCursor = {
                    editor.textBeforeCursor(1)?.takeIf { it.isNotEmpty() }?.get(0)
                },
            )
        ) {
            if (!HabitSpace.staysArmed(preferences.autoSpaceHabit)) {
                pendingAutoSpace = false
            }
            shiftAfterDelimiter(heldByUser)
            return
        }

        // Two spaces within DOUBLE_SPACE_MILLIS after a word character become ". ".
        if (shifted == ' '.code && typed.isEmpty() && preferences.doubleSpacePeriod && !session.addressField &&
            clock.currentTimeMillis() - lastSpaceAt < DOUBLE_SPACE_MILLIS &&
            endsWithWordCharacterBeforeSpace(editor)
        ) {
            ownEditPending = true
            editor.beginBatchEdit()
            editor.deleteSurroundingText(1, 0)
            editor.commitText(". ", 1)
            editor.endBatchEdit()
            lastSpaceAt = 0L
            pendingSpacePeriod = true
            pendingAutoSpace = true
            pendingCorrection = null
            checkpointField()
            refreshContextFromEditor()
            applyAutoShift(justCommitted = ". ")
            requestSuggestions()
            return
        }
        if (shifted == ' '.code) {
            lastSpaceAt = clock.currentTimeMillis()
        }
        pendingSpacePeriod = false

        ownEditPending = true
        editor.beginBatchEdit()
        // A space before a tight mark is removed, except the one French writes before ! ? ; :.
        if (typed.isEmpty() && preferences.removeSpaceBeforePunctuation &&
            isTightPunctuation(shifted) && !isFrenchSpacedPunctuation(shifted)
        ) {
            val before = editor.textBeforeCursor(1)
            if (before != null && before.length == 1 && before[0] == ' ') {
                editor.deleteSurroundingText(1, 0)
            }
        }
        val added = spaceAfter(shifted)
        pendingAutoSpace = added.isNotEmpty()
        val delimiter = String(Character.toChars(shifted)) + added
        if (correction != null) {
            // commitText replaces the composing region with the correction.
            composing.setLength(0)
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
            pendingCorrection = PendingCorrection(
                typed, correction, delimiter, contextWord, grandContextWord,
                composingWord.capitalisedByUser, learn = !rewrite,
            )
            if (!rewrite && preferences.languageSwitchCorrectionMode != KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
                recordLanguageSwitchFlag(editor, typed, correction, delimiter)
            }
        } else {
            if (typed.isNotEmpty()) {
                recordLearned(typed, contextWord, grandContextWord, composingWord.capitalisedByUser)
            }
            pendingCorrection = null
        }
        // A sentence mark clears the context for the next word, after this word was learned.
        if (isSentenceEndingPunctuation(shifted)) {
            wordContext = WordContext.NONE
        }
        checkpointField()
        shiftAfterDelimiter(heldByUser, justCommitted = delimiter)
        requestSuggestions()
        engine.dominantLanguageTag { tag -> dominantLanguageTag = tag }
        if (preferences.languageSwitchCorrectionMode != KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
            checkLanguageSwitch()
        }
    }

    private fun handleDelete() {
        host.dismissRing()
        val editor = currentEditor() ?: return
        if (session.terminalField) {
            deleteInTerminal(editor)
            return
        }
        val hasSelection = selectionEnd > selectionStart
        // A selection is deleted whole, by committing empty text over it.
        if (hasSelection) {
            composing.setLength(0)
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
        if (pendingSpacePeriod) {
            // Turns ". " back into the two spaces.
            pendingSpacePeriod = false
            val before = editor.textBeforeCursor(2)
            if (before != null && before.toString() == ". ") {
                editor.beginBatchEdit()
                editor.deleteSurroundingText(2, 0)
                editor.commitText("  ", 1)
                editor.endBatchEdit()
                refreshContextFromEditor()
                applyAutoShift()
                requestSuggestions()
                return
            }
        }
        if (revertCorrection(editor)) {
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            // After a backspace, typed letters extend a swiped word.
            composingWord.fromGesture = false
            // Deletes one code point.
            val length = composing.length
            val start = composing.offsetByCodePoints(length, -1)
            composing.setLength(start)
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
            recordLearned(finished, contextWord, grandContextWord, composingWord.capitalisedByUser)
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
        pendingAutoSpace = false
        pendingSpacePeriod = false
        lastSpaceAt = 0L
        refreshContextFromEditor()
        applyAutoShift()
    }

    private fun handleShift() {
        val now = clock.currentTimeMillis()
        // Two taps within DOUBLE_TAP_MILLIS lock, from any state.
        val doubleTap = now - lastShiftPressAt < DOUBLE_TAP_MILLIS
        val releasedAutoLock = shiftState == ShiftState.LOCKED && autoLockedShift
        shiftState = when {
            shiftState == ShiftState.LOCKED -> ShiftState.OFF
            doubleTap -> ShiftState.LOCKED
            shiftState == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftPressAt = now
        shiftHeldByUser = shiftState != ShiftState.OFF
        autoLockedShift = false
        userReleasedAutoLock = releasedAutoLock
        host.showShiftState(shiftState)
        // The strip's words are re-cased for the new shift state.
        requestSuggestions()
    }

    /** Locks shift, from holding it. */
    private fun lockShift() {
        shiftState = ShiftState.LOCKED
        shiftHeldByUser = true
        autoLockedShift = false
        userReleasedAutoLock = false
        host.showShiftState(shiftState)
        requestSuggestions()
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
        // The caret report this edit causes is not a caret move.
        ownEditPending = true
        editor.beginBatchEdit()
        val finished = finishComposing(editor)
        editor.endBatchEdit()
        if (finished != null) {
            recordLearned(finished, contextWord, grandContextWord, composingWord.capitalisedByUser)
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
        var cased = candidates.map { candidate ->
            if (candidate.isProperNoun) {
                candidate.copy(text = candidate.text.replaceFirstChar { it.uppercaseChar() })
            } else {
                candidate
            }
        }
        val state = shiftState
        if (state != ShiftState.OFF) {
            cased = cased.map { candidate ->
                candidate.copy(
                    text = if (state == ShiftState.LOCKED) {
                        candidate.text.uppercase()
                    } else {
                        candidate.text.replaceFirstChar { it.uppercaseChar() }
                    },
                )
            }
        }
        composingWord.capitalisedByUser = shiftHeldByUser && state != ShiftState.OFF
        if (state == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        shiftHeldByUser = false
        return cased.distinctBy { it.text }
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
        composing.setLength(0)
        composing.append(best)
        editor.setComposingText(composing, 1)
        editor.endBatchEdit()
        composingWord.fromGesture = true
        lastQuery = best
        searchAnswer = searchAnswer.copy(
            query = best,
            knownWord = best,
            correction = best,
            correctionIsName = cased.first().isProperNoun,
        )
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
        composing.setLength(0)
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
     * before it, and offers the rest of [cased] on the strip. The word stays [terminalWord], so a
     * pick from the strip replaces it the way it replaces typed letters.
     */
    fun swipeIntoTerminal(cased: List<Candidate>) {
        val editor = currentEditor() ?: return
        val best = cased.first().text
        ownEditPending = true
        editor.beginBatchEdit()
        writeToTerminal(editor, if (terminalWord.isNotEmpty()) " $best" else best)
        editor.endBatchEdit()
        terminalWord.setLength(0)
        terminalWord.append(best)
        lastQuery = best
        searchAnswer = searchAnswer.copy(
            query = best,
            knownWord = best,
            correction = best,
            correctionIsName = cased.first().isProperNoun,
        )
        host.showSuggestions(cased, -1, -1)
        host.playEffect(EffectEvent.SwipeAccepted, best)
    }

    // ---- the language switch ------------------------------------------------------------------

    /** Records where [correction] landed, read from the cursor, for [checkLanguageSwitch]. */
    private fun recordLanguageSwitchFlag(
        editor: FieldEditor,
        typed: String,
        correction: String,
        delimiter: String,
    ) {
        val cursor = editor.extractedText(FIELD_HISTORY_CHARS)?.selectionEnd ?: return
        val end = cursor - delimiter.length
        val start = end - correction.length
        if (start < 0) {
            return
        }
        languageSwitchCorrector.recordCorrection(
            LanguageSwitchCorrector.Flag(typed, correction, start, end),
        )
    }

    /**
     * Asks whether the conversation's language changed and which recent corrections that leaves
     * wrong. An answer about an older field is dropped.
     */
    private fun checkLanguageSwitch() {
        val generation = session.generation
        engine.dominantPack { dominantPack ->
            if (generation != session.generation || !languageSwitchCorrector.observeDominantPack(dominantPack)) {
                return@dominantPack
            }
            val editor = currentEditor() ?: return@dominantPack
            val verified = languageSwitchCorrector.snapshot().filter { flag ->
                textAt(editor, flag.startOffset, flag.endOffset) == flag.appliedText
            }
            if (verified.isEmpty()) {
                return@dominantPack
            }
            engine.candidatesForPack(dominantPack, verified.map { it.typedText }) { suggestions ->
                if (generation != session.generation) {
                    return@candidatesForPack
                }
                val replacements = languageSwitchCorrector.resolve(verified, suggestions)
                if (replacements.isNotEmpty()) {
                    onLanguageSwitchReplacements(replacements)
                }
            }
        }
    }

    /** The field's text between two offsets, or null if either is out of range. */
    private fun textAt(editor: FieldEditor, start: Int, endExclusive: Int): String? {
        if (start < 0 || endExclusive < start) {
            return null
        }
        val text = editor.extractedText(FIELD_HISTORY_CHARS)?.text ?: return null
        if (endExclusive > text.length) {
            return null
        }
        return text.subSequence(start, endExclusive).toString()
    }

    /** The selection in the offsets [textAt] uses, or null when the editor does not report it. */
    private fun selectionOf(editor: FieldEditor): Pair<Int, Int>? {
        val extracted = editor.extractedText(FIELD_HISTORY_CHARS) ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        return if (start < 0 || end < 0) null else Pair(start, end)
    }

    /** `Ask` shows the revert panel; `Auto-apply` edits the field itself, right away. */
    private fun onLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        if (preferences.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY) {
            applyLanguageSwitchReplacements(replacements)
        } else {
            host.offerLanguageReplacements(replacements)
        }
    }

    /**
     * Applies the replacements in their order, each only if its text is still in place, with one
     * [checkpointField] for the batch, then puts the caret back where the user is writing.
     */
    fun applyLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        val editor = currentEditor() ?: return
        val applied = ArrayList<LanguageSwitchCorrector.Replacement>(replacements.size)
        editor.beginBatchEdit()
        finishComposing(editor)
        val caret = selectionOf(editor)
        for (replacement in replacements) {
            if (textAt(editor, replacement.startOffset, replacement.endOffset) !=
                replacement.previousText
            ) {
                continue
            }
            editor.setComposingRegion(replacement.startOffset, replacement.endOffset)
            editor.setComposingText(replacement.text, 1)
            editor.finishComposingText()
            applied += replacement
        }
        // The caret goes back, shifted by the length the text before it changed.
        if (applied.isNotEmpty() && caret != null) {
            val (start, end) = caret
            editor.setSelection(
                languageSwitchCorrector.caretAfter(start, applied),
                languageSwitchCorrector.caretAfter(end, applied),
            )
        }
        editor.endBatchEdit()
        val changed = applied.isNotEmpty()
        if (changed) {
            checkpointField()
            refreshContextFromEditor()
            requestSuggestions()
        }
    }

    // ---- the commit decision -------------------------------------------------------------------

    /** Confirms the pending correction and learns it. */
    private fun confirmPendingCorrection() {
        val pending = pendingCorrection ?: return
        pendingCorrection = null
        if (!pending.learn) {
            return
        }
        recordLearned(
            pending.corrected, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital,
        )
    }

    /** Drops the pending correction without learning it, after an edit that rewrote the field. */
    fun dropPendingCorrection() {
        pendingCorrection = null
    }

    /** What the key [endedBy] would write in place of [typed]; see [WordCommit]. Reads no editor. */
    internal fun commitOutcome(typed: String, endedBy: Int): WordCommit.Outcome = WordCommit.decide(
        typed = typed,
        endedBy = endedBy,
        fromGesture = composingWord.fromGesture,
        runningText = composingWord.runningText,
        shortcuts = preferences.textShortcuts,
        contractions = contractions,
        possessive = searchAnswer.possessive,
        suggestion = searchAnswer.correction,
        suggestionQuery = searchAnswer.query,
        knownWord = searchAnswer.knownWord,
        isProperNoun = searchAnswer.correctionIsName,
        inflection = searchAnswer.inflection,
        settings = WordCommit.Settings(
            autoCorrectOnSpace = preferences.autoCorrectOnSpace,
            autoCapitalise = preferences.autoCapitalise,
            minimumLength = preferences.minCorrectionLength,
            correctionDistance = preferences.correctionDistance,
            capitaliseNames = preferences.capitaliseNames,
        ),
    )

    /** The word the strip outlines: what a space would write, unless it is a text shortcut. */
    private fun outlinedCommit(typed: String): String? {
        val outcome = commitOutcome(typed, ' '.code)
        return if (outcome.kind == WordCommit.Kind.SHORTCUT) null else outcome.text
    }

    /** Whether [pending]'s correction and delimiter are still the text before the caret. */
    private fun correctionBeforeCaret(pending: PendingCorrection): Boolean {
        val committed = pending.corrected + pending.delimiter
        val before = currentEditor()?.textBeforeCursor(committed.length) ?: return false
        return before.toString() == committed
    }

    /**
     * Replaces a pending correction and its delimiter with what was typed, in one batch edit.
     * Returns false when there is nothing to revert. [viaBackspace] is whether the backspace key
     * asked, which [KeyboardPreferences.revertCorrectionOnBackspace] governs.
     */
    private fun revertCorrection(editor: FieldEditor, viaBackspace: Boolean = true): Boolean {
        val pending = pendingCorrection ?: return false
        pendingCorrection = null
        if (viaBackspace && !preferences.revertCorrectionOnBackspace) {
            // An ordinary backspace confirms the correction.
            if (pending.learn) {
                recordLearned(
                    pending.corrected, pending.contextWord, pending.grandContextWord,
                    pending.deliberateCapital,
                )
            }
            return false
        }
        val committed = pending.corrected + pending.delimiter
        val before = editor.textBeforeCursor(committed.length)
        if (before == null || before.toString() != committed) {
            // The text before the caret changed: the correction stands and is learned.
            recordLearned(
                pending.corrected, pending.contextWord, pending.grandContextWord,
                pending.deliberateCapital,
            )
            return false
        }
        editor.beginBatchEdit()
        editor.deleteSurroundingText(committed.length, 0)
        editor.commitText(pending.typed + pending.delimiter, 1)
        editor.endBatchEdit()
        wordContext = wordContext.copy(previous1 = pending.typed)
        // The typed word is learned as asserted; the rejected correction is forgotten from the
        // personal dictionary, never blocked.
        recordLearned(
            pending.typed, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital, asserted = true,
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
        languageSwitchCorrector.reset()
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
        pendingCorrection = null
        refreshContextFromEditor()
        requestSuggestions()
    }

    // ---- picks ---------------------------------------------------------------------------------

    /** A word picked from the strip or the ring, [index] being its slot. */
    fun onPick(index: Int, word: String) {
        val editor = currentEditor() ?: return
        host.dismissRing()
        if (session.terminalField) {
            pickIntoTerminal(editor, word)
            return
        }
        // While a correction is pending, the typed chip reverts it.
        val pending = pendingCorrection
        if (pending != null && index == host.stripTypedIndex() &&
            word == pending.typed && composing.isEmpty()
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
        editor.beginBatchEdit()
        // A pick replaces the word being typed or the word the caret sits in; with neither, it
        // is a prediction inserted at the caret. An adopted word is deleted first, when it is
        // still the text before the caret.
        val replacesWord = composing.isNotEmpty() || lastQuery.isNotEmpty()
        if (composing.isEmpty() && lastQuery.isNotEmpty()) {
            val before = editor.textBeforeCursor(lastQuery.length)
            if (before != null && before.toString() == lastQuery) {
                editor.deleteSurroundingText(lastQuery.length, 0)
            }
        }
        composing.setLength(0)
        composing.append(word)
        // A replacing pick also deletes the rest of the word after the caret.
        val after = editor.textAfterCursor(CONTEXT_WINDOW_CHARS)
        var tail = 0
        if (after != null && replacesWord) {
            while (tail < after.length && isWordCharacter(after[tail].code)) {
                tail++
            }
        }
        if (tail > 0) {
            editor.deleteSurroundingText(0, tail)
        }
        // A space follows the pick unless one is already next, the setting is off, or the field
        // holds an address.
        val nextChar = after?.getOrNull(tail)
        val space = if (!preferences.spaceAfterSuggestion || session.addressField || nextChar == ' ') "" else " "
        ownEditPending = true
        editor.commitText(word + space, 1)
        editor.endBatchEdit()
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        pendingAutoSpace = space.isNotEmpty()
        // A pick spends a one-shot shift.
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        shiftHeldByUser = false

        // Each word of the pick is learned as asserted.
        val words = word.split(' ').filter { it.isNotEmpty() }
        var previous = contextWord
        var grandPrevious = grandContextWord
        for (part in words) {
            recordLearned(part, previous, grandPrevious, asserted = true)
            grandPrevious = previous
            previous = part
        }
        // The picked words become the context for the next word.
        wordContext = WordContext(
            previous1 = words.lastOrNull() ?: word,
            previous2 = if (words.size >= 2) words[words.size - 2] else wordContext.previous1,
        )
        composing.setLength(0)
        host.clearStrip()
        checkpointField()
        applyAutoShift()
        requestSuggestions()
    }

    // ---- terminals -----------------------------------------------------------------------------

    /**
     * Types [code] into a terminal: written at once, never composed. A letter extends
     * [terminalWord], which the strip completes; anything else ends it. A one-shot shift is
     * spent by the letter it capitalised, as in an ordinary field.
     */
    private fun typeIntoTerminal(editor: FieldEditor, code: Int) {
        val letter = if (terminalWord.isEmpty()) Character.isLetter(code) else isWordCharacter(code)
        if (letter && shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        shiftHeldByUser = false
        ownEditPending = true
        writeToTerminal(editor, String(Character.toChars(code)))
        if (letter) {
            terminalWord.appendCodePoint(code)
        } else {
            terminalWord.setLength(0)
        }
        requestTerminalSuggestions()
    }

    /**
     * Writes [text] to a terminal: each character as the key that carries it, shift held for a
     * capital, and as text only where no plain key carries it.
     */
    private fun writeToTerminal(editor: FieldEditor, text: String) {
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            val keyCode = if (code < 128) PhysicalKeys.keyCodeFor(code) else 0
            if (keyCode == 0) {
                editor.commitText(String(Character.toChars(code)), 1)
                continue
            }
            val meta = if (Character.isUpperCase(code)) SHIFT_META else 0
            host.sendPhysicalKey(keyCode, meta)
        }
    }

    /** [count] characters back, as the key events a terminal deletes by. */
    private fun deleteInTerminal(editor: FieldEditor, count: Int = 1) {
        ownEditPending = true
        repeat(count) {
            host.sendPhysicalKey(KeyEvent.KEYCODE_DEL, 0)
        }
        if (terminalWord.isNotEmpty()) {
            terminalWord.setLength(terminalWord.offsetByCodePoints(terminalWord.length, -1))
        }
        requestTerminalSuggestions()
    }

    /**
     * Replaces the letters typed into a terminal with [word], and a space when set to. A word
     * that continues the letters has only its remainder written; any other deletes them first.
     */
    private fun pickIntoTerminal(editor: FieldEditor, word: String) {
        host.playEffect(EffectEvent.SuggestionPicked, word)
        val typed = terminalWord.toString()
        val space = if (preferences.spaceAfterSuggestion) " " else ""
        ownEditPending = true
        editor.beginBatchEdit()
        if (word.length >= typed.length && word.startsWith(typed)) {
            writeToTerminal(editor, word.substring(typed.length) + space)
        } else {
            repeat(typed.codePointCount(0, typed.length)) {
                host.sendPhysicalKey(KeyEvent.KEYCODE_DEL, 0)
            }
            writeToTerminal(editor, word + space)
        }
        editor.endBatchEdit()
        terminalWord.setLength(0)
        if (space.isEmpty()) {
            terminalWord.append(word)
        }
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host.showShiftState(shiftState)
        }
        shiftHeldByUser = false
        host.clearStrip()
        requestTerminalSuggestions()
    }

    /** Enter in a terminal: the key itself, which is what runs the line. */
    private fun enterInTerminal() {
        terminalWord.setLength(0)
        ownEditPending = true
        host.sendPhysicalKey(KeyEvent.KEYCODE_ENTER, 0)
        requestTerminalSuggestions()
    }

    /** The strip's completions of [terminalWord]; a terminal has no words before it to read. */
    private fun requestTerminalSuggestions() {
        lastQuery = terminalWord.toString()
        wordContext = WordContext.NONE
        if (!session.policy.suggestionsAllowed) {
            return
        }
        suggestionsRequestedAt = clock.uptimeMillis()
        engine.requestSuggestions(lastQuery, null, null)
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
        if (query != lastQuery) {
            return
        }
        if (suggestionsRequestedAt != 0L) {
            KeyboardStats.suggestionMillis.add(
                (clock.uptimeMillis() - suggestionsRequestedAt).toDouble(),
            )
            suggestionsRequestedAt = 0L
        }
        if (composing.isNotEmpty()) {
            host.setEditorEmpty(false)
        }
        // The word the corrections heap settled on, in the engine's own case.
        val marked = candidates.firstOrNull { it.isCorrection }
        searchAnswer = SearchAnswer(
            query = query,
            knownWord = knownWord,
            correction = marked?.text,
            correctionIsName = marked?.isProperNoun == true,
            possessive = possessive,
            inflection = inflection,
        )
        // Every candidate is cased: after the typed prefix mid-word, by the shift state with
        // nothing typed. Caps lock wins over a name's capital; otherwise a name is capitalised
        // and any other word starts lower case.
        val cased = candidates.map { candidate ->
            val word = candidate.text
            candidate.copy(
                text = if (lastQuery.isNotEmpty()) {
                    AutoCorrection.matchCase(
                        lastQuery, word, candidate.isProperNoun && preferences.capitaliseNames,
                    )
                } else {
                    when {
                        shiftState == ShiftState.LOCKED -> word.uppercase()
                        candidate.isProperNoun && preferences.capitaliseNames ->
                            word.replaceFirstChar { it.uppercaseChar() }
                        shiftState == ShiftState.ON -> word.replaceFirstChar { it.uppercaseChar() }
                        else -> word.replaceFirstChar { it.lowercaseChar() }
                    }
                },
            )
        }
        if (!preferences.showSuggestionStrip) {
            return
        }
        val slots = host.stripWordSlots() ?: return
        // Arranged for the slots that hold words, outlining what a delimiter would commit.
        val row = suggestionRow.arrange(
            cased, lastQuery, preferences.suggestionCount.coerceAtMost(slots),
            correction = outlinedCommit(lastQuery),
            revertable = pendingCorrection?.takeIf { correctionBeforeCaret(it) }?.typed,
        )
        host.showSuggestions(row, suggestionRow.typedIndex, suggestionRow.appliedIndex)
    }

    /** Asks the engine about the composing word; never for a password field. */
    fun requestSuggestions() {
        lastQuery = composing.toString()
        if (session.policy.privateField) {
            host.refreshPrivateReveal()
        }
        if (!session.policy.suggestionsAllowed) {
            return
        }
        suggestionsRequestedAt = clock.uptimeMillis()
        engine.requestSuggestions(lastQuery, wordContext.previous1, wordContext.previous2)
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
        composing.setLength(0)
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
        host.onWordReset()
        pendingCorrection = null
        searchAnswer = searchAnswer.copy(
            query = "",
            knownWord = "",
            correction = null,
            correctionIsName = false,
            inflection = false,
        )
        composing.setLength(0)
        terminalWord.setLength(0)
        composingWord.runningText = true
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        pendingAutoSpace = false
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
        composing.setLength(0)
        composingWord.fromGesture = false
        composingWord.autoSpaceBefore = false
        composingWord.capitalisedByUser = false
        // The pending correction stays.
        val editor = currentEditor()
        editor?.finishComposingText()

        val before = editor?.textBeforeCursor(CONTEXT_WINDOW_CHARS)
        if (editor == null || before.isNullOrEmpty()) {
            wordContext = WordContext.NONE
            lastQuery = ""
            engine.requestSuggestions("", null, null)
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
        lastQuery = partial
        if (partial.isNotEmpty()) {
            composing.append(partial)
            val caret = selectionEnd
            editor.setComposingRegion(caret - partial.length, caret)
        }
        engine.requestSuggestions(partial, wordContext.previous1, wordContext.previous2)
    }

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
        composing.setLength(0)
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

    // ---- spacing and shift ---------------------------------------------------------------------

    /** True when what precedes the single trailing space is a word character or a digit. */
    private fun endsWithWordCharacterBeforeSpace(editor: FieldEditor): Boolean {
        val before = editor.textBeforeCursor(2) ?: return false
        return before.length == 2 && before[1] == ' ' &&
            (isWordCharacter(before[0].code) || before[0].isDigit())
    }

    /** Marks that close up against the word before them. */
    private fun isTightPunctuation(code: Int): Boolean =
        code == '.'.code || code == ','.code || code == '!'.code || code == '?'.code ||
            code == ';'.code || code == ':'.code

    /** Marks that end a sentence. */
    private fun isSentenceEndingPunctuation(code: Int): Boolean =
        code == '.'.code || code == '!'.code || code == '?'.code

    /** The space that follows a sentence mark, or nothing at all; see [PunctuationSpace]. */
    private fun spaceAfter(code: Int): String {
        val editor = currentEditor()
        val follows = PunctuationSpace.follows(
            enabled = preferences.spaceAfterPunctuation,
            addressField = session.addressField,
            insideNumbers = preferences.spaceInsideNumbers,
            tightPunctuation = isTightPunctuation(code),
            before = { editor?.textBeforeCursor(1)?.firstOrNull() },
            after = { editor?.textAfterCursor(1)?.firstOrNull() },
        )
        return if (follows) " " else ""
    }

    /**
     * Re-derives shift after a delimiter, unless caps lock is on or the user pressed shift
     * ([heldByUser]). [justCommitted] is what this keystroke wrote; see [applyAutoShift].
     */
    private fun shiftAfterDelimiter(heldByUser: Boolean, justCommitted: String = "") {
        if (shiftState == ShiftState.LOCKED || heldByUser) {
            return
        }
        applyAutoShift(justCommitted)
    }

    /** Whether [code] is one of ! ? ; : and the text is French. */
    private fun isFrenchSpacedPunctuation(code: Int): Boolean =
        (code == '!'.code || code == '?'.code || code == ';'.code || code == ':'.code) &&
            writingInFrench()

    /**
     * Whether the text being written is French: the dominant language, or, before there is one,
     * the only language enabled.
     */
    private fun writingInFrench(): Boolean {
        val dominant = dominantLanguageTag
        if (dominant != null) {
            return dominant.startsWith("fr", ignoreCase = true)
        }
        val tags = languageTags
        return tags.isNotEmpty() && tags.all { it.startsWith("fr", ignoreCase = true) }
    }

    /**
     * Sets shift from what the field asks for and the text before the caret, unless the user set
     * it. [justCommitted] is text just written, appended to what the editor reports.
     */
    fun applyAutoShift(justCommitted: String = "") {
        if (shiftState == ShiftState.LOCKED && !autoLockedShift) {
            return
        }
        if (shiftHeldByUser || userReleasedAutoLock) {
            return
        }
        val wanted = autoShiftState(justCommitted)
        autoLockedShift = wanted == ShiftState.LOCKED
        if (shiftState != wanted) {
            shiftState = wanted
            host.showShiftState(shiftState)
        }
    }

    /**
     * What shift should be here, per [AutoShift], from the field's caps mode and the text before
     * the cursor.
     */
    private fun autoShiftState(justCommitted: String = ""): Int {
        if (!session.described) {
            return ShiftState.OFF
        }
        return AutoShift.stateFor(
            autoCapitaliseEnabled = preferences.autoCapitalise,
            inputType = session.inputType,
            composingIsEmpty = composing.isEmpty(),
            forceCapitaliseSentences = preferences.forceCapitaliseSentences,
            capsMode = {
                currentEditor()?.cursorCapsMode(session.inputType) ?: session.initialCapsMode
            },
            textBeforeCursor = {
                val before = currentEditor()?.textBeforeCursor(CONTEXT_WINDOW_CHARS)
                if (justCommitted.isEmpty()) before else (before ?: "").toString() + justCommitted
            },
        )
    }

    // ---- learning ------------------------------------------------------------------------------

    /**
     * Whether anything is learned, and whether the personal dictionary is consulted: off with the
     * learning switch or in a private field.
     */
    private fun applyLearningGate(preferences: KeyboardPreferences = this.preferences) {
        session = session.copy(
            policy = session.policy.withLearning(preferences.learningEnabled),
        )
        learning.enabled = session.policy.personalAllowed
        engine.setPersonalModelEnabled(learning.enabled)
    }

    /**
     * Records a confirmed word, and the pair and triple it makes with [contextWord] and
     * [grandContextWord], read before the commit. [deliberateCapital] is whether its capital was
     * typed with shift; [asserted] whether the user chose it on purpose.
     */
    private fun recordLearned(
        word: String,
        contextWord: String?,
        grandContextWord: String?,
        deliberateCapital: Boolean = false,
        asserted: Boolean = false,
    ) {
        if (!learning.enabled || word.length < MIN_LEARNED_LENGTH) {
            return
        }
        // The input-method subtype's tag, recorded with the word.
        val locale = host.subtypeTag()
        val now = clock.currentTimeMillis()
        // A word with nothing before it is paired with the sentence start.
        val pairContext = contextWord ?: UserBigram.SENTENCE_START
        learning.recordPair(pairContext, word, now)
        if (contextWord != null && grandContextWord != null) {
            learning.recordTriple(grandContextWord, contextWord, word, now)
        }
        if (learning.record(word, locale, now, deliberateCapital, asserted)) {
            host.playEffect(EffectEvent.LearnedWord, word)
            engine.learn(
                listOf(LearnedWord(word, locale, 1, now, deliberateCapital, asserted)),
                pairContext, grandContextWord,
            )
        }
        if (!host.viewAttached) {
            return
        }
        host.removeCallbacks(flushLearningRunnable)
        if (learning.isDue(clock.currentTimeMillis())) {
            flushLearning()
        } else {
            host.postDelayed(flushLearningRunnable, LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        }
    }

    /** Writes the buffered learning to the [store]. */
    private fun flushLearning() {
        val batch = drainLearning() ?: return
        store.persist(batch)
    }

    /** Empties [learning], or returns null when there was nothing in it. */
    private fun drainLearning(): LearningBatch? {
        val updates = learning.drain()
        val pairs = learning.drainPairs()
        val triples = learning.drainTriples()
        if (updates.isEmpty() && pairs.isEmpty() && triples.isEmpty()) {
            return null
        }
        return LearningBatch(updates, pairs, triples)
    }

    companion object {
        const val CONTEXT_WINDOW_CHARS = 64

        /** How much of the field [checkpointField] and the field's history read. */
        const val FIELD_HISTORY_CHARS = 20_000

        /** The longest gap between two spaces that become a full stop. */
        const val DOUBLE_SPACE_MILLIS = 1200L

        const val DOUBLE_TAP_MILLIS = 400L

        const val MIN_LEARNED_LENGTH = 2

        /** The characters a swiped word follows without a space; see [spaceBeforeSwipedWord]. */
        const val SWIPE_NO_SPACE_AFTER = "([{\"'/-_@#\n"

        /** Shift held, as a key event carries it. */
        const val SHIFT_META = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        /** A letter, an apostrophe or a hyphen: what continues a word once a letter began it. */
        fun isWordCharacter(code: Int): Boolean =
            Character.isLetter(code) || code == '\''.code || code == '-'.code
    }
}
