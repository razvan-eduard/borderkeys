// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.borderkeys.data.DataGraph
import com.borderkeys.data.DirectBoot
import com.borderkeys.data.entity.KeyTouch
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.draft.DraftProtocol
import com.borderkeys.data.theme.EffectEvent
import com.borderkeys.data.theme.EffectFrequency
import com.borderkeys.effects.EffectStyle
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.ClipboardExclusions
import java.time.ZonedDateTime
import com.borderkeys.data.theme.TimestampPattern
import com.borderkeys.data.theme.QuickActionBar
import com.borderkeys.data.theme.QuickActionBarItem
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.ime.fx.applyParticleLayer
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.CorrectionOffer
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.predict.ScoreExplanation
import com.borderkeys.predict.SwipeModelLoad
import com.borderkeys.theme.DynamicColors
import com.borderkeys.theme.ThemeMode
import com.borderkeys.theme.ThemePaints
import com.borderkeys.typing.FieldPolicy
import com.borderkeys.typing.FieldSession
import com.borderkeys.typing.KeyGeometrySnapshot
import com.borderkeys.typing.LearningBatch
import com.borderkeys.typing.LearningStore
import com.borderkeys.typing.RingUi
import com.borderkeys.typing.TypingClock
import com.borderkeys.typing.TypingHost
import com.borderkeys.typing.TypingOrchestrator
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
import kotlin.math.hypot
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys
import java.util.Locale

/**
 * The input method. Owns the [InputConnection]; dictionaries, scoring and database writes run on
 * other threads or on a debounce.
 */
class BorderKeysService :
    InputMethodService(),
    KeyboardCanvasView.Listener,
    SuggestionStripView.Listener,
    QuickSettingsView.Listener,
    QuickActionsView.Listener,
    ClipboardPanelView.Listener,
    ExplainPanelView.Listener,
    LanguageRevertPanelView.Listener,
    RadialSuggestionMenuView.Listener,
    PredictionEngine.ResultListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Where learning is written to the database; not cancelled in [onDestroy]. */
    private val learningJob = SupervisorJob()
    private val learningScope = CoroutineScope(learningJob + Dispatchers.IO)

    private val paints = ThemePaints()
    private val engine = PredictionEngine()

    private var host: KeyboardHostView? = null

    /** Whether the strip shows a private field's text, at the user's request, this field. */
    private var privateReveal = false
    private var preferences = KeyboardPreferences()
    private var particleEffects = ParticleEffectsSettings()

    /** Whether the user has unlocked since boot; see [DirectBoot]. Set once, never back. */
    private var unlocked = false

    /** Unregisters the unlock receiver, while one waits. */
    private var unlockRegistration: (() -> Unit)? = null

    /** Draws the device-protected appearance before the first unlock; cancelled at it. */
    private var lockedAppearanceJob: kotlinx.coroutines.Job? = null

    /** The interface language, resolved once when the service starts. */
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

    /** Whether the phone is in landscape. */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    /** [preferences]' height, width, position and offsets for the orientation the phone is in
     *  right now. See [KeyboardPreferences.placementFor]. */
    private fun activePlacement(): com.borderkeys.data.theme.KeyboardPlacement =
        preferences.placementFor(isLandscape())

    private var alphabeticLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /** Each language tag's extra keys; see [ExtraKeys]. */
    private var extraKeysByLanguage: Map<String, List<ExtraKeys.Entry>> = emptyMap()

    /** The extra keys on the letter page now drawn. */
    private var extraKeyPlacements: List<ExtraKeys.Placement> = emptyList()

    /** The language tags of this keyboard's enabled subtypes, the current one first; read at each field. */
    private var subtypeTags: List<String>? = null

    /** [ChosenSubtypes.count], read at each field. */
    private var chosenSubtypes: Int? = null

    private fun chosenSubtypeCount(): Int =
        chosenSubtypes ?: ChosenSubtypes.count(this).also { chosenSubtypes = it }

    private fun enabledSubtypeTags(): List<String> {
        subtypeTags?.let { return it }
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        val ours = manager?.enabledInputMethodList?.firstOrNull { it.packageName == packageName }
        val enabled = ours?.let { manager.getEnabledInputMethodSubtypeList(it, true) }.orEmpty().map { it.languageTag }
        val current = manager?.currentInputMethodSubtype?.languageTag
        val tags = (listOfNotNull(current) + enabled).filter { it.isNotEmpty() }.distinct()
        subtypeTags = tags
        return tags
    }

    /** The layout id the current subtype names, which the user's choice may stand in for. */
    private var subtypeLayoutId: String = DEFAULT_ALPHABETIC_LAYOUT

    /** The letter layout for [subtypeLayoutId]: the user's choice for it, else its asset. */
    private fun resolveLetterLayout(): KeyboardLayout = LayoutChoice.resolve(
        subtypeLayoutId,
        LayoutChoice.forOrientation(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape, isLandscape()),
        preferences.customLayouts,
    ) { id -> LayoutLoader.load(assets, id) }

    /** Resolves the letter layout again, off the main thread, and redraws the current page. */
    private fun reloadLetterLayout() {
        scope.launch(Dispatchers.IO) {
            val layout = resolveLetterLayout()
            withContext(Dispatchers.Main) {
                alphabeticLayout = layout
                useSwipeModelForLayout()
                host?.let { showPage(page) }
            }
        }
    }

    private var symbolsLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsNumpadLeftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsNumpadRightLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsShiftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var numpadLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()


    /** The tier B load in flight. */
    private var swipeModelJob: kotlinx.coroutines.Job? = null

    /** Which page is on screen. */
    private var page = PAGE_ALPHABETIC

    /** Control and alt from the modifier row, each armed for the next key. */
    private var controlArmed = false
    private var altArmed = false

    /** Where the text field sat on screen when the ring opened, or NaN before the first report. */
    private var ringEditorOriginX = Float.NaN
    private var ringEditorOriginY = Float.NaN

    /** Which clip the clipboard chip may show. */
    private val clipOffers = ClipChipOffers()

    /** Shows "decoding" on the strip when a swipe's answer is late. */
    private val gestureDecodingRunnable = Runnable { host?.suggestionStrip?.decoding = true }

    /** When the last swipe was lifted, for the debug timing line in onGestureCandidates. */
    private var gestureLiftedAt = 0L

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    /** Whether the radial ring is open. */
    private val swipeRadialController = SwipeRadialController()

    /** Where a swipe paused, in the keyboard view's pixels. */
    private var lastGestureX = 0f
    private var lastGestureY = 0f

    /** The words [syncDebugRing]'s sample ring offers. */
    private val DEBUG_RING_WORDS = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

    /**
     * Resolves the ring [KeyboardPreferences.radialPickTimeoutMillis] after it opens, unless
     * steering, a resolution or a dismissal cancels it first.
     */
    private val radialTimeoutRunnable: Runnable = Runnable { orchestrator.onRingTimedOut() }

    /** The word the strip is currently asking about, between the hold and the answer. */
    private var pendingForget: String? = null

    /** The composing text the strip was about when [pendingForget] was held down. */
    private var pendingExplainQuery: String = ""

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false

    // ---- the typing flow -----------------------------------------------------------------------

    /** The views and the input method, as [orchestrator] reaches them. */
    private val clearNotice = Runnable { host?.suggestionStrip?.notice = null }

    private val typingHost = object : TypingHost {
        override val viewAttached: Boolean
            get() = host != null

        override fun stripWordSlots(): Int? = host?.suggestionStrip?.wordSlotLimit

        override fun showSuggestions(row: List<Candidate>, typedIndex: Int, appliedIndex: Int) {
            val strip = host?.suggestionStrip ?: return
            strip.setSuggestions(row)
            strip.typedIndex = typedIndex
            strip.appliedIndex = appliedIndex
        }

        override fun clearStrip() {
            host?.suggestionStrip?.clear()
        }

        override fun stripTypedIndex(): Int? = host?.suggestionStrip?.typedIndex

        override fun clearStripActions() {
            val strip = host?.suggestionStrip ?: return
            if (strip.actionMode) {
                strip.clear()
                pendingForget = null
            }
        }

        override fun setEditorEmpty(empty: Boolean) {
            host?.suggestionStrip?.editorEmpty = empty
        }

        override fun showShiftState(state: Int) {
            host?.keyboard?.shiftState = state
        }

        override fun onWordReset() {
            pendingForget = null
        }

        override fun onSwipeLifted(
            xs: FloatArray,
            ys: FloatArray,
            timestamps: LongArray,
            count: Int,
        ) {
            host?.postDelayed(gestureDecodingRunnable, GESTURE_DECODING_NOTICE_MILLIS)
            gestureLiftedAt = android.os.SystemClock.uptimeMillis()
            recordSwipeShape(xs, ys, timestamps, count)
        }

        override fun onSwipeAnswered() {
            host?.removeCallbacks(gestureDecodingRunnable)
            host?.suggestionStrip?.decoding = false
        }

        override fun onSwipeDecoded(candidates: Int) = recordSwipeDecode(candidates)

        override fun traceSwipeDecode(candidates: Int) {
            if (debuggable) {
                android.util.Log.d(
                    "BorderKeys",
                    "swipe: decode ${engine.lastGestureDecodeMicros / 1000.0} ms, lift to text " +
                        "${android.os.SystemClock.uptimeMillis() - gestureLiftedAt} ms, tier " +
                        "${if (engine.lastGestureUsedNeural) "B" else "A"}, " +
                        "$candidates candidates",
                )
            }
        }

        override fun refreshPrivateReveal() = this@BorderKeysService.refreshPrivateReveal()

        override fun playEffect(event: EffectEvent, word: String) =
            this@BorderKeysService.playEffect(event, word)

        override fun offerLanguageReplacements(
            replacements: List<LanguageSwitchCorrector.Replacement>,
        ) {
            host?.let { view ->
                view.languageRevertPanel.offer(replacements)
                view.setLanguageRevertPanelVisible(true)
            }
        }

        override fun forgetWord(word: String, blockWhenNotPersonal: Boolean) =
            this@BorderKeysService.forgetWord(word, blockWhenNotPersonal)

        override fun subtypeTag(): String = currentSubtypeTag()

        override fun showNotice(key: String) {
            val strip = host?.suggestionStrip ?: return
            strip.notice = strings[key]
            strip.removeCallbacks(clearNotice)
            strip.postDelayed(clearNotice, NOTICE_MILLIS)
        }

        override fun showAccent(deadCode: Int, locked: Boolean, composing: String?) {
            val view = host ?: return
            view.keyboard.setArmedAccent(
                when {
                    deadCode != 0 -> deadCode
                    composing != null -> KeyCodes.COMPOSE
                    else -> KeyCodes.NONE
                },
            )
            view.suggestionStrip.removeCallbacks(clearNotice)
            view.suggestionStrip.notice = composing?.let { strings.getString(Keys.COMPOSE_PENDING, it) }
        }

        override fun runQuickAction(id: Int) {
            val action = QuickAction.fromId(id) ?: return
            onQuickAction(QuickActionBarItem.Builtin(action))
        }

        override val modifiersArmed: Boolean
            get() = controlArmed || altArmed

        override fun releaseModifiers() = setArmedModifiers(control = false, alt = false)

        override fun sendPhysicalKey(keyCode: Int, metaState: Int) {
            val connection = currentInputConnection ?: return
            this@BorderKeysService.sendPhysicalKey(connection, keyCode, metaState)
        }

        override fun postDelayed(action: Runnable, delayMillis: Long) {
            host?.postDelayed(action, delayMillis)
        }

        override fun removeCallbacks(action: Runnable) {
            host?.removeCallbacks(action)
        }
    }

    /** The radial ring, as [orchestrator] opens, reads and closes it. */
    private val ringUi: RingUi = object : RingUi {
        override val isOpen: Boolean
            get() = swipeRadialController.state == SwipeRadialController.State.OPEN

        override fun open(
            words: List<String>,
            trustedIndex: Int,
            waitsForTap: Boolean,
            pickTimeoutMillis: Long?,
        ): Boolean {
            val view = host ?: return false
            if (!swipeRadialController.onRingOpened(words)) {
                return false
            }
            val (anchorX, anchorY) = radialAnchor(view)
            view.radialSuggestionMenu.show(anchorX, anchorY, words, trustedIndex)
            if (waitsForTap) {
                view.radialSuggestionMenu.acceptsOwnTouches = true
            }
            view.setRadialMenuVisible(true)
            watchEditorWhileRingOpen(true)
            refreshTouchableArea()
            if (!waitsForTap) {
                view.removeCallbacks(radialTimeoutRunnable)
                pickTimeoutMillis?.let { view.postDelayed(radialTimeoutRunnable, it) }
            }
            return true
        }

        override fun selection(): RingUi.Selection =
            when (val selection = host?.radialSuggestionMenu?.currentSelection()) {
                is RadialSuggestionMenuView.Selection.Word ->
                    RingUi.Selection.Word(selection.index, selection.word)
                RadialSuggestionMenuView.Selection.Cancel -> RingUi.Selection.Cancel
                RadialSuggestionMenuView.Selection.None, null -> RingUi.Selection.None
            }

        override fun keepOpenForTap() {
            val view = host ?: return
            view.removeCallbacks(radialTimeoutRunnable)
            view.radialSuggestionMenu.acceptsOwnTouches = true
        }

        override fun close(celebrateIndex: Int?) = closeRadialRing(celebrateIndex)

        override fun dismiss(): Boolean {
            if (swipeRadialController.state != SwipeRadialController.State.OPEN || debugRingOpen) {
                return false
            }
            closeRadialRing()
            host?.keyboard?.abandonRingStroke()
            return true
        }

        override fun resumeGestureCapture() {
            host?.keyboard?.resumeGestureCapture()
        }
    }

    /** The personal dictionary, as what the typing flow learns is written to it. */
    private val learningStore = object : LearningStore {
        override fun persist(batch: LearningBatch) {
            learningScope.launch {
                runCatching { persistLearning(batch) }
                    .onFailure { error ->
                        android.util.Log.e("BorderKeys", "learning flush failed", error)
                    }
            }
        }

        override fun loadTouches(bucket: String, onLoaded: (List<KeyTouch>) -> Unit) {
            learningScope.launch {
                val rows = runCatching { DataGraph.dictionary.touchesIn(bucket) }
                    .onFailure { error -> android.util.Log.e("BorderKeys", "heatmap load failed", error) }
                    .getOrDefault(emptyList())
                withContext(Dispatchers.Main) { onLoaded(rows) }
            }
        }

        /** Waits up to [FINAL_FLUSH_TIMEOUT_MILLIS] for the writes in flight and for [batch]. */
        override fun persistBeforeShutdown(batch: LearningBatch?) {
            val inFlight = learningJob.children.toList()
            if (batch == null && inFlight.isEmpty()) {
                return
            }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(FINAL_FLUSH_TIMEOUT_MILLIS) {
                        inFlight.forEach { it.join() }
                        if (batch != null) {
                            withContext(Dispatchers.IO) { persistLearning(batch) }
                        }
                    }
                }
            }.onFailure { error ->
                android.util.Log.w("BorderKeys", "the final learning flush failed", error)
            }
        }
    }

    private val typingClock = object : TypingClock {
        override fun currentTimeMillis(): Long = System.currentTimeMillis()

        override fun uptimeMillis(): Long = android.os.SystemClock.uptimeMillis()
    }

    /** The field being typed into, as the typing flow edits it. */
    private val fieldEditor = ConnectionFieldEditor { currentInputConnection }

    /** Each word, from its first key to what is learned from it. */
    private val orchestrator: TypingOrchestrator = TypingOrchestrator(
        currentEditor = { if (currentInputConnection != null) fieldEditor else null },
        engine = engine,
        host = typingHost,
        ring = ringUi,
        store = learningStore,
        clock = typingClock,
    )

    /** The language packs, the blocked words and the personal dictionary, loaded into [engine]. */
    private val dictionaryLoader: DictionaryLoader by lazy {
        DictionaryLoader(assets, engine, orchestrator, { preferences }) {
            host?.let {
                it.emojiPanel.keywords = dictionaryLoader.emojiKeywords
                showPage(page)
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    /** Listens for the wallpaper changing, through the platform's own registerReceiver. */
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerWallpaperReceiver() {
        val filter = IntentFilter(Intent.ACTION_WALLPAPER_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wallpaperChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wallpaperChangedReceiver, filter)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DataGraph.install(applicationContext)
        unlocked = DataGraph.isUserUnlocked()
        // Loaded before anything draws.
        strings = LanguageManager(this).apply {
            loadResolved(
                if (unlocked) {
                    DataGraph.themes.currentPreferences().uiLanguage
                } else {
                    DataGraph.lockedAppearance.current().preferences.uiLanguage
                },
            )
        }
        engine.listener = this
        engine.start()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        registerWallpaperReceiver()

        scope.launch(Dispatchers.IO) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            subtypeLayoutId = layoutIdFromSubtype(manager?.currentInputMethodSubtype)
            alphabeticLayout = resolveLetterLayout()
            withContext(Dispatchers.Main) { useSwipeModelForLayout() }
            symbolsLayout = LayoutLoader.load(assets, SYMBOLS_LAYOUT)
            symbolsNumpadLeftLayout = LayoutLoader.load(assets, SYMBOLS_NUMPAD_LEFT_LAYOUT)
            symbolsNumpadRightLayout = LayoutLoader.load(assets, SYMBOLS_NUMPAD_RIGHT_LAYOUT)
            symbolsShiftLayout = LayoutLoader.load(assets, SYMBOLS_SHIFT_LAYOUT)
            numpadLayout = LayoutLoader.load(assets, NUMPAD_LAYOUT)
            val sequences = ComposeSequences.load(assets)
            val extra = ExtraKeys.load(assets)
            withContext(Dispatchers.Main) {
                orchestrator.composeSequences = sequences
                extraKeysByLanguage = extra
                host?.let { showPage(page) }
            }
            if (unlocked) {
                loadDictionaries()
            }
        }
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        if (unlocked) {
            startUnlocked()
        } else {
            observeLockedAppearance()
            unlockRegistration = DirectBoot.whenUnlocked(this) { onUserUnlocked() }
        }
    }

    /** The packs, the personal dictionary and the saved language evidence; off the main thread. */
    private suspend fun loadDictionaries() {
        // A failed load leaves the keyboard typing without dictionaries.
        runCatching { dictionaryLoader.load() }
            .onSuccess { restoreLanguageEvidence() }
            .onFailure { error -> degradeWithoutDictionaries(error) }
        File(filesDir, LEGACY_USER_MODEL_SNAPSHOT).delete()
        runCatching { DataGraph.clipboard.sweepMedia() }
    }

    /** What reads credential-encrypted storage: the settings, the packs and the dictionary edits. */
    private fun startUnlocked() {
        observeSettings()
        observeLanguagePacks()
        observeDictionaryEdits()
    }

    /** The user unlocked while the keyboard ran from the device-protected copy. */
    private fun onUserUnlocked() {
        if (unlocked) {
            return
        }
        unlocked = true
        unlockRegistration = null
        lockedAppearanceJob?.cancel()
        lockedAppearanceJob = null
        orchestrator.onUserUnlocked()
        scope.launch(Dispatchers.IO) { loadDictionaries() }
        startUnlocked()
        paints.reloadImage(this)
        host?.onThemeChanged()
    }

    /** Before the first unlock: the appearance from device-protected storage, default effects. */
    private fun observeLockedAppearance() {
        lockedAppearanceJob = scope.launch {
            DataGraph.lockedAppearance.data
                .catch { error ->
                    android.util.Log.e("BorderKeys", "locked appearance unreadable, using defaults", error)
                }
                .collect { locked -> applyAppearance(locked.asAppearance()) }
        }
    }

    /**
     * Reloads the blocked words and the personal model after an edit on the Personal dictionary
     * screen.
     */
    private fun observeDictionaryEdits() {
        scope.launch {
            DataGraph.dictionary.edits.collect {
                withContext(Dispatchers.IO) { dictionaryLoader.reloadPersonal() }
                orchestrator.reloadTouches()
                orchestrator.requestSuggestions()
            }
        }
    }

    /**
     * Turns tier B on or off: loads its weights from the assets, or frees them. Does nothing
     * after a failed load ([KeyboardPreferences.swipeModelFailed]).
     */
    private fun applySwipeModel(enabled: Boolean) {
        swipeModelJob?.cancel()
        if (!enabled) {
            engine.setSwipeModelEnabled(false)
            SwipeModelLoad.set(SwipeModelLoad.State.Off)
            return
        }
        if (preferences.swipeModelFailed) {
            SwipeModelLoad.set(SwipeModelLoad.State.Failed)
            return
        }
        engine.setSwipeModelEnabled(true)
        useSwipeModelForLayout()
    }

    /** The script models the build ships, by file name; none in `core`. */
    private val shippedSwipeModels: Set<String> by lazy {
        runCatching { assets.list(SwipeModels.DIRECTORY)?.toSet() }.getOrNull().orEmpty()
    }

    /** Failed loads by model asset, kept across starts; [SwipeModels.MAX_FAILURES] write one off. */
    private val swipeModelFailures by lazy { getSharedPreferences(SWIPE_MODEL_FAILURES_PREFS, MODE_PRIVATE) }

    private fun deadSwipeModels(): Set<String> =
        swipeModelFailures.all.filter { (_, count) -> (count as? Int ?: 0) >= SwipeModels.MAX_FAILURES }.keys

    /**
     * Has the engine decode the letter layout with its model ([SwipeModels.modelFor]), loading it
     * when it is not held, or with the geometric decoder when it has none. Does nothing while the
     * model is off or written off.
     */
    private fun useSwipeModelForLayout() {
        if (!preferences.experimentalSwipeModelEnabled || preferences.swipeModelFailed || !unlocked) {
            return
        }
        val letters = alphabeticLayout.rows.flatMap { row -> row.keys.map { it.label } }
        val model = SwipeModels.modelFor(alphabeticLayout.id, letters, shippedSwipeModels, deadSwipeModels())
        if (model == null) {
            engine.useSwipeModel(null, read = { null }) { SwipeModelLoad.set(SwipeModelLoad.State.Ready) }
            return
        }
        SwipeModelLoad.set(SwipeModelLoad.State.Loading)
        engine.useSwipeModel(
            model.script,
            read = { runCatching { assets.open(model.asset).use { it.readBytes() } }.getOrNull() },
        ) { ready ->
            if (ready) {
                SwipeModelLoad.set(SwipeModelLoad.State.Ready)
                return@useSwipeModel
            }
            val failures = swipeModelFailures.getInt(model.asset, 0) + 1
            swipeModelFailures.edit().putInt(model.asset, failures).apply()
            if (model == SwipeModels.LATIN) {
                if (failures >= SwipeModels.MAX_FAILURES) {
                    failSwipeModel("the swipe model's weights could not be loaded")
                } else {
                    SwipeModelLoad.set(SwipeModelLoad.State.Off)
                }
            } else {
                android.util.Log.w("BorderKeys", "the swipe model ${model.asset} could not be loaded")
                // The geometric decoder takes the layout once this one is written off.
                if (failures >= SwipeModels.MAX_FAILURES) {
                    useSwipeModelForLayout()
                }
            }
        }
    }

    /**
     * Writes off tier B for this installation: drops it from the engine, turns the switch off,
     * and records the failure in the preferences.
     */
    private fun failSwipeModel(reason: String?) {
        android.util.Log.w(
            "BorderKeys",
            reason ?: "the swipe model's weights could not be read",
        )
        engine.setSwipeModelEnabled(false)
        SwipeModelLoad.set(SwipeModelLoad.State.Failed)
        updatePreferences { it.copy(swipeModelFailed = true, experimentalSwipeModelEnabled = false) }
    }

    /** Carries on without dictionaries: no prediction, correction or learning. */
    private fun degradeWithoutDictionaries(error: Throwable) {
        android.util.Log.e("BorderKeys", "starting without dictionaries", error)
        orchestrator.stopLearning()
        scope.launch { host?.suggestionStrip?.clear() }
    }

    /** Reloads the language packs when the enabled packs, their files or weights change. */
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
                    // The first emission only redraws the layout.
                    val first = previous == null
                    previous = signature
                    if (first) {
                        host?.let { showPage(page) }
                        return@collect
                    }
                    runCatching { withContext(Dispatchers.IO) { dictionaryLoader.load() } }
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
                DataGraph.themes.particleEffects,
                ::KeyboardAppearance,
            ).catch { error ->
                android.util.Log.e("BorderKeys", "settings unavailable, using defaults", error)
            }.collect { appearance -> applyAppearance(appearance) }
        }
    }

    /** Applies [appearance]: the orchestrator, the engine, the paints and the views follow it. */
    private fun applyAppearance(appearance: KeyboardAppearance) {
        val (newTheme, newLightTheme, newPreferences, newParticleEffects) = appearance
        theme = newTheme
        lightTheme = newLightTheme
        val wasForcingDebugRing = preferences.debugForceRadialRing
        val offensiveSwitchFlipped =
            preferences.blockOffensiveWords != newPreferences.blockOffensiveWords
        val wordLimitChanged =
            preferences.learnedWordLimit != newPreferences.learnedWordLimit
        val swipeModelFlipped =
            preferences.experimentalSwipeModelEnabled !=
                newPreferences.experimentalSwipeModelEnabled
        val layoutChoiceChanged = preferences.subtypeLayouts != newPreferences.subtypeLayouts ||
            preferences.subtypeLayoutsLandscape != newPreferences.subtypeLayoutsLandscape ||
            preferences.customLayouts != newPreferences.customLayouts
        preferences = newPreferences
        if (layoutChoiceChanged) {
            reloadLetterLayout()
        }
        orchestrator.applySettings(newPreferences)
        if (swipeModelFlipped) {
            applySwipeModel(newPreferences.experimentalSwipeModelEnabled)
        }
        particleEffects = newParticleEffects
        if (unlocked && (offensiveSwitchFlipped || wordLimitChanged)) {
            scope.launch(Dispatchers.IO) {
                dictionaryLoader.reloadPersonal()
                withContext(Dispatchers.Main) { orchestrator.requestSuggestions() }
            }
        }
        val resolvedTheme = ThemeMode.resolve(
            newTheme, newLightTheme, newPreferences, this@BorderKeysService,
        )
        val effectiveTheme = if (newPreferences.followSystemColors) {
            DynamicColors.apply(resolvedTheme, this@BorderKeysService)
        } else {
            resolvedTheme
        }
        val changed = paints.update(
            effectiveTheme, resources.displayMetrics,
            newPreferences.placementFor(isLandscape()).heightScale,
            this@BorderKeysService,
        )
        host?.let { view ->
            applyPlacement(view, newPreferences)
            applyParticleSettings(view, newParticleEffects)
            if (view.quickSettingsVisible) {
                pushQuickSettingsState(view)
            }
            applyHaptics(view, newPreferences)
            view.keyboard.soundEnabled = newPreferences.keySound
            view.keyboard.keyPopupEnabled = newPreferences.keyPopup
            view.keyboard.spaceCursorEnabled = newPreferences.spaceCursorControl
            view.keyboard.holdHintsEnabled = newPreferences.longPressHints
            view.keyboard.longPressDelayMillis = newPreferences.longPressMillis.toLong()
            view.keyboard.flickMinFraction = newPreferences.flickMinFraction
            view.keyboard.flickMaxFraction = newPreferences.flickMaxFraction
            view.keyboard.backspaceSlideEnabled = newPreferences.backspaceSlideSelects
            view.keyboard.spaceTrackpointEnabled = newPreferences.spaceTrackpoint
            view.keyboard.trackpointSpeedPercent = newPreferences.trackpointSpeed
            view.keyboard.radialMenuEnabled = newPreferences.radialMenuEnabled
            view.keyboard.radialPauseDwellMillis =
                newPreferences.radialPauseDwellMillis.toLong()
            view.keyboard.radialMinPathLetters = newPreferences.radialMinPathLetters
            view.radialSuggestionMenu.sizeScale =
                KeyboardPreferences.radialSizeScale(newPreferences.radialMenuSize)
            view.radialBlurBackground = newPreferences.radialBlurBackground
            view.suggestionStrip.visibleLimit = newPreferences.suggestionCount
            applyQuickActions(view)
            refreshClipboardChip()
            if (wasForcingDebugRing && !newPreferences.debugForceRadialRing) {
                closeDebugRing()
            }
            syncDebugRing()
            view.keyboard.swipeEnabled =
                newPreferences.swipeEnabled && orchestrator.session.policy.suggestionsAllowed
            view.suggestionStripEnabled = newPreferences.showSuggestionStrip
            showPage(page)
            view.fullWidthBackground = resolvedTheme.fullWidthBackground
            view.navigationBarBackground = resolvedTheme.navigationBarBackground
            view.opacity = resolvedTheme.opacity
            if (changed) {
                view.keyboard.onThemeChanged()
                view.quickSettings.onThemeChanged()
                view.onThemeChanged()
                view.relayoutForNewMetrics()
            }
        }
    }

    /** A tap in the editor, also when the caret did not move: closes the ring. */
    @Deprecated("Deprecated in Java")
    override fun onViewClicked(focusChanged: Boolean) {
        @Suppress("DEPRECATION")
        super.onViewClicked(focusChanged)
        orchestrator.dismissRing()
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        orchestrator.dismissRing()
    }

    /** Called whenever the cursor or the selection moves. */
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
        orchestrator.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onActionPicked(index: Int) {
        // The held word's actions: forget, cancel, explain.
        val forgetting = pendingForget ?: return
        pendingForget = null
        host?.suggestionStrip?.clear()
        when (index) {
            0 -> forgetWord(forgetting)
            2 -> {
                explainWord(pendingExplainQuery, forgetting)
                orchestrator.requestSuggestions()
            }
            else -> orchestrator.requestSuggestions()
        }
    }

    /** Which touches vibrate, and how firmly. */
    private fun applyHaptics(view: KeyboardHostView, preferences: KeyboardPreferences) {
        val constant = HapticStrength.constantFor(preferences.hapticStrength)
        view.keyboard.hapticConstant = constant
        view.suggestionStrip.hapticConstant = constant
        view.radialSuggestionMenu.hapticConstant = constant
        view.emojiPanel.hapticConstant = constant
        view.keyboard.hapticEnabled = preferences.hapticFeedback && preferences.hapticKeys
        view.suggestionStrip.hapticEnabled = preferences.hapticFeedback && preferences.hapticSuggestions
        view.emojiPanel.hapticEnabled = preferences.hapticFeedback && preferences.hapticSuggestions
        view.radialSuggestionMenu.hapticEnabled = preferences.hapticFeedback && preferences.hapticRing
    }

    override fun onCreateInputView(): View {
        paints.update(effectiveTheme(), resources.displayMetrics, activePlacement().heightScale, this)
        val view = KeyboardHostView(this, paints, strings)
        applyPlacement(view, preferences)
        applyParticleSettings(view, particleEffects)
        view.keyboard.listener = this
        applyHaptics(view, preferences)
        view.keyboard.swipeEnabled = preferences.swipeEnabled
        view.keyboard.soundEnabled = preferences.keySound
        view.keyboard.keyPopupEnabled = preferences.keyPopup
        view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
        view.keyboard.holdHintsEnabled = preferences.longPressHints
        view.keyboard.longPressDelayMillis = preferences.longPressMillis.toLong()
        view.keyboard.flickMinFraction = preferences.flickMinFraction
        view.keyboard.flickMaxFraction = preferences.flickMaxFraction
        view.keyboard.backspaceSlideEnabled = preferences.backspaceSlideSelects
        view.keyboard.spaceTrackpointEnabled = preferences.spaceTrackpoint
        view.keyboard.trackpointSpeedPercent = preferences.trackpointSpeed
        view.keyboard.radialMenuEnabled = preferences.radialMenuEnabled
        view.keyboard.radialPauseDwellMillis = preferences.radialPauseDwellMillis.toLong()
        view.keyboard.radialMinPathLetters = preferences.radialMinPathLetters
        view.radialSuggestionMenu.sizeScale =
            KeyboardPreferences.radialSizeScale(preferences.radialMenuSize)
        view.radialBlurBackground = preferences.radialBlurBackground
        view.keyboard.setLayout(composedLayout(alphabeticLayout))
        applyWritingDirection(view)
        view.suggestionStrip.listener = this
        view.suggestionStrip.visibleLimit = preferences.suggestionCount
        view.quickSettings.listener = this
        view.quickActions.listener = this
        view.clipboardPanel.listener = this
        view.languageRevertPanel.listener = this
        view.explainPanel.listener = this
        view.radialSuggestionMenu.listener = this
        view.emojiPanel.listener = EmojiPanelView.Listener { emoji -> onEmojiPicked(emoji) }
        view.emojiPanel.recents = preferences.emojiRecents
        view.emojiPanel.keywords = dictionaryLoader.emojiKeywords
        applyQuickActions(view)
        view.onMoveToOtherSide = { moveKeyboardToOtherSide() }
        view.onResizeDrag = { height, width, offset -> previewResize(height, width, offset) }
        view.onResizeFinished = { commitResize() }
        view.onResizeExit = { endResize() }
        view.fullWidthBackground = effectiveTheme().fullWidthBackground
        view.navigationBarBackground = effectiveTheme().navigationBarBackground
        view.opacity = effectiveTheme().opacity
        view.onThemeChanged()
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> pushKeyGeometry() }
        host = view
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        subtypeTags = null
        chosenSubtypes = null

        val session = FieldSession(
            generation = orchestrator.session.generation + 1,
            policy = FieldPolicy.of(
                passwordField = info != null && PrivateMode.isPasswordField(info.inputType),
                privateField = PrivateMode.isPrivate(info),
                learningEnabled = preferences.learningEnabled,
                heatmapEnabled = preferences.heatmapEnabled,
                userUnlocked = unlocked,
            ),
            addressField = info != null && AddressField.isAddress(info.inputType),
            terminalField = TerminalField.isTerminal(info, preferences.terminalPackages),
            inputType = info?.inputType ?: 0,
            imeOptions = info?.imeOptions ?: 0,
            initialCapsMode = info?.initialCapsMode ?: 0,
            described = info != null,
            contentMimeTypes = info?.contentMimeTypes?.toList().orEmpty(),
        )
        engine.setLearningSpeed(
            KeyboardPreferences.learningSpeedFactor(preferences.learningSpeed),
        )
        engine.setCorrectionStrictness(preferences.correctionStrictness)
        engine.setLanguageLock(
            KeyboardPreferences.languageLockEvidence(preferences.languageLock),
            KeyboardPreferences.languageLockStrict(preferences.languageLock),
        )
        engine.setPreferredLanguage(preferences.preferredLanguageTag)
        if (!remembersLanguage(preferences)) {
            engine.resetLanguageEvidence()
        }
        engine.setPhraseSuggestions(preferences.phraseSuggestions)

        privateReveal = false
        host?.let { view ->
            view.suggestionStrip.privateMode = session.policy.privateField
            view.suggestionStrip.privateReveal = false
            view.suggestionStrip.privateText = null
            view.suggestionStrip.clear()
            applyHaptics(view, preferences)
            view.keyboard.swipeEnabled = preferences.swipeEnabled && session.policy.suggestionsAllowed
            view.suggestionStripEnabled = preferences.showSuggestionStrip
            view.keyboard.soundEnabled = preferences.keySound
            view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
            applyParticleSettings(view, particleEffects)
        }
        showPage(pageFor(info))
        orchestrator.startField(session)
        // Posted, to run after the first layout.
        host?.post { syncDebugRing() }

        host?.setClipboardPanelVisible(false)
        host?.setEmojiPanelVisible(false)
        registerClipboardListener()
        refreshClipboardChip()
        pushKeyGeometry()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        orchestrator.dismissRing()
        clipOffers.keyboardClosed(
            preferences.clipboardSuggestionOnce,
            currentInputEditorInfo?.packageName,
        )
        unregisterClipboardListener()
        saveLanguageEvidence()
    }

    /** Whether a field starts in the language the last one was written in. */
    private fun remembersLanguage(settings: KeyboardPreferences): Boolean =
        settings.rememberDetectedLanguage && settings.preferredLanguageTag.isEmpty()

    /** Writes the language evidence to [LANGUAGE_EVIDENCE_PREFS], or empties it when not kept. */
    private fun saveLanguageEvidence() {
        if (!unlocked) {
            return
        }
        val store = getSharedPreferences(LANGUAGE_EVIDENCE_PREFS, MODE_PRIVATE)
        if (!remembersLanguage(preferences)) {
            store.edit().clear().apply()
            return
        }
        engine.languageEvidence { evidence ->
            if (evidence == null) {
                return@languageEvidence
            }
            val editor = store.edit().clear()
            for ((tag, value) in evidence) {
                editor.putFloat(tag, value)
            }
            editor.apply()
        }
    }

    /** Puts back the evidence [saveLanguageEvidence] wrote, when the settings keep it. */
    private fun restoreLanguageEvidence() {
        if (!remembersLanguage(DataGraph.themes.currentPreferences())) {
            return
        }
        val saved = getSharedPreferences(LANGUAGE_EVIDENCE_PREFS, MODE_PRIVATE).all
            .mapNotNull { (tag, value) -> (value as? Float)?.let { tag to it } }
            .toMap()
        if (saved.isNotEmpty()) {
            engine.restoreLanguageEvidence(saved)
        }
    }

    /** Closes the ring when the window hides. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        orchestrator.dismissRing()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        orchestrator.finishField()
        if (!unlocked) {
            return
        }
        scope.launch(Dispatchers.IO) {
            if (preferences.clearClipboardOnClose) {
                DataGraph.clipboard.deleteUnpinned()
            }
            DataGraph.clipboard.purgeExpired()
        }
    }

    /** Redraws with the wallpaper's colours when the wallpaper changes. */
    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (preferences.followSystemColors) {
                refreshTheme()
            }
        }
    }

    /** Re-applies [effectiveTheme] to what is on screen. */
    private fun refreshTheme() {
        val view = host ?: return
        val changed =
            paints.update(effectiveTheme(), resources.displayMetrics, activePlacement().heightScale, this)
        view.fullWidthBackground = effectiveTheme().fullWidthBackground
        view.navigationBarBackground = effectiveTheme().navigationBarBackground
        if (changed) {
            view.keyboard.onThemeChanged()
            view.quickSettings.onThemeChanged()
            view.onThemeChanged()
            view.relayoutForNewMetrics()
        }
    }

    /** Applies the other orientation's size and position when the phone rotates. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = host ?: return
        applyPlacement(view, preferences)
        refreshTheme()
        if (preferences.subtypeLayoutsLandscape.isNotEmpty()) {
            reloadLetterLayout()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        unlockRegistration?.invoke()
        unregisterClipboardListener()
        // Runs while the engine is still alive.
        super.onDestroy()
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        orchestrator.shutdown()
        engine.shutdown()
        scope.cancel()
        host = null
    }

    // ---- geometry ------------------------------------------------------------------------------

    /** Moves a narrowed keyboard to the opposite side, or mirrors a floating one's offset. */
    private fun moveKeyboardToOtherSide() {
        val landscape = isLandscape()
        updatePreferences { current ->
            current.withPlacement(landscape) { placement ->
                when (placement.positionMode) {
                    KeyboardPreferences.MODE_ONE_HANDED_LEFT ->
                        placement.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_RIGHT)
                    KeyboardPreferences.MODE_ONE_HANDED_RIGHT ->
                        placement.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_LEFT)
                    KeyboardPreferences.MODE_FLOATING ->
                        placement.copy(horizontalOffsetDp = -placement.horizontalOffsetDp)
                    else -> placement
                }
            }
        }
    }

    /**
     * Asks the system to blur what shows beside a narrowed keyboard; not for a docked keyboard or
     * a full-width background. Where the system refuses, there is no blur.
     */
    private fun applyBlur(settings: KeyboardPreferences) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return
        }
        val target = window?.window ?: return
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
        val placement = settings.placementFor(isLandscape())
        view.edgeArrows = settings.edgeArrows
        applyBlur(settings)
        view.setPlacement(
            placement.positionMode,
            placement.widthScale,
            (placement.bottomOffsetDp * density).toInt(),
            (placement.horizontalOffsetDp * density).toInt(),
        )
    }

    /** Pushes each region's particle-effect settings onto the view that draws that region. */
    private fun applyParticleSettings(view: KeyboardHostView, settings: ParticleEffectsSettings) {
        val surfaces = listOf(
            view.keyboard.particles to settings.keyboard,
            view.radialSuggestionMenu.particles to settings.radial,
            view.suggestionStrip.particles to settings.strip,
            view.languageRevertPanel.particles to settings.languageRevert,
            view.quickActions.particles to settings.quickActions,
        )
        for ((surface, region) in surfaces) {
            applyParticleLayer(surface, region)
        }
    }

    private fun pushKeyGeometry() {
        val view = host?.keyboard ?: return
        val keyWidth = view.averageKeyWidth
        val keyHeight = view.averageKeyHeight
        if (keyWidth <= 0f || keyHeight <= 0f) {
            return
        }
        engine.setKeyGeometry(
            0, keyWidth, keyHeight,
            aliases = { codes, bases -> view.exportAliases(codes, bases) },
        ) { codes, x, y ->
            view.exportGeometry(codes, x, y)
        }
        orchestrator.keyGeometry = keyGeometrySnapshot(view, keyWidth, keyHeight)
    }

    /** The letter keys of [view] as its taps land on them, in the current bucket. */
    private fun keyGeometrySnapshot(
        view: KeyboardCanvasView,
        keyWidth: Float,
        keyHeight: Float,
    ): KeyGeometrySnapshot {
        val codes = IntArray(MAX_SNAPSHOT_KEYS)
        val centreX = FloatArray(MAX_SNAPSHOT_KEYS)
        val centreY = FloatArray(MAX_SNAPSHOT_KEYS)
        val count = view.exportGeometry(codes, centreX, centreY)
        return KeyGeometrySnapshot(
            bucket = KeyGeometrySnapshot.Bucket(
                landscape = isLandscape(),
                positionMode = activePlacement().positionMode,
                layoutId = view.layoutId,
            ),
            keyWidth = keyWidth,
            keyHeight = keyHeight,
            density = resources.displayMetrics.density,
            codes = codes.copyOf(count),
            centreX = centreX.copyOf(count),
            centreY = centreY.copyOf(count),
        )
    }

    // ---- key handling ----------------------------------------------------------------------------

    override fun onKeyDown(code: Int) = Unit

    override fun onCursorNudge(steps: Int) = orchestrator.onCursorNudge(steps)

    override fun onCursorNudgeLines(lines: Int) = orchestrator.onCursorNudgeLines(lines)

    override fun onBackspaceSelect(steps: Int) = orchestrator.onBackspaceSelect(steps)

    override fun onBackspaceSelectLines(lines: Int) = orchestrator.onBackspaceSelectLines(lines)

    override fun onBackspaceSelectionLift() = orchestrator.onBackspaceSelectionLift()

    /** A completed swipe; its last point anchors a ring that opens after the lift. */
    override fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        orchestrator.onGesture(xs, ys, timestamps, count)
    }

    /** The swipe's path in key widths, its duration and its sample count, for the stats. */
    private fun recordSwipeShape(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (count < 2) {
            return
        }
        var path = 0.0
        for (index in 1 until count) {
            val dx = (xs[index] - xs[index - 1]).toDouble()
            val dy = (ys[index] - ys[index - 1]).toDouble()
            path += Math.sqrt(dx * dx + dy * dy)
        }
        val keyWidth = host?.keyboard?.keyWidthPx ?: 0f
        if (keyWidth > 0f) {
            KeyboardStats.swipePathKeys.add(path / keyWidth)
        }
        KeyboardStats.swipeMillis.add((timestamps[count - 1] - timestamps[0]).toDouble())
        KeyboardStats.swipeSamples.add(count.toDouble())
        KeyboardStats.input(gestureLiftedAt)
    }

    /** The decode's timings and its candidate count, for the stats. */
    private fun recordSwipeDecode(candidates: Int) {
        KeyboardStats.decodeMillis.add(engine.lastGestureDecodeMicros / 1000.0)
        KeyboardStats.liftToTextMillis.add(
            (android.os.SystemClock.uptimeMillis() - gestureLiftedAt).toDouble(),
        )
        KeyboardStats.swipeCandidates.add(candidates.toDouble())
        KeyboardStats.neuralDecoder = engine.lastGestureUsedNeural
        KeyboardStats.words++
    }

    /** The finger paused mid-swipe; the pause point anchors the ring and starts its steering. */
    override fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        steerLeftPausePoint = false
        orchestrator.onGesturePaused(xs, ys, timestamps, count)
    }

    /** Whether the steering finger has moved past the touch slop since the pause. */
    private var steerLeftPausePoint = false

    /** Steering on the same touch that paused, forwarded to the ring. */
    override fun onGestureSteered(x: Float, y: Float) {
        val view = host ?: return
        // Nothing steers until the finger leaves the pause point by more than the touch slop.
        if (!steerLeftPausePoint) {
            val slop = view.keyboard.touchSlopPx
            if (hypot(x - lastGestureX, y - lastGestureY) <= slop) {
                return
            }
            steerLeftPausePoint = true
        }
        // The ring spans the whole host, so the point is offset by the keyboard's origin.
        val menu = view.radialSuggestionMenu
        val before = menu.currentSelection()
        menu.steerTo(x + view.keyboard.left, y + view.keyboard.top)
        // A change of highlight cancels the pick timeout.
        if (menu.currentSelection() != before) {
            view.removeCallbacks(radialTimeoutRunnable)
        }
    }

    override fun onGestureRingResolved() = orchestrator.onRingLifted()

    override fun onGestureRingCancelled() = orchestrator.onRingCancelled()

    override fun onRadialTapResolved(selection: RadialSuggestionMenuView.Selection) =
        orchestrator.onRingTapped(
            when (selection) {
                is RadialSuggestionMenuView.Selection.Word ->
                    RingUi.Selection.Word(selection.index, selection.word)
                RadialSuggestionMenuView.Selection.Cancel -> RingUi.Selection.Cancel
                RadialSuggestionMenuView.Selection.None -> RingUi.Selection.None
            },
        )

    /**
     * A touch outside a ring waiting for a tap: closes the ring, and hides the keyboard when
     * [KeyboardPreferences.radialOutsideTapHidesKeyboard] is on.
     */
    override fun onRadialDismissed() {
        orchestrator.dismissRing()
        if (preferences.radialOutsideTapHidesKeyboard) {
            requestHideSelf(0)
        }
    }

    /**
     * Where the editor sits on screen, reported while a ring is open. A move from the first
     * report closes the ring when [KeyboardPreferences.radialCloseOnEditorMove] is on.
     */
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (cursorAnchorInfo == null || swipeRadialController.state != SwipeRadialController.State.OPEN) {
            return
        }
        val values = FloatArray(9)
        cursorAnchorInfo.matrix.getValues(values)
        val x = values[Matrix.MTRANS_X]
        val y = values[Matrix.MTRANS_Y]
        if (ringEditorOriginX.isNaN()) {
            ringEditorOriginX = x
            ringEditorOriginY = y
            return
        }
        if (preferences.radialCloseOnEditorMove &&
            hypot(x - ringEditorOriginX, y - ringEditorOriginY) > EDITOR_MOVE_DISMISS_PX
        ) {
            orchestrator.dismissRing()
        }
    }

    /**
     * While the window reaches above the keys ([KeyboardHostView.reserveScreenAbove]), reports
     * the keys' top as the content top and makes the whole window touchable.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = host ?: return
        if (view.reserveScreenAbove) {
            outInsets.contentTopInsets += view.keyboardAreaTop
            outInsets.visibleTopInsets += view.keyboardAreaTop
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        }
    }

    /** Whether a real ring, not the debug ring, is open. */
    private fun ringOwnsWholeScreen(): Boolean =
        swipeRadialController.state == SwipeRadialController.State.OPEN && !debugRingOpen

    /** Grows the window to the top of the screen while a ring is open, and back when it closes. */
    private fun refreshTouchableArea() {
        host?.reserveScreenAbove = ringOwnsWholeScreen()
    }

    /** Starts or stops the editor's cursor-anchor reports for [onUpdateCursorAnchorInfo]. */
    private fun watchEditorWhileRingOpen(watch: Boolean) {
        ringEditorOriginX = Float.NaN
        ringEditorOriginY = Float.NaN
        val connection = currentInputConnection ?: return
        if (watch && preferences.radialCloseOnEditorMove) {
            connection.requestCursorUpdates(
                InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
            )
        } else {
            connection.requestCursorUpdates(0)
        }
    }

    override fun onGesturePreviewCandidates(candidates: List<Candidate>) =
        orchestrator.onGesturePreviewCandidates(candidates)

    /**
     * Where the ring is centred, per [KeyboardPreferences.radialMenuAnchor], in the host's
     * coordinates. The tangent modes give the keyboard's edge; [RadialSuggestionMenuView.show]
     * keeps the ring inside the view.
     */
    private fun radialAnchor(view: KeyboardHostView): Pair<Float, Float> {
        val x = view.keyboard.left.toFloat()
        val y = view.keyboard.top.toFloat()
        return when (preferences.radialMenuAnchor) {
            KeyboardPreferences.RADIAL_ANCHOR_CENTER ->
                x + view.keyboard.width / 2f to y + view.keyboard.height / 2f
            KeyboardPreferences.RADIAL_ANCHOR_TANGENT_LEFT -> x to y + lastGestureY
            KeyboardPreferences.RADIAL_ANCHOR_TANGENT_RIGHT ->
                x + view.keyboard.width.toFloat() to y + lastGestureY
            else -> x + lastGestureX to y + lastGestureY
        }
    }

    /**
     * Opens a tap-only sample ring while [KeyboardPreferences.debugForceRadialRing] is on and no
     * ring is open. Debuggable builds only.
     */
    private fun syncDebugRing() {
        val view = host ?: return
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || !preferences.debugForceRadialRing || !preferences.radialMenuEnabled) {
            return
        }
        if (swipeRadialController.state == SwipeRadialController.State.OPEN || view.keyboard.width == 0) {
            return
        }
        val words = DEBUG_RING_WORDS.take(preferences.radialSuggestionCount)
        if (!swipeRadialController.onRingOpened(words)) {
            return
        }
        debugRingOpen = true
        val x = view.keyboard.left + view.keyboard.width / 2f
        val y = view.keyboard.top + view.keyboard.height / 2f
        view.radialSuggestionMenu.show(x, y, words)
        view.radialSuggestionMenu.acceptsOwnTouches = true
        view.setRadialMenuVisible(true)
    }

    /**
     * Whether the open ring is [syncDebugRing]'s sample. It ignores dismissals and reopens after
     * it resolves; only [closeDebugRing] ends it.
     */
    private var debugRingOpen = false

    private fun closeDebugRing() {
        if (!debugRingOpen) {
            return
        }
        debugRingOpen = false
        closeRadialRing()
    }

    /**
     * Closes the ring: cancels the pick timeout, tells the controller, and hides the view.
     * [celebrateIndex] is the picked wedge, which gets a burst, or null.
     */
    private fun closeRadialRing(celebrateIndex: Int? = null) {
        val view = host
        view?.removeCallbacks(radialTimeoutRunnable)
        val wasOpen = swipeRadialController.state == SwipeRadialController.State.OPEN
        swipeRadialController.onResolved()
        if (wasOpen) {
            watchEditorWhileRingOpen(false)
            refreshTouchableArea()
            // The stroke that opened the ring ends with it.
            view?.keyboard?.abandonRingStroke()
        }
        if (debugRingOpen) {
            // The debug ring reopens on the next frame.
            debugRingOpen = false
            view?.post { syncDebugRing() }
        }
        if (celebrateIndex != null) {
            view?.radialSuggestionMenu?.celebrate(celebrateIndex)
        } else {
            view?.radialSuggestionMenu?.hide()
        }
        view?.setRadialMenuVisible(false)
    }

    override fun onGestureCandidates(candidates: List<Candidate>) =
        orchestrator.onGestureCandidates(candidates)

    override fun onKeyRepeat(code: Int) = orchestrator.onKeyRepeat(code)

    override fun onText(text: CharSequence) = orchestrator.onText(text)

    /**
     * A flick on the key at [keyIndex]: the user's own flick for the key's code first, else the
     * layout's text; a key the orchestrator leaves goes where a tap on it goes.
     */
    override fun onFlick(keyIndex: Int, direction: Int) {
        val view = host ?: return
        val code = view.keyboard.keyCodeAt(keyIndex)
        val own = preferences.keyFlicks.firstOrNull { it.keyCode == code && it.direction == direction }
        if (own != null) {
            if (!orchestrator.onFlick(own)) {
                onKey(KeyCodes.named(own.value), keyIndex, Float.NaN, Float.NaN)
            }
            return
        }
        val extra = extraKeyPlacements.firstOrNull { it.keyCode == code && it.direction == direction }
        if (extra != null) {
            val name = extra.keyName
            if (name != null) {
                onKey(KeyCodes.named(name), keyIndex, Float.NaN, Float.NaN)
            } else {
                orchestrator.onText(extra.text)
            }
            return
        }
        val text = view.keyboard.flickTextAt(keyIndex, direction)
        if (text.isNotEmpty()) {
            orchestrator.onText(text)
        }
    }

    /** The typing flow's keys go to [orchestrator]; pages, panels and the modifiers stay here. */
    override fun onKey(code: Int, keyIndex: Int, x: Float, y: Float) {
        if (orchestrator.onKey(code, keyIndex, x, y)) {
            return
        }
        when (code) {
            KeyCodes.SYMBOLS -> showPage(
                if (page == PAGE_ALPHABETIC) PAGE_SYMBOLS else PAGE_ALPHABETIC,
            )
            KeyCodes.SYMBOLS_SHIFT -> showPage(
                if (page == PAGE_SYMBOLS) PAGE_SYMBOLS_SHIFT else PAGE_SYMBOLS,
            )
            KeyCodes.LANGUAGE -> switchLanguage()
            KeyCodes.SETTINGS -> toggleQuickSettings()
            KeyCodes.KEYBOARD_PICKER -> pickKeyboard(hold = false)
            KeyCodes.VOICE -> voiceInput(hold = false)
            KeyCodes.EMOJI -> toggleEmojiPanel()
            KeyCodes.CONTROL -> setArmedModifiers(control = !controlArmed, alt = altArmed)
            KeyCodes.ALT -> setArmedModifiers(control = controlArmed, alt = !altArmed)
        }
    }

    private fun setArmedModifiers(control: Boolean, alt: Boolean) {
        controlArmed = control
        altArmed = alt
        host?.keyboard?.setArmedModifiers(control, alt)
    }

    /**
     * Sends [keyCode] to the application as a pressed and released hardware key carrying
     * [meta] and the armed modifiers, the modifier keys themselves going down before it and
     * up after it. The armed modifiers are released by the send.
     */
    private fun sendPhysicalKey(connection: InputConnection, keyCode: Int, meta: Int) {
        var state = meta
        if (controlArmed) {
            state = state or android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        }
        if (altArmed) {
            state = state or android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON
        }
        val time = android.os.SystemClock.uptimeMillis()
        val down = android.view.KeyEvent.ACTION_DOWN
        val up = android.view.KeyEvent.ACTION_UP
        if (controlArmed) {
            connection.sendKeyEvent(physicalKeyEvent(time, down, android.view.KeyEvent.KEYCODE_CTRL_LEFT, state))
        }
        if (altArmed) {
            connection.sendKeyEvent(physicalKeyEvent(time, down, android.view.KeyEvent.KEYCODE_ALT_LEFT, state))
        }
        connection.sendKeyEvent(physicalKeyEvent(time, down, keyCode, state))
        connection.sendKeyEvent(physicalKeyEvent(time, up, keyCode, state))
        if (altArmed) {
            connection.sendKeyEvent(physicalKeyEvent(time, up, android.view.KeyEvent.KEYCODE_ALT_LEFT, meta))
        }
        if (controlArmed) {
            connection.sendKeyEvent(physicalKeyEvent(time, up, android.view.KeyEvent.KEYCODE_CTRL_LEFT, meta))
        }
        if (controlArmed || altArmed) {
            setArmedModifiers(control = false, alt = false)
        }
    }

    private fun physicalKeyEvent(time: Long, action: Int, keyCode: Int, meta: Int) =
        android.view.KeyEvent(
            time, time, action, keyCode, 0, meta,
            android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            android.view.KeyEvent.FLAG_KEEP_TOUCH_MODE,
            android.view.InputDevice.SOURCE_KEYBOARD,
        )

    /**
     * Holding a key that has no alternates: space and the globe switch the layout, shift locks,
     * backspace deletes a word, the picker key opens the system's keyboard picker, the voice key
     * its chooser, and enter or the settings key open the quick panel.
     */
    override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean {
        if (code == ' '.code) {
            switchLanguage()
            return true
        }
        if (orchestrator.onKeyLongPress(code)) {
            return true
        }
        when (code) {
            KeyCodes.LANGUAGE -> switchLanguage()
            KeyCodes.KEYBOARD_PICKER -> pickKeyboard(hold = true)
            KeyCodes.VOICE -> voiceInput(hold = true)
            KeyCodes.ENTER, KeyCodes.SETTINGS -> toggleQuickSettings()
            else -> return false
        }
        return true
    }

    override fun onLanguageRevertPicked(replacement: LanguageSwitchCorrector.Replacement) {
        orchestrator.applyLanguageSwitchReplacements(listOf(replacement))
        val remaining = host?.languageRevertPanel?.remove(replacement) ?: 0
        if (remaining == 0) {
            host?.setLanguageRevertPanelVisible(false)
        }
    }

    override fun onLanguageRevertDismissed() {
        host?.setLanguageRevertPanelVisible(false)
    }

    /**
     * Applies the layout settings that compose onto a page: the accent overlays, the digits on
     * the top row or in a row of their own, the modifier row, the emoji key and the globe key.
     * [allowNumberRow] is false for the number-pad symbols pages.
     */
    private fun composedLayout(layout: KeyboardLayout, allowNumberRow: Boolean = true): KeyboardLayout {
        var result = layout
        val extra = if (preferences.extraAccents) {
            preferences.extraAccentLanguages.filter { it !in dictionaryLoader.accentTags }
        } else {
            emptyList()
        }
        val accents = AccentOverlays.withExtra(
            dictionaryLoader.accentOverlays, dictionaryLoader.accentTags, extra, dictionaryLoader.everyAccentOverlay,
        )
        if (preferences.accentedCharacters && accents.isNotEmpty()) {
            result = result.withAccents(accents, dictionaryLoader.accentSignature + extra.joinToString("") { ",$it" })
        }
        // The top letter row hints digits, or symbols once the number row holds the digits.
        result = if (preferences.numberRow && allowNumberRow) {
            result.withTopRowSymbols()
        } else {
            result.withTopRowDigits()
        }
        if (preferences.numberRow && allowNumberRow) {
            result = result.withNumberRow()
        }
        if (preferences.modifierRow) {
            val names = com.borderkeys.data.theme.ModifierRowKeys.drawn(
                preferences.modifierRowKeys,
                accentKeys = preferences.deadKeys,
                voice = voiceKeyboards().isNotEmpty(),
            )
            result = result.withModifierRow(
                keys = names.map { KeyCodes.named(it) },
                atBottom = preferences.modifierRowPosition == KeyboardPreferences.MODIFIER_ROW_BELOW,
            )
        }
        if (!preferences.emojiKey) {
            result = result.withoutEmojiKey()
        }
        if (!preferences.languageKey || chosenSubtypeCount() < ChosenSubtypes.GLOBE_FROM) {
            result = result.withoutLanguageKey()
        }
        if (preferences.keyFlicks.isNotEmpty()) {
            result = result.withFlickLabels(flickLabelsByKey(preferences.keyFlicks))
        }
        val owned = preferences.keyFlicks.mapTo(HashSet()) { (it.keyCode.toLong() shl 3) or it.direction.toLong() }
        val placements = ExtraKeys.place(
            enabledSubtypeTags().flatMap { extraKeysByLanguage[it].orEmpty() },
            result,
            preferences.extraKeysOff.toSet(),
        ) { code, direction -> ((code.toLong() shl 3) or direction.toLong()) in owned }
        if (placements.isNotEmpty()) {
            val labels = HashMap<Int, Array<String>>()
            for (placement in placements) {
                labels.getOrPut(placement.keyCode) { Array(KeyboardLayout.FLICK_DIRECTIONS) { "" } }[placement.direction] =
                    placement.text
            }
            result = result.withFlickLabels(labels)
        }
        if (layout === alphabeticLayout) {
            extraKeyPlacements = placements
        }
        return result
    }

    /** The user's flick labels by key code, eight per key north first and clockwise, "" for none. */
    private fun flickLabelsByKey(flicks: List<KeyFlick>): Map<Int, Array<String>> {
        val byKey = HashMap<Int, Array<String>>()
        for (flick in flicks) {
            val labels = byKey.getOrPut(flick.keyCode) { Array(KeyboardLayout.FLICK_DIRECTIONS) { "" } }
            labels[flick.direction] = KeyFlick.shownLabel(flick).ifEmpty { FLICK_PLACEHOLDER }
        }
        return byKey
    }

    /** The symbols page [KeyboardPreferences.symbolsNumberPosition] asks for. */
    private fun symbolsPage(): KeyboardLayout = when (preferences.symbolsNumberPosition) {
        KeyboardPreferences.SYMBOLS_NUMBER_LEFT -> symbolsNumpadLeftLayout
        KeyboardPreferences.SYMBOLS_NUMBER_RIGHT -> symbolsNumpadRightLayout
        else -> symbolsLayout
    }

    private fun showPage(next: Int) {
        page = next
        // Every page but the numeric keypad is composed.
        val layout = when (next) {
            PAGE_SYMBOLS -> composedLayout(
                symbolsPage(),
                allowNumberRow = preferences.symbolsNumberPosition == KeyboardPreferences.SYMBOLS_NUMBER_TOP,
            )
            PAGE_SYMBOLS_SHIFT -> composedLayout(symbolsShiftLayout)
            PAGE_NUMPAD -> numpadLayout
            else -> composedLayout(alphabeticLayout)
        }
        host?.keyboard?.setLayout(layout)
        orchestrator.modmap = layout.modmap
        host?.let { applyWritingDirection(it) }
        pushKeyGeometry()
    }

    /** The strip, the ring and the panels follow the alphabetic layout's reading direction. */
    private fun applyWritingDirection(view: KeyboardHostView) {
        view.suggestionStrip.rightToLeft = alphabeticLayout.rightToLeft
        view.radialSuggestionMenu.rightToLeft = alphabeticLayout.rightToLeft
        view.emojiPanel.rightToLeft = alphabeticLayout.rightToLeft
        view.clipboardPanel.rightToLeft = alphabeticLayout.rightToLeft
        view.quickActions.rightToLeft = alphabeticLayout.rightToLeft
    }

    /** The page a field asks for: the numeric keypad for a number or phone field, when set to. */
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

    /** The language tag of the input-method subtype the globe key last selected, or "und". */
    private fun currentSubtypeTag(): String {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        val tag = manager?.currentInputMethodSubtype?.languageTag
        return if (tag.isNullOrBlank()) "und" else tag
    }

    /**
     * The `layouts/<id>.json` asset [subtype]'s `layout=` extra value names, or
     * [DEFAULT_ALPHABETIC_LAYOUT] when it names none or a `*_qwerty` id.
     */
    private fun layoutIdFromSubtype(subtype: android.view.inputmethod.InputMethodSubtype?): String =
        LayoutChoice.layoutIdOf(subtype?.extraValue)

    /** Loads the letter layout the new subtype names and redraws the current page. */
    override fun onCurrentInputMethodSubtypeChanged(subtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        subtypeLayoutId = layoutIdFromSubtype(subtype)
        subtypeTags = null
        scope.launch(Dispatchers.IO) {
            val layout = resolveLetterLayout()
            withContext(Dispatchers.Main) {
                alphabeticLayout = layout
                useSwipeModelForLayout()
                host?.let { showPage(page) }
            }
        }
    }

    /** Switches to this input method's next subtype, that is its next layout. */
    /**
     * The next of this keyboard's enabled subtypes, in the order the Your layouts list puts the
     * layouts they draw; the platform's own next when there are not two.
     */
    private fun switchLanguage() {
        val manager = inputMethodManager()
        val ours = manager?.enabledInputMethodList?.firstOrNull { it.packageName == packageName }
        val subtypes = ours?.let { manager.getEnabledInputMethodSubtypeList(it, true) }.orEmpty()
        if (ours == null || subtypes.size < 2) {
            switchToNextInputMethod(true)
            return
        }
        val choices = LayoutChoice.forOrientation(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape, isLandscape())
        val drawn = subtypes.map { subtype -> LayoutChoice.layoutIdOf(subtype.extraValue).let { choices[it] ?: it } }
        val order = LayoutChoice.cycleOrder(drawn, preferences.layoutOrder)
        val current = manager.currentInputMethodSubtype
        val at = order.indexOfFirst { subtypes[it] == current }
        val next = subtypes[order[(at + 1).mod(order.size)]]
        switchInputMethod(ours.id, next)
    }

    private fun inputMethodManager(): android.view.inputmethod.InputMethodManager? =
        getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager

    /**
     * The picker key: a tap goes back to the previous keyboard when the setting says so, and
     * opens the system's picker otherwise or on [hold], or when there is no previous keyboard.
     */
    private fun pickKeyboard(hold: Boolean) {
        if (!hold && preferences.pickerKeySwitchesBack && switchToPreviousInputMethod()) {
            return
        }
        inputMethodManager()?.showInputMethodPicker()
    }

    /** The enabled input methods offering a voice subtype, this keyboard aside, with that subtype. */
    private fun voiceKeyboards(): List<Pair<android.view.inputmethod.InputMethodInfo, android.view.inputmethod.InputMethodSubtype>> {
        val manager = inputMethodManager() ?: return emptyList()
        return manager.enabledInputMethodList
            .filter { it.packageName != packageName }
            .mapNotNull { method ->
                manager.getEnabledInputMethodSubtypeList(method, true)
                    .firstOrNull { it.mode == VOICE_SUBTYPE_MODE }
                    ?.let { method to it }
            }
    }

    /**
     * The voice key: switches to the voice keyboard [VoiceInput] decides on and remembers it,
     * or shows the system's picker when it decides on that, or when no keyboard offers voice typing.
     */
    private fun voiceInput(hold: Boolean) {
        val keyboards = voiceKeyboards()
        val available = keyboards.map { it.first.id }
        when (val step = VoiceInput.decide(available, preferences.voiceKeyboardId, preferences.voiceKeyboardSet, hold)) {
            is VoiceInput.Step.Switch -> {
                val (method, subtype) = keyboards.first { it.first.id == step.id }
                val signature = VoiceInput.signature(available)
                if (preferences.voiceKeyboardId != step.id || preferences.voiceKeyboardSet != signature) {
                    updatePreferences { it.copy(voiceKeyboardId = step.id, voiceKeyboardSet = signature) }
                }
                switchInputMethod(method.id, subtype)
            }
            VoiceInput.Step.Picker, VoiceInput.Step.None -> inputMethodManager()?.showInputMethodPicker()
        }
    }

    // ---- the panel on the keyboard ------------------------------------------------------------

    /** Opens the quick panel, or closes it if it is open. */
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
            fullSettings = unlocked,
        )
    }

    /**
     * Writes the preferences to the store the settings application uses; before the first
     * unlock, their appearance fields to the device-protected copy, for this boot.
     */
    private fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch {
            if (unlocked) {
                DataGraph.themes.updatePreferences(transform)
            } else {
                DataGraph.lockedAppearance.update {
                    it.copy(preferences = transform(it.preferences).sanitised().forLockedStart())
                }
            }
        }
    }

    override fun onStartResize() {
        val view = host ?: return
        view.showQuickSettings(false)
        draggedHeight = activePlacement().heightScale
        draggedWidth = activePlacement().widthScale
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
        // withPositionMode also narrows a keyboard leaving the dock for the first time.
        val landscape = isLandscape()
        updatePreferences { it.withPositionMode(mode, landscape) }
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

    /**
     * Opens the settings application, on [screen] when one is named, with [clipId] for a
     * screen that edits a clipboard entry.
     */
    private fun openSettings(screen: String? = null, clipId: Long = -1L) {
        if (!unlocked) {
            return
        }
        // By class name, with no compile-time dependency on :settings.
        val intent = Intent(Intent.ACTION_MAIN)
            .setClassName(packageName, SETTINGS_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (screen != null) {
            intent.putExtra(SETTINGS_EXTRA_SCREEN, screen)
                .putExtra(SETTINGS_EXTRA_CLIP_ID, clipId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { startActivity(intent) }
    }

    // ---- suggestions ------------------------------------------------------------------------------

    override fun onSuggestionPicked(index: Int, word: String) = orchestrator.onPick(index, word)

    /** A suggestion held down: the strip offers Forget, Cancel and Why. */
    override fun onSuggestionLongPressed(index: Int, word: String) {
        if (orchestrator.session.policy.privateField || word.isEmpty()) {
            return
        }
        pendingForget = word
        pendingExplainQuery = orchestrator.lastQuery
        val actions = listOf(
            Candidate(strings.getString(Keys.ASSISTANT_FORGET, word)),
            Candidate(strings[Keys.ASSISTANT_CANCEL]),
            Candidate(strings[Keys.STRIP_WHY_THIS_WORD]),
        )
        host?.suggestionStrip?.setActions(actions)
    }

    /** Whether this is a debuggable build. */
    private val debuggable: Boolean
        get() = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * Shows the engine's own account of [word]'s score for [query] in the explain panel: one
     * line per term, in the catalogue's words, or the one line saying the word is not offered.
     */
    private fun explainWord(query: String, word: String) {
        val languages = orchestrator.languageTags
        engine.explain(query, word) { explanation ->
            val view = host ?: return@explain
            val lines = if (explanation == null) {
                listOf(strings.getString(Keys.STRIP_NOT_OFFERED, word))
            } else {
                explanationLines(explanation, languages)
            }
            view.explainPanel.show(strings.getString(Keys.STRIP_EXPLAIN_TITLE, word, query), lines)
            view.setExplainPanelVisible(true)
        }
    }

    private fun explanationLines(explanation: ScoreExplanation, languages: List<String>): List<String> {
        fun number(value: Float): String = String.format(Locale.ROOT, "%+.1f", value)
        val language = languages.getOrNull(explanation.packIndex)
            ?.let { Locale.forLanguageTag(it).displayLanguage }
            .orEmpty()
        return listOf(
            strings.getString(Keys.STRIP_EXPLAIN_RANK, (explanation.rank + 1).toString(), language),
            strings.getString(Keys.STRIP_EXPLAIN_LANGUAGE_MODEL, number(explanation.languageModel)),
            strings.getString(Keys.STRIP_EXPLAIN_PACK_WEIGHT, number(explanation.packWeight)),
            strings.getString(Keys.STRIP_EXPLAIN_PERSONAL, number(explanation.personal)),
            strings.getString(
                Keys.STRIP_EXPLAIN_EDITS,
                explanation.editDistance.toString(),
                explanation.addedCharacters.toString(),
                number(explanation.editsAndCompletion),
            ),
            strings.getString(Keys.STRIP_EXPLAIN_TOTAL, number(explanation.total)),
        )
    }

    override fun onExplainDismissed() {
        host?.setExplainPanelVisible(false)
        orchestrator.requestSuggestions()
    }

    /**
     * Forgets a personal word, with the pairs and triples it is part of. A word the personal
     * dictionary does not hold is blocked in lower case instead, when [blockWhenNotPersonal].
     */
    private fun forgetWord(word: String, blockWhenNotPersonal: Boolean = true) {
        if (!unlocked) {
            return
        }
        scope.launch {
            val dictionary = DataGraph.dictionary
            // Looked up ignoring case.
            val personal = dictionary.findIgnoreCase(word)
            if (personal != null) {
                dictionary.forget(personal.word)
            } else if (blockWhenNotPersonal) {
                dictionary.block(word.lowercase())
                dictionaryLoader.refreshBlockedWords(dictionary)
            }
            dictionaryLoader.loadPersonalModel(dictionary)
            orchestrator.requestSuggestions()
        }
    }

    override fun onSuggestions(
        candidates: List<Candidate>,
        knownWord: String,
        knownWordExact: Boolean,
        knownWordIsName: Boolean,
        query: String,
        possessive: String?,
        corrections: List<CorrectionOffer>,
    ) = orchestrator.onSuggestions(
        candidates, knownWord, knownWordExact, knownWordIsName, query, possessive, corrections,
    )

    // The words each event has shown an effect for, for this run only.
    private val effectsShown = HashMap<EffectEvent, MutableSet<String>>()

    /** Plays [event]'s effect for [word], per its style, frequency and colour settings. */
    private fun playEffect(event: EffectEvent, word: String) {
        val settings = preferences.effects
        if (!settings.enabled || word.isEmpty()) {
            return
        }
        val setting = settings.forEvent(event)
        // A style name this build does not know plays nothing.
        val style = EffectStyle.entries.firstOrNull { it.name == setting.style } ?: return
        if (setting.frequency == EffectFrequency.FirstTime &&
            !effectsShown.getOrPut(event) { HashSet() }.add(word.lowercase())
        ) {
            return
        }
        host?.effects?.playWord(word, style, setting.colour.takeIf { it != 0 })
    }

    private suspend fun persistLearning(batch: LearningBatch) {
        DataGraph.dictionary.applyLearned(batch.updates)
        DataGraph.dictionary.applyLearnedBigrams(batch.pairs)
        DataGraph.dictionary.applyLearnedTrigrams(batch.triples)
        DataGraph.dictionary.applyTouches(batch.touches, batch.touchHalfLifeMillis)
        maybeDecayPersonalDictionary()
    }

    /** Runs [DictionaryRepository.decayStaleEntries] at most once a day. */
    private suspend fun maybeDecayPersonalDictionary() {
        val prefs = getSharedPreferences(DECAY_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastSweep = prefs.getLong(DECAY_LAST_SWEEP_AT, 0L)
        if (now - lastSweep < DECAY_SWEEP_INTERVAL_MILLIS) {
            return
        }
        val limit = DataGraph.themes.preferences.first().learnedWordLimit
        DataGraph.dictionary.decayStaleEntries(limit, now)
        prefs.edit().putLong(DECAY_LAST_SWEEP_AT, now).apply()
    }

    // ---- clipboard --------------------------------------------------------------------------------------

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered || !unlocked || orchestrator.session.policy.privateField ||
            !preferences.clipboardEnabled
        ) {
            return
        }
        // Delivered only while this input method has focus.
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

    // ---- quick actions --------------------------------------------------------------------

    /** The size a drag is proposing, before it is written down. */
    private var draggedHeight = 1f
    private var draggedWidth = 1f

    /** Resizes the keyboard under the finger, on the views only; [commitResize] stores it. */
    private fun previewResize(height: Float, width: Float, offset: Float) {
        val view = host ?: return
        draggedHeight = height.coerceIn(
            KeyboardPreferences.MIN_HEIGHT_SCALE, KeyboardPreferences.MAX_HEIGHT_SCALE,
        )
        draggedWidth = width.coerceIn(KeyboardPreferences.MIN_WIDTH_SCALE, 1f)
        view.heightScaleForDrag = draggedHeight
        paints.update(effectiveTheme(), resources.displayMetrics, draggedHeight, this)
        val placement = activePlacement()
        view.setPlacement(
            placement.positionMode,
            draggedWidth,
            (placement.bottomOffsetDp * resources.displayMetrics.density).toInt(),
            (placement.horizontalOffsetDp * resources.displayMetrics.density).toInt(),
        )
        view.relayoutForNewMetrics()
    }

    /** Stores the size the finger stopped at for the orientation on screen. */
    private fun commitResize() {
        val height = draggedHeight
        val width = draggedWidth
        val landscape = isLandscape()
        updatePreferences {
            it.withPlacement(landscape) { placement ->
                placement.copy(heightScale = height, widthScale = width)
            }
        }
    }

    /**
     * Puts the saved bar on the view: which buttons, in what order, open or collapsed, and
     * against which edge. The draft-box button is left out while the draft box is off.
     */
    private fun applyQuickActions(view: KeyboardHostView) {
        val bar = view.quickActions
        if (!preferences.quickActionsEnabled || orchestrator.session.policy.privateField) {
            bar.visibility = View.GONE
            return
        }
        val chosen = QuickActionBar.resolve(preferences.quickActions, preferences.customQuickActions)
            .filterNot { it is QuickActionBarItem.Builtin && it.action == QuickAction.COMPOSE && !preferences.composerEnabled }
        if (chosen.isEmpty()) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        bar.items = chosen
        bar.collapsible =
            preferences.quickActionsMode == KeyboardPreferences.QUICK_ACTIONS_COLLAPSED
        bar.sizeLevel = preferences.quickActionsSize
        bar.showLabels = preferences.quickActionsLabels
        view.quickActionsPlacement = preferences.quickActionsPlacement
    }


    /**
     * Runs one of the bar's buttons: a single [QuickAction], or every step of a
     * [QuickActionBarItem.Custom] macro in order, then refreshes the suggestions once.
     */
    override fun onQuickAction(item: QuickActionBarItem) {
        val connection = currentInputConnection ?: return
        val steps = when (item) {
            is QuickActionBarItem.Builtin -> listOf(item.action)
            is QuickActionBarItem.Custom ->
                QuickActionBar.flatten(item.action, preferences.customQuickActions)
        }
        for (action in steps) {
            runQuickAction(connection, action)
        }
        // The actions in NO_REFRESH_QUICK_ACTIONS do not change the field.
        if (steps.singleOrNull() !in NO_REFRESH_QUICK_ACTIONS) {
            orchestrator.refreshContextFromEditor()
            orchestrator.requestSuggestions()
        }
    }

    /** Runs one quick action against [connection]. */
    private fun runQuickAction(connection: InputConnection, action: QuickAction) {
        when (action) {
            QuickAction.COPY_PREVIOUS_WORD -> copyToClipboard(wordBeforeCursor(connection))
            QuickAction.COPY_LINE -> copyToClipboard(lineAroundCursor(connection))
            // Copies the field's text as read, not through the editor's select-all.
            QuickAction.COPY_ALL -> copyToClipboard(
                connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty(),
            )
            QuickAction.PASTE -> onClipboardPicked()
            QuickAction.PRIVATE_COPY -> privateCopy()
            QuickAction.WORD_LEFT, QuickAction.WORD_RIGHT, QuickAction.SELECT_WORD_LEFT,
            QuickAction.SELECT_WORD_RIGHT, QuickAction.SELECT_TO_LINE_START,
            QuickAction.SELECT_TO_LINE_END, QuickAction.DELETE_WORD_FORWARD, QuickAction.ESCAPE,
            QuickAction.TAB -> orchestrator.runEditingAction(action)
            QuickAction.PICK_KEYBOARD -> pickKeyboard(hold = false)
            QuickAction.VOICE_INPUT -> voiceInput(hold = false)
            QuickAction.CLIPBOARD_HISTORY -> offerClipboardHistory()
            QuickAction.SELECT_ALL -> connection.performContextMenuAction(android.R.id.selectAll)
            // The editor's own cut, then the result recorded for undo.
            QuickAction.CUT -> {
                connection.performContextMenuAction(android.R.id.cut)
                orchestrator.checkpointField()
            }
            QuickAction.SELECT_WORD -> selectWordAtCursor(connection)
            QuickAction.DELETE_WORD -> orchestrator.deleteWordBeforeCursor()
            QuickAction.CURSOR_START -> {
                orchestrator.resetComposing()
                connection.setSelection(0, 0)
            }
            QuickAction.CURSOR_END -> {
                orchestrator.resetComposing()
                val all = connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0
                connection.setSelection(all, all)
            }
            QuickAction.NEWLINE -> {
                orchestrator.finishWord()
                connection.commitText("\n", 1)
                orchestrator.afterNewlineCommitted()
                orchestrator.clearContext()
            }
            QuickAction.SWITCH_LAYOUT -> switchLanguage()
            QuickAction.SETTINGS -> openSettings()
            QuickAction.COMPOSE -> {
                if (!unlocked || orchestrator.session.policy.privateField || !preferences.composerEnabled) return
                // The draft box starts with the selection, or with the whole field.
                val selection = currentInputConnection?.getSelectedText(0)?.toString().orEmpty()
                val whole = selection.ifEmpty {
                    connection.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString().orEmpty()
                }
                val seed = if (whole.length <= AssistProtocol.MAX_SELECTION_CHARS) whole else ""
                val intent = Intent(DraftProtocol.ACTION_QUICK_DRAFT)
                    .setClassName(packageName, SETTINGS_ACTIVITY)
                    .putExtra(Intent.EXTRA_PROCESS_TEXT, seed)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(intent) }
                return
            }
            QuickAction.UNDO -> orchestrator.undo()
            QuickAction.REDO -> orchestrator.redo()
            QuickAction.CAPITAL -> toggleCapitalAtCursor(connection)
            QuickAction.NORMALISE -> normaliseField(connection)
            // Sent as arrow key events.
            QuickAction.CURSOR_LEFT -> {
                orchestrator.resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            }
            QuickAction.CURSOR_RIGHT -> {
                orchestrator.resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            // The timestamp clears the context for the next word.
            QuickAction.TIMESTAMP -> {
                orchestrator.finishWord()
                connection.commitText(
                    TimestampPattern.format(preferences.timestampPattern, ZonedDateTime.now(), Locale.getDefault()),
                    1,
                )
                orchestrator.checkpointField()
                orchestrator.clearContext()
            }
        }
    }

    /** The word immediately before the cursor, empty when the cursor follows a space. */
    private fun wordBeforeCursor(connection: InputConnection): String {
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            return ""
        }
        var end = before.length
        while (end > 0 && !TypingOrchestrator.isWordCharacter(before[end - 1].code)) {
            end--
        }
        var start = end
        while (start > 0 && TypingOrchestrator.isWordCharacter(before[start - 1].code)) {
            start--
        }
        return before.substring(start, end)
    }

    /**
     * Flips the first letter of the word at the cursor (see [SentenceCase.wordAt]) by replacing
     * that one character through a selection, and puts the selection back.
     */
    private fun toggleCapitalAtCursor(connection: InputConnection) {
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        ) ?: return
        val text = extracted.text?.toString() ?: return
        val base = extracted.startOffset
        val range = SentenceCase.wordAt(
            text, extracted.selectionEnd, TypingOrchestrator::isWordCharacter,
        ) ?: return
        val word = text.substring(range.first, range.last + 1)
        val toggled = SentenceCase.toggleInitial(word)
        if (toggled == word) {
            return
        }
        connection.beginBatchEdit()
        orchestrator.finishWord()
        val first = base + range.first
        connection.setSelection(first, first + 1)
        connection.commitText(toggled.substring(0, 1), 1)
        connection.setSelection(base + extracted.selectionStart, base + extracted.selectionEnd)
        connection.endBatchEdit()
        orchestrator.dropPendingCorrection()
        orchestrator.checkpointField()
    }

    /**
     * Rewrites the span of the field that [SentenceCase.capitaliseSentences] changes, and puts
     * the selection back.
     */
    private fun normaliseField(connection: InputConnection) {
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        ) ?: return
        val current = extracted.text?.toString() ?: return
        val target = SentenceCase.capitaliseSentences(current)
        if (target == current) {
            return
        }
        val base = extracted.startOffset
        val span = FieldRestore.diff(current, target)
        connection.beginBatchEdit()
        orchestrator.finishWord()
        val boundary = base + span.deleteFrom + span.deleteCount
        connection.setSelection(boundary, boundary)
        if (span.deleteCount > 0) {
            connection.deleteSurroundingText(span.deleteCount, 0)
        }
        if (span.insert.isNotEmpty()) {
            connection.commitText(span.insert, 1)
        }
        connection.setSelection(base + extracted.selectionStart, base + extracted.selectionEnd)
        connection.endBatchEdit()
        orchestrator.dropPendingCorrection()
        orchestrator.checkpointField()
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

    /** Keeps the selection in the history as a private entry; the system clipboard is not touched. */
    private fun privateCopy() {
        if (!unlocked) {
            return
        }
        val text = orchestrator.privateCopyText() ?: return
        if (text.length > MAX_CLIP_LENGTH) {
            return
        }
        val source = currentInputEditorInfo?.packageName
        scope.launch(Dispatchers.IO) { DataGraph.clipboard.rememberPrivately(text, source) }
        typingHost.showNotice(Keys.CLIP_PRIVATE)
    }

    private fun copyToClipboard(text: String) {
        if (text.isEmpty() || orchestrator.session.policy.privateField) {
            return
        }
        val clip = ClipData.newPlainText(null, text)
        clipboardManager?.setPrimaryClip(clip)
        // The chip is built from the clip in hand, not read back from the clipboard.
        clipOffers.copied(clipSignature(clip), currentInputEditorInfo?.packageName)
        refreshClipboardChip(clip)
    }

    /** Opens the clipboard history as a panel of cards, read and decoded off the main thread. */
    private fun offerClipboardHistory() {
        if (!unlocked || orchestrator.session.policy.privateField || DirectBoot.isKeyguardLocked(this)) {
            return
        }
        scope.launch {
            val view = host ?: return@launch
            val (entries, thumbnails) = withContext(Dispatchers.IO) {
                val recent = DataGraph.clipboard.recent(MAX_CLIPBOARD_CARDS)
                recent to view.clipboardPanel.decodeThumbnails(recent)
            }
            view.clipboardPanel.query = searchWordAtCaret()
            view.clipboardPanel.setEntries(entries, thumbnails)
            view.setClipboardPanelVisible(true)
        }
    }

    override fun onClipEdited(entry: com.borderkeys.data.entity.ClipEntry) {
        host?.setClipboardPanelVisible(false)
        openSettings(screen = SETTINGS_SCREEN_CLIPBOARD, clipId = entry.id)
    }

    override fun onClipPicked(entry: com.borderkeys.data.entity.ClipEntry) {
        host?.setClipboardPanelVisible(false)
        if (entry.isImage) {
            // A stored image is served by the keyboard's own provider; a legacy entry by its URI.
            val media = entry.mediaFile
            val uri = if (media != null) {
                ClipMediaProvider.uriFor(this, media).toString()
            } else {
                entry.uri ?: return
            }
            orchestrator.pasteImage(uri, entry.mimeType ?: "image/*")
        } else {
            orchestrator.pasteText(entry.content)
        }
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

    /** Opens the emoji grid searched by the word at hand, or closes it. */
    private fun toggleEmojiPanel() {
        val view = host ?: return
        val show = !view.emojiPanelVisible
        view.setEmojiPanelVisible(show)
        if (show) {
            view.emojiPanel.query = searchWordAtCaret()
        }
    }

    /** The word being typed, or the letters just before the caret: the panels' search word. */
    private fun searchWordAtCaret(): String {
        val composing = orchestrator.composingText
        if (composing.isNotEmpty()) {
            return composing
        }
        val before = currentInputConnection?.getTextBeforeCursor(SEARCH_QUERY_CHARS, 0)
            ?: return ""
        var start = before.length
        while (start > 0 && Character.isLetter(before[start - 1])) {
            start--
        }
        return before.substring(start)
    }

    /** Inserts an emoji and moves it to the front of the recents; the panel stays open. */
    private fun onEmojiPicked(emoji: String) {
        val connection = currentInputConnection ?: return
        orchestrator.finishWord()
        connection.commitText(emoji, 1)
        orchestrator.checkpointField()
        orchestrator.refreshContextFromEditor()
        orchestrator.requestSuggestions()

        val updated = (listOf(emoji) + preferences.emojiRecents.filterNot { it == emoji })
            .take(KeyboardPreferences.MAX_EMOJI_RECENTS)
        host?.emojiPanel?.recents = updated
        updatePreferences { it.copy(emojiRecents = updated) }
    }

    override fun onPrivateRevealToggled() {
        if (!orchestrator.session.policy.privateField) {
            return
        }
        privateReveal = !privateReveal
        host?.suggestionStrip?.privateReveal = privateReveal
        refreshPrivateReveal()
    }

    /** Re-reads a private field's text into the strip while it is being shown. */
    private fun refreshPrivateReveal() {
        val strip = host?.suggestionStrip ?: return
        if (!orchestrator.session.policy.privateField || !privateReveal) {
            return
        }
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(PRIVATE_REVEAL_CHARS, 0) ?: ""
        val after = connection.getTextAfterCursor(PRIVATE_REVEAL_CHARS, 0) ?: ""
        strip.privateText = before.toString() + after.toString()
    }

    override fun onClipboardPanelClosed() {
        host?.setClipboardPanelVisible(false)
        orchestrator.refreshContextFromEditor()
        orchestrator.requestSuggestions()
    }

    /** Re-reads the history into an open panel, after something in it changed. */
    private fun refreshClipboardPanel() {
        val view = host ?: return
        if (!view.clipboardPanelVisible) {
            return
        }
        scope.launch {
            val (entries, thumbnails) = withContext(Dispatchers.IO) {
                val recent = DataGraph.clipboard.recent(MAX_CLIPBOARD_CARDS)
                recent to view.clipboardPanel.decodeThumbnails(recent)
            }
            view.clipboardPanel.setEntries(entries, thumbnails)
        }
    }

    /** Selects the word the cursor is inside, so the next action can act on it. */
    private fun selectWordAtCursor(connection: InputConnection) {
        orchestrator.resetComposing()
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(CONTEXT_WINDOW_CHARS, 0)?.toString().orEmpty()
        var back = 0
        while (back < before.length &&
            TypingOrchestrator.isWordCharacter(before[before.length - 1 - back].code)
        ) {
            back++
        }
        var forward = 0
        while (forward < after.length && TypingOrchestrator.isWordCharacter(after[forward].code)) {
            forward++
        }
        if (back == 0 && forward == 0) {
            return
        }
        // The editor's own caret, read with the text.
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        val caret = if (extracted != null && extracted.selectionEnd >= 0) {
            extracted.startOffset + extracted.selectionEnd
        } else {
            orchestrator.selectionEnd
        }
        connection.setSelection(caret - back, caret + forward)
    }

    // ---- the clipboard chip ---------------------------------------------------------------

    /** A clip's identity, its text or its image's URI; null for a clip the chip does not offer. */
    private fun clipSignature(clip: ClipData?): String? {
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val description = clip.description ?: return null
        return if (description.hasMimeType("image/*")) {
            item.uri?.toString()
        } else {
            item.coerceToText(this)?.toString()?.trim()?.ifEmpty { null }
        }
    }

    /** Whether the copying app marked the clip sensitive; always false before Android 13. */
    private fun isSensitiveClip(clip: ClipData): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return clip.description?.extras
            ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }

    /**
     * Rebuilds the strip's chip for the clip on the clipboard, with its label; none in private
     * mode, for a withheld or sensitive clip, or with the setting off.
     */
    private fun refreshClipboardChip(clip: ClipData? = clipboardManager?.primaryClip) {
        val strip = host?.suggestionStrip ?: return
        if (!unlocked || orchestrator.session.policy.privateField || !preferences.clipboardSuggestion) {
            strip.clipboardChip = null
            clipOffers.shown(null, currentInputEditorInfo?.packageName)
            return
        }
        val description = clip?.description
        if (clip == null || clip.itemCount == 0 || description == null ||
            !clipOffers.mayShow(clipSignature(clip)) || isSensitiveClip(clip)
        ) {
            strip.clipboardChip = null
            clipOffers.shown(null, currentInputEditorInfo?.packageName)
            return
        }
        val text = when {
            description.hasMimeType("image/*") -> strings[Keys.CLIP_PHOTO]
            else -> {
                val plain = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
                if (plain.isEmpty()) {
                    null
                } else {
                    // The first words, up to CHIP_PREVIEW_CHARS.
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
        clipOffers.shown(
            if (text != null) clipSignature(clip) else null,
            currentInputEditorInfo?.packageName,
        )
    }

    override fun onClipboardPicked() {
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0 || orchestrator.session.policy.privateField) {
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        val description = clip.description
        if (uri != null && description != null && description.hasMimeType("image/*")) {
            orchestrator.pasteImage(uri.toString(), description.getMimeType(0) ?: "image/*")
            return
        }
        val text = item.coerceToText(this)?.toString() ?: return
        orchestrator.pasteText(text)
        clipOffers.used(clipSignature(clip), preferences.clipboardSuggestionOnce)
        if (preferences.clipboardSuggestionOnce) {
            host?.suggestionStrip?.clipboardChip = null
        }
        if (preferences.clearClipboardAfterInsert) {
            // Empties the clipboard by writing an empty clip, and removes the chip.
            clipboardManager?.setPrimaryClip(ClipData.newPlainText(null, ""))
            host?.suggestionStrip?.clipboardChip = null
        }
        if (preferences.clipboardDeleteAfterUse) {
            // Deletes the history entry with this text, unless it is pinned.
            scope.launch(Dispatchers.IO) { DataGraph.clipboard.deleteIfUnpinned(text) }
        }
    }

    /** Up to [cap] bytes at [uri], or null when it cannot be read or holds more. */
    private fun readClipBytes(uri: android.net.Uri, cap: Long): ByteArray? = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > cap) return@use null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun onClipboardChanged() {
        if (orchestrator.session.policy.privateField || !preferences.clipboardEnabled) {
            return
        }
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) {
            return
        }
        clipOffers.copied(clipSignature(clip), currentInputEditorInfo?.packageName)
        refreshClipboardChip()
        if (isSensitiveClip(clip)) {
            return
        }
        // A copy made in an app [ClipboardExclusions] excludes is not kept.
        if (ClipboardExclusions.isExcluded(
                currentInputEditorInfo?.packageName,
                preferences.clipboardExcludedPackages,
            )
        ) {
            return
        }

        val description = clip.description
        val uri = clip.getItemAt(0).uri
        if (uri != null && description != null && description.hasMimeType("image/*")) {
            // The bytes are read while the clip's grant holds.
            val mime = description.getMimeType(0) ?: "image/*"
            scope.launch(Dispatchers.IO) {
                val bytes = readClipBytes(uri, DataGraph.clipboard.imageCapBytes()) ?: return@launch
                DataGraph.clipboard.rememberImageBytes(bytes, mime)
            }
            return
        }

        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text.isEmpty() || text.length > MAX_CLIP_LENGTH) {
            return
        }
        scope.launch(Dispatchers.IO) { DataGraph.clipboard.remember(text) }
    }

    // ---- inline autofill suggestions ------------------------------------------------------------

    /**
     * How an autofill service's inline suggestions look in the strip: their size and colours.
     * The platform renders them in the service's process; their text never reaches this class.
     */
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (currentInputEditorInfo == null) {
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

        // One spec; the platform repeats the last spec for further suggestions.
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
            // Inflated asynchronously in the service's process, delivered on the main executor.
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
        const val DEFAULT_ALPHABETIC_LAYOUT = LayoutChoice.DEFAULT_LAYOUT
        const val SYMBOLS_LAYOUT = "symbols"
        const val SYMBOLS_NUMPAD_LEFT_LAYOUT = "symbols_numpad_left"
        const val SYMBOLS_NUMPAD_RIGHT_LAYOUT = "symbols_numpad_right"
        const val SYMBOLS_SHIFT_LAYOUT = "symbols_shift"
        const val NUMPAD_LAYOUT = "numpad"
        /** The `plus`-only tier B weights asset (keyboard/src/plus/assets/). */
        const val SWIPE_MODEL_FAILURES_PREFS = "swipe_model_failures"

        const val PAGE_ALPHABETIC = 0
        const val PAGE_SYMBOLS = 1
        const val PAGE_SYMBOLS_SHIFT = 2
        const val PAGE_NUMPAD = 3
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"

        /** The settings activity's extras: a screen to open on, and a clip that screen edits. */
        const val SETTINGS_EXTRA_SCREEN = "com.borderkeys.settings.SCREEN"
        const val SETTINGS_EXTRA_CLIP_ID = "com.borderkeys.settings.CLIP_ID"
        const val SETTINGS_SCREEN_CLIPBOARD = "Clipboard"

        /** An obsolete snapshot of the personal model, deleted at start. */
        const val LEGACY_USER_MODEL_SNAPSHOT = "user_model.bku"

        /** How long a notice stays on the strip. */
        const val NOTICE_MILLIS = 2_000L

        /** The label drawn for a flick that has none of its own. */
        const val FLICK_PLACEHOLDER = "\u2022"

        /** How long the last learning flush, at shutdown, waits for the database. */
        const val FINAL_FLUSH_TIMEOUT_MILLIS = 2_000L

        /** Quick actions that do not change the field and refresh no suggestions. */
        val NO_REFRESH_QUICK_ACTIONS = setOf(
            QuickAction.CLIPBOARD_HISTORY, QuickAction.COMPOSE, QuickAction.PRIVATE_COPY,
            QuickAction.PICK_KEYBOARD, QuickAction.VOICE_INPUT,
        )

        /** The subtype mode of a keyboard that takes dictation. */
        const val VOICE_SUBTYPE_MODE = "voice"

        /** The preferences file where [maybeDecayPersonalDictionary] records its last run. */
        const val DECAY_PREFS = "personal_dictionary_decay"
        const val DECAY_LAST_SWEEP_AT = "last_sweep_at"

        /** The preferences file holding the language evidence, a float per language tag. */
        const val LANGUAGE_EVIDENCE_PREFS = "language_evidence"

        /** The shortest interval between two [maybeDecayPersonalDictionary] runs. */
        const val DECAY_SWEEP_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        const val CONTEXT_WINDOW_CHARS = 64

        /** How much of the text before the caret an emoji search reads its word from. */
        const val SEARCH_QUERY_CHARS = 32

        /** How much of a private field's text, either side of the caret, the strip can show. */
        const val PRIVATE_REVEAL_CHARS = 256

        /** How much of the field the capital and sentence-case quick actions read. */
        const val FIELD_HISTORY_CHARS = 20_000

        /** How much of a copied text the chip shows. */
        const val CHIP_PREVIEW_CHARS = 24

        /** How many cards the history panel holds. */
        const val MAX_CLIPBOARD_CARDS = 40

        /** How far either side of the cursor "the line" is looked for. */
        const val LINE_WINDOW_CHARS = 1024

        const val GESTURE_DECODING_NOTICE_MILLIS = 50L

        /** The most letter keys a geometry snapshot takes, as many as the engine's geometry. */
        const val MAX_SNAPSHOT_KEYS = 64


        /** How far, in pixels, the text field must move to close an open ring. */
        const val EDITOR_MOVE_DISMISS_PX = 8f
        const val MAX_CLIP_LENGTH = 20_000
        const val MAX_INLINE_SUGGESTIONS = 5
        const val MIN_CHIP_WIDTH_DP = 120
        const val BLUR_RADIUS_DP = 24f
        const val CHIP_PADDING_PX = 12
    }
}
