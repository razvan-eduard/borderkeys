// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.borderkeys.data.DataGraph
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.decayed
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.draft.DraftProtocol
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.theme.DynamicColors
import com.borderkeys.theme.ThemeMode
import com.borderkeys.theme.ThemePaints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys

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
    QuickSettingsView.Listener,
    QuickActionsView.Listener,
    ClipboardPanelView.Listener,
    PredictionEngine.ResultListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val paints = ThemePaints()
    private val engine = PredictionEngine()
    private val learning = LearningBuffer()

    private var host: KeyboardHostView? = null

    private val composing = StringBuilder(48)

    /**
     * The editor's selection, as of the last onUpdateSelection.
     *
     * Held rather than fetched because both callers are on the touch path: backspace has to know
     * whether there is a selection to delete before it does anything else, and asking the editor
     * costs an IPC per keystroke to answer a question the platform already told us.
     */
    private var selectionStart = 0
    private var selectionEnd = 0

    /** Whether the field holds any text at all, which is not the same as "we are composing". */
    private var editorEmpty = true
    private var previousWord1: String? = null
    private var previousWord2: String? = null

    private var privateMode = false
    private var preferences = KeyboardPreferences()

    /**
     * The interface language, resolved once when the service starts.
     *
     * Built here rather than per view so the JSON is parsed once for the process. It is not
     * reloaded when preferences change: the language picker recreates the input view, which is
     * the only moment a different catalogue could take effect anyway.
     */
    private lateinit var strings: LanguageManager
    private var theme = KeyboardTheme()

    /** The theme shown instead of [theme] in [KeyboardPreferences.THEME_MODE_AUTO_SYSTEM] mode
     *  when the system is not in dark mode. See [ThemeMode]. */
    private var lightTheme = KeyboardTheme()

    /** [theme] or [lightTheme], whichever [ThemeMode] picks, then recoloured from the wallpaper
     *  when the setting asks for it. See [ThemeMode] and [DynamicColors]. */
    private fun effectiveTheme(): KeyboardTheme {
        val chosen = ThemeMode.resolve(theme, lightTheme, preferences, this)
        return if (preferences.followSystemColors) DynamicColors.apply(chosen, this) else chosen
    }

    private var alphabeticLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsShiftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var numpadLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /** Which page is on screen. The numeric one is chosen by the field, not by the user. */
    private var page = PAGE_ALPHABETIC

    private var shiftState = ShiftState.OFF

    /**
     * Set when the user pressed shift themselves, cleared by the character it applied to.
     *
     * Without it the automatic state overwrites a deliberate press the moment the caret moves,
     * which is every time a character is typed.
     */
    private var shiftHeldByUser = false

    /** When the last space was committed, for the two-spaces-make-a-full-stop window. */
    private var lastSpaceAt = 0L

    /** Set for exactly one keystroke after two spaces became a full stop, so backspace undoes it. */
    private var pendingSpacePeriod = false

    /**
     * Set when a space was added after a sentence mark, so the space the user types next is
     * swallowed rather than doubled.
     *
     * Muscle memory types the space anyway. Without this, "hello." followed by the space
     * everyone presses out of habit produces two -- and then the two-spaces rule turns them
     * into a second full stop.
     */
    private var pendingAutoSpace = false

    /**
     * The clip whose chip has already served its purpose: it was used, or a session that
     * offered it closed with "offer it only once" on. Null when nothing is withheld.
     *
     * The clip's own content, not a flag. A flag cannot tell "this exact thing was already
     * offered" from "something copied while no field was focused, which never got the chance to
     * be" -- and the platform only delivers a change notification to the input method that
     * currently has focus (see [registerClipboardListener]), so a copy made between sessions
     * reaches neither. Keying withdrawal off a boolean meant every session after the first
     * stayed withdrawn regardless of what was actually on the clipboard by the time it opened;
     * comparing content instead means a *different* clip always gets its own turn, seen or not.
     *
     * Set back to null whenever an actual copy is observed (see [onClipboardChanged]) -- a copy
     * is always a new offer, even one that happens to repeat the same words.
     */
    private var withdrawnClip: String? = null

    /**
     * The signature of whatever clip the chip is actually showing right now, or null when it is
     * showing nothing. Set alongside [SuggestionStripView.clipboardChip] in
     * [refreshClipboardChip], and read (not re-derived) by [onFinishInputView] when deciding
     * what "offer it only once" should withdraw.
     *
     * A fresh read of the live clipboard at close time would race a copy that happens in the
     * same gesture as the field losing focus -- selecting text to copy often blurs the field as
     * part of dismissing the selection toolbar, and by the time onFinishInputView runs the
     * clipboard can already hold the *new* clip. Withdrawing that would mean a copy nobody has
     * ever seen a chip for gets silently marked as already offered before its first chance,
     * which read as the chip vanishing for a brand new copy rather than for the one before it.
     */
    private var shownClipSignature: String? = null

    /** Whether the current lock came from the field asking for capitals rather than from shift. */
    private var autoLockedShift = false
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

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }
    /** The leading suggestion, kept so the delimiter path can apply it. */
    private var topSuggestion: String? = null

    /**
     * A correction that has been applied and can still be taken back.
     *
     * Alive for exactly one keystroke: the next key either reverts it, if it is backspace, or
     * confirms it, whichever it is. Anything that moves the cursor drops it, because reverting
     * text the user has since navigated away from would edit the wrong place.
     */
    private data class PendingCorrection(
        val typed: String,
        val corrected: String,
        val delimiter: String,
        /** The word before it, kept so the pair is learned against the right one. */
        val contextWord: String?,
    )

    private var pendingCorrection: PendingCorrection? = null

    /**
     * The field's own undo/redo history, for this input session only.
     *
     * The same version-graph class the draft box uses, unmodified: a step is the field's whole
     * text after a discrete commit, and editing from a version that isn't the newest discards
     * whatever came after it -- exactly the rule [QuickAction.UNDO] and [QuickAction.REDO] need,
     * for the same reason [Composer] already has it. Scoped to the session and not the field --
     * cleared and reseeded in [onStartInputView], the same place [pendingCorrection] is dropped
     * for a new field -- rather than persisted across switching fields or reopening the keyboard.
     */
    private val fieldHistory = Composer()

    /** The word the strip is currently asking about, between the hold and the answer. */
    private var pendingForget: String? = null

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false

    // ---- lifecycle ---------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        DataGraph.install(applicationContext)
        // Before anything that draws. The stored language is read on this thread because the
        // service has nothing to show until it is known, and it is one small file read at
        // process start rather than something on the typing path.
        strings = LanguageManager(this).apply {
            loadResolved(DataGraph.themes.currentPreferences().uiLanguage)
        }
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
        // Repaired before the integrity sweep runs, not after: the sweep switches off anything
        // whose file no longer matches, and a pack that has been switched off is a pack the
        // repair would never look at again.
        reinstallOutdatedBundledPacks(repository)
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
        loadPersonalModel(dictionary)
        val blockedWords = dictionary.blockedWordSet()
        engine.setBlockedWords(blockedWords)
        learning.setBlockedWords(blockedWords)
    }

    /**
     * Pushes the personal dictionary into the native model, decayed for how long each entry has
     * sat unused.
     *
     * This is "restore" in [com.borderkeys.data.PersonalWordDecay]'s sense: the counts stored in
     * Room only shrink on the occasional sweep in [flushLearning]; what the engine actually
     * scores against is decayed here, every single time it is loaded, so a word or phrase not
     * written in months contributes less than one written this week without either of them ever
     * having to be deleted.
     */
    private suspend fun loadPersonalModel(dictionary: DictionaryRepository) {
        val now = System.currentTimeMillis()
        engine.loadUserWords(dictionary.topWords().map { it.decayed(now) })
        // After the words, never before: a pair names two words, and the model resolves those
        // names against what it already holds.
        engine.loadUserBigrams(dictionary.topBigrams().map { it.decayed(now) })
        engine.loadUserTrigrams(dictionary.topTrigrams().map { it.decayed(now) })
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
            combine(
                DataGraph.themes.theme, DataGraph.themes.lightTheme, DataGraph.themes.preferences,
            ) { theme, lightTheme, preferences ->
                Triple(theme, lightTheme, preferences)
            }.catch { error ->
                // The theme store failing is not a reason to have no keyboard either; the
                // defaults are perfectly usable colours.
                android.util.Log.e("BorderKeys", "settings unavailable, using defaults", error)
            }.collect { (newTheme, newLightTheme, newPreferences) ->
                theme = newTheme
                lightTheme = newLightTheme
                preferences = newPreferences
                val resolvedTheme = ThemeMode.resolve(
                    newTheme, newLightTheme, newPreferences, this@BorderKeysService,
                )
                val effectiveTheme = if (newPreferences.followSystemColors) {
                    DynamicColors.apply(resolvedTheme, this@BorderKeysService)
                } else {
                    resolvedTheme
                }
                val changed = paints.update(
                    effectiveTheme, resources.displayMetrics, newPreferences.heightScale,
                    this@BorderKeysService,
                )
                host?.let { view ->
                    applyPlacement(view, newPreferences)
                    if (view.quickSettingsVisible) {
                        // Open while the settings application changed something: the panel shows
                        // what is stored, so it follows rather than holding a stale copy.
                        pushQuickSettingsState(view)
                    }
                    view.keyboard.hapticEnabled = newPreferences.hapticFeedback
                    view.keyboard.soundEnabled = newPreferences.keySound
                    view.keyboard.spaceCursorEnabled = newPreferences.spaceCursorControl
                    view.suggestionStrip.visibleLimit = newPreferences.suggestionCount
                    applyQuickActions(view)
                    refreshClipboardChip()
                    view.keyboard.swipeEnabled = newPreferences.swipeEnabled
                    // The number row is a layout change, not a colour change, so it has to be
                    // applied even when the paints are unchanged.
                    showPage(page)
                    view.fullWidthBackground = resolvedTheme.fullWidthBackground
                    if (changed) {
                        view.keyboard.onThemeChanged()
                        view.quickSettings.onThemeChanged()
                        view.onThemeChanged()
                        view.relayoutForNewMetrics()
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
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        // newSelEnd > 0 means there is text before the caret; the extracted-text path below
        // covers a caret at zero with text after it.
        updateEditorEmpty(newSelEnd > 0)
        val view = host ?: return
        val hasSelection = newSelEnd > newSelStart
        if (view.suggestionStrip.actionMode) {
            // Never the assistant's doing any more -- see below. What is left of actionMode
            // (rejecting a suggestion, "Forget / Cancel") is dismissed the same way selecting
            // text dismisses anything else stale on the strip.
            view.suggestionStrip.clear()
        }
        if (!hasSelection) {
            // The caret moved. If our own edit moved it the composing region already agrees with
            // where it is, and re-deriving would be work for the same answer; if something else
            // moved it -- a tap into the middle of a sentence, an arrow key, a backspace out of
            // one word and into another -- then the word under the caret has changed and the
            // strip is describing a word the user has left. Re-deriving is what keeps it live.
            if (composingMatchesCaret(newSelEnd)) {
                requestSuggestions()
            } else {
                adoptWordAtCaret()
            }
            // Shift is derived from the text before the caret, so moving the caret is exactly
            // when it has to be looked at again.
            applyAutoShift()
        }
        // A selection used to turn the strip into three assistant buttons here. It no longer
        // does anything: the strip is corrections and predictions, never anything else -- the
        // assistant is reached through the draft box now, opened deliberately from the quick
        // action or (for a selection outside any field this keyboard is bound to) from the
        // system's own text-selection menu, never by the mere act of selecting text.
    }

    override fun onActionPicked(index: Int) {
        // actionMode used to be shared with the assistant's own actions; it is only ever the
        // "forget this word" question now, so a pending word is the only case there is.
        val forgetting = pendingForget ?: return
        pendingForget = null
        host?.suggestionStrip?.clear()
        if (index == 0) {
            forgetWord(forgetting)
        } else {
            requestSuggestions()
        }
    }

    override fun onCreateInputView(): View {
        // Built in code. LayoutInflater would parse XML and reflect to construct three views,
        // every time the keyboard is shown in a new editor.
        paints.update(effectiveTheme(), resources.displayMetrics, preferences.heightScale, this)
        val view = KeyboardHostView(this, paints, strings)
        applyPlacement(view, preferences)
        view.keyboard.listener = this
        view.keyboard.hapticEnabled = preferences.hapticFeedback
        view.keyboard.swipeEnabled = preferences.swipeEnabled
        view.keyboard.soundEnabled = preferences.keySound
        view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
        view.keyboard.setLayout(composedLayout(alphabeticLayout))
        view.suggestionStrip.listener = this
        view.suggestionStrip.visibleLimit = preferences.suggestionCount
        view.quickSettings.listener = this
        view.quickActions.listener = this
        view.clipboardPanel.listener = this
        view.emojiPanel.listener = EmojiPanelView.Listener { emoji -> onEmojiPicked(emoji) }
        view.emojiPanel.recents = preferences.emojiRecents
        applyQuickActions(view)
        view.onMoveToOtherSide = { moveKeyboardToOtherSide() }
        view.onResizeDrag = { height, width, offset -> previewResize(height, width, offset) }
        view.onResizeFinished = { commitResize() }
        view.onResizeExit = { endResize() }
        view.fullWidthBackground = effectiveTheme().fullWidthBackground
        view.onThemeChanged()
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
        engine.setLearningSpeed(
            KeyboardPreferences.learningSpeedFactor(preferences.learningSpeed),
        )
        engine.setCorrectionStrictness(preferences.correctionStrictness)
        engine.setLanguageLock(
            KeyboardPreferences.languageLockEvidence(preferences.languageLock),
            KeyboardPreferences.languageLockStrict(preferences.languageLock),
        )
        engine.setPhraseSuggestions(preferences.phraseSuggestions)
        if (privateMode) {
            learning.discard()
        }

        host?.let { view ->
            view.suggestionStrip.privateMode = privateMode
            view.suggestionStrip.clear()
            view.keyboard.hapticEnabled = preferences.hapticFeedback
            view.keyboard.swipeEnabled = preferences.swipeEnabled
        view.keyboard.soundEnabled = preferences.keySound
        view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
        }
        showPage(pageFor(info))
        shiftHeldByUser = false
        resetComposing()
        resetFieldHistory()
        applyAutoShift()

        host?.setClipboardPanelVisible(false)
        host?.setEmojiPanelVisible(false)
        registerClipboardListener()
        refreshClipboardChip()
        pushKeyGeometry()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Shown for one session. A clip that actually had a chip has had its chance to be
        // offered; keeping the offer alive across every field afterwards is what makes it
        // clutter rather than a convenience. shownClipSignature, not a fresh read of the
        // clipboard -- see its own doc comment for why the live clipboard can already be a clip
        // nobody has seen a chip for yet by the time this runs.
        if (preferences.clipboardSuggestionOnce && shownClipSignature != null) {
            withdrawnClip = shownClipSignature
        }
        unregisterClipboardListener()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // The session is over, so everything held in memory is written now rather than waiting
        // for a debounce that may never fire: the process can be killed the moment the keyboard
        // is hidden.
        flushLearning()
        engine.cancelPending()
        resetComposing()
        scope.launch(Dispatchers.IO) {
            if (preferences.clearClipboardOnClose) {
                // Everything unpinned, whether it was copied a second ago or an hour: the
                // setting is deliberately blunter than the retention timer beside it.
                DataGraph.clipboard.deleteUnpinned()
            }
            DataGraph.clipboard.purgeExpired()
        }
    }

    override fun onDestroy() {
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
    /**
     * Moves a narrowed keyboard to the opposite side, or across the middle when it is floating.
     *
     * The gesture behind the arrow in the gutter. One tap, reachable by the thumb that is
     * already on that side, for the case the settings screen answers badly: needing the
     * keyboard on the other side right now, with the hand that cannot reach the settings key.
     */
    private fun moveKeyboardToOtherSide() {
        updatePreferences { current ->
            when (current.positionMode) {
                KeyboardPreferences.MODE_ONE_HANDED_LEFT ->
                    current.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_RIGHT)
                KeyboardPreferences.MODE_ONE_HANDED_RIGHT ->
                    current.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_LEFT)
                KeyboardPreferences.MODE_FLOATING ->
                    // Floating has no side, so the arrow mirrors the offset instead.
                    current.copy(horizontalOffsetDp = -current.horizontalOffsetDp)
                else -> current
            }
        }
    }

    /**
     * Asks the system to blur what shows through beside a narrowed keyboard.
     *
     * Only when there is something to see through: a docked keyboard covers its whole window
     * and blurring behind it costs a compositor pass for a result nobody can see. The system
     * refuses outright on devices where cross-window blur is disabled, and asking is how you
     * find out -- there is no fallback worth having, so a refusal is simply no blur.
     */
    private fun applyBlur(settings: KeyboardPreferences) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return
        }
        val target = window?.window ?: return
        // Nothing shows through a background that reaches both edges, so blurring what is
        // behind it is a per-frame cost for an effect nobody can see.
        val wanted = settings.blurBehindKeyboard && !effectiveTheme().fullWidthBackground &&
            settings.positionMode != KeyboardPreferences.MODE_DOCKED
        val radius = if (wanted) {
            (resources.displayMetrics.density * BLUR_RADIUS_DP).toInt()
        } else {
            0
        }
        runCatching { target.setBackgroundBlurRadius(radius) }
    }

    private fun applyPlacement(view: KeyboardHostView, settings: KeyboardPreferences) {
        val density = resources.displayMetrics.density
        view.edgeArrows = settings.edgeArrows
        applyBlur(settings)
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
     * Moves the caret by [steps] characters, from a slide along the space bar.
     *
     * Through setSelection rather than by sending arrow keys: an editor that treats an arrow
     * key as navigation between fields would jump out of the text entirely, and the caret
     * position is something we can ask for and set exactly.
     */
    override fun onCursorNudge(steps: Int) {
        val connection = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            // Committing first, because moving the caret out of a composing region leaves the
            // editor holding an underline around text nobody is editing any more.
            finishComposing(connection)
        }
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val length = extracted.text?.length ?: return
        val target = (selectionEnd + steps).coerceIn(0, length)
        if (target == selectionEnd) {
            return
        }
        selectionStart = target
        selectionEnd = target
        connection.setSelection(target, target)
    }

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
            val contextWord = previousWord1
            connection.beginBatchEdit()
            val finished = finishComposing(connection)
            connection.endBatchEdit()
            if (finished != null) {
                recordLearned(finished, contextWord)
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
        checkpointField()
    }

    override fun onKey(code: Int, keyIndex: Int) {
        // Backspace is the one key that gets to look at the pending correction; every other key
        // settles it. Doing this here rather than in each handler is what keeps a correction
        // from surviving three words and then being undone by a backspace that meant something
        // else entirely.
        if (code != KeyCodes.DELETE) {
            confirmPendingCorrection()
        }
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
            KeyCodes.SETTINGS -> toggleQuickSettings()
            KeyCodes.EMOJI -> host?.setEmojiPanelVisible(host?.emojiPanelVisible != true)
            else -> if (KeyCodes.isCharacter(code)) handleCharacter(code)
        }
    }

    /**
     * Holding a key that has nothing else to offer.
     *
     * Three keys reach the quick panel: the globe, the settings key, and enter. There is a
     * `settings` key code in the layout format and no layout uses it -- a whole key spent on
     * settings is a key not spent on typing -- so in practice it is the globe, which is already
     * the key about "which keyboard is this" and where people look, and enter, which is the
     * largest key on the board and the easiest to hold without looking.
     */
    override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean {
        // Holding the space bar cycles this keyboard's layouts. That is what the globe key
        // did, and the globe is off by default now that the panel is on enter -- so the
        // function moves to the key that is always there and impossible to miss. A press that
        // slides instead of holding still moves the caret: the drag disarms the hold.
        if (code == ' '.code) {
            switchLanguage()
            return true
        }
        // Holding backspace takes the whole word before the cursor, not one more character than
        // a tap would have. The correction gets first refusal, the same as the quick actions
        // bar's own undo button does for the identical situation: a hold that lands right after
        // an autocorrect reads as "put back what I typed", not "eat a word I did not mean to."
        if (code == KeyCodes.DELETE) {
            val connection = currentInputConnection
            if (connection != null && !revertCorrection(connection)) {
                deleteWordBeforeCursor(connection)
            }
            refreshContextFromEditor()
            requestSuggestions()
            return true
        }
        // Enter, the globe and the settings key all open the same panel. Enter used to open
        // the settings application instead, which meant the one shortcut people find by
        // accident threw them out of the field they were typing in; the panel has the "All
        // settings" line for the times they wanted the application after all.
        if (code != KeyCodes.ENTER && code != KeyCodes.LANGUAGE && code != KeyCodes.SETTINGS) {
            return false
        }
        toggleQuickSettings()
        return true
    }

    private fun handleCharacter(code: Int) {
        val connection = currentInputConnection ?: return
        val shifted = if (shiftState != ShiftState.OFF) {
            Character.toUpperCase(code)
        } else {
            code
        }
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        shiftHeldByUser = false

        if (isWordCharacter(shifted)) {
            pendingAutoSpace = false
            composing.appendCodePoint(shifted)
            // One IPC for the whole update. setComposingText replaces the composing region, so
            // the editor is told the new word rather than the character that changed.
            connection.setComposingText(composing, 1)
            requestSuggestions()
            return
        }

        // A delimiter ends the word. By default what was typed is committed as typed -- no
        // silent replacement with the leading suggestion. A keyboard that rewrites what you
        // wrote because it has a better idea is the failure mode this project was written
        // against, so choosing a suggestion is an act rather than a default.
        //
        // `autoCorrectOnSpace` turns that default off for people who want the other trade, and
        // it is only defensible together with the revert below: the objection to autocorrect is
        // really an objection to a correction that costs more to undo than it saved.
        val typed = composing.toString()
        val correction = correctionFor(typed)
        // Captured before anything commits: finishComposing and the correction branch both
        // advance previousWord1 to the word being written now.
        val contextWord = previousWord1

        // The space we just added ourselves, typed again out of habit. Swallowed, and the
        // window for the two-spaces rule is not opened by it either.
        if (shifted == ' '.code && typed.isEmpty() && pendingAutoSpace) {
            pendingAutoSpace = false
            return
        }

        // Two spaces in quick succession end the sentence instead. Only after a word
        // character, so it never fires on an empty line or after punctuation that already
        // ended one, and only inside the window -- two spaces a minute apart are two spaces.
        if (shifted == ' '.code && typed.isEmpty() && preferences.doubleSpacePeriod &&
            System.currentTimeMillis() - lastSpaceAt < DOUBLE_SPACE_MILLIS &&
            endsWithWordCharacterBeforeSpace(connection)
        ) {
            connection.beginBatchEdit()
            connection.deleteSurroundingText(1, 0)
            connection.commitText(". ", 1)
            connection.endBatchEdit()
            lastSpaceAt = 0L
            pendingSpacePeriod = true
            pendingCorrection = null
            checkpointField()
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (shifted == ' '.code) {
            lastSpaceAt = System.currentTimeMillis()
        }
        pendingSpacePeriod = false

        connection.beginBatchEdit()
        // "word ." is not something anyone means. A space before a sentence mark is taken back
        // before the mark lands, which is what makes adding one after a mark safe: the pair of
        // settings is one idea, and either half alone would be worse than neither.
        if (typed.isEmpty() && preferences.removeSpaceBeforePunctuation &&
            isTightPunctuation(shifted)
        ) {
            val before = connection.getTextBeforeCursor(1, 0)
            if (before != null && before.length == 1 && before[0] == ' ') {
                connection.deleteSurroundingText(1, 0)
            }
        }
        val added = spaceAfter(shifted)
        pendingAutoSpace = added.isNotEmpty()
        val delimiter = String(Character.toChars(shifted)) + added
        if (correction != null) {
            // commitText replaces the composing region, which is the whole point: the letters
            // that are on screen become the correction in one edit. Calling finishComposingText
            // first would *commit* them and leave the correction appended to what was typed,
            // which is what the first version of this did.
            composing.setLength(0)
            connection.commitText(correction + delimiter, 1)
        } else {
            finishComposing(connection)
            connection.commitText(delimiter, 1)
        }
        connection.endBatchEdit()

        if (correction != null) {
            previousWord2 = previousWord1
            previousWord1 = correction
            // Learning waits until the correction survives the next keystroke. Recording it
            // here would teach the personal dictionary a word the user is about to reject, and
            // the whole point of the revert is that rejecting it is expected.
            pendingCorrection = PendingCorrection(typed, correction, delimiter, contextWord)
        } else {
            if (typed.isNotEmpty()) {
                recordLearned(typed, contextWord)
            }
            pendingCorrection = null
        }
        checkpointField()
        shiftAfterDelimiter(shifted)
        requestSuggestions()
    }

    /**
     * Accepts the applied correction: it survived, so it is what the user meant.
     *
     * This is where the correction is learned, rather than at the moment it was applied. A
     * correction the user is about to reject should not teach the personal dictionary anything,
     * and one keystroke of patience is what tells the two cases apart.
     */
    private fun confirmPendingCorrection() {
        val pending = pendingCorrection ?: return
        pendingCorrection = null
        recordLearned(pending.corrected, pending.contextWord)
    }

    /**
     * The correction a delimiter should apply, or null to commit what was typed.
     *
     * The setting is checked here and the rest of the decision is [AutoCorrection]'s, which is
     * where it can be tested without an editor, an input connection and a dictionary.
     */
    private fun correctionFor(typed: String): String? {
        if (!preferences.autoCorrectOnSpace) {
            return null
        }
        return AutoCorrection.correctionFor(
            typed, topSuggestion, knownQuery, preferences.minCorrectionLength,
        )
    }

    /**
     * Puts back exactly what was typed, if the last thing that happened was a correction.
     *
     * Deletes the correction and its delimiter and writes the original in their place, in one
     * batch edit so the editor sees a single change rather than a deletion followed by a
     * reinsertion. Returns false when there is nothing to revert, and the caller then does what
     * backspace normally does.
     */
    private fun revertCorrection(connection: InputConnection): Boolean {
        val pending = pendingCorrection ?: return false
        pendingCorrection = null
        if (!preferences.revertCorrectionOnBackspace) {
            // Backspace is an ordinary backspace, so this is the correction being accepted the
            // same way any other key would accept it. Dropping it unlearned instead would make
            // the setting quietly change what the dictionary remembers.
            recordLearned(pending.corrected, pending.contextWord)
            return false
        }
        val committed = pending.corrected + pending.delimiter
        val before = connection.getTextBeforeCursor(committed.length, 0)
        if (before == null || before.toString() != committed) {
            // The cursor moved, or something else edited the field. Reverting blind would
            // delete text nobody asked us to touch, so the correction stands and is accepted.
            recordLearned(pending.corrected, pending.contextWord)
            return false
        }
        connection.beginBatchEdit()
        connection.deleteSurroundingText(committed.length, 0)
        connection.commitText(pending.typed + pending.delimiter, 1)
        connection.endBatchEdit()
        previousWord1 = pending.typed
        // Reverting is the user asserting that what they typed is a word, which is exactly the
        // signal the personal dictionary exists to record.
        recordLearned(pending.typed, pending.contextWord)
        // And the word they rejected is unlearned. A correction is only offered that strongly
        // because something taught it -- often this dictionary, from an earlier typo confirmed
        // by accident -- and rejecting it is the clearest statement available that it should
        // not have been. Harmless when the word came from the language pack instead: there is
        // then nothing personal to forget, and the pack is not touched.
        forgetWord(pending.corrected)
        refreshContextFromEditor()
        return true
    }

    /**
     * Records the field's current text as a step in [fieldHistory], if it actually changed.
     *
     * Read fresh from the editor every time rather than trusting whatever this class last
     * committed: a mismatch would mean something outside this keyboard changed the field, which
     * is exactly the class of thing [revertCorrection] already guards against for the one
     * keystroke it owns. The dedup check is what keeps a call from a site that turned out not to
     * have changed anything from polluting the history with a version equal to the one before it.
     */
    private fun checkpointField() {
        val connection = currentInputConnection ?: return
        val text = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        )?.text?.toString() ?: return
        if (text == fieldHistory.current()) {
            return
        }
        fieldHistory.addResult(text)
    }

    /**
     * Starts a fresh undo/redo history for a field just opened, seeded with what was already in
     * it.
     *
     * Without this, undoing the very first word typed this session would have nowhere to go
     * back to -- there would be no "before" on record. Called once, from [onStartInputView]
     * only: [resetComposing] is also called from actions that only move the cursor
     * ([onQuickAction]'s CURSOR_START/CURSOR_END, [selectWordAtCursor]), and none of those are a
     * new field to seed a history for.
     */
    private fun resetFieldHistory() {
        fieldHistory.clear()
        checkpointField()
    }

    /**
     * Puts the field back to a version from [fieldHistory], touching only what differs from
     * what is actually there right now.
     *
     * The live text is read fresh rather than trusting a cached copy, the same defensive choice
     * [checkpointField] makes: whatever changed the field since the last step, comparing against
     * what is on screen is what keeps this from ever landing on the wrong text -- only ever on a
     * larger edit than strictly necessary. [FieldRestore.diff] is what trims that edit down to
     * the part that actually differs, so a document that changed in one place doesn't get
     * rewritten end to end for it.
     */
    private fun restoreFieldVersion(target: String?) {
        if (target == null) {
            return
        }
        val connection = currentInputConnection ?: return
        val current = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        )?.text?.toString() ?: return
        if (current == target) {
            return
        }
        val span = FieldRestore.diff(current, target)
        connection.beginBatchEdit()
        finishComposing(connection)
        val boundary = span.deleteFrom + span.deleteCount
        connection.setSelection(boundary, boundary)
        if (span.deleteCount > 0) {
            connection.deleteSurroundingText(span.deleteCount, 0)
        }
        if (span.insert.isNotEmpty()) {
            connection.commitText(span.insert, 1)
        }
        connection.endBatchEdit()
        // Not resetComposing(): that also drops previousWord1/2 and clears the strip in ways
        // that belong to a genuinely new field, not to stepping through this one's own history.
        pendingCorrection = null
        refreshContextFromEditor()
        requestSuggestions()
    }

    private fun handleDelete() {
        val connection = currentInputConnection ?: return
        val hasSelection = selectionEnd > selectionStart
        // A selection is what backspace deletes, all of it, before anything else is considered.
        // deleteSurroundingText would not do it: it deletes *around* the selection and leaves
        // the selected text exactly where it was, which reads as the key having done nothing.
        if (hasSelection) {
            composing.setLength(0)
            pendingCorrection = null
            connection.commitText("", 1)
            refreshContextFromEditor()
            requestSuggestions()
            return
        }
        if (pendingSpacePeriod) {
            // Undone the same way a correction is: the objection to a substitution is always
            // that undoing it costs more than not having it.
            pendingSpacePeriod = false
            val before = connection.getTextBeforeCursor(2, 0)
            if (before != null && before.toString() == ". ") {
                connection.beginBatchEdit()
                connection.deleteSurroundingText(2, 0)
                connection.commitText("  ", 1)
                connection.endBatchEdit()
                refreshContextFromEditor()
                applyAutoShift()
                requestSuggestions()
                return
            }
        }
        if (revertCorrection(connection)) {
            return
        }
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
        val contextWord = previousWord1
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
            checkpointField()
        }
        if (finished != null) {
            recordLearned(finished, contextWord)
        }
        requestSuggestions()
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
        shiftState = when {
            shiftState == ShiftState.LOCKED -> ShiftState.OFF
            shiftState == ShiftState.ON && now - lastShiftPressAt < DOUBLE_TAP_MILLIS -> ShiftState.LOCKED
            shiftState == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftPressAt = now
        // Pressed deliberately, so the automatic state stops having an opinion until the next
        // character consumes it.
        shiftHeldByUser = shiftState != ShiftState.OFF
        autoLockedShift = false
        host?.keyboard?.shiftState = shiftState
    }

    /**
     * Puts a page on screen, applying the number-row setting to the alphabetic one.
     *
     * The number row is composed rather than authored into a second copy of every layout: two
     * assets per language that differ by one row is two assets to keep in step, and they would
     * drift the first time a key moved.
     */
    /**
     * Applies the layout settings that compose rather than replace: the number row, the emoji
     * key and the globe key.
     *
     * One place, because the pages are set from four of them and a page that forgot one was how
     * the number row used to disappear when the symbols page came back.
     */
    private fun composedLayout(layout: KeyboardLayout): KeyboardLayout {
        var result = layout
        if (!preferences.emojiKey) {
            result = result.withoutEmojiKey()
        }
        if (!preferences.languageKey) {
            result = result.withoutLanguageKey()
        }
        if (preferences.numberRow) {
            result = result.withNumberRow()
        }
        return result
    }

    private fun showPage(next: Int) {
        page = next
        // The symbol pages carry an emoji key too, so they compose the same way. Only the
        // numeric keypad is left alone: it has neither a space bar nor room for one.
        val layout = when (next) {
            PAGE_SYMBOLS -> composedLayout(symbolsLayout)
            PAGE_SYMBOLS_SHIFT -> composedLayout(symbolsShiftLayout)
            PAGE_NUMPAD -> numpadLayout
            else -> composedLayout(alphabeticLayout)
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

    // ---- the panel on the keyboard ------------------------------------------------------------

    /**
     * Opens the quick panel, or closes it if it is already open.
     *
     * The settings key opens this rather than the application, because the settings people reach
     * for while typing are the ones about the keyboard being in the way -- and judging that
     * means looking at the keyboard, in the app where it felt wrong. The panel's last row opens
     * the full settings for everything else.
     */
    private fun toggleQuickSettings() {
        val view = host ?: return
        val opening = !view.quickSettingsVisible
        if (opening) {
            pushQuickSettingsState(view)
        }
        view.showQuickSettings(opening)
    }

    private fun pushQuickSettingsState(view: KeyboardHostView) {
        view.quickSettings.setState(
            placement = when (preferences.positionMode) {
                KeyboardPreferences.MODE_ONE_HANDED_LEFT -> QuickSettingsView.Placement.LEFT
                KeyboardPreferences.MODE_ONE_HANDED_RIGHT -> QuickSettingsView.Placement.RIGHT
                KeyboardPreferences.MODE_FLOATING -> QuickSettingsView.Placement.FLOATING
                else -> QuickSettingsView.Placement.DOCKED
            },
            numberRow = preferences.numberRow,
        )
    }

    /**
     * The panel writes to the same store the settings application writes to.
     *
     * Not to a local copy, and not straight to the view: the value goes to the DataStore, the
     * preferences flow re-emits, and the keyboard resizes through the path that already existed.
     * That is why the panel and the settings screen cannot disagree.
     */
    private fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { DataGraph.themes.updatePreferences(transform) }
    }

    override fun onStartResize() {
        val view = host ?: return
        view.showQuickSettings(false)
        draggedHeight = preferences.heightScale
        draggedWidth = preferences.widthScale
        view.heightScaleForDrag = draggedHeight
        view.resizing = true
    }

    /** Leaves resize mode, writing whatever the last drag left. */
    private fun endResize() {
        val view = host ?: return
        view.resizing = false
        commitResize()
    }

    override fun onPlacementChanged(placement: QuickSettingsView.Placement) {
        val mode = when (placement) {
            QuickSettingsView.Placement.LEFT -> KeyboardPreferences.MODE_ONE_HANDED_LEFT
            QuickSettingsView.Placement.RIGHT -> KeyboardPreferences.MODE_ONE_HANDED_RIGHT
            QuickSettingsView.Placement.FLOATING -> KeyboardPreferences.MODE_FLOATING
            QuickSettingsView.Placement.DOCKED -> KeyboardPreferences.MODE_DOCKED
        }
        // withPositionMode rather than copy: leaving the dock for the first time also narrows the
        // keyboard, or the mode changes nothing visible and reads as broken.
        updatePreferences { it.withPositionMode(mode) }
    }

    override fun onNumberRowChanged(enabled: Boolean) =
        updatePreferences { it.copy(numberRow = enabled) }

    override fun onOpenFullSettings() {
        host?.showQuickSettings(false)
        openSettings()
    }

    override fun onCloseQuickSettings() {
        host?.showQuickSettings(false)
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
        // Read before the commit, for the same reason as everywhere else: what is being learned
        // is that this word followed the one already in the text, not that it followed itself.
        val contextWord = previousWord1
        connection.beginBatchEdit()
        // While actively typing, commitText below replaces the composing region on its own --
        // that is what a composing region is for. But the strip also offers suggestions for a
        // word the cursor merely sits in, adopted by adoptWordAtCaret rather than composed
        // (deliberately without a composing region -- see its own doc comment), and there
        // commitText would only insert beside that word rather than replace it. lastQuery is
        // "what the strip is about" either way, so composing being empty while lastQuery is not
        // is exactly that case; the text immediately before the cursor is checked against it
        // first, the same guard revertCorrection uses, so a stale lastQuery deletes nothing
        // rather than deleting whatever happens to be there.
        if (composing.isEmpty() && lastQuery.isNotEmpty()) {
            val before = connection.getTextBeforeCursor(lastQuery.length, 0)
            if (before != null && before.toString() == lastQuery) {
                connection.deleteSurroundingText(lastQuery.length, 0)
            }
        }
        composing.setLength(0)
        composing.append(word)
        connection.commitText("$word ", 1)
        connection.endBatchEdit()

        // Choosing a candidate that was not already the top one is the learning signal. This is
        // where personalisation happens: a count goes up, and nothing is retrained.
        // A two-word suggestion is two words confirmed, not one long one. Learning it whole
        // would put "vreau sa" in the personal dictionary as a single entry, which would then be
        // offered as a completion of "vr" and never match anything the user typed.
        val words = word.split(' ').filter { it.isNotEmpty() }
        var previous = contextWord
        for (part in words) {
            recordLearned(part, previous)
            previous = part
        }
        // The picked word is now the context for whatever comes next. Nothing else sets this on
        // this path -- onUpdateSelection only refreshes it when the cursor moves on its own --
        // so without it the next-word prediction after picking a suggestion would be made
        // against the word before the one the user just chose.
        previousWord2 = if (words.size >= 2) words[words.size - 2] else previousWord1
        previousWord1 = words.lastOrNull() ?: word
        composing.setLength(0)
        host?.suggestionStrip?.clear()
        requestSuggestions()
    }

    /**
     * A suggestion held down: offer to forget it.
     *
     * The strip becomes a question with two answers rather than opening a dialog, because a
     * dialog over a keyboard covers the text the decision is about. The word is not forgotten
     * here; holding only asks.
     */
    override fun onSuggestionLongPressed(index: Int, word: String) {
        if (privateMode || word.isEmpty()) {
            return
        }
        pendingForget = word
        host?.suggestionStrip?.setActions(arrayOf(strings.getString(Keys.ASSISTANT_FORGET, word), strings[Keys.ASSISTANT_CANCEL]), 2)
    }

    /**
     * Forgets a word and cuts the chains it was part of.
     *
     * Both halves, and the second is the point. Deleting the word alone would leave the pairs
     * that name it, so it would go on being predicted after the word before it -- forgotten from
     * the dictionary and still suggested, which reads as the button not working. Deleting the
     * pairs on both sides cuts the chain at that word: what came before it still leads to it no
     * longer, and what came after is no longer reached through it. The head of the chain is
     * untouched, because it is evidence about other words.
     */
    private fun forgetWord(word: String) {
        scope.launch {
            val dictionary = DataGraph.dictionary
            // The repository suspends on its own dispatcher; the reloads only post to the
            // prediction thread, so there is nothing here to move off the main thread.
            dictionary.forget(word)
            loadPersonalModel(dictionary)
            requestSuggestions()
        }
    }

    override fun onSuggestions(words: Array<String?>, count: Int, knownWord: String, query: String) {
        // This answer was asked for on an earlier keystroke and lost the race against a later
        // one: the engine has one thread and posts its answer back rather than blocking, so an
        // answer computed for "Ac" can still arrive after the strip -- and lastQuery -- have
        // already moved on to "Acm". Rendering it here would border a word for "Ac" over text
        // that now reads "Acm", or worse, let correctionFor below compare "Acm" against
        // knownWord/topSuggestion that are actually about "Ac", the exact way a border ended up
        // drawn on a correction that revertCorrection's own real-word guard would then refuse to
        // apply. Dropping it leaves whatever the last genuinely current answer already drew.
        if (query != lastQuery) {
            return
        }
        knownQuery = knownWord
        // Settled here as well as in onUpdateSelection: an editor that does not report selection
        // changes -- and some do not, for their own reasons -- would otherwise leave the idle
        // line standing over a field the user has already written in.
        if (composing.isNotEmpty()) {
            host?.suggestionStrip?.editorEmpty = false
        }
        // Kept because the delimiter path needs it and the strip is a view, not a model. One
        // reference assignment per suggestion round, off the hot path.
        //
        // Read before the row is rearranged, and before the case-matching just below, so it
        // stays the engine's own lower-case answer whatever the row ends up looking like:
        // AutoCorrection.correctionFor already applies matchCase to this on its own, and doing
        // it here first would just be the same rule read twice for one decision.
        topSuggestion = if (count > 0) words[0] else null
        // The rest of the row is not a decision the way the one correction above is -- it is
        // what the strip shows, and showing "welcome" one slot over from a correction that
        // already reads "Welcome" is the same word told two different ways for a difference the
        // user never made. The dictionaries only ever store the lower-case spelling, so every
        // candidate needs this, not only the one AutoCorrection separately decides to apply.
        //
        // Two different sources of truth for it, depending on whether there is a typed prefix
        // to read: mid-word, the prefix already answers the question definitively -- "WELCO"
        // means the rest is "WELCOME", even though shiftState itself auto-released back to OFF
        // after the first letter and no longer says so. Nothing typed yet is the opposite case:
        // there is no prefix to match, so these are the keyboard's own next-word predictions,
        // and what they should look like is exactly what shiftState says the next letter typed
        // right now would come out as.
        for (index in 0 until count) {
            words[index] = words[index]?.let { word ->
                if (lastQuery.isNotEmpty()) {
                    AutoCorrection.matchCase(lastQuery, word)
                } else {
                    when (shiftState) {
                        ShiftState.LOCKED -> word.uppercase()
                        ShiftState.ON -> word.replaceFirstChar { it.uppercaseChar() }
                        else -> word
                    }
                }
            }
        }
        val shown = if (preferences.showSuggestionStrip) {
            suggestionRow.arrange(
                words, count, lastQuery, preferences.suggestionCount,
                correcting = correctionFor(lastQuery) != null,
            )
        } else {
            count
        }
        host?.suggestionStrip?.let { strip ->
            strip.typedIndex = suggestionRow.typedIndex
            strip.appliedIndex = suggestionRow.appliedIndex
            strip.setSuggestions(words, shown)
        }
    }

    /** Where the typed word and the word a delimiter would apply end up on the strip. */
    private val suggestionRow = SuggestionRow()

    /**
     * The last query the dictionaries recognised, or empty.
     *
     * Stored as the word rather than as a flag, so a stale answer cannot be read as being about
     * the word now being typed: the check is "is this the word we were told about", not "was
     * something known recently".
     */
    private var knownQuery: String = ""

    /**
     * The word the engine was last asked about.
     *
     * Not the composing region: suggestions are also asked for when the caret moves into a word
     * nobody is composing, and the word on screen is the one the row is about either way. An
     * editor that inserts text without going through our keys -- a paste, an automation -- also
     * leaves the composing region empty while the strip is very much describing a word.
     */
    private var lastQuery: String = ""

    private fun requestSuggestions() {
        if (!preferences.showSuggestionStrip) {
            return
        }
        lastQuery = composing.toString()
        engine.requestSuggestions(lastQuery, previousWord1, previousWord2)
    }

    /**
     * Tells the strip whether the field has anything in it.
     *
     * [hasTextBeforeCaret] is what the selection callback knows for free. A caret sitting at
     * zero says nothing about text after it, so that one case is settled with a read -- rare,
     * and only when the cheap answer is "empty".
     */
    private fun updateEditorEmpty(hasTextBeforeCaret: Boolean) {
        val strip = host?.suggestionStrip ?: return
        strip.editorEmpty = if (hasTextBeforeCaret) {
            false
        } else {
            currentInputConnection?.getTextAfterCursor(1, 0).isNullOrEmpty()
        }
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
        pendingCorrection = null
        pendingForget = null
        composing.setLength(0)
        currentInputConnection?.finishComposingText()
        refreshContextFromEditor()
        host?.suggestionStrip?.clear()
        // Cleared and then asked again rather than left blank: on an empty field the engine
        // answers with what sentences in this language actually open with, which is a better
        // use of the row than an instruction to start typing.
        requestSuggestions()
    }

    /**
     * Reads the two words before the cursor back out of the editor.
     *
     * Needed after a deletion or a cursor move, where our own idea of the context is no longer
     * what is on screen. Bounded to a short window: this is an IPC, and the n-gram model only
     * looks two words back anyway.
     */
    /**
     * True when the composing region is the run of letters immediately before [caret].
     *
     * Deliberately the application's connection and not the target's: this answers a question
     * asked by onUpdateSelection, which is the platform reporting where the application's caret
     * went. It is never asked about the draft box, whose caret the platform knows nothing about.
     */
    private fun composingMatchesCaret(caret: Int): Boolean {
        if (composing.isEmpty()) {
            return false
        }
        val connection = currentInputConnection ?: return false
        val before = connection.getTextBeforeCursor(composing.length, 0) ?: return false
        return before.length == composing.length && before.contentEquals(composing)
    }

    /**
     * Makes the word the caret is sitting in the one the strip is about.
     *
     * Deliberately does *not* set a composing region on it. Marking text the user merely moved
     * into would underline it and put it one keystroke away from being replaced wholesale, which
     * is a surprise for someone who only wanted to look. The strip offers; nothing is committed
     * until a chip is tapped.
     */
    private fun adoptWordAtCaret() {
        composing.setLength(0)
        // The pending correction is deliberately *not* cleared here.
        //
        // Committing a correction ends the composing region, so the selection change that
        // follows our own commit lands in this branch -- and clearing it here meant the very
        // next backspace had nothing to undo, which is the entire feature. Nothing is lost by
        // keeping it: revertCorrection checks that the text immediately before the cursor is
        // still exactly what it committed, and declines when the caret has really moved.
        currentInputConnection?.finishComposingText()

        val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            previousWord1 = null
            previousWord2 = null
            lastQuery = ""
            engine.requestSuggestions("", null, null)
            return
        }
        // One read, split once. The run touching the caret is the word being asked about; the
        // words before it are its context. Splitting the whole window and then deciding which
        // part is which is cheaper than two getTextBeforeCursor calls, and it cannot disagree
        // with itself the way two reads at two moments can.
        val words = before.split(*WORD_SEPARATORS).filter { it.isNotEmpty() }
        val caretInsideWord = isWordCharacter(before[before.length - 1].code)
        val partial = if (caretInsideWord) words.lastOrNull().orEmpty() else ""
        val contextEnd = if (caretInsideWord) words.size - 1 else words.size
        previousWord1 = words.getOrNull(contextEnd - 1)
        previousWord2 = words.getOrNull(contextEnd - 2)
        lastQuery = partial
        engine.requestSuggestions(partial, previousWord1, previousWord2)
    }

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

    /** True when what precedes the single trailing space is a word character. */
    private fun endsWithWordCharacterBeforeSpace(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0) ?: return false
        return before.length == 2 && before[1] == ' ' && isWordCharacter(before[0].code)
    }

    /**
     * Marks that close up against the word before them, so a space in front of one is a typo.
     *
     * Not every delimiter: a dash or an opening bracket is often preceded by a space on
     * purpose, and taking it away would be the keyboard rewriting rather than tidying.
     */
    private fun isTightPunctuation(code: Int): Boolean =
        code == '.'.code || code == ','.code || code == '!'.code || code == '?'.code ||
            code == ';'.code || code == ':'.code

    /** The space that follows a sentence mark, or nothing at all. */
    private fun spaceAfter(code: Int): String {
        if (!preferences.spaceAfterPunctuation || !isTightPunctuation(code)) {
            return ""
        }
        // Not before something that is already a space, and not at the very end of a field the
        // user may be about to leave -- an editor that trims trailing whitespace would then
        // show the cursor jumping back on its own.
        val after = currentInputConnection?.getTextAfterCursor(1, 0)
        return if (after != null && after.isNotEmpty() && after[0] == ' ') "" else " "
    }

    private fun shiftAfterDelimiter(code: Int) {
        if (shiftState == ShiftState.LOCKED || shiftHeldByUser) {
            return
        }
        applyAutoShift()
    }

    /**
     * Sets shift from what the editor asked for and what is already written.
     *
     * Derived rather than remembered. The previous version set the state when the field opened
     * and after a delimiter it had typed itself, which meant shift never came back after
     * deleting to the start of a field, moving the caret there, or pasting -- and never came on
     * at all in a field that asks for capitals on every word rather than every sentence.
     *
     * A shift the user pressed is left alone. Deciding for them immediately after they decided
     * for themselves is the one thing worse than not deciding at all.
     */
    private fun applyAutoShift() {
        if (shiftState == ShiftState.LOCKED && !autoLockedShift) {
            return
        }
        if (shiftHeldByUser) {
            return
        }
        val wanted = autoShiftState()
        autoLockedShift = wanted == ShiftState.LOCKED
        if (shiftState != wanted) {
            shiftState = wanted
            host?.keyboard?.shiftState = shiftState
        }
    }

    /**
     * What shift should be here, from the field's request and the text before the cursor.
     *
     * The decision itself lives in [AutoShift], pure and tested on its own; this is the thin
     * Android-facing half, reading the current target and asking the platform's own
     * [InputConnection.getCursorCapsMode] rather than walking the text before the cursor by
     * hand -- the same computation the framework and every other IME already do, correctly
     * handling word/sentence boundaries and an empty field without this class re-deriving them.
     */
    private fun autoShiftState(): Int {
        val info = currentInputEditorInfo ?: return ShiftState.OFF
        return AutoShift.stateFor(
            autoCapitaliseEnabled = preferences.autoCapitalise,
            inputType = info.inputType,
            composingIsEmpty = composing.isEmpty(),
        ) {
            currentInputConnection?.getCursorCapsMode(info.inputType) ?: info.initialCapsMode
        }
    }

    private fun isWordCharacter(code: Int): Boolean =
        Character.isLetter(code) || code == '\''.code || code == '-'.code

    // ---- learning -----------------------------------------------------------------------------------

    /**
     * Records a confirmed word, and the pair it makes with the word before it.
     *
     * [contextWord] is passed rather than read from [previousWord1] because by the time a caller
     * gets here that field has usually already been advanced to *this* word: `finishComposing`
     * sets it as part of ending the composing region. Reading it here produced a pair of a word
     * with itself, which the pair store rejects, so nothing was ever learned and the feature
     * looked like it did not work at all. It has to be captured before the commit.
     */
    private fun recordLearned(word: String, contextWord: String?) {
        if (!learning.enabled || word.length < MIN_LEARNED_LENGTH) {
            return
        }
        // The layout being typed on, not the system input-method subtype.
        //
        // The subtype is one tag declared in method.xml, and this keyboard's premise is that
        // several languages are active at once with no switching between them -- so the subtype
        // said "en-US" for a Romanian word typed on a Romanian layout, and the personal
        // dictionary displayed that. The layout is at least something the user chose and can
        // see. It is still not a claim about which language the word belongs to: nothing here
        // can know that for a word that was typed rather than picked from a suggestion, which
        // is why the settings screen says "typed on" rather than naming a language.
        val locale = alphabeticLayout.languageTag
        val now = System.currentTimeMillis()
        contextWord?.let { learning.recordPair(it, word, now) }
        // The triple uses the word before the context word, which the service still holds:
        // recordLearned is called before previousWord2 is advanced.
        if (contextWord != null && previousWord2 != null) {
            learning.recordTriple(previousWord2!!, contextWord, word, now)
        }
        if (learning.record(word, locale, now)) {
            engine.learn(
                listOf(
                    com.borderkeys.data.dao.LearnedWord(word, locale, 1, now),
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
        val pairs = learning.drainPairs()
        val triples = learning.drainTriples()
        if (updates.isEmpty() && pairs.isEmpty() && triples.isEmpty()) {
            return
        }
        val snapshotPath = File(filesDir, USER_MODEL_SNAPSHOT).absolutePath
        scope.launch(Dispatchers.IO) {
            DataGraph.dictionary.applyLearned(updates)
            DataGraph.dictionary.applyLearnedBigrams(pairs)
            DataGraph.dictionary.applyLearnedTrigrams(triples)
            maybeDecayPersonalDictionary()
            engine.snapshotUserModel(snapshotPath)
        }
    }

    /**
     * Runs [DictionaryRepository.decayStaleEntries], at most once a day.
     *
     * This is "snapshot" in [com.borderkeys.data.PersonalWordDecay]'s sense: it is what actually
     * shrinks a stale count on disk, rather than only correcting for it on the way into the
     * native model ([loadPersonalModel], "restore"). [flushLearning] runs on a four-second
     * debounce while the user is actively typing, and the sweep's own `WHERE lastUsedAt <
     * :cutoff` already makes it safe to run repeatedly -- a row it just touched will not be due
     * again for another ninety days -- but there is no reason to scan the whole table that
     * often, so a one-line marker file throttles it to once a day instead.
     */
    private suspend fun maybeDecayPersonalDictionary() {
        val prefs = getSharedPreferences(DECAY_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSweep = prefs.getLong(DECAY_LAST_SWEEP_AT, 0L)
        if (now - lastSweep < DECAY_SWEEP_INTERVAL_MILLIS) {
            return
        }
        DataGraph.dictionary.decayStaleEntries(now)
        prefs.edit().putLong(DECAY_LAST_SWEEP_AT, now).apply()
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

    /**
     * Replaces bundled packs the running build can no longer read.
     *
     * The pack format is versioned and a version this build does not know is refused rather
     * than misread -- correct, and it leaves the keyboard with no dictionary at all until
     * someone works out that the fix is to add the language again. For a pack that came from
     * inside the application there is nothing to work out: the current one is in assets, so it
     * is copied over the old one and the entry is updated in place.
     *
     * Only for bundled packs. A file someone imported themselves cannot be regenerated here,
     * and silently replacing it with a bundled dictionary of the same language would be worse
     * than the refusal.
     */
    private suspend fun reinstallOutdatedBundledPacks(
        repository: com.borderkeys.data.LanguagePackRepository,
    ) {
        // Nothing in here may take the service down with it. This runs on the path that builds
        // the keyboard, and a keyboard that fails to start is worse than any dictionary
        // problem it was trying to repair -- which is exactly what happened when the first
        // version of this used insert on a row that already existed.
        runCatching { repairBundledPacks(repository) }
            .onFailure { android.util.Log.w("BorderKeys", "pack repair failed", it) }
    }

    private suspend fun repairBundledPacks(
        repository: com.borderkeys.data.LanguagePackRepository,
    ) {
        for (entry in repository.allPacks()) {
            val bundled = BundledDictionaries.ALL.firstOrNull { it.tag == entry.tag } ?: continue
            val file = repository.fileFor(entry)
            // Anything the running build cannot read, for any reason: a format it does not
            // know, a file that no longer matches its recorded hash, a file that is gone. All
            // three end the same way for a pack that came from inside the application -- the
            // current one is in assets, so it is copied over whatever is there.
            val stale = !file.isFile ||
                LanguagePackInspector.inspect(file) !is LanguagePackInspector.Result.Valid ||
                runCatching { LanguagePackRepository.sha256Of(file) }.getOrNull() != entry.sha256
            if (!stale) {
                continue
            }
            val staged = runCatching {
                BundledDictionaries.open(assets, bundled).use { stream ->
                    repository.stage(stream, bundled.fileName)
                }
            }.getOrNull()?.getOrNull() ?: continue

            val checked = LanguagePackInspector.inspect(staged.file)
            if (checked !is LanguagePackInspector.Result.Valid) {
                staged.file.delete()
                continue
            }
            repository.replace(
                LanguagePackEntry(
                    id = entry.id,
                    tag = checked.info.tag,
                    displayName = entry.displayName,
                    fileName = staged.file.name,
                    formatVersion = checked.info.formatVersion,
                    wordCount = checked.info.wordCount,
                    sizeBytes = staged.sizeBytes,
                    sha256 = staged.sha256,
                    importedAt = System.currentTimeMillis(),
                    // Switched back on only where the keyboard switched it off itself. A pack
                    // the user turned off stays off: repairing a file is not permission to
                    // start using it again.
                    enabled = entry.enabled || entry.integrityFailedAt != null,
                    weight = entry.weight,
                    integrityFailedAt = null,
                    licenseNote = entry.licenseNote,
                ),
            )
            android.util.Log.i(
                "BorderKeys",
                "replaced the bundled ${entry.tag} pack, which this build cannot read",
            )
        }
    }

    // ---- quick actions --------------------------------------------------------------------

    /**
     * Puts the saved bar on the view: which buttons, in what order, open or collapsed, and
     * against which edge.
     *
     * Called when the view is built and again whenever preferences change, so editing the bar
     * in the settings app is visible the next time the keyboard is opened rather than after a
     * restart.
     */
    /** The size a drag is proposing, before it is written down. */
    private var draggedHeight = 1f
    private var draggedWidth = 1f

    /**
     * Resizes the keyboard under the finger.
     *
     * Applied to the views and not to the store: a preferences write per frame would be sixty
     * database writes a second for a value only the last of which matters, and the flow that
     * comes back would fight the finger for who decides the size.
     */
    private fun previewResize(height: Float, width: Float, offset: Float) {
        val view = host ?: return
        draggedHeight = height.coerceIn(
            KeyboardPreferences.MIN_HEIGHT_SCALE, KeyboardPreferences.MAX_HEIGHT_SCALE,
        )
        draggedWidth = width.coerceIn(KeyboardPreferences.MIN_WIDTH_SCALE, 1f)
        view.heightScaleForDrag = draggedHeight
        paints.update(effectiveTheme(), resources.displayMetrics, draggedHeight, this)
        view.setPlacement(
            preferences.positionMode,
            draggedWidth,
            (preferences.bottomOffsetDp * resources.displayMetrics.density).toInt(),
            (preferences.horizontalOffsetDp * resources.displayMetrics.density).toInt(),
        )
        view.relayoutForNewMetrics()
    }

    /** Writes the size the finger stopped at, once. */
    private fun commitResize() {
        val height = draggedHeight
        val width = draggedWidth
        scope.launch {
            DataGraph.themes.updatePreferences {
                it.copy(heightScale = height, widthScale = width)
            }
        }
    }

    private fun applyQuickActions(view: KeyboardHostView) {
        val bar = view.quickActions
        if (!preferences.quickActionsEnabled || privateMode) {
            bar.visibility = View.GONE
            return
        }
        // Compose's own button goes with Compose. Switching the feature off has to take away
        // every way to reach it, not just the screen that explains it -- a button that does
        // nothing is the worst of both.
        val chosen = QuickAction.fromIds(preferences.quickActions)
            .filter { it != QuickAction.COMPOSE || preferences.composerEnabled }
        if (chosen.isEmpty()) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        bar.actions = chosen
        bar.collapsible =
            preferences.quickActionsMode == KeyboardPreferences.QUICK_ACTIONS_COLLAPSED
        view.quickActionsPlacement = preferences.quickActionsPlacement
    }


    /**
     * Runs one of the bar's buttons.
     *
     * Everything here goes through InputConnection rather than through key events: an editor
     * that handles selection its own way -- a code editor, a rich text field -- gets the
     * platform's own idea of "select all" rather than our idea of which keys mean that.
     */
    override fun onQuickAction(action: QuickAction) {
        val connection = currentInputConnection ?: return
        when (action) {
            QuickAction.COPY_PREVIOUS_WORD -> copyToClipboard(wordBeforeCursor(connection))
            QuickAction.COPY_LINE -> copyToClipboard(lineAroundCursor(connection))
            // Read and copied here rather than asked of the editor as selectAll-then-copy: the
            // editor applies a selection asynchronously, so the copy that follows in the same
            // breath copies whatever was selected before -- usually nothing.
            QuickAction.COPY_ALL -> copyToClipboard(
                connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty(),
            )
            QuickAction.PASTE -> onClipboardPicked()
            QuickAction.CLIPBOARD_HISTORY -> offerClipboardHistory()
            QuickAction.SELECT_ALL -> connection.performContextMenuAction(android.R.id.selectAll)
            // The edit happens entirely inside the target app's own cut implementation --
            // nothing here calls commitText or deleteSurroundingText for it. checkpointField()
            // reads the live result back afterward, which is what lets a cut be undone at all.
            QuickAction.CUT -> {
                connection.performContextMenuAction(android.R.id.cut)
                checkpointField()
            }
            QuickAction.SELECT_WORD -> selectWordAtCursor(connection)
            QuickAction.DELETE_WORD -> deleteWordBeforeCursor(connection)
            QuickAction.CURSOR_START -> {
                resetComposing()
                connection.setSelection(0, 0)
            }
            QuickAction.CURSOR_END -> {
                resetComposing()
                val all = connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0
                connection.setSelection(all, all)
            }
            QuickAction.NEWLINE -> {
                finishComposing(connection)
                connection.commitText("\n", 1)
                checkpointField()
            }
            QuickAction.SWITCH_LAYOUT -> switchLanguage()
            QuickAction.SETTINGS -> openSettings()
            QuickAction.COMPOSE -> {
                if (privateMode || !preferences.composerEnabled) return
                val selection = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
                val seed = if (selection.length <= AssistProtocol.MAX_SELECTION_CHARS) selection else ""
                val intent = Intent(DraftProtocol.ACTION_QUICK_DRAFT)
                    .setClassName(packageName, SETTINGS_ACTIVITY)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, seed)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
                return
            }
            QuickAction.UNDO -> restoreFieldVersion(fieldHistory.back())
            QuickAction.REDO -> restoreFieldVersion(fieldHistory.forward())
        }
        if (action != QuickAction.CLIPBOARD_HISTORY) {
            refreshContextFromEditor()
            requestSuggestions()
        }
    }

    /** The word immediately before the cursor, empty when the cursor follows a space. */
    private fun wordBeforeCursor(connection: InputConnection): String {
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            return ""
        }
        var end = before.length
        while (end > 0 && !isWordCharacter(before[end - 1].code)) {
            end--
        }
        var start = end
        while (start > 0 && isWordCharacter(before[start - 1].code)) {
            start--
        }
        return before.substring(start, end)
    }

    /** The line the cursor sits on, both sides of it. */
    private fun lineAroundCursor(connection: InputConnection): String {
        val before = connection.getTextBeforeCursor(LINE_WINDOW_CHARS, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(LINE_WINDOW_CHARS, 0)?.toString().orEmpty()
        val start = before.lastIndexOf('\n') + 1
        val breakAfter = after.indexOf('\n')
        val tail = if (breakAfter >= 0) after.substring(0, breakAfter) else after
        return before.substring(start) + tail
    }

    private fun copyToClipboard(text: String) {
        if (text.isEmpty() || privateMode) {
            return
        }
        clipboardManager?.setPrimaryClip(ClipData.newPlainText(null, text))
        refreshClipboardChip()
    }

    /**
     * Opens the clipboard history as a panel of cards.
     *
     * A panel rather than the suggestion strip: the strip fits a few words, and the history is
     * a list someone reads -- several lines of a copied paragraph, and a picture that can only
     * be recognised by looking at it.
     */
    private fun offerClipboardHistory() {
        if (privateMode) {
            return
        }
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                DataGraph.clipboard.recent(MAX_CLIPBOARD_CARDS)
            }
            val view = host ?: return@launch
            view.clipboardPanel.setEntries(entries)
            view.setClipboardPanelVisible(true)
        }
    }

    override fun onClipPicked(entry: com.borderkeys.data.entity.ClipEntry) {
        val view = host
        view?.setClipboardPanelVisible(false)
        // Text follows the draft box; an image cannot, and takes the branch below that needs the
        // application's own connection to know what it will accept.
        val connection = currentInputConnection ?: return
        if (entry.isImage) {
            val uri = android.net.Uri.parse(entry.uri)
            val description = android.content.ClipDescription(
                null, arrayOf(entry.mimeType ?: "image/*"),
            )
            commitImage(uri, description)
        } else {
            finishComposing(connection)
            connection.commitText(entry.content, 1)
            checkpointField()
        }
        refreshContextFromEditor()
        requestSuggestions()
    }

    override fun onClipPinToggled(entry: com.borderkeys.data.entity.ClipEntry) {
        scope.launch {
            withContext(Dispatchers.IO) {
                DataGraph.clipboard.setPinned(entry.id, !entry.isPinned)
            }
            refreshClipboardPanel()
        }
    }

    override fun onClipDeleted(entry: com.borderkeys.data.entity.ClipEntry) {
        scope.launch {
            withContext(Dispatchers.IO) { DataGraph.clipboard.delete(entry.id) }
            refreshClipboardPanel()
        }
    }

    /**
     * Inserts an emoji and remembers that it was used.
     *
     * The panel stays open: choosing one emoji is very often choosing three, and a picker that
     * closes on every pick is a picker reopened on every pick.
     */
    private fun onEmojiPicked(emoji: String) {
        val connection = currentInputConnection ?: return
        finishComposing(connection)
        connection.commitText(emoji, 1)
        checkpointField()
        refreshContextFromEditor()
        requestSuggestions()

        val updated = (listOf(emoji) + preferences.emojiRecents.filterNot { it == emoji })
            .take(KeyboardPreferences.MAX_EMOJI_RECENTS)
        host?.emojiPanel?.recents = updated
        scope.launch {
            DataGraph.themes.updatePreferences { it.copy(emojiRecents = updated) }
        }
    }

    override fun onClipboardPanelClosed() {
        host?.setClipboardPanelVisible(false)
    }

    /** Re-reads the history into an open panel, after something in it changed. */
    private fun refreshClipboardPanel() {
        val view = host ?: return
        if (!view.clipboardPanelVisible) {
            return
        }
        scope.launch {
            val entries = withContext(Dispatchers.IO) {
                DataGraph.clipboard.recent(MAX_CLIPBOARD_CARDS)
            }
            view.clipboardPanel.setEntries(entries)
        }
    }

    /** Selects the word the cursor is inside, so the next action can act on it. */
    private fun selectWordAtCursor(connection: InputConnection) {
        resetComposing()
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(CONTEXT_WINDOW_CHARS, 0)?.toString().orEmpty()
        var back = 0
        while (back < before.length && isWordCharacter(before[before.length - 1 - back].code)) {
            back++
        }
        var forward = 0
        while (forward < after.length && isWordCharacter(after[forward].code)) {
            forward++
        }
        if (back == 0 && forward == 0) {
            return
        }
        val caret = selectionEnd
        connection.setSelection(caret - back, caret + forward)
    }

    /** Deletes back to the start of the word before the cursor, in one press. */
    private fun deleteWordBeforeCursor(connection: InputConnection) {
        if (selectionEnd > selectionStart) {
            connection.commitText("", 1)
            checkpointField()
            return
        }
        composing.setLength(0)
        connection.finishComposingText()
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
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
        connection.deleteSurroundingText(count.coerceAtLeast(1), 0)
        checkpointField()
    }

    // ---- the clipboard chip ---------------------------------------------------------------

    /**
     * A stable identity for a clip, for telling "the same thing already offered" apart from
     * "something new that happens to be sitting in the same slot."
     *
     * The text itself, or the image's URI -- not the [ClipData] object, which the platform hands
     * out fresh on every read even when nothing has changed. Null for anything this chip would
     * not offer anyway, so callers can compare it directly against [withdrawnClip].
     */
    private fun clipSignature(clip: ClipData?): String? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val description = clip.description ?: return null
        return if (description.hasMimeType("image/*")) {
            item.uri?.toString()
        } else {
            item.coerceToText(this)?.toString()?.trim()?.ifEmpty { null }
        }
    }

    /**
     * Rebuilds the chip that offers what is on the clipboard.
     *
     * Reads the clip rather than the history, because what someone means by "what I copied" is
     * the last thing they copied, not the last thing this keyboard happened to record. The
     * label is built here and handed to the view as a finished string: the strip draws, it does
     * not decide what to say.
     */
    private fun refreshClipboardChip() {
        val strip = host?.suggestionStrip ?: return
        if (privateMode || !preferences.clipboardSuggestion) {
            strip.clipboardChip = null
            shownClipSignature = null
            return
        }
        val clip = clipboardManager?.primaryClip
        val description = clip?.description
        if (clip == null || clip.itemCount == 0 || description == null ||
            clipSignature(clip) == withdrawnClip
        ) {
            strip.clipboardChip = null
            shownClipSignature = null
            return
        }
        val text = when {
            description.hasMimeType("image/*") -> strings[Keys.CLIP_PHOTO]
            else -> {
                val plain = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
                if (plain.isEmpty()) {
                    null
                } else {
                    // The first few words, so the chip says which of several copied things this
                    // is without becoming a paragraph in a slot a thumb has to hit.
                    val preview = plain.take(CHIP_PREVIEW_CHARS).substringBeforeLast(' ', "")
                        .ifEmpty { plain.take(CHIP_PREVIEW_CHARS) }
                    if (preview.length < plain.length) {
                        strings.getString(Keys.CLIP_TEXT, preview)
                    } else {
                        strings.getString(Keys.CLIP_TEXT_WHOLE, preview)
                    }
                }
            }
        }
        strip.clipboardChip = text
        // What is actually on screen right now, kept separately from re-reading the clipboard
        // later: see shownClipSignature's own doc comment for why onFinishInputView needs this
        // rather than a fresh read at close time.
        shownClipSignature = if (text != null) clipSignature(clip) else null
    }

    override fun onClipboardPicked() {
        val connection = currentInputConnection ?: return
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0 || privateMode) {
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val description = clip.description
        if (uri != null && description != null && description.hasMimeType("image/*")) {
            commitImage(uri, description)
            return
        }
        val text = item.coerceToText(this)?.toString() ?: return
        finishComposing(connection)
        connection.commitText(text, 1)
        checkpointField()
        if (preferences.clipboardSuggestionOnce) {
            withdrawnClip = clipSignature(clip)
            host?.suggestionStrip?.clipboardChip = null
        }
        if (preferences.clearClipboardAfterInsert) {
            // Emptied by writing an empty clip rather than by any clear API, because there is
            // no permission-free way to clear another app's clipboard and this is ours to set
            // while we hold focus. The chip goes with it.
            clipboardManager?.setPrimaryClip(ClipData.newPlainText(null, ""))
            host?.suggestionStrip?.clipboardChip = null
        }
        refreshContextFromEditor()
        requestSuggestions()
    }

    /**
     * Hands an image to the editor, if it said it would take one.
     *
     * commitContent is the only way an input method may insert anything that is not text, and
     * it works solely where the editor advertised the type in contentMimeTypes -- a chat app
     * usually does, a plain text field never. Where it is refused there is nothing to fall back
     * to, so the chip is simply not honoured rather than pasting a content URI as text.
     */
    private fun commitImage(
        uri: android.net.Uri,
        description: android.content.ClipDescription,
    ) {
        val connection = currentInputConnection ?: return
        val accepted = currentInputEditorInfo?.contentMimeTypes.orEmpty()
        val supported = accepted.any { mime ->
            description.hasMimeType(mime) || mime == "*/*"
        }
        if (!supported) {
            return
        }
        val info = InputContentInfo(uri, description)
        // The permission is granted for this one insertion and released by the platform when
        // the target is done with it; without the flag the editor gets a URI it cannot read.
        connection.commitContent(
            info,
            InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            null,
        )
    }

    private fun onClipboardChanged() {
        if (privateMode || !preferences.clipboardEnabled) {
            return
        }
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) {
            return
        }
        withdrawnClip = null
        refreshClipboardChip()

        val description = clip.description
        val uri = clip.getItemAt(0).uri
        if (uri != null && description != null && description.hasMimeType("image/*")) {
            // Remembered by reference. The read grant that came with the clip is temporary, so
            // the thumbnail may stop loading later -- which the panel says, rather than the
            // alternative of copying megabytes into the database on every screenshot.
            val mime = description.getMimeType(0) ?: "image/*"
            scope.launch(Dispatchers.IO) {
                DataGraph.clipboard.rememberImage(uri.toString(), mime)
            }
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
            .setBackgroundColor(effectiveTheme().keyColor)
            .setPadding(CHIP_PADDING_PX, 0, CHIP_PADDING_PX, 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(chipBackground)
            .setChipStyle(chipBackground)
            .setTitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(effectiveTheme().textColor)
                    .setTextSize(effectiveTheme().labelTextSizeSp * 0.8f)
                    .build(),
            )
            .setSubtitleStyle(
                TextViewStyle.Builder()
                    .setTextColor(effectiveTheme().secondaryTextColor)
                    .setTextSize(effectiveTheme().labelTextSizeSp * 0.62f)
                    .build(),
            )
            .build()

        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val density = resources.displayMetrics.density
        val height = (effectiveTheme().rowHeightDp * 0.78f * density).toInt()
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
        val chipHeight = (effectiveTheme().rowHeightDp * 0.78f * density).toInt().coerceAtLeast(1)
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

        /** Where [maybeDecayPersonalDictionary] remembers when it last ran. Its own small file
         *  rather than a field on [KeyboardPreferences]: it is not a setting, nobody reads it,
         *  and it has no business being in the same document a settings screen edits and writes
         *  back whole. */
        const val DECAY_PREFS = "personal_dictionary_decay"
        const val DECAY_LAST_SWEEP_AT = "last_sweep_at"

        /** How often [maybeDecayPersonalDictionary] is allowed to run its `UPDATE`s -- once a
         *  day is plenty against a ninety-day half-life, and far less than the four-second
         *  learning-flush debounce that would otherwise run it on every flush of an active
         *  typing session. */
        const val DECAY_SWEEP_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        const val DOUBLE_TAP_MILLIS = 400L

        const val CONTEXT_WINDOW_CHARS = 64

        /**
         * How much of the field [checkpointField] and [restoreFieldVersion] will read.
         *
         * A version list is not the place for an unbounded copy of a document -- the same
         * reasoning [CONTEXT_WINDOW_CHARS] applies to the n-gram context read, at a scale that
         * covers a real message or email rather than two words of it.
         */
        const val FIELD_HISTORY_CHARS = 20_000

        /** How close two spaces must be to mean the end of a sentence rather than two spaces. */
        const val DOUBLE_SPACE_MILLIS = 1200L

        /** How much of a copied text the chip shows before it stops being a label. */
        const val CHIP_PREVIEW_CHARS = 24

        /** How many cards the history panel holds. Beyond this, scrolling stops being reading. */
        const val MAX_CLIPBOARD_CARDS = 40

        /** How far either side of the cursor "the line" is looked for. */
        const val LINE_WINDOW_CHARS = 1024

        /** BkdStatus.kBkdErrVersion, mirrored so the service can tell that case from the rest. */
        const val BKD_ERR_VERSION = -4
        const val MIN_LEARNED_LENGTH = 2

        const val GESTURE_DECODING_NOTICE_MILLIS = 50L
        const val MAX_CLIP_LENGTH = 20_000
        const val MAX_INLINE_SUGGESTIONS = 5
        const val MIN_CHIP_WIDTH_DP = 120
        const val BLUR_RADIUS_DP = 24f
        const val CHIP_PADDING_PX = 12

        val WORD_SEPARATORS = charArrayOf(
            ' ', '\n', '\t', '.', ',', '!', '?', ';', ':', '(', ')', '[', ']', '"', '/',
        )
    }
}
