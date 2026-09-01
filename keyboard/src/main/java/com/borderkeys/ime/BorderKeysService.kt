// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.borderkeys.assist.AssistClient
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.theme.ThemePaints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * The input method itself.
 *
 * Holds the pieces together and owns the one thing none of them can: the [InputConnection], and
 * therefore the rule that a key press costs one inter-process call. Everything expensive --
 * mapping dictionaries, scoring candidates, writing to the database -- happens on another
 * thread or on a debounce, so that the path from a finger going down to a character appearing
 * is a hit test, an array read and a single `commitText`.
 */
class BorderKeysService :
    InputMethodService(),
    KeyboardCanvasView.Listener,
    SuggestionStripView.Listener,
    AssistSheetView.Listener,
    AssistClient.Listener,
    PredictionEngine.ResultListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paints = ThemePaints()
    private val engine = PredictionEngine()
    private val learning = LearningBuffer()

    private var host: KeyboardHostView? = null

    private val composing = StringBuilder(48)
    private var previousWord1: String? = null
    private var previousWord2: String? = null

    private var privateMode = false
    private var preferences = KeyboardPreferences()
    private var theme = KeyboardTheme()

    private var alphabeticLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsShiftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var numpadLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /** Which page is on screen. The numeric one is chosen by the field, not by the user. */
    private var page = PAGE_ALPHABETIC

    private var shiftState = SHIFT_OFF
    private var lastShiftPressAt = 0L

    private val flushLearningRunnable = Runnable { flushLearning() }

    /**
     * Shows "decoding" only if the answer is late.
     *
     * A swipe is decoded in well under a millisecond on the measurements taken so far, so in
     * practice this never fires. It exists for the case where it does -- a very long word, a
     * device under load -- because a strip that goes blank for a moment reads as the gesture
     * having been ignored.
     */
    private val gestureDecodingRunnable = Runnable { host?.suggestionStrip?.decoding = true }

    // ---- text assistant ----------------------------------------------------------------------
    //
    // Everything below is inert in the free build: AssistClient resolves the service by name,
    // :assist is attached only to the `plus` flavor, and resolution simply fails. There is no
    // flag to check and nothing to disable.

    private val assist by lazy { AssistClient(this).also { it.listener = this } }
    private var assistAvailable = false
    private var assistRequestId = -1
    private var assistSelection = ""
    private var assistTask: AssistTask? = null

    /**
     * The actions offered for a selection.
     *
     * Three, because the strip has three slots and because a longer menu would need somewhere
     * else to live. Translation is offered in one direction at a time, chosen by the layout's
     * language: a Romanian keyboard offers English, and the other way round.
     */
    private val assistActions = arrayOfNulls<String>(SuggestionStripView.MAX_SUGGESTIONS)
    private var assistTasks: Array<AssistTask> = emptyArray()

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false
    private val inputMethods: InputMethodManager? by lazy {
        getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        DataGraph.install(applicationContext)
        engine.listener = this
        engine.start()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        scope.launch(Dispatchers.IO) {
            alphabeticLayout = LayoutLoader.load(assets, DEFAULT_ALPHABETIC_LAYOUT)
            symbolsLayout = LayoutLoader.load(assets, SYMBOLS_LAYOUT)
            symbolsShiftLayout = LayoutLoader.load(assets, SYMBOLS_SHIFT_LAYOUT)
            numpadLayout = LayoutLoader.load(assets, NUMPAD_LAYOUT)
            // A keyboard that cannot start is one the user cannot replace without already
            // having another keyboard installed, so nothing on this path is allowed to be
            // fatal. Failing to open the database costs suggestions; it must not cost typing.
            //
            // This is not hypothetical: a schema change without a version bump made Room refuse
            // to open the database, and the whole input method died on start with it.
            runCatching { loadDictionaries() }
                .onFailure { error -> degradeWithoutDictionaries(error) }
        }
        observeSettings()
        observeLanguagePacks()
    }

    /**
     * Loads what the engine needs before the first keystroke: the language packs the user has
     * enabled, their personal dictionary, and the words they have refused.
     *
     * Every enabled pack is re-hashed first. The files are in private storage, but private is a
     * statement about other applications -- not about a restore that substituted one or a
     * filesystem that corrupted it. A pack that fails switches itself off rather than being
     * mapped.
     */
    private suspend fun loadDictionaries() {
        val repository = DataGraph.languagePacks
        repository.verifyEnabled()

        val enabled = repository.enabledPacks()
        for (entry in enabled) {
            val file = repository.fileFor(entry)
            if (!file.isFile) {
                continue
            }
            runCatching {
                val descriptor = android.content.res.AssetFileDescriptor(
                    android.os.ParcelFileDescriptor.open(
                        file, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    ),
                    0L,
                    file.length(),
                )
                engine.loadLanguage(entry.tag, descriptor, entry.weight)
            }
        }
        if (enabled.isNotEmpty()) {
            engine.setActiveLanguages(
                Array(enabled.size) { enabled[it].tag },
                FloatArray(enabled.size) { enabled[it].weight },
            )
        }

        val dictionary = DataGraph.dictionary
        engine.loadUserWords(dictionary.topWords())
        val blockedWords = dictionary.blockedWordSet()
        engine.setBlockedWords(blockedWords)
        learning.setBlockedWords(blockedWords)
    }

    /**
     * Carries on with no dictionary rather than dying.
     *
     * Typing, deleting, shift, layouts and the clipboard all still work; what is lost is
     * prediction and correction. That is the right trade for the one application on the device
     * that the user cannot uninstall their way out of.
     */
    private fun degradeWithoutDictionaries(error: Throwable) {
        android.util.Log.e("BorderKeys", "starting without dictionaries", error)
        learning.enabled = false
        scope.launch { host?.suggestionStrip?.clear() }
    }

    /**
     * Reloads the language packs whenever the set of them changes.
     *
     * Settings runs in this process, so importing a pack, switching one off or moving a weight
     * happens a few metres from an engine that has already mapped what it was told to map at
     * start. Without this the change takes effect the next time the input method is created,
     * which from the user's side looks like the setting having been ignored.
     *
     * Reloading a tag replaces it in the engine rather than taking a second slot, which is what
     * makes this safe to run on every emission: the signature below means it runs only when
     * something that actually affects loading has changed, not on every unrelated write.
     */
    private fun observeLanguagePacks() {
        scope.launch {
            var previous: String? = null
            DataGraph.languagePacks.packs
                .catch { error ->
                    android.util.Log.e("BorderKeys", "the pack list is unreadable", error)
                }
                .collect { packs ->
                    val signature = packs
                        .filter { it.enabled }
                        .sortedBy { it.id }
                        .joinToString("|") { "${it.id}:${it.tag}:${it.sha256}:${it.weight}" }
                    if (signature == previous) {
                        return@collect
                    }
                    // The first emission arrives after loadDictionaries has already run, and
                    // repeating that work would map every pack a second time for nothing.
                    val first = previous == null
                    previous = signature
                    if (first) {
                        return@collect
                    }
                    runCatching { loadDictionaries() }
                        .onFailure { error ->
                            android.util.Log.e("BorderKeys", "reloading packs failed", error)
                        }
                }
        }
    }

    private fun observeSettings() {
        scope.launch {
            combine(DataGraph.themes.theme, DataGraph.themes.preferences) { theme, preferences ->
                theme to preferences
            }.catch { error ->
                // The theme store failing is not a reason to have no keyboard either; the
                // defaults are perfectly usable colours.
                android.util.Log.e("BorderKeys", "settings unavailable, using defaults", error)
            }.collect { (newTheme, newPreferences) ->
                theme = newTheme
                preferences = newPreferences
                val changed = paints.update(
                    newTheme, resources.displayMetrics, newPreferences.heightScale,
                )
                host?.let { view ->
                    applyPlacement(view, newPreferences)
                    view.keyboard.hapticEnabled = newPreferences.hapticFeedback
                    view.keyboard.swipeEnabled = newPreferences.swipeEnabled
                    // The number row is a layout change, not a colour change, so it has to be
                    // applied even when the paints are unchanged.
                    showPage(page)
                    if (changed) {
                        view.keyboard.onThemeChanged()
                        view.requestLayout()
                    }
                }
            }
        }
    }

    /**
     * Called whenever the cursor or the selection moves.
     *
     * A non-empty selection is the assistant's only entry point. It is not offered while typing,
     * it never appears on its own, and it is refused outright in a private field -- checked here
     * rather than only in the service, because a feature that reads the user's selected text
     * must be impossible to reach from a password box by any path.
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd,
        )
        val view = host ?: return
        if (view.assistSheetVisible) {
            return
        }
        val hasSelection = newSelEnd > newSelStart
        if (!hasSelection || privateMode || !assistAvailable) {
            if (view.suggestionStrip.actionMode) {
                view.suggestionStrip.clear()
                requestSuggestions()
            }
            return
        }
        offerAssistActions()
    }

    private fun offerAssistActions() {
        val view = host ?: return
        val selection = currentInputConnection
            ?.getSelectedText(0)?.toString().orEmpty()
        if (selection.isEmpty() || selection.length > AssistProtocol.MAX_SELECTION_CHARS) {
            return
        }
        assistSelection = selection

        // The translation direction follows the layout: a Romanian keyboard offers English.
        val translate = if (alphabeticLayout.languageTag.startsWith("ro")) {
            AssistTask.TRANSLATE_TO_ENGLISH
        } else {
            AssistTask.TRANSLATE_TO_ROMANIAN
        }
        assistTasks = arrayOf(AssistTask.SUMMARISE, AssistTask.CORRECT, translate)
        assistActions[0] = "Summarise"
        assistActions[1] = "Correct"
        assistActions[2] = if (translate == AssistTask.TRANSLATE_TO_ENGLISH) "→ English" else "→ Română"
        view.suggestionStrip.setActions(assistActions, assistTasks.size)
    }

    override fun onActionPicked(index: Int) {
        val task = assistTasks.getOrNull(index) ?: return
        if (privateMode || assistSelection.isEmpty()) {
            return
        }
        val view = host ?: return
        assistTask = task
        view.assistSheet.listener = this
        view.assistSheet.showRunning(assistActionTitle(task), assistSelection)
        view.showAssistSheet(true)
        assistRequestId = assist.run(task, assistSelection)
        if (assistRequestId < 0) {
            view.assistSheet.showError("The assistant is not installed.")
        }
    }

    private fun assistActionTitle(task: AssistTask): String = when (task) {
        AssistTask.SUMMARISE -> "Summary"
        AssistTask.CORRECT -> "Correction"
        AssistTask.REWRITE_FORMAL -> "Formal rewrite"
        AssistTask.TRANSLATE_TO_ENGLISH -> "Translation into English"
        AssistTask.TRANSLATE_TO_ROMANIAN -> "Traducere în română"
    }

    override fun onAssistResult(requestId: Int, text: String, modelName: String?) {
        // A late answer to a request the user has already dismissed is discarded rather than
        // shown over whatever they are doing now.
        if (requestId != assistRequestId) {
            return
        }
        host?.assistSheet?.showResult(text, modelName)
    }

    override fun onAssistError(requestId: Int, error: Int) {
        if (requestId != assistRequestId) {
            return
        }
        host?.assistSheet?.showError(
            when (error) {
                AssistProtocol.ERROR_NO_MODEL ->
                    "No model imported yet. Settings › Text assistant."
                AssistProtocol.ERROR_MODEL_CHANGED ->
                    "The model file changed since it was imported and was not loaded."
                AssistProtocol.ERROR_LOAD_FAILED ->
                    "The model could not be loaded. It may not fit in memory on this device."
                AssistProtocol.ERROR_TOO_LONG ->
                    "The selection is longer than this model's context window."
                AssistProtocol.ERROR_BUSY -> "Still working on the previous request."
                else -> "The assistant could not finish."
            },
        )
    }

    override fun onAssistAvailability(available: Boolean, modelName: String?) {
        assistAvailable = available
    }

    /**
     * The sheet's buttons.
     *
     * Replace is the only one that writes anything, and it writes in a single batch edit so the
     * editor sees one change rather than a delete followed by an insert.
     */
    override fun onAssistButton(button: AssistSheetView.Button) {
        val view = host ?: return
        when (button) {
            AssistSheetView.Button.REPLACE -> {
                val text = view.assistSheet.currentProposal()
                val connection = currentInputConnection
                if (text.isNotEmpty() && connection != null) {
                    connection.beginBatchEdit()
                    connection.commitText(text, 1)
                    connection.endBatchEdit()
                }
            }
            AssistSheetView.Button.COPY -> {
                val text = view.assistSheet.currentProposal()
                if (text.isNotEmpty()) {
                    clipboardManager?.setPrimaryClip(
                        android.content.ClipData.newPlainText("BorderKeys", text),
                    )
                }
            }
            AssistSheetView.Button.DISCARD -> assist.cancel()
        }
        closeAssistSheet()
    }

    private fun closeAssistSheet() {
        assistRequestId = -1
        assistTask = null
        assistSelection = ""
        host?.showAssistSheet(false)
        host?.suggestionStrip?.clear()
        // The model unloads itself on its own timer; dropping the binding is what lets the
        // process stop rather than lingering for the rest of the session.
        assist.disconnect()
        requestSuggestions()
    }

    override fun onCreateInputView(): View {
        // Built in code. LayoutInflater would parse XML and reflect to construct three views,
        // every time the keyboard is shown in a new editor.
        paints.update(theme, resources.displayMetrics, preferences.heightScale)
        val view = KeyboardHostView(this, paints)
        applyPlacement(view, preferences)
        view.keyboard.listener = this
        view.keyboard.hapticEnabled = preferences.hapticFeedback
        view.keyboard.swipeEnabled = preferences.swipeEnabled
        view.keyboard.setLayout(
            if (preferences.numberRow) alphabeticLayout.withNumberRow() else alphabeticLayout,
        )
        view.suggestionStrip.listener = this
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pushKeyGeometry() }
        host = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        // Recomputed on every field, from the EditorInfo alone. No setting can switch it off,
        // which is the point: it is a security requirement, not a preference.
        privateMode = PrivateMode.isPrivate(info)
        learning.enabled = preferences.learningEnabled && !privateMode
        if (privateMode) {
            learning.discard()
        }

        host?.let { view ->
            view.suggestionStrip.privateMode = privateMode
            view.suggestionStrip.clear()
            view.keyboard.hapticEnabled = preferences.hapticFeedback
            view.keyboard.swipeEnabled = preferences.swipeEnabled
        }
        showPage(pageFor(info))
        // Asked once per field rather than once per selection: the answer is about whether the
        // flavor has an assistant and a verified model, neither of which changes mid-session.
        if (!privateMode) {
            assist.queryAvailability()
        } else {
            assistAvailable = false
        }
        shiftState = if (info != null && shouldAutoCapitalise(info)) SHIFT_ON else SHIFT_OFF

        resetComposing()
        registerClipboardListener()
        pushKeyGeometry()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        unregisterClipboardListener()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        if (host?.assistSheetVisible == true) {
            closeAssistSheet()
        } else {
            assist.disconnect()
        }
        // The session is over, so everything held in memory is written now rather than waiting
        // for a debounce that may never fire: the process can be killed the moment the keyboard
        // is hidden.
        flushLearning()
        engine.cancelPending()
        resetComposing()
        scope.launch(Dispatchers.IO) { DataGraph.clipboard.purgeExpired() }
    }

    override fun onDestroy() {
        assist.disconnect()
        unregisterClipboardListener()
        flushLearning()
        // Zeroes the handle under a lock before freeing, so a request already in flight
        // completes against a live engine and anything after it sees zero and returns.
        engine.shutdown()
        scope.cancel()
        host = null
        super.onDestroy()
    }

    // ---- geometry ------------------------------------------------------------------------------

    /**
     * Applies size and position.
     *
     * Called from the preferences flow, so dragging a slider in Settings moves the keyboard that
     * is on screen at that moment rather than the next one.
     */
    private fun applyPlacement(view: KeyboardHostView, settings: KeyboardPreferences) {
        val density = resources.displayMetrics.density
        view.setPlacement(
            settings.positionMode,
            settings.widthScale,
            (settings.bottomOffsetDp * density).toInt(),
            (settings.horizontalOffsetDp * density).toInt(),
        )
    }

    private fun pushKeyGeometry() {
        val view = host?.keyboard ?: return
        val keyWidth = view.averageKeyWidth
        val keyHeight = view.averageKeyHeight
        if (keyWidth <= 0f || keyHeight <= 0f) {
            return
        }
        engine.setKeyGeometry(0, keyWidth, keyHeight) { codes, x, y ->
            view.exportGeometry(codes, x, y)
        }
    }

    // ---- key handling ----------------------------------------------------------------------------

    override fun onKeyDown(code: Int) = Unit

    /**
     * A completed swipe.
     *
     * The word in progress is committed first: a swipe starts a new word, and leaving the
     * previous one composing would make the decoded word replace it.
     */
    override fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.swipeEnabled) {
            return
        }
        val connection = currentInputConnection
        if (connection != null && composing.isNotEmpty()) {
            connection.beginBatchEdit()
            val finished = finishComposing(connection)
            connection.endBatchEdit()
            if (finished != null) {
                recordLearned(finished)
            }
        }
        host?.postDelayed(gestureDecodingRunnable, GESTURE_DECODING_NOTICE_MILLIS)
        engine.decodeGesture(xs, ys, timestamps, count, previousWord1, previousWord2)
    }

    /**
     * The decoded candidates.
     *
     * The first one is committed immediately, as composing text, and the rest go to the strip.
     * The user does not wait for a confirmation: the common case is that the top candidate is
     * right, and leaving it uncommitted would make every swipe a two-step action. Because it is
     * composing rather than committed, tapping another candidate replaces it in one edit rather
     * than deleting and retyping.
     */
    override fun onGestureCandidates(words: Array<String?>, count: Int) {
        host?.removeCallbacks(gestureDecodingRunnable)
        val view = host
        view?.suggestionStrip?.decoding = false
        if (count == 0) {
            view?.suggestionStrip?.clear()
            return
        }
        val connection = currentInputConnection ?: return
        val best = words[0] ?: return

        connection.beginBatchEdit()
        composing.setLength(0)
        composing.append(best)
        connection.setComposingText(composing, 1)
        connection.endBatchEdit()

        view?.suggestionStrip?.setSuggestions(words, count)
    }

    override fun onKeyRepeat(code: Int) {
        if (code == KeyCodes.DELETE) {
            handleDelete()
        }
    }

    override fun onText(text: CharSequence) {
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        finishComposing(connection)
        connection.commitText(text, 1)
        connection.endBatchEdit()
    }

    override fun onKey(code: Int, keyIndex: Int) {
        when (code) {
            KeyCodes.SHIFT -> handleShift()
            KeyCodes.DELETE -> handleDelete()
            KeyCodes.ENTER -> handleEnter()
            KeyCodes.SYMBOLS -> showPage(
                if (page == PAGE_ALPHABETIC) PAGE_SYMBOLS else PAGE_ALPHABETIC,
            )
            KeyCodes.SYMBOLS_SHIFT -> showPage(
                if (page == PAGE_SYMBOLS) PAGE_SYMBOLS_SHIFT else PAGE_SYMBOLS,
            )
            KeyCodes.LANGUAGE -> switchLanguage()
            KeyCodes.SETTINGS -> openSettings()
            KeyCodes.EMOJI -> Unit
            else -> if (KeyCodes.isCharacter(code)) handleCharacter(code)
        }
    }

    private fun handleCharacter(code: Int) {
        val connection = currentInputConnection ?: return
        val shifted = if (shiftState != SHIFT_OFF) {
            Character.toUpperCase(code)
        } else {
            code
        }
        if (shiftState == SHIFT_ON) {
            shiftState = SHIFT_OFF
        }

        if (isWordCharacter(shifted)) {
            composing.appendCodePoint(shifted)
            // One IPC for the whole update. setComposingText replaces the composing region, so
            // the editor is told the new word rather than the character that changed.
            connection.setComposingText(composing, 1)
            requestSuggestions()
            return
        }

        // A delimiter ends the word. What was typed is committed as typed -- no silent
        // replacement with the top suggestion. A keyboard that rewrites what you wrote because
        // it has a better idea is the failure mode this project exists to avoid; choosing a
        // suggestion is an act, not a default.
        connection.beginBatchEdit()
        val finished = finishComposing(connection)
        connection.commitText(String(Character.toChars(shifted)), 1)
        connection.endBatchEdit()
        if (finished != null) {
            recordLearned(finished)
        }
        shiftAfterDelimiter(shifted)
        requestSuggestions()
    }

    private fun handleDelete() {
        val connection = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            // A surrogate pair is one character to the user and two to the buffer.
            val length = composing.length
            val start = composing.offsetByCodePoints(length, -1)
            composing.setLength(start)
            connection.setComposingText(composing, 1)
            requestSuggestions()
            return
        }
        connection.beginBatchEdit()
        val before = connection.getTextBeforeCursor(2, 0)
        val toDelete = if (before != null && before.length == 2 &&
            Character.isSurrogatePair(before[0], before[1])
        ) {
            2
        } else {
            1
        }
        connection.deleteSurroundingText(toDelete, 0)
        connection.endBatchEdit()
        refreshContextFromEditor()
        requestSuggestions()
    }

    private fun handleEnter() {
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        val finished = finishComposing(connection)
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.endBatchEdit()
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
            connection.endBatchEdit()
        }
        if (finished != null) {
            recordLearned(finished)
        }
        requestSuggestions()
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == SHIFT_LOCKED -> SHIFT_OFF
            shiftState == SHIFT_ON && now - lastShiftPressAt < DOUBLE_TAP_MILLIS -> SHIFT_LOCKED
            shiftState == SHIFT_ON -> SHIFT_OFF
            else -> SHIFT_ON
        }
        lastShiftPressAt = now
    }

    /**
     * Puts a page on screen, applying the number-row setting to the alphabetic one.
     *
     * The number row is composed rather than authored into a second copy of every layout: two
     * assets per language that differ by one row is two assets to keep in step, and they would
     * drift the first time a key moved.
     */
    private fun showPage(next: Int) {
        page = next
        val layout = when (next) {
            PAGE_SYMBOLS -> symbolsLayout
            PAGE_SYMBOLS_SHIFT -> symbolsShiftLayout
            PAGE_NUMPAD -> numpadLayout
            else -> if (preferences.numberRow) alphabeticLayout.withNumberRow() else alphabeticLayout
        }
        host?.keyboard?.setLayout(layout)
        pushKeyGeometry()
    }

    /**
     * The page a field asks for.
     *
     * A phone number field gets a keypad, not a QWERTY with digits hidden behind a symbols
     * key. The framework already told us what kind of field it is; ignoring that and making the
     * user find the digits is a choice, and the wrong one.
     */
    private fun pageFor(info: EditorInfo?): Int {
        if (info == null || !preferences.numericKeypad) {
            return PAGE_ALPHABETIC
        }
        return when (info.inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_PHONE,
            -> PAGE_NUMPAD
            else -> PAGE_ALPHABETIC
        }
    }

    private fun switchLanguage() {
        // Cycles this input method's own subtypes -- the layouts -- rather than jumping to
        // another keyboard. Which dictionaries are active is a separate setting: the engine
        // scores several languages at once and switching layout does not change what it knows.
        switchToNextInputMethod(true)
    }

    private fun openSettings() {
        // A string class name, exactly like android:settingsActivity in method.xml. It is the
        // only reference from :keyboard towards :settings, and it creates no compile-time edge.
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(packageName, SETTINGS_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(intent) }
    }

    // ---- suggestions ------------------------------------------------------------------------------

    override fun onSuggestionPicked(index: Int, word: String) {
        val connection = currentInputConnection ?: return
        connection.beginBatchEdit()
        composing.setLength(0)
        composing.append(word)
        connection.commitText("$word ", 1)
        connection.endBatchEdit()

        // Choosing a candidate that was not already the top one is the learning signal. This is
        // where personalisation happens: a count goes up, and nothing is retrained.
        recordLearned(word)
        composing.setLength(0)
        host?.suggestionStrip?.clear()
        requestSuggestions()
    }

    override fun onSuggestions(words: Array<String?>, count: Int) {
        host?.suggestionStrip?.setSuggestions(words, count)
    }

    private fun requestSuggestions() {
        if (!preferences.showSuggestionStrip) {
            return
        }
        engine.requestSuggestions(composing.toString(), previousWord1, previousWord2)
    }

    // ---- composing state ---------------------------------------------------------------------------

    /** Ends the composing region and returns the word that was committed, if any. */
    private fun finishComposing(connection: InputConnection): String? {
        if (composing.isEmpty()) {
            connection.finishComposingText()
            return null
        }
        val word = composing.toString()
        connection.finishComposingText()
        composing.setLength(0)
        previousWord2 = previousWord1
        previousWord1 = word
        return word
    }

    private fun resetComposing() {
        composing.setLength(0)
        currentInputConnection?.finishComposingText()
        refreshContextFromEditor()
        host?.suggestionStrip?.clear()
    }

    /**
     * Reads the two words before the cursor back out of the editor.
     *
     * Needed after a deletion or a cursor move, where our own idea of the context is no longer
     * what is on screen. Bounded to a short window: this is an IPC, and the n-gram model only
     * looks two words back anyway.
     */
    private fun refreshContextFromEditor() {
        val connection = currentInputConnection
        val before = connection?.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            previousWord1 = null
            previousWord2 = null
            return
        }
        val words = before.split(*WORD_SEPARATORS).filter { it.isNotEmpty() }
        previousWord1 = words.getOrNull(words.size - 1)
        previousWord2 = words.getOrNull(words.size - 2)
    }

    private fun shiftAfterDelimiter(code: Int) {
        if (shiftState == SHIFT_LOCKED) {
            return
        }
        shiftState = if (code == '.'.code || code == '!'.code || code == '?'.code) SHIFT_ON
        else SHIFT_OFF
    }

    private fun shouldAutoCapitalise(info: EditorInfo): Boolean =
        (info.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0

    private fun isWordCharacter(code: Int): Boolean =
        Character.isLetter(code) || code == '\''.code || code == '-'.code

    // ---- learning -----------------------------------------------------------------------------------

    private fun recordLearned(word: String) {
        if (!learning.enabled || word.length < MIN_LEARNED_LENGTH) {
            return
        }
        val locale = inputMethods?.currentInputMethodSubtype?.languageTag?.takeIf { it.isNotEmpty() }
            ?: alphabeticLayout.languageTag
        if (learning.record(word, locale, System.currentTimeMillis())) {
            engine.learn(
                listOf(
                    com.borderkeys.data.dao.LearnedWord(
                        word, locale, 1, System.currentTimeMillis(),
                    ),
                ),
                previousWord1, previousWord2,
            )
        }
        val view = host ?: return
        view.removeCallbacks(flushLearningRunnable)
        if (learning.isDue(System.currentTimeMillis())) {
            flushLearning()
        } else {
            view.postDelayed(flushLearningRunnable, LearningBuffer.DEFAULT_DEBOUNCE_MILLIS)
        }
    }

    /**
     * Writes the buffered learning to the database and asks the native model to snapshot itself.
     *
     * Never on a keystroke. An INSERT is a transaction, a disk write and an encryption pass, and
     * one of those on the path of a key press would spend the whole two-millisecond budget.
     */
    private fun flushLearning() {
        val updates = learning.drain()
        if (updates.isEmpty()) {
            return
        }
        val snapshotPath = File(filesDir, USER_MODEL_SNAPSHOT).absolutePath
        scope.launch(Dispatchers.IO) {
            DataGraph.dictionary.applyLearned(updates)
            engine.snapshotUserModel(snapshotPath)
        }
    }

    // ---- clipboard --------------------------------------------------------------------------------------

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered || privateMode || !preferences.clipboardEnabled) {
            return
        }
        // The platform only delivers this to the input method that currently has focus, which is
        // the only reason a keyboard can implement clipboard history at all without a permission.
        clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = true
    }

    private fun unregisterClipboardListener() {
        if (!clipboardListenerRegistered) {
            return
        }
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        clipboardListenerRegistered = false
    }

    private fun onClipboardChanged() {
        if (privateMode || !preferences.clipboardEnabled) {
            return
        }
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) {
            return
        }
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isEmpty() || text.length > MAX_CLIP_LENGTH) {
            return
        }
        scope.launch(Dispatchers.IO) { DataGraph.clipboard.remember(text) }
    }

    // ---- inline autofill suggestions (section 5.4) ----------------------------------------------------

    /**
     * Describes how a password manager's suggestions should look inside our strip.
     *
     * This is the whole of our involvement with them. The platform renders the content in the
     * autofill service's process and hands back a surface; we say how large and what colour, and
     * we are never given the text. That is why this integration needs no permission and cannot
     * leak a password even in principle -- there is no API through which we could read one.
     */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (privateMode.not() && currentInputEditorInfo == null) {
            return null
        }
        val chipBackground = ViewStyle.Builder()
            .setBackgroundColor(theme.keyColor)
            .setPadding(CHIP_PADDING_PX, 0, CHIP_PADDING_PX, 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(chipBackground)
            .setChipStyle(chipBackground)
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.textColor)
                    .setTextSize(theme.labelTextSizeSp * 0.8f)
                    .build(),
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(theme.secondaryTextColor)
                    .setTextSize(theme.labelTextSizeSp * 0.62f)
                    .build(),
            )
            .build()

        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val density = resources.displayMetrics.density
        val height = (theme.rowHeightDp * 0.78f * density).toInt()
        val spec = InlinePresentationSpec
            .Builder(Size(MIN_CHIP_WIDTH_DP, height), Size(Int.MAX_VALUE, height))
            .setStyle(styles)
            .build()

        // One spec, reused for every suggestion: the platform repeats the last one when there
        // are fewer specs than suggestions, which is exactly the behaviour wanted here.
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_INLINE_SUGGESTIONS)
            .build()
    }

    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val view = host ?: return false
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            view.inlineSuggestions.clearSuggestions()
            view.showInlineSuggestions(false)
            return false
        }

        val density = resources.displayMetrics.density
        val chipHeight = (theme.rowHeightDp * 0.78f * density).toInt().coerceAtLeast(1)
        val chipWidth = (view.width / 2).coerceAtLeast(MIN_CHIP_WIDTH_DP)
        val size = Size(chipWidth, chipHeight)
        val inflated = ArrayList<android.widget.inline.InlineContentView>(suggestions.size)
        var remaining = suggestions.size
        for (suggestion in suggestions) {
            // inflate() is asynchronous: the view is built in the other process and handed back
            // on the executor. Nothing about the content is visible to us at any point.
            suggestion.inflate(this, size, mainExecutor) { contentView ->
                if (contentView != null) {
                    inflated += contentView
                }
                remaining--
                if (remaining == 0) {
                    view.inlineSuggestions.setSuggestions(inflated)
                    view.showInlineSuggestions(inflated.isNotEmpty())
                }
            }
        }
        return true
    }

    private companion object {
        const val DEFAULT_ALPHABETIC_LAYOUT = "qwerty_ro"
        const val SYMBOLS_LAYOUT = "symbols"
        const val SYMBOLS_SHIFT_LAYOUT = "symbols_shift"
        const val NUMPAD_LAYOUT = "numpad"

        const val PAGE_ALPHABETIC = 0
        const val PAGE_SYMBOLS = 1
        const val PAGE_SYMBOLS_SHIFT = 2
        const val PAGE_NUMPAD = 3
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"
        const val USER_MODEL_SNAPSHOT = "user_model.bku"

        const val SHIFT_OFF = 0
        const val SHIFT_ON = 1
        const val SHIFT_LOCKED = 2
        const val DOUBLE_TAP_MILLIS = 400L

        const val CONTEXT_WINDOW_CHARS = 64
        const val MIN_LEARNED_LENGTH = 2
        const val GESTURE_DECODING_NOTICE_MILLIS = 50L
        const val MAX_CLIP_LENGTH = 20_000
        const val MAX_INLINE_SUGGESTIONS = 5
        const val MIN_CHIP_WIDTH_DP = 120
        const val CHIP_PADDING_PX = 12

        val WORD_SEPARATORS = charArrayOf(
            ' ', '\n', '\t', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']', '"', '/',
        )
    }
}
