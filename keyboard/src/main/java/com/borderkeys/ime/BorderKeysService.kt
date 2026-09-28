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
import android.view.inputmethod.InputContentInfo
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.DataGraph
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.KeyboardStats
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.decayed
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.BundledDictionaries
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
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.ParticleEffectsSettings
import com.borderkeys.ime.fx.applyParticleLayer
import com.borderkeys.predict.Candidate
import com.borderkeys.predict.LearningBuffer
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.predict.RefusedWords
import com.borderkeys.predict.ScoreExplanation
import com.borderkeys.predict.SwipeModelLoad
import com.borderkeys.predict.WordFold
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
import kotlinx.coroutines.sync.withLock
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

    /** Serialises [loadDictionaries]. */
    private val dictionaryLoad = kotlinx.coroutines.sync.Mutex()
    private val paints = ThemePaints()
    private val engine = PredictionEngine()
    private val learning = LearningBuffer()

    private var host: KeyboardHostView? = null

    private val composing = StringBuilder(48)

    /** The editor's selection, as of the last onUpdateSelection. */
    private var selectionStart = 0
    private var selectionEnd = 0

    /** What the last slide along the space bar selected, so one drag keeps one anchor. */
    private var lastNudge: CaretNudge.Selection? = null

    /** Whether the field holds any text. */
    private var editorEmpty = true

    /** Whether the field is a password field. */
    private var passwordField = false

    /** Whether the field holds an e-mail or web address; see [AddressField]. */
    private var addressField = false

    /** Whether the field is a terminal; see [TerminalField]. */
    private var terminalField = false

    /** The letters typed into a terminal since the last delimiter. */
    private val terminalWord = StringBuilder()

    /**
     * Whether dictionary words may be suggested, corrected, swiped or offered on the ring in this
     * field. False only for a password field.
     */
    private val dictionaryAllowed: Boolean get() = !passwordField
    private var previousWord1: String? = null
    private var previousWord2: String? = null

    /**
     * Whether nothing about the user may be read or written in this field: no learning, no
     * clipboard history, no personal dictionary, no assistant. See [PrivateMode].
     */
    private var privateMode = false

    /** Whether the strip shows a private field's text, at the user's request, this field. */
    private var privateReveal = false
    private var preferences = KeyboardPreferences()
    private var particleEffects = ParticleEffectsSettings()

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
    private var symbolsLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsNumpadLeftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsNumpadRightLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var symbolsShiftLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()
    private var numpadLayout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /** Diacritics for the enabled languages, merged onto the letter keys by [composedLayout]. */
    private var accentOverlays: Map<Char, String> = emptyMap()

    /** Distinguishes one enabled-language set from another in the compiled-geometry cache key. */
    private var accentSignature: String = ""

    /** The tags of the packs the engine consults, heaviest first. */
    @Volatile
    private var activeLanguageTags: List<String> = emptyList()

    /** The offensive-word lists of the enabled packs, merged and folded; see [OffensiveWords]. */
    private var offensiveWords: Set<String> = emptySet()

    /** The emoji panel's keywords for the languages switched on; see [EmojiKeywords]. */
    private var emojiKeywords: Map<String, List<String>> = emptyMap()

    /** Apostrophe spellings for the languages switched on; see [Contractions]. */
    private var contractions: Map<String, String> = emptyMap()

    /** The tier B load in flight. */
    private var swipeModelJob: kotlinx.coroutines.Job? = null

    /** The language the conversation is considered written in, or null. */
    private var dominantLanguageTag: String? = null

    /** Which page is on screen. */
    private var page = PAGE_ALPHABETIC

    private var shiftState = ShiftState.OFF

    /** Control and alt from the modifier row, each armed for the next key. */
    private var controlArmed = false
    private var altArmed = false

    /** Set when the user pressed shift, cleared by the character it applied to. */
    private var shiftHeldByUser = false

    /** Whether the word being composed started with a capital the user typed with shift. */
    private var composingCapitalisedByUser = false

    /** When the last space was committed, for the two-spaces-make-a-full-stop window. */
    private var lastSpaceAt = 0L

    /** Set for one keystroke after two spaces became a full stop; backspace then undoes it. */
    private var pendingSpacePeriod = false

    /** Set when a space was added after a sentence mark; the next typed space is swallowed. */
    private var pendingAutoSpace = false

    /**
     * Whether the word being composed came from a swipe. A letter typed after it starts the
     * next word.
     */
    private var composingFromGesture = false

    /** Whether a space was inserted before the swiped word being composed. */
    private var swipeAutoSpaceInserted = false

    /** Set when the user released a caps lock that auto-shift applied, until the next letter. */
    private var userReleasedAutoLock = false

    /**
     * Set right before a commit of this class's own and spent by the next selection report,
     * which is then not treated as a caret move. Cleared by any key press.
     */
    private var ownEditPending = false

    /** Whether [adoptWordAtCaret] is running; selection reports meanwhile are its own edits. */
    private var adoptingWordAtCaret = false

    /** Whether this gesture's pause-time preview already composed a word. */
    private var previewComposedThisGesture = false

    /** Where the text field sat on screen when the ring opened, or NaN before the first report. */
    private var ringEditorOriginX = Float.NaN
    private var ringEditorOriginY = Float.NaN

    /** The clip whose chip is withheld, or null when none is. */
    private var withdrawnClip: String? = null

    /** The signature of the clip the chip is showing, or null. */
    private var shownClipSignature: String? = null

    /** Whether the current lock came from the field asking for capitals rather than from shift. */
    private var autoLockedShift = false
    private var lastShiftPressAt = 0L

    private val flushLearningRunnable = Runnable { flushLearning() }

    /** Shows "decoding" on the strip when a swipe's answer is late. */
    private val gestureDecodingRunnable = Runnable { host?.suggestionStrip?.decoding = true }

    /** When the last swipe was lifted, for the debug timing line in onGestureCandidates. */
    private var gestureLiftedAt = 0L

    /** Uptime of the keystroke the engine was last asked about, for the strip latency figure. */
    private var suggestionsRequestedAt = 0L

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }
    /** The leading suggestion, kept so the delimiter path can apply it. */
    private var topSuggestion: String? = null

    /** Whether [topSuggestion] is a name. */
    private var topSuggestionIsProperNoun: Boolean = false

    /** The possessive rewrite of the word being typed, or null. */
    private var possessiveSuggestion: String? = null

    /**
     * Whether the word being typed is a regular inflection of a known word that the offered
     * correction is not built on.
     */
    private var queryIsInflection = false

    /** The word [topSuggestion] is an answer about. */
    private var suggestionQuery: String = ""

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
        /** [composingCapitalisedByUser] when [typed] was finished. */
        val deliberateCapital: Boolean,
        /** Whether confirming it learns [corrected]; false for a text shortcut's expansion. */
        val learn: Boolean = true,
    )

    private var pendingCorrection: PendingCorrection? = null

    /** The field's undo and redo history for this input session. */
    private val fieldHistory = Composer()

    /** Finds the words that look wrong once the conversation's language has changed. */
    private val languageSwitchCorrector = LanguageSwitchCorrector()

    /**
     * Whether the word being composed is running text rather than an address, a path or code;
     * decided at its first letter. See [RunningText].
     */
    private var composingIsRunningText = true

    /** Whether the radial ring is open. */
    private val swipeRadialController = SwipeRadialController()

    /** Where a swipe paused, in the keyboard view's pixels. */
    private var lastGestureX = 0f
    private var lastGestureY = 0f

    /** The words [syncDebugRing]'s sample ring offers. */
    private val DEBUG_RING_WORDS = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

    /** The pause-time decode's rank one, applied when the ring resolves without a pick. */
    private var radialTopWord: String? = null

    /**
     * The ring's words: the decode's first [KeyboardPreferences.radialSuggestionCount], or none
     * for a decode of fewer than two.
     */
    private fun ringWedges(candidates: List<Candidate>): List<String> =
        if (candidates.size < 2) {
            emptyList()
        } else {
            candidates.take(preferences.radialSuggestionCount).map { it.text }
        }

    /** Which wedge carries the word already in the field. */
    private val trustedWedgeIndex = 0

    /** Whether rank one holds at least [DECISIVE_SHARE_PER_MILLE] of the decode. */
    private fun decodeWasDecisive(candidates: List<Candidate>): Boolean =
        candidates.size < 2 || candidates.first().share >= DECISIVE_SHARE_PER_MILLE

    /** Resolves the ring from its current selection: applies or cancels. */
    private fun forceResolveRadialRing() {
        val selection = host?.radialSuggestionMenu?.currentSelection()
            ?: RadialSuggestionMenuView.Selection.None
        closeRadialRing((selection as? RadialSuggestionMenuView.Selection.Word)?.index)
        resolveRadialSelection(selection)
    }

    /**
     * Resolves the ring [KeyboardPreferences.radialPickTimeoutMillis] after it opens, unless
     * steering, a resolution or a dismissal cancels it first.
     */
    private val radialTimeoutRunnable = Runnable { forceResolveRadialRing() }

    /** Bumped by [resetFieldHistory]; a language check from an older generation is not applied. */
    private var fieldGeneration = 0

    /** The word the strip is currently asking about, between the hold and the answer. */
    private var pendingForget: String? = null

    /** The composing text the strip was about when [pendingForget] was held down. */
    private var pendingExplainQuery: String = ""

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false

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
        // Loaded before anything draws.
        strings = LanguageManager(this).apply {
            loadResolved(DataGraph.themes.currentPreferences().uiLanguage)
        }
        engine.listener = this
        engine.start()

        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        registerWallpaperReceiver()

        scope.launch(Dispatchers.IO) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            alphabeticLayout = LayoutLoader.load(
                assets, layoutIdFromSubtype(manager?.currentInputMethodSubtype),
            )
            symbolsLayout = LayoutLoader.load(assets, SYMBOLS_LAYOUT)
            symbolsNumpadLeftLayout = LayoutLoader.load(assets, SYMBOLS_NUMPAD_LEFT_LAYOUT)
            symbolsNumpadRightLayout = LayoutLoader.load(assets, SYMBOLS_NUMPAD_RIGHT_LAYOUT)
            symbolsShiftLayout = LayoutLoader.load(assets, SYMBOLS_SHIFT_LAYOUT)
            numpadLayout = LayoutLoader.load(assets, NUMPAD_LAYOUT)
            // A failed load leaves the keyboard typing without dictionaries.
            runCatching { loadDictionaries() }
                .onFailure { error -> degradeWithoutDictionaries(error) }
            File(filesDir, LEGACY_USER_MODEL_SNAPSHOT).delete()
        }
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        observeSettings()
        observeLanguagePacks()
        observeDictionaryEdits()
    }

    /**
     * Reloads the blocked words and the personal model after an edit on the Personal dictionary
     * screen, under [dictionaryLoad].
     */
    private fun observeDictionaryEdits() {
        scope.launch {
            DataGraph.dictionary.edits.collect {
                withContext(Dispatchers.IO) {
                    dictionaryLoad.withLock {
                        val dictionary = DataGraph.dictionary
                        refreshBlockedWords(dictionary)
                        loadPersonalModel(dictionary)
                    }
                }
                requestSuggestions()
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
        SwipeModelLoad.set(SwipeModelLoad.State.Loading)
        swipeModelJob = scope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                assets.open(SWIPE_MODEL_ASSET).use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                withContext(Dispatchers.Main) { failSwipeModel(null) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                engine.setSwipeModelEnabled(true)
                engine.loadSwipeWeights(bytes) { loaded ->
                    if (loaded) {
                        SwipeModelLoad.set(SwipeModelLoad.State.Ready)
                    } else {
                        failSwipeModel("the swipe model's weights are not valid")
                    }
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
        scope.launch {
            DataGraph.themes.updatePreferences {
                it.copy(swipeModelFailed = true, experimentalSwipeModelEnabled = false)
            }
        }
    }

    /**
     * Loads the enabled language packs, re-hashing each first, then the blocked words and the
     * personal dictionary. A pack whose hash no longer matches is switched off.
     */
    private suspend fun loadDictionaries() {
        dictionaryLoad.withLock {
            val repository = DataGraph.languagePacks
            reinstallOutdatedBundledPacks(repository)
            repository.verifyEnabled()

            // Heaviest first, cut to the engine's slots.
            val everyEnabled = repository.enabledPacks()
            val enabled = everyEnabled.take(LanguagePackRepository.MAX_ENABLED)
            if (enabled.size < everyEnabled.size) {
                android.util.Log.w(
                    "BorderKeys",
                    "${everyEnabled.size} packs enabled, loading the ${enabled.size} heaviest",
                )
            }

            // The accents, offensive words, emoji keywords and contractions of the enabled packs.
            accentOverlays = AccentOverlays.merge(enabled.map { AccentOverlays.load(assets, it.tag) })
            accentSignature = enabled.joinToString(",") { it.tag }
            activeLanguageTags = enabled.map { it.tag }
            offensiveWords = OffensiveWords.merge(enabled.map { OffensiveWords.load(assets, it.tag) })
            emojiKeywords = EmojiKeywords.load(assets, enabled.map { it.tag })
            contractions = Contractions.of(
                enabled.map { Contractions.load(assets, it.tag) },
                enabled.map { it.tag },
            )
            withContext(Dispatchers.Main) {
                host?.let {
                    it.emojiPanel.keywords = emojiKeywords
                    showPage(page)
                }
            }

            // The set is sent before the packs load, to free the slots of packs no longer named,
            // and again after; also when it is empty.
            val tags = Array(enabled.size) { enabled[it].tag }
            val weights = FloatArray(enabled.size) { enabled[it].weight }
            engine.setActiveLanguages(tags, weights)
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
            engine.setActiveLanguages(tags, weights)

            val dictionary = DataGraph.dictionary
            refreshBlockedWords(dictionary)
            loadPersonalModel(dictionary)
        }
    }

    /**
     * Pushes the blocked words to the engine, which treats them as absent from every dictionary,
     * and them and the offensive words, while their switch is on, to the engine's filter and to
     * the learning buffer.
     */
    private suspend fun refreshBlockedWords(dictionary: DictionaryRepository) {
        val blocked = dictionary.blockedWordSet()
        val refused = RefusedWords.of(
            blocked,
            if (preferences.blockOffensiveWords) offensiveWords else emptySet(),
        )
        engine.setBlockedWords(blocked)
        engine.setRefusedWords(refused)
        learning.setRefusedWords(refused)
    }

    /**
     * Pushes the personal dictionary into the native model, each entry decayed for how long it
     * has sat unused.
     */
    private suspend fun loadPersonalModel(dictionary: DictionaryRepository) {
        val now = System.currentTimeMillis()
        // With the offensive-word switch on, words, pairs and triples that contain an offensive
        // word are left out.
        val hidden = if (preferences.blockOffensiveWords) offensiveWords else emptySet()
        fun shown(word: String) = hidden.isEmpty() || WordFold.fold(word) !in hidden
        engine.loadUserWords(
            dictionary.topWords().filter { shown(it.word) }.map { it.decayed(now) },
        )
        engine.loadUserBigrams(
            dictionary.topBigrams()
                .filter { shown(it.previousWord) && shown(it.word) }
                .map { it.decayed(now) },
        )
        engine.loadUserTrigrams(
            dictionary.topTrigrams()
                .filter { shown(it.previousWord2) && shown(it.previousWord1) && shown(it.word) }
                .map { it.decayed(now) },
        )
    }

    /** Carries on without dictionaries: no prediction, correction or learning. */
    private fun degradeWithoutDictionaries(error: Throwable) {
        android.util.Log.e("BorderKeys", "starting without dictionaries", error)
        learning.enabled = false
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
                    runCatching { withContext(Dispatchers.IO) { loadDictionaries() } }
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
            }.collect { (newTheme, newLightTheme, newPreferences, newParticleEffects) ->
                theme = newTheme
                lightTheme = newLightTheme
                val wasForcingDebugRing = preferences.debugForceRadialRing
                val offensiveSwitchFlipped =
                    preferences.blockOffensiveWords != newPreferences.blockOffensiveWords
                val swipeModelFlipped =
                    preferences.experimentalSwipeModelEnabled !=
                        newPreferences.experimentalSwipeModelEnabled
                preferences = newPreferences
                if (swipeModelFlipped) {
                    applySwipeModel(newPreferences.experimentalSwipeModelEnabled)
                }
                particleEffects = newParticleEffects
                if (offensiveSwitchFlipped) {
                    // Rebuilds the refused words and the personal model under [dictionaryLoad].
                    scope.launch(Dispatchers.IO) {
                        dictionaryLoad.withLock {
                            val dictionary = DataGraph.dictionary
                            refreshBlockedWords(dictionary)
                            loadPersonalModel(dictionary)
                        }
                        withContext(Dispatchers.Main) { requestSuggestions() }
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
                    view.keyboard.radialMenuEnabled = newPreferences.radialMenuEnabled
                    view.keyboard.radialPauseDwellMillis =
                        newPreferences.radialPauseDwellMillis.toLong()
                    view.keyboard.radialMinPathLetters = newPreferences.radialMinPathLetters
                    view.radialSuggestionMenu.sizeScale =
                        KeyboardPreferences.radialSizeScale(newPreferences.radialMenuSize)
                    view.radialBlurBackground = newPreferences.radialBlurBackground
                    view.suggestionStrip.visibleLimit = newPreferences.suggestionCount
                    applyLearningGate(newPreferences)
                    applyQuickActions(view)
                    refreshClipboardChip()
                    if (wasForcingDebugRing && !newPreferences.debugForceRadialRing) {
                        closeDebugRing()
                    }
                    syncDebugRing()
                    view.keyboard.swipeEnabled = newPreferences.swipeEnabled && dictionaryAllowed
                    view.suggestionStripEnabled = newPreferences.showSuggestionStrip
                    applyAutoShift()
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
        }
    }

    /** A tap in the editor, also when the caret did not move: closes the ring. */
    @Deprecated("Deprecated in Java")
    override fun onViewClicked(focusChanged: Boolean) {
        @Suppress("DEPRECATION")
        super.onViewClicked(focusChanged)
        dismissRadialMenu()
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        dismissRadialMenu()
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
        selectionStart = newSelStart
        selectionEnd = newSelEnd
        refreshPrivateReveal()
        updateEditorEmpty(newSelEnd > 0)
        val view = host ?: return
        val hasSelection = newSelEnd > newSelStart
        if (view.suggestionStrip.actionMode) {
            view.suggestionStrip.clear()
            pendingForget = null
        }
        if (!hasSelection) {
            // A caret that still ends the composing text asks for suggestions; the echo of this
            // class's own commit is spent; any other move closes the ring and adopts the word
            // under the caret.
            val caretMatches = composingMatchesCaret(newSelEnd)
            if (caretMatches) {
                if (lastQuery != composing.toString()) {
                    requestSuggestions()
                }
            } else if (ownEditPending) {
                ownEditPending = false
            } else if (terminalField) {
                dismissRadialMenu()
            } else {
                dismissRadialMenu()
                adoptWordAtCaret()
            }
            applyAutoShift()
        } else {
            dismissRadialMenu()
        }
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
                requestSuggestions()
            }
            else -> requestSuggestions()
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
        view.emojiPanel.keywords = emojiKeywords
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

        privateMode = PrivateMode.isPrivate(info)
        passwordField = info != null && PrivateMode.isPasswordField(info.inputType)
        addressField = info != null && AddressField.isAddress(info.inputType)
        terminalField = TerminalField.isTerminal(info, preferences.terminalPackages)
        terminalWord.setLength(0)
        applyLearningGate()
        engine.setLearningSpeed(
            KeyboardPreferences.learningSpeedFactor(preferences.learningSpeed),
        )
        engine.setCorrectionStrictness(preferences.correctionStrictness)
        engine.setLanguageLock(
            KeyboardPreferences.languageLockEvidence(preferences.languageLock),
            KeyboardPreferences.languageLockStrict(preferences.languageLock),
        )
        engine.setPreferredLanguage(preferences.preferredLanguageTag)
        // With a preferred language, each field starts without a language verdict.
        if (preferences.preferredLanguageTag.isNotEmpty()) {
            engine.resetLanguageEvidence()
        }
        engine.setPhraseSuggestions(preferences.phraseSuggestions)
        if (privateMode) {
            learning.discard()
        }

        privateReveal = false
        host?.let { view ->
            view.suggestionStrip.privateMode = privateMode
            view.suggestionStrip.privateReveal = false
            view.suggestionStrip.privateText = null
            view.suggestionStrip.clear()
            applyHaptics(view, preferences)
            view.keyboard.swipeEnabled = preferences.swipeEnabled && dictionaryAllowed
            view.suggestionStripEnabled = preferences.showSuggestionStrip
            view.keyboard.soundEnabled = preferences.keySound
            view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
            applyParticleSettings(view, particleEffects)
        }
        showPage(pageFor(info))
        // Each field starts with shift and caps lock off.
        shiftHeldByUser = false
        userReleasedAutoLock = false
        autoLockedShift = false
        shiftState = ShiftState.OFF
        host?.keyboard?.shiftState = shiftState
        setArmedModifiers(control = false, alt = false)
        ownEditPending = false
        resetComposing()
        resetFieldHistory()
        applyAutoShift()
        updateEditorEmpty(currentInputConnection?.getTextBeforeCursor(1, 0)?.isNotEmpty() == true)
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
        dismissRadialMenu()
        // With "offer it only once" on, the clip shown this session is withheld from now on.
        if (preferences.clipboardSuggestionOnce && shownClipSignature != null) {
            withdrawnClip = shownClipSignature
        }
        unregisterClipboardListener()
    }

    /** Closes the ring when the window hides. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        dismissRadialMenu()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        flushLearning()
        engine.cancelPending()
        resetComposing()
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
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        unregisterClipboardListener()
        // Runs while the engine is still alive.
        super.onDestroy()
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        flushLearningBeforeDestroy()
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
        engine.setKeyGeometry(0, keyWidth, keyHeight) { codes, x, y ->
            view.exportGeometry(codes, x, y)
        }
    }

    // ---- key handling ----------------------------------------------------------------------------

    override fun onKeyDown(code: Int) = Unit

    /** Moves the caret by [steps] characters through setSelection, from a space-bar slide. */
    override fun onCursorNudge(steps: Int) {
        dismissRadialMenu()
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val length = extracted.text?.length ?: return
        val next = CaretNudge.slide(
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            steps = steps,
            length = length,
            selecting = shiftState != ShiftState.OFF,
        )
        applyNudge(connection, next)
    }

    /**
     * Finishes the composing word and moves the caret, or the selection, to [next] in one batch
     * edit.
     */
    private fun applyNudge(connection: InputConnection, next: CaretNudge.Selection) {
        lastNudge = next
        if (next.start == selectionStart && next.end == selectionEnd) {
            return
        }
        connection.beginBatchEdit()
        if (composing.isNotEmpty()) {
            finishComposing(connection)
        }
        selectionStart = next.start
        selectionEnd = next.end
        connection.setSelection(next.anchor, next.caret)
        connection.endBatchEdit()
    }

    /** Moves the caret up or down by [lines], keeping its column; with shift held it selects. */
    override fun onCursorNudgeLines(lines: Int) {
        dismissRadialMenu()
        val connection = currentInputConnection ?: return
        val text = connection.getExtractedText(ExtractedTextRequest(), 0)?.text ?: return
        val next = CaretNudge.slideLines(
            text = text,
            start = selectionStart,
            end = selectionEnd,
            previous = lastNudge,
            lines = lines,
            selecting = shiftState != ShiftState.OFF,
        )
        applyNudge(connection, next)
    }

    /** A completed swipe. The word in progress is committed first. */
    override fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.swipeEnabled || !dictionaryAllowed) {
            return
        }
        radialTopWord = null
        // Closes a ring and drops a pause-time decode still in flight.
        dismissRadialMenu()
        engine.cancelPendingPreview()
        // The ring's anchor, for a swipe that did not pause.
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        if (previewComposedThisGesture) {
            // The preview's guess is taken back; this decode replaces it.
            previewComposedThisGesture = false
            cancelRadialGesture()
        } else {
            finishWordBeforeSwipe()
        }
        host?.postDelayed(gestureDecodingRunnable, GESTURE_DECODING_NOTICE_MILLIS)
        gestureLiftedAt = android.os.SystemClock.uptimeMillis()
        recordSwipeShape(xs, ys, timestamps, count)
        engine.decodeGesture(xs, ys, timestamps, count, previousWord1, previousWord2)
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

    /** Finishes and learns the word in progress before a swipe. */
    private fun finishWordBeforeSwipe() {
        val connection = currentInputConnection ?: return
        if (composing.isEmpty()) {
            return
        }
        val contextWord = previousWord1
        val grandContextWord = previousWord2
        // The caret report this edit causes is not a caret move.
        ownEditPending = true
        connection.beginBatchEdit()
        val finished = finishComposing(connection)
        connection.endBatchEdit()
        if (finished != null) {
            recordLearned(finished, contextWord, grandContextWord, composingCapitalisedByUser)
        }
    }

    /** The finger paused mid-swipe: decodes the path so far for the ring, when the ring is on. */
    override fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.radialMenuEnabled || !dictionaryAllowed) {
            host?.keyboard?.resumeGestureCapture()
            return
        }
        radialTopWord = null
        if (previewComposedThisGesture) {
            // A second pause: the first preview's guess is taken back.
            previewComposedThisGesture = false
            cancelRadialGesture()
        } else {
            finishWordBeforeSwipe()
        }
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        steerLeftPausePoint = false
        engine.decodeGesturePreview(xs, ys, timestamps, count, previousWord1, previousWord2)
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

    /** The finger lifted while the ring was open. */
    override fun onGestureRingResolved() {
        resolveRadialRing()
    }

    /** The touch stream was interrupted while the ring was open: discards the swipe. */
    override fun onGestureRingCancelled() {
        closeRadialRing()
        cancelRadialGesture()
    }

    /**
     * A tap on a ring kept open after a lift: a wedge applies its word, the centre cancels, and
     * anything else only closes the ring.
     */
    override fun onRadialTapResolved(selection: RadialSuggestionMenuView.Selection) {
        when (selection) {
            is RadialSuggestionMenuView.Selection.Word -> {
                closeRadialRing(selection.index)
                resolveRadialSelection(selection)
            }
            RadialSuggestionMenuView.Selection.Cancel -> {
                closeRadialRing()
                cancelRadialGesture()
            }
            RadialSuggestionMenuView.Selection.None -> dismissRadialMenu()
        }
    }

    /**
     * A touch outside a ring waiting for a tap: closes the ring, and hides the keyboard when
     * [KeyboardPreferences.radialOutsideTapHidesKeyboard] is on.
     */
    override fun onRadialDismissed() {
        dismissRadialMenu()
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
            dismissRadialMenu()
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

    /**
     * The pause-time decode's answer: the top candidate composes at once, and the ring opens with
     * [ringWedges].
     */
    override fun onGesturePreviewCandidates(candidates: List<Candidate>) {
        val view = host ?: return
        val connection = currentInputConnection ?: return
        if (candidates.isEmpty() || terminalField) {
            // No ring: the stroke goes back to plain capture.
            view.keyboard.resumeGestureCapture()
            return
        }
        val cased = caseSwipedWords(candidates)
        val best = cased.first().text
        radialTopWord = best
        connection.beginBatchEdit()
        spaceBeforeSwipedWord(connection)
        composing.setLength(0)
        composing.append(best)
        connection.setComposingText(composing, 1)
        connection.endBatchEdit()
        composingFromGesture = true
        recordSwipeDecode(candidates.size)
        if (debuggable) {
            android.util.Log.d(
                "BorderKeys",
                "swipe: decode ${engine.lastGestureDecodeMicros / 1000.0} ms, lift to text " +
                    "${android.os.SystemClock.uptimeMillis() - gestureLiftedAt} ms, tier " +
                    "${if (engine.lastGestureUsedNeural) "B" else "A"}, " +
                    "${candidates.size} candidates",
            )
        }
        previewComposedThisGesture = true
        lastQuery = best
        suggestionQuery = best
        knownQuery = best
        topSuggestion = best
        topSuggestionIsProperNoun = cased.first().isProperNoun

        val wedgeWords = ringWedges(cased)
        if (!swipeRadialController.onRingOpened(wedgeWords)) {
            // Too few words for a ring: the preview stays composing and the stroke goes back to
            // plain capture.
            radialTopWord = null
            view.keyboard.resumeGestureCapture()
            return
        }
        val (anchorX, anchorY) = radialAnchor(view)
        view.radialSuggestionMenu.show(anchorX, anchorY, wedgeWords, trustedWedgeIndex)
        view.setRadialMenuVisible(true)
        watchEditorWhileRingOpen(true)
        refreshTouchableArea()
        view.removeCallbacks(radialTimeoutRunnable)
        // No pick timeout while the ring is kept open.
        if (!preferences.radialLiftKeepsOpen) {
            view.postDelayed(radialTimeoutRunnable, preferences.radialPickTimeoutMillis.toLong())
        }
    }

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
     * Resolves the ring from its selection at a lift. With no wedge or Cancel under the finger
     * and [KeyboardPreferences.radialLiftKeepsOpen] on, an open ring stays open for a tap.
     */
    private fun resolveRadialRing() {
        val view = host ?: return
        previewComposedThisGesture = false
        val selection = view.radialSuggestionMenu.currentSelection()
        if (selection == RadialSuggestionMenuView.Selection.None && preferences.radialLiftKeepsOpen &&
            swipeRadialController.state == SwipeRadialController.State.OPEN
        ) {
            view.removeCallbacks(radialTimeoutRunnable)
            view.radialSuggestionMenu.acceptsOwnTouches = true
            return
        }
        closeRadialRing((selection as? RadialSuggestionMenuView.Selection.Word)?.index)
        resolveRadialSelection(selection)
    }

    /**
     * A wedge applies its word and Cancel discards the swipe; with neither,
     * [KeyboardPreferences.radialTimeoutDefault] applies rank one or cancels.
     */
    private fun resolveRadialSelection(selection: RadialSuggestionMenuView.Selection) {
        when (selection) {
            is RadialSuggestionMenuView.Selection.Word -> {
                onSuggestionPicked(selection.index, selection.word)
                playEffect(EffectEvent.SwipeAccepted, selection.word)
            }
            RadialSuggestionMenuView.Selection.Cancel -> cancelRadialGesture()
            RadialSuggestionMenuView.Selection.None -> {
                if (preferences.radialTimeoutDefault == KeyboardPreferences.RADIAL_TIMEOUT_CANCEL) {
                    cancelRadialGesture()
                } else {
                    val word = radialTopWord
                    if (word != null) {
                        onSuggestionPicked(0, word)
                        playEffect(EffectEvent.SwipeAccepted, word)
                    } else {
                        cancelRadialGesture()
                    }
                }
            }
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
        radialTopWord = null
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
        previewComposedThisGesture = false
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

    /**
     * Discards the swiped word, with no commit and no learning: empties the composing region, or
     * deletes the word before the caret when it is no longer composing, and the space inserted
     * before it.
     */
    private fun cancelRadialGesture() {
        val connection = currentInputConnection
        if (connection != null) {
            connection.beginBatchEdit()
            if (composing.isNotEmpty()) {
                connection.setComposingText("", 1)
                connection.finishComposingText()
            } else {
                connection.finishComposingText()
                deleteWordBeforeCaret(connection)
            }
            // The space inserted before the swiped word, when it is still there.
            if (swipeAutoSpaceInserted) {
                val before = connection.getTextBeforeCursor(1, 0)
                if (before != null && before.length == 1 && before[0] == ' ') {
                    connection.deleteSurroundingText(1, 0)
                }
            }
            connection.endBatchEdit()
        }
        composing.setLength(0)
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        host?.suggestionStrip?.clear()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    /** Deletes the run of [isWordCharacter] characters before the caret. */
    private fun deleteWordBeforeCaret(connection: InputConnection) {
        val before = connection.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            return
        }
        var length = 0
        while (length < before.length && isWordCharacter(before[before.length - 1 - length].code)) {
            length++
        }
        if (length > 0) {
            connection.deleteSurroundingText(length, 0)
        }
    }

    /**
     * Cases the swipe candidates as typed letters would come out under the current shift, names
     * capitalised, then spends a one-shot shift. Never lower-cases. Drops candidates that become
     * the same text.
     */
    private fun caseSwipedWords(candidates: List<Candidate>): List<Candidate> {
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
        composingCapitalisedByUser = shiftHeldByUser && state != ShiftState.OFF
        if (state == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        shiftHeldByUser = false
        return cased.distinctBy { it.text }
    }

    /**
     * Inserts a space before a swiped word unless the caret follows whitespace, nothing, a
     * character in [SWIPE_NO_SPACE_AFTER], or is in an address field. Records it in
     * [swipeAutoSpaceInserted].
     */
    private fun spaceBeforeSwipedWord(connection: InputConnection) {
        swipeAutoSpaceInserted = false
        if (addressField) {
            return
        }
        val before = connection.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) {
            return
        }
        val previous = before[0]
        if (previous.isWhitespace() || previous in SWIPE_NO_SPACE_AFTER) {
            return
        }
        connection.commitText(" ", 1)
        swipeAutoSpaceInserted = true
    }

    /**
     * A decoded swipe that did not pause: the first candidate composes at once and all of them go
     * to the strip. With [KeyboardPreferences.radialLiftKeepsOpen] on, a tap-only ring opens too,
     * unless the decode was decisive and [KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY] is set.
     */
    override fun onGestureCandidates(candidates: List<Candidate>) {
        host?.removeCallbacks(gestureDecodingRunnable)
        val view = host
        view?.suggestionStrip?.decoding = false
        if (candidates.isEmpty()) {
            view?.suggestionStrip?.clear()
            requestSuggestions()
            return
        }
        val connection = currentInputConnection ?: return
        val cased = caseSwipedWords(candidates)
        val best = cased.first().text
        if (terminalField) {
            swipeIntoTerminal(connection, cased)
            return
        }

        connection.beginBatchEdit()
        spaceBeforeSwipedWord(connection)
        composing.setLength(0)
        composing.append(best)
        connection.setComposingText(composing, 1)
        connection.endBatchEdit()
        composingFromGesture = true
        recordSwipeDecode(candidates.size)
        if (debuggable) {
            android.util.Log.d(
                "BorderKeys",
                "swipe: decode ${engine.lastGestureDecodeMicros / 1000.0} ms, lift to text " +
                    "${android.os.SystemClock.uptimeMillis() - gestureLiftedAt} ms, tier " +
                    "${if (engine.lastGestureUsedNeural) "B" else "A"}, " +
                    "${candidates.size} candidates",
            )
        }
        // The strip shows the swipe's alternatives, with no typed chip and no correction.
        lastQuery = best
        suggestionQuery = best
        knownQuery = best
        topSuggestion = best
        topSuggestionIsProperNoun = cased.first().isProperNoun
        view?.suggestionStrip?.let { strip ->
            strip.typedIndex = -1
            strip.appliedIndex = -1
            strip.setSuggestions(cased)
        }

        if (view != null && preferences.radialMenuEnabled && preferences.radialLiftKeepsOpen) {
            radialTopWord = best
            if (preferences.radialTrustedWord == KeyboardPreferences.RADIAL_TRUSTED_AUTO_APPLY &&
                decodeWasDecisive(cased)
            ) {
                playEffect(EffectEvent.SwipeAccepted, best)
                return
            }
            val wedgeWords = ringWedges(cased)
            if (swipeRadialController.onRingOpened(wedgeWords)) {
                val (anchorX, anchorY) = radialAnchor(view)
                view.radialSuggestionMenu.show(anchorX, anchorY, wedgeWords, trustedWedgeIndex)
                view.radialSuggestionMenu.acceptsOwnTouches = true
                view.setRadialMenuVisible(true)
                watchEditorWhileRingOpen(true)
                refreshTouchableArea()
            }
        }
    }

    override fun onKeyRepeat(code: Int) {
        if (code == KeyCodes.DELETE) {
            handleDelete()
        } else if (KeyCodes.isArrow(code)) {
            handleNavigationKey(code)
        } else if (code == KeyCodes.FORWARD_DELETE) {
            handleHardwareKey(android.view.KeyEvent.KEYCODE_FORWARD_DEL)
        }
    }

    override fun onText(text: CharSequence) {
        dismissRadialMenu()
        val connection = currentInputConnection ?: return
        confirmPendingCorrection()
        connection.beginBatchEdit()
        finishComposing(connection)
        connection.commitText(text, 1)
        connection.endBatchEdit()
        checkpointField()
        refreshContextFromEditor()
        applyAutoShift()
        requestSuggestions()
    }

    override fun onKey(code: Int, keyIndex: Int) {
        // Every key but backspace confirms a pending correction and closes the ring.
        if (code != KeyCodes.DELETE) {
            confirmPendingCorrection()
            dismissRadialMenu()
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
            KeyCodes.EMOJI -> toggleEmojiPanel()
            KeyCodes.ESCAPE -> handleHardwareKey(android.view.KeyEvent.KEYCODE_ESCAPE)
            KeyCodes.TAB -> handleHardwareKey(android.view.KeyEvent.KEYCODE_TAB)
            KeyCodes.CONTROL -> setArmedModifiers(control = !controlArmed, alt = altArmed)
            KeyCodes.ALT -> setArmedModifiers(control = controlArmed, alt = !altArmed)
            KeyCodes.ARROW_LEFT, KeyCodes.ARROW_RIGHT, KeyCodes.ARROW_UP, KeyCodes.ARROW_DOWN,
            KeyCodes.HOME, KeyCodes.END, KeyCodes.PAGE_UP, KeyCodes.PAGE_DOWN ->
                handleNavigationKey(code)
            KeyCodes.FORWARD_DELETE -> handleHardwareKey(android.view.KeyEvent.KEYCODE_FORWARD_DEL)
            KeyCodes.INSERT -> handleHardwareKey(android.view.KeyEvent.KEYCODE_INSERT)
            else -> if (KeyCodes.isCharacter(code)) handleCharacter(code)
        }
    }

    private fun setArmedModifiers(control: Boolean, alt: Boolean) {
        controlArmed = control
        altArmed = alt
        host?.keyboard?.setArmedModifiers(control, alt)
    }

    /**
     * A caret key from the modifier row, sent as the hardware key, selecting when shift is held.
     * The word being typed is finished first.
     */
    private fun handleNavigationKey(code: Int) {
        val connection = currentInputConnection ?: return
        val keyCode = when (code) {
            KeyCodes.ARROW_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
            KeyCodes.ARROW_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            KeyCodes.ARROW_UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
            KeyCodes.ARROW_DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
            KeyCodes.HOME -> android.view.KeyEvent.KEYCODE_MOVE_HOME
            KeyCodes.END -> android.view.KeyEvent.KEYCODE_MOVE_END
            KeyCodes.PAGE_UP -> android.view.KeyEvent.KEYCODE_PAGE_UP
            else -> android.view.KeyEvent.KEYCODE_PAGE_DOWN
        }
        val meta = heldShiftMeta(spend = false)
        ownEditPending = composing.isNotEmpty()
        resetComposing()
        sendPhysicalKey(connection, keyCode, meta)
        refreshContextFromEditor()
        applyAutoShift()
    }

    /**
     * Escape, tab, or a character under control or alt: commits the word being typed, then sends
     * the key.
     */
    private fun handleHardwareKey(keyCode: Int) {
        val connection = currentInputConnection ?: return
        val meta = heldShiftMeta(spend = true)
        ownEditPending = composing.isNotEmpty()
        connection.beginBatchEdit()
        finishComposing(connection)
        sendPhysicalKey(connection, keyCode, meta)
        connection.endBatchEdit()
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
            host?.keyboard?.shiftState = shiftState
        }
        return android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
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
     * Holding a key that has no alternates: space switches the layout, shift locks, backspace
     * deletes a word, and enter, the globe or the settings key open the quick panel.
     */
    override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean {
        if (code == ' '.code) {
            switchLanguage()
            return true
        }
        if (code == KeyCodes.SHIFT) {
            lockShift()
            return true
        }
        // Reverts a pending correction, or deletes the word before the cursor.
        if (code == KeyCodes.DELETE) {
            val connection = currentInputConnection
            if (connection != null && !revertCorrection(connection)) {
                deleteWordBeforeCursor(connection)
            }
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return true
        }
        if (code != KeyCodes.ENTER && code != KeyCodes.LANGUAGE && code != KeyCodes.SETTINGS) {
            return false
        }
        toggleQuickSettings()
        return true
    }

    private fun handleCharacter(code: Int) {
        val connection = currentInputConnection ?: return
        KeyboardStats.keystrokes++
        KeyboardStats.input(android.os.SystemClock.uptimeMillis())
        // Under an armed control or alt, a character goes out as its hardware key; one with no
        // hardware key releases the modifiers and is typed.
        if (controlArmed || altArmed) {
            val keyCode = PhysicalKeys.keyCodeFor(code)
            if (keyCode != 0) {
                handleHardwareKey(keyCode)
                return
            }
            setArmedModifiers(control = false, alt = false)
        }
        ownEditPending = false
        val shifted = if (shiftState != ShiftState.OFF) {
            Character.toUpperCase(code)
        } else {
            code
        }
        if (terminalField) {
            typeIntoTerminal(connection, shifted)
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
            host?.keyboard?.shiftState = shiftState
        }
        val heldByUser = shiftHeldByUser
        if (composing.isEmpty() && letter) {
            composingCapitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            val ahead = connection.getTextBeforeCursor(1, 0)
            composingIsRunningText = ahead.isNullOrEmpty() || !RunningText.isMark(ahead[0])
        }
        if (letter) {
            shiftHeldByUser = false
            userReleasedAutoLock = false
        }

        if (letter) {
            pendingAutoSpace = false
            if (composingFromGesture) {
                // A letter after a swiped word finishes and learns it, adds a space, and starts
                // the next word.
                composingFromGesture = false
                val contextWord = previousWord1
                val grandContextWord = previousWord2
                connection.beginBatchEdit()
                val finished = finishComposing(connection)
                connection.commitText(" ", 1)
                connection.endBatchEdit()
                if (finished != null) {
                    recordLearned(finished, contextWord, grandContextWord, composingCapitalisedByUser)
                }
                checkpointField()
                composingCapitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            }
            composing.appendCodePoint(shifted)
            connection.setComposingText(composing, 1)
            requestSuggestions()
            return
        }

        // A delimiter ends the word. What replaces the typed word, if anything, is decided by
        // [commitOutcome]; a rewrite is committed in its place and can be reverted.
        val typed = composing.toString()
        val outcome = commitOutcome(typed)
        val rewrite = outcome.isRewrite
        val correction = outcome.text
        // Read before anything commits.
        val contextWord = previousWord1
        val grandContextWord = previousWord2

        // A space typed right after one this keyboard added is handled per
        // KeyboardPreferences.autoSpaceHabit; see [HabitSpace].
        if (shifted == ' '.code &&
            HabitSpace.swallows(
                composingEmpty = typed.isEmpty(),
                pendingAutoSpace = pendingAutoSpace,
                habit = preferences.autoSpaceHabit,
                characterBeforeCursor = {
                    connection.getTextBeforeCursor(1, 0)?.takeIf { it.isNotEmpty() }?.get(0)
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
        if (shifted == ' '.code && typed.isEmpty() && preferences.doubleSpacePeriod && !addressField &&
            System.currentTimeMillis() - lastSpaceAt < DOUBLE_SPACE_MILLIS &&
            endsWithWordCharacterBeforeSpace(connection)
        ) {
            ownEditPending = true
            connection.beginBatchEdit()
            connection.deleteSurroundingText(1, 0)
            connection.commitText(". ", 1)
            connection.endBatchEdit()
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
            lastSpaceAt = System.currentTimeMillis()
        }
        pendingSpacePeriod = false

        ownEditPending = true
        connection.beginBatchEdit()
        // A space before a tight mark is removed, except the one French writes before ! ? ; :.
        if (typed.isEmpty() && preferences.removeSpaceBeforePunctuation &&
            isTightPunctuation(shifted) && !isFrenchSpacedPunctuation(shifted)
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
            // commitText replaces the composing region with the correction.
            composing.setLength(0)
            connection.commitText(correction + delimiter, 1)
            playEffect(EffectEvent.AutocorrectApplied, correction)
        } else {
            finishComposing(connection)
            connection.commitText(delimiter, 1)
        }
        connection.endBatchEdit()

        if (correction != null) {
            previousWord2 = previousWord1
            // An expansion's last word is the context the next word follows.
            previousWord1 = if (rewrite) correction.substringAfterLast(' ') else correction
            // Learned once the correction survives the next keystroke.
            pendingCorrection = PendingCorrection(
                typed, correction, delimiter, contextWord, grandContextWord,
                composingCapitalisedByUser, learn = !rewrite,
            )
            if (!rewrite && preferences.languageSwitchCorrectionMode != KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
                recordLanguageSwitchFlag(connection, typed, correction, delimiter)
            }
        } else {
            if (typed.isNotEmpty()) {
                recordLearned(typed, contextWord, grandContextWord, composingCapitalisedByUser)
            }
            pendingCorrection = null
        }
        // A sentence mark clears the context for the next word, after this word was learned.
        if (isSentenceEndingPunctuation(shifted)) {
            previousWord1 = null
            previousWord2 = null
        }
        checkpointField()
        shiftAfterDelimiter(heldByUser, justCommitted = delimiter)
        requestSuggestions()
        engine.dominantLanguageTag { tag -> dominantLanguageTag = tag }
        if (preferences.languageSwitchCorrectionMode != KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
            checkLanguageSwitch()
        }
    }

    /** Records where [correction] landed, read from the cursor, for [checkLanguageSwitch]. */
    private fun recordLanguageSwitchFlag(
        connection: InputConnection,
        typed: String,
        correction: String,
        delimiter: String,
    ) {
        val cursor = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        )?.selectionEnd ?: return
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
     * wrong. An answer from an older [fieldGeneration] is dropped.
     */
    private fun checkLanguageSwitch() {
        val generation = fieldGeneration
        engine.dominantPack { dominantPack ->
            if (generation != fieldGeneration || !languageSwitchCorrector.observeDominantPack(dominantPack)) {
                return@dominantPack
            }
            val connection = currentInputConnection ?: return@dominantPack
            val verified = languageSwitchCorrector.snapshot().filter { flag ->
                textAt(connection, flag.startOffset, flag.endOffset) == flag.appliedText
            }
            if (verified.isEmpty()) {
                return@dominantPack
            }
            engine.candidatesForPack(dominantPack, verified.map { it.typedText }) { suggestions ->
                if (generation != fieldGeneration) {
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
    private fun textAt(connection: InputConnection, start: Int, endExclusive: Int): String? {
        if (start < 0 || endExclusive < start) {
            return null
        }
        val text = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        )?.text ?: return null
        if (endExclusive > text.length) {
            return null
        }
        return text.subSequence(start, endExclusive).toString()
    }

    /** The selection in the offsets [textAt] uses, or null when the editor does not report it. */
    private fun selectionOf(connection: InputConnection): Pair<Int, Int>? {
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        ) ?: return null
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        return if (start < 0 || end < 0) null else Pair(start, end)
    }

    /** `Ask` shows the revert panel; `Auto-apply` edits the field itself, right away. */
    private fun onLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        if (preferences.languageSwitchCorrectionMode == KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY) {
            applyLanguageSwitchReplacements(replacements)
        } else {
            host?.let { view ->
                view.languageRevertPanel.offer(replacements)
                view.setLanguageRevertPanelVisible(true)
            }
        }
    }

    override fun onLanguageRevertPicked(replacement: LanguageSwitchCorrector.Replacement) {
        applyLanguageSwitchReplacements(listOf(replacement))
        val remaining = host?.languageRevertPanel?.remove(replacement) ?: 0
        if (remaining == 0) {
            host?.setLanguageRevertPanelVisible(false)
        }
    }

    override fun onLanguageRevertDismissed() {
        host?.setLanguageRevertPanelVisible(false)
    }

    /**
     * Closes an open ring, not the debug one, and leaves the swiped word as it is. The steering
     * stroke, if still down, is abandoned.
     */
    private fun dismissRadialMenu() {
        if (swipeRadialController.state != SwipeRadialController.State.OPEN || debugRingOpen) {
            return
        }
        closeRadialRing()
        host?.keyboard?.abandonRingStroke()
    }

    /**
     * Applies the replacements in their order, each only if its text is still in place, with one
     * [checkpointField] for the batch, then puts the caret back where the user is writing.
     */
    private fun applyLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        val connection = currentInputConnection ?: return
        val applied = ArrayList<LanguageSwitchCorrector.Replacement>(replacements.size)
        connection.beginBatchEdit()
        finishComposing(connection)
        val caret = selectionOf(connection)
        for (replacement in replacements) {
            if (textAt(connection, replacement.startOffset, replacement.endOffset) !=
                replacement.previousText
            ) {
                continue
            }
            connection.setComposingRegion(replacement.startOffset, replacement.endOffset)
            connection.setComposingText(replacement.text, 1)
            connection.finishComposingText()
            applied += replacement
        }
        // The caret goes back, shifted by the length the text before it changed.
        if (applied.isNotEmpty() && caret != null) {
            val (start, end) = caret
            connection.setSelection(
                languageSwitchCorrector.caretAfter(start, applied),
                languageSwitchCorrector.caretAfter(end, applied),
            )
        }
        connection.endBatchEdit()
        val changed = applied.isNotEmpty()
        if (changed) {
            checkpointField()
            refreshContextFromEditor()
            requestSuggestions()
        }
    }

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

    /** What a delimiter would write in place of [typed]; see [WordCommit]. Reads no editor. */
    private fun commitOutcome(typed: String): WordCommit.Outcome = WordCommit.decide(
        typed = typed,
        fromGesture = composingFromGesture,
        runningText = composingIsRunningText,
        shortcuts = preferences.textShortcuts,
        contractions = contractions,
        possessive = possessiveSuggestion,
        suggestion = topSuggestion,
        suggestionQuery = suggestionQuery,
        knownWord = knownQuery,
        isProperNoun = topSuggestionIsProperNoun,
        inflection = queryIsInflection,
        settings = WordCommit.Settings(
            autoCorrectOnSpace = preferences.autoCorrectOnSpace,
            autoCapitalise = preferences.autoCapitalise,
            minimumLength = preferences.minCorrectionLength,
            correctionDistance = preferences.correctionDistance,
            capitaliseNames = preferences.capitaliseNames,
        ),
    )

    /** The word the strip outlines: what a delimiter would write, unless it is a text shortcut. */
    private fun outlinedCommit(typed: String): String? {
        val outcome = commitOutcome(typed)
        return if (outcome.kind == WordCommit.Kind.SHORTCUT) null else outcome.text
    }

    /** Whether [pending]'s correction and delimiter are still the text before the caret. */
    private fun correctionBeforeCaret(pending: PendingCorrection): Boolean {
        val committed = pending.corrected + pending.delimiter
        val before = currentInputConnection?.getTextBeforeCursor(committed.length, 0)
            ?: return false
        return before.toString() == committed
    }

    /**
     * Replaces a pending correction and its delimiter with what was typed, in one batch edit.
     * Returns false when there is nothing to revert. [viaBackspace] is whether the backspace key
     * asked, which [KeyboardPreferences.revertCorrectionOnBackspace] governs.
     */
    private fun revertCorrection(connection: InputConnection, viaBackspace: Boolean = true): Boolean {
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
        val before = connection.getTextBeforeCursor(committed.length, 0)
        if (before == null || before.toString() != committed) {
            // The text before the caret changed: the correction stands and is learned.
            recordLearned(
                pending.corrected, pending.contextWord, pending.grandContextWord,
                pending.deliberateCapital,
            )
            return false
        }
        connection.beginBatchEdit()
        connection.deleteSurroundingText(committed.length, 0)
        connection.commitText(pending.typed + pending.delimiter, 1)
        connection.endBatchEdit()
        previousWord1 = pending.typed
        // The typed word is learned as asserted; the rejected correction is forgotten from the
        // personal dictionary, never blocked.
        recordLearned(
            pending.typed, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital, asserted = true,
        )
        forgetWord(pending.corrected, blockWhenNotPersonal = false)
        playEffect(EffectEvent.CorrectionReverted, pending.typed)
        refreshContextFromEditor()
        return true
    }

    /** Records the field's text as a step in [fieldHistory] when it changed. */
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

    /** Starts a new undo and redo history for a field just opened, seeded with its text. */
    private fun resetFieldHistory() {
        fieldHistory.clear()
        languageSwitchCorrector.reset()
        fieldGeneration++
        checkpointField()
    }

    /**
     * Puts the field back to [target], editing only the span where the live text differs; see
     * [FieldRestore.diff].
     */
    private fun restoreFieldVersion(target: String?) {
        if (target == null) {
            return
        }
        val connection = currentInputConnection ?: return
        val extracted = connection.getExtractedText(
            ExtractedTextRequest().apply { hintMaxChars = FIELD_HISTORY_CHARS },
            0,
        ) ?: return
        val current = extracted.text?.toString() ?: return
        if (current == target) {
            return
        }
        val span = FieldRestore.diff(current, target)
        connection.beginBatchEdit()
        finishComposing(connection)
        // The diff's offsets are into the extracted window, which starts at startOffset.
        val boundary = extracted.startOffset + span.deleteFrom + span.deleteCount
        connection.setSelection(boundary, boundary)
        if (span.deleteCount > 0) {
            connection.deleteSurroundingText(span.deleteCount, 0)
        }
        if (span.insert.isNotEmpty()) {
            connection.commitText(span.insert, 1)
        }
        connection.endBatchEdit()
        pendingCorrection = null
        refreshContextFromEditor()
        requestSuggestions()
    }

    private fun handleDelete() {
        dismissRadialMenu()
        val connection = currentInputConnection ?: return
        if (terminalField) {
            deleteInTerminal(connection)
            return
        }
        val hasSelection = selectionEnd > selectionStart
        // A selection is deleted whole, by committing empty text over it.
        if (hasSelection) {
            composing.setLength(0)
            composingFromGesture = false
            confirmPendingCorrection()
            connection.commitText("", 1)
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return
        }
        // A swiped word is deleted whole when KeyboardPreferences.swipeBackspaceDeletesWord is on.
        if (composingFromGesture && preferences.swipeBackspaceDeletesWord && composing.isNotEmpty()) {
            cancelRadialGesture()
            applyAutoShift()
            return
        }
        if (pendingSpacePeriod) {
            // Turns ". " back into the two spaces.
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
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            // After a backspace, typed letters extend a swiped word.
            composingFromGesture = false
            // Deletes one code point.
            val length = composing.length
            val start = composing.offsetByCodePoints(length, -1)
            composing.setLength(start)
            connection.setComposingText(composing, 1)
            if (composing.isEmpty()) {
                applyAutoShift()
            }
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
        applyAutoShift()
        requestSuggestions()
    }

    private fun handleEnter() {
        val connection = currentInputConnection ?: return
        if (terminalField) {
            enterInTerminal(connection)
            return
        }
        val contextWord = previousWord1
        val grandContextWord = previousWord2
        connection.beginBatchEdit()
        val finished = finishComposing(connection)
        val imeOptions = currentInputEditorInfo?.imeOptions ?: 0
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
            connection.endBatchEdit()
            connection.performEditorAction(action)
        } else {
            connection.commitText("\n", 1)
            connection.endBatchEdit()
            afterNewlineCommitted()
        }
        if (finished != null) {
            recordLearned(finished, contextWord, grandContextWord, composingCapitalisedByUser)
        }
        // Enter clears the context for the next word.
        previousWord1 = null
        previousWord2 = null
        requestSuggestions()
    }

    /**
     * After a committed newline, from [handleEnter] or the quick-action bar: records the step,
     * resets the spacing state, and re-derives the context and shift.
     */
    private fun afterNewlineCommitted() {
        checkpointField()
        pendingAutoSpace = false
        pendingSpacePeriod = false
        lastSpaceAt = 0L
        refreshContextFromEditor()
        applyAutoShift()
    }

    private fun handleShift() {
        val now = System.currentTimeMillis()
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
        host?.keyboard?.shiftState = shiftState
        // The strip's words are re-cased for the new shift state.
        requestSuggestions()
    }

    /** Locks shift, from holding it. */
    private fun lockShift() {
        shiftState = ShiftState.LOCKED
        shiftHeldByUser = true
        autoLockedShift = false
        userReleasedAutoLock = false
        host?.keyboard?.shiftState = shiftState
        requestSuggestions()
    }

    /**
     * Applies the layout settings that compose onto a page: the accent overlays, the digits on
     * the top row or in a row of their own, the modifier row, the emoji key and the globe key.
     * [allowNumberRow] is false for the number-pad symbols pages.
     */
    private fun composedLayout(layout: KeyboardLayout, allowNumberRow: Boolean = true): KeyboardLayout {
        var result = layout
        if (preferences.accentedCharacters && accentOverlays.isNotEmpty()) {
            result = result.withAccents(accentOverlays, accentSignature)
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
            result = result.withModifierRow(
                keys = preferences.modifierRowKeys.map { KeyCodes.named(it) },
                atBottom = preferences.modifierRowPosition == KeyboardPreferences.MODIFIER_ROW_BELOW,
            )
        }
        if (!preferences.emojiKey) {
            result = result.withoutEmojiKey()
        }
        if (!preferences.languageKey) {
            result = result.withoutLanguageKey()
        }
        return result
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
        host?.let { applyWritingDirection(it) }
        pushKeyGeometry()
    }

    /** The strip, the ring and the panels follow the alphabetic layout's reading direction. */
    private fun applyWritingDirection(view: KeyboardHostView) {
        view.suggestionStrip.rightToLeft = alphabeticLayout.rightToLeft
        view.radialSuggestionMenu.rightToLeft = alphabeticLayout.rightToLeft
        view.emojiPanel.rightToLeft = alphabeticLayout.rightToLeft
        view.clipboardPanel.rightToLeft = alphabeticLayout.rightToLeft
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
    private fun layoutIdFromSubtype(subtype: android.view.inputmethod.InputMethodSubtype?): String {
        val pair = subtype?.extraValue?.split(",")?.firstOrNull { it.startsWith("layout=") }
            ?: return DEFAULT_ALPHABETIC_LAYOUT
        val id = pair.removePrefix("layout=")
        return if (id.isEmpty() || id.endsWith("_qwerty")) DEFAULT_ALPHABETIC_LAYOUT else id
    }

    /** Loads the letter layout the new subtype names and redraws the current page. */
    override fun onCurrentInputMethodSubtypeChanged(subtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        scope.launch(Dispatchers.IO) {
            val layout = LayoutLoader.load(assets, layoutIdFromSubtype(subtype))
            withContext(Dispatchers.Main) {
                alphabeticLayout = layout
                host?.let { showPage(page) }
            }
        }
    }

    /** Switches to this input method's next subtype, that is its next layout. */
    private fun switchLanguage() {
        switchToNextInputMethod(true)
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
        )
    }

    /** Writes the preferences to the store the settings application uses. */
    private fun updatePreferences(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { DataGraph.themes.updatePreferences(transform) }
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

    override fun onSuggestionPicked(index: Int, word: String) {
        val connection = currentInputConnection ?: return
        dismissRadialMenu()
        if (terminalField) {
            pickIntoTerminal(connection, word)
            return
        }
        // While a correction is pending, the typed chip reverts it.
        val pending = pendingCorrection
        if (pending != null && index == host?.suggestionStrip?.typedIndex &&
            word == pending.typed && composing.isEmpty()
        ) {
            revertCorrection(connection, viaBackspace = false)
            host?.suggestionStrip?.clear()
            requestSuggestions()
            return
        }
        playEffect(EffectEvent.SuggestionPicked, word)
        confirmPendingCorrection()
        // Read before the commit.
        val contextWord = previousWord1
        val grandContextWord = previousWord2
        connection.beginBatchEdit()
        // A pick replaces the word being typed or the word the caret sits in; with neither, it
        // is a prediction inserted at the caret. An adopted word is deleted first, when it is
        // still the text before the caret.
        val replacesWord = composing.isNotEmpty() || lastQuery.isNotEmpty()
        if (composing.isEmpty() && lastQuery.isNotEmpty()) {
            val before = connection.getTextBeforeCursor(lastQuery.length, 0)
            if (before != null && before.toString() == lastQuery) {
                connection.deleteSurroundingText(lastQuery.length, 0)
            }
        }
        composing.setLength(0)
        composing.append(word)
        // A replacing pick also deletes the rest of the word after the caret.
        val after = connection.getTextAfterCursor(CONTEXT_WINDOW_CHARS, 0)
        var tail = 0
        if (after != null && replacesWord) {
            while (tail < after.length && isWordCharacter(after[tail].code)) {
                tail++
            }
        }
        if (tail > 0) {
            connection.deleteSurroundingText(0, tail)
        }
        // A space follows the pick unless one is already next, the setting is off, or the field
        // holds an address.
        val nextChar = after?.getOrNull(tail)
        val space = if (!preferences.spaceAfterSuggestion || addressField || nextChar == ' ') "" else " "
        ownEditPending = true
        connection.commitText(word + space, 1)
        connection.endBatchEdit()
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        pendingAutoSpace = space.isNotEmpty()
        // A pick spends a one-shot shift.
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
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
        previousWord2 = if (words.size >= 2) words[words.size - 2] else previousWord1
        previousWord1 = words.lastOrNull() ?: word
        composing.setLength(0)
        host?.suggestionStrip?.clear()
        checkpointField()
        applyAutoShift()
        requestSuggestions()
    }

    /** A suggestion held down: the strip offers Forget, Cancel and Why. */
    override fun onSuggestionLongPressed(index: Int, word: String) {
        if (privateMode || word.isEmpty()) {
            return
        }
        pendingForget = word
        pendingExplainQuery = lastQuery
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
        val languages = activeLanguageTags
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

    // ---- terminals ------------------------------------------------------------------------

    /**
     * Types [code] into a terminal: written at once, never composed. A letter extends
     * [terminalWord], which the strip completes; anything else ends it. A one-shot shift is
     * spent by the letter it capitalised, as in an ordinary field.
     */
    private fun typeIntoTerminal(connection: InputConnection, code: Int) {
        val letter = if (terminalWord.isEmpty()) Character.isLetter(code) else isWordCharacter(code)
        if (letter && shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        shiftHeldByUser = false
        ownEditPending = true
        writeToTerminal(connection, String(Character.toChars(code)))
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
    private fun writeToTerminal(connection: InputConnection, text: String) {
        var index = 0
        while (index < text.length) {
            val code = text.codePointAt(index)
            index += Character.charCount(code)
            val keyCode = if (code < 128) PhysicalKeys.keyCodeFor(code) else 0
            if (keyCode == 0) {
                connection.commitText(String(Character.toChars(code)), 1)
                continue
            }
            val meta = if (Character.isUpperCase(code)) SHIFT_META else 0
            sendPhysicalKey(connection, keyCode, meta)
        }
    }

    /** [count] characters back, as the key events a terminal deletes by. */
    private fun deleteInTerminal(connection: InputConnection, count: Int = 1) {
        ownEditPending = true
        repeat(count) {
            sendPhysicalKey(connection, android.view.KeyEvent.KEYCODE_DEL, 0)
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
    private fun pickIntoTerminal(connection: InputConnection, word: String) {
        playEffect(EffectEvent.SuggestionPicked, word)
        val typed = terminalWord.toString()
        val space = if (preferences.spaceAfterSuggestion) " " else ""
        ownEditPending = true
        connection.beginBatchEdit()
        if (word.length >= typed.length && word.startsWith(typed)) {
            writeToTerminal(connection, word.substring(typed.length) + space)
        } else {
            repeat(typed.codePointCount(0, typed.length)) {
                sendPhysicalKey(connection, android.view.KeyEvent.KEYCODE_DEL, 0)
            }
            writeToTerminal(connection, word + space)
        }
        connection.endBatchEdit()
        terminalWord.setLength(0)
        if (space.isEmpty()) {
            terminalWord.append(word)
        }
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        shiftHeldByUser = false
        host?.suggestionStrip?.clear()
        requestTerminalSuggestions()
    }

    /**
     * Writes a swiped word whole, after a space when letters were typed just before it, and
     * offers the rest of the decode on the strip. The word stays [terminalWord], so a pick from
     * the strip replaces it the way it replaces typed letters.
     */
    private fun swipeIntoTerminal(connection: InputConnection, cased: List<Candidate>) {
        val best = cased.first().text
        ownEditPending = true
        connection.beginBatchEdit()
        writeToTerminal(connection, if (terminalWord.isNotEmpty()) " $best" else best)
        connection.endBatchEdit()
        terminalWord.setLength(0)
        terminalWord.append(best)
        recordSwipeDecode(cased.size)
        lastQuery = best
        suggestionQuery = best
        knownQuery = best
        topSuggestion = best
        topSuggestionIsProperNoun = cased.first().isProperNoun
        host?.suggestionStrip?.let { strip ->
            strip.typedIndex = -1
            strip.appliedIndex = -1
            strip.setSuggestions(cased)
        }
        playEffect(EffectEvent.SwipeAccepted, best)
    }

    /** Enter in a terminal: the key itself, which is what runs the line. */
    private fun enterInTerminal(connection: InputConnection) {
        terminalWord.setLength(0)
        ownEditPending = true
        sendPhysicalKey(connection, android.view.KeyEvent.KEYCODE_ENTER, 0)
        requestTerminalSuggestions()
    }

    /** The strip's completions of [terminalWord]; a terminal has no words before it to read. */
    private fun requestTerminalSuggestions() {
        lastQuery = terminalWord.toString()
        previousWord1 = null
        previousWord2 = null
        if (!dictionaryAllowed) {
            return
        }
        suggestionsRequestedAt = android.os.SystemClock.uptimeMillis()
        engine.requestSuggestions(lastQuery, null, null)
    }

    override fun onExplainDismissed() {
        host?.setExplainPanelVisible(false)
        requestSuggestions()
    }

    /**
     * Forgets a personal word, with the pairs and triples it is part of. A word the personal
     * dictionary does not hold is blocked in lower case instead, when [blockWhenNotPersonal].
     */
    private fun forgetWord(word: String, blockWhenNotPersonal: Boolean = true) {
        scope.launch {
            val dictionary = DataGraph.dictionary
            // Looked up ignoring case.
            val personal = dictionary.findIgnoreCase(word)
            if (personal != null) {
                dictionary.forget(personal.word)
            } else if (blockWhenNotPersonal) {
                dictionary.block(word.lowercase())
                refreshBlockedWords(dictionary)
            }
            loadPersonalModel(dictionary)
            requestSuggestions()
        }
    }

    override fun onSuggestions(
        candidates: List<Candidate>,
        knownWord: String,
        query: String,
        possessive: String?,
        inflection: Boolean,
    ) {
        // An answer about an older query is dropped.
        if (query != lastQuery) {
            return
        }
        if (suggestionsRequestedAt != 0L) {
            KeyboardStats.suggestionMillis.add(
                (android.os.SystemClock.uptimeMillis() - suggestionsRequestedAt).toDouble(),
            )
            suggestionsRequestedAt = 0L
        }
        knownQuery = knownWord
        if (composing.isNotEmpty()) {
            host?.suggestionStrip?.editorEmpty = false
        }
        // The word the corrections heap settled on, in the engine's own case.
        val marked = candidates.firstOrNull { it.isCorrection }
        topSuggestion = marked?.text
        topSuggestionIsProperNoun = marked?.isProperNoun == true
        possessiveSuggestion = possessive
        queryIsInflection = inflection
        suggestionQuery = query
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
        val strip = host?.suggestionStrip ?: return
        // Arranged for the slots that hold words, outlining what a delimiter would commit.
        val row = suggestionRow.arrange(
            cased, lastQuery, preferences.suggestionCount.coerceAtMost(strip.wordSlotLimit),
            correction = outlinedCommit(lastQuery),
            revertable = pendingCorrection?.takeIf { correctionBeforeCaret(it) }?.typed,
        )
        strip.setSuggestions(row)
        strip.typedIndex = suggestionRow.typedIndex
        strip.appliedIndex = suggestionRow.appliedIndex
    }

    /** Where the typed word and the word a delimiter would apply end up on the strip. */
    private val suggestionRow = SuggestionRow()

    /** The last query the dictionaries recognised, or empty. */
    private var knownQuery: String = ""

    /** The word the engine was last asked about. */
    private var lastQuery: String = ""

    /** Asks the engine about the composing word; never for a password field. */
    private fun requestSuggestions() {
        lastQuery = composing.toString()
        if (privateMode) {
            refreshPrivateReveal()
        }
        if (!dictionaryAllowed) {
            return
        }
        suggestionsRequestedAt = android.os.SystemClock.uptimeMillis()
        engine.requestSuggestions(lastQuery, previousWord1, previousWord2)
    }

    /**
     * Tells the strip whether the field has anything in it; with no text before the caret, the
     * text after it is read.
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
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        if (composing.isEmpty()) {
            connection.finishComposingText()
            return null
        }
        val word = composing.toString()
        connection.finishComposingText()
        composing.setLength(0)
        previousWord2 = previousWord1
        previousWord1 = word
        KeyboardStats.words++
        return word
    }

    private fun resetComposing() {
        dismissRadialMenu()
        engine.cancelPendingPreview()
        engine.cancelPendingGesture()
        previewComposedThisGesture = false
        pendingCorrection = null
        pendingForget = null
        topSuggestion = null
        topSuggestionIsProperNoun = false
        suggestionQuery = ""
        knownQuery = ""
        queryIsInflection = false
        composing.setLength(0)
        terminalWord.setLength(0)
        composingIsRunningText = true
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        pendingAutoSpace = false
        currentInputConnection?.finishComposingText()
        refreshContextFromEditor()
        host?.suggestionStrip?.clear()
        requestSuggestions()
    }

    /** True when the composing word is the text immediately before the application's caret. */
    private fun composingMatchesCaret(caret: Int): Boolean {
        if (composing.isEmpty()) {
            return false
        }
        val connection = currentInputConnection ?: return false
        val before = connection.getTextBeforeCursor(composing.length, 0) ?: return false
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
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        composingCapitalisedByUser = false
        // The pending correction stays.
        val connection = currentInputConnection
        connection?.finishComposingText()

        val before = connection?.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            previousWord1 = null
            previousWord2 = null
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
        previousWord1 = context1
        previousWord2 = context2
        lastQuery = partial
        if (partial.isNotEmpty()) {
            composing.append(partial)
            val caret = selectionEnd
            connection.setComposingRegion(caret - partial.length, caret)
        }
        engine.requestSuggestions(partial, previousWord1, previousWord2)
    }

    /** Reads the two words before the cursor back from the editor. */
    private fun refreshContextFromEditor() {
        val connection = currentInputConnection
        val before = connection?.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
        if (before.isNullOrEmpty()) {
            previousWord1 = null
            previousWord2 = null
            return
        }
        val (context1, context2) = contextWordsBefore(before, before.length)
        previousWord1 = context1
        previousWord2 = context2
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

    /** True when what precedes the single trailing space is a word character or a digit. */
    private fun endsWithWordCharacterBeforeSpace(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0) ?: return false
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

    /** The space that follows a sentence mark, or nothing at all -- see [PunctuationSpace]. */
    private fun spaceAfter(code: Int): String {
        val connection = currentInputConnection
        val follows = PunctuationSpace.follows(
            enabled = preferences.spaceAfterPunctuation,
            addressField = addressField,
            insideNumbers = preferences.spaceInsideNumbers,
            tightPunctuation = isTightPunctuation(code),
            before = { connection?.getTextBeforeCursor(1, 0)?.firstOrNull() },
            after = { connection?.getTextAfterCursor(1, 0)?.firstOrNull() },
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
        val tags = activeLanguageTags
        return tags.isNotEmpty() && tags.all { it.startsWith("fr", ignoreCase = true) }
    }

    /**
     * Sets shift from what the field asks for and the text before the caret, unless the user set
     * it. [justCommitted] is text just written, appended to what the editor reports.
     */
    private fun applyAutoShift(justCommitted: String = "") {
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
            host?.keyboard?.shiftState = shiftState
        }
    }

    /**
     * What shift should be here, per [AutoShift], from the field's caps mode
     * ([InputConnection.getCursorCapsMode]) and the text before the cursor.
     */
    private fun autoShiftState(justCommitted: String = ""): Int {
        val info = currentInputEditorInfo ?: return ShiftState.OFF
        return AutoShift.stateFor(
            autoCapitaliseEnabled = preferences.autoCapitalise,
            inputType = info.inputType,
            composingIsEmpty = composing.isEmpty(),
            forceCapitaliseSentences = preferences.forceCapitaliseSentences,
            capsMode = {
                currentInputConnection?.getCursorCapsMode(info.inputType) ?: info.initialCapsMode
            },
            textBeforeCursor = {
                val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_WINDOW_CHARS, 0)
                if (justCommitted.isEmpty()) before else (before ?: "").toString() + justCommitted
            },
        )
    }

    private fun isWordCharacter(code: Int): Boolean =
        Character.isLetter(code) || code == '\''.code || code == '-'.code

    // ---- learning -----------------------------------------------------------------------------------

    /**
     * Whether anything is learned, and whether the personal dictionary is consulted: off with the
     * learning switch or in a private field.
     */
    private fun applyLearningGate(preferences: KeyboardPreferences = this.preferences) {
        learning.enabled = preferences.learningEnabled && !privateMode
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
        val locale = currentSubtypeTag()
        val now = System.currentTimeMillis()
        // A word with nothing before it is paired with the sentence start.
        val pairContext = contextWord ?: UserBigram.SENTENCE_START
        learning.recordPair(pairContext, word, now)
        if (contextWord != null && grandContextWord != null) {
            learning.recordTriple(grandContextWord, contextWord, word, now)
        }
        if (learning.record(word, locale, now, deliberateCapital, asserted)) {
            playEffect(EffectEvent.LearnedWord, word)
            engine.learn(
                listOf(
                    com.borderkeys.data.dao.LearnedWord(
                        word, locale, 1, now, deliberateCapital, asserted,
                    ),
                ),
                pairContext, grandContextWord,
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

    /** Writes the buffered learning to the database, off the main thread. */
    private fun flushLearning() {
        val batch = drainLearning() ?: return
        learningScope.launch {
            runCatching { persistLearning(batch) }
                .onFailure { error -> android.util.Log.e("BorderKeys", "learning flush failed", error) }
        }
    }

    /**
     * The last flush, from [onDestroy]: waits, up to [FINAL_FLUSH_TIMEOUT_MILLIS], for the writes
     * in flight and for the rest of the buffer.
     */
    private fun flushLearningBeforeDestroy() {
        val batch = drainLearning()
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

    /** What [learning] had accumulated, taken out of it in one go. */
    private class LearningBatch(
        val updates: List<com.borderkeys.data.dao.LearnedWord>,
        val pairs: List<com.borderkeys.data.dao.LearnedBigram>,
        val triples: List<com.borderkeys.data.dao.LearnedTrigram>,
    )

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

    private suspend fun persistLearning(batch: LearningBatch) {
        DataGraph.dictionary.applyLearned(batch.updates)
        DataGraph.dictionary.applyLearnedBigrams(batch.pairs)
        DataGraph.dictionary.applyLearnedTrigrams(batch.triples)
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
        DataGraph.dictionary.decayStaleEntries(now)
        prefs.edit().putLong(DECAY_LAST_SWEEP_AT, now).apply()
    }

    // ---- clipboard --------------------------------------------------------------------------------------

    private fun registerClipboardListener() {
        if (clipboardListenerRegistered || privateMode || !preferences.clipboardEnabled) {
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

    /**
     * Replaces bundled packs that this build cannot read or ships a different edition of, from
     * the assets. Imported packs are left alone. Failures are logged.
     */
    private suspend fun reinstallOutdatedBundledPacks(
        repository: com.borderkeys.data.LanguagePackRepository,
    ) {
        runCatching { repairBundledPacks(repository) }
            .onFailure { android.util.Log.w("BorderKeys", "pack repair failed", it) }
    }

    /** The pack files [LanguagePackInspector] has accepted in this process, as `path:sha256`. */
    private val readablePacks = HashSet<String>()

    private fun packReadable(file: File, sha256: String): Boolean {
        val key = "${file.path}:$sha256"
        if (key in readablePacks) {
            return true
        }
        val readable = LanguagePackInspector.inspect(file) is LanguagePackInspector.Result.Valid
        if (readable) {
            readablePacks += key
        }
        return readable
    }

    private suspend fun repairBundledPacks(
        repository: com.borderkeys.data.LanguagePackRepository,
    ) {
        for (entry in repository.allPacks()) {
            val bundled = BundledDictionaries.ALL.firstOrNull { it.tag == entry.tag } ?: continue
            val file = repository.fileFor(entry)
            // Stale: missing, unreadable, not matching its hash, or a different edition than the
            // shipped one by content CRC, word count or size.
            val shipped = runCatching {
                BundledDictionaries.open(assets, bundled).use { BundledDictionaries.contentCrc(it) }
            }.getOrNull()
            val installed = runCatching {
                file.inputStream().use { BundledDictionaries.contentCrc(it) }
            }.getOrNull()
            val stale = !file.isFile ||
                shipped == null || shipped != installed ||
                entry.wordCount != bundled.wordCount ||
                entry.sizeBytes != bundled.sizeBytes ||
                runCatching { repository.cachedSha256(file) }.getOrNull() != entry.sha256 ||
                !packReadable(file, entry.sha256)
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
            readablePacks += "${staged.file.path}:${staged.sha256}"
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
                    // Switched back on only where an integrity failure switched it off.
                    enabled = entry.enabled || entry.integrityFailedAt != null,
                    weight = entry.weight,
                    integrityFailedAt = null,
                    licenseNote = entry.licenseNote,
                ),
            )
            android.util.Log.i(
                "BorderKeys",
                "replaced the bundled ${entry.tag} pack: unreadable by this build, or an older edition than it ships",
            )
        }
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
        scope.launch {
            DataGraph.themes.updatePreferences {
                it.withPlacement(landscape) { placement ->
                    placement.copy(heightScale = height, widthScale = width)
                }
            }
        }
    }

    /**
     * Puts the saved bar on the view: which buttons, in what order, open or collapsed, and
     * against which edge. The draft-box button is left out while the draft box is off.
     */
    private fun applyQuickActions(view: KeyboardHostView) {
        val bar = view.quickActions
        if (!preferences.quickActionsEnabled || privateMode) {
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
            refreshContextFromEditor()
            requestSuggestions()
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
            QuickAction.CLIPBOARD_HISTORY -> offerClipboardHistory()
            QuickAction.SELECT_ALL -> connection.performContextMenuAction(android.R.id.selectAll)
            // The editor's own cut, then the result recorded for undo.
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
                afterNewlineCommitted()
                previousWord1 = null
                previousWord2 = null
            }
            QuickAction.SWITCH_LAYOUT -> switchLanguage()
            QuickAction.SETTINGS -> openSettings()
            QuickAction.COMPOSE -> {
                if (privateMode || !preferences.composerEnabled) return
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
            QuickAction.UNDO -> restoreFieldVersion(fieldHistory.back())
            QuickAction.REDO -> restoreFieldVersion(fieldHistory.forward())
            QuickAction.CAPITAL -> toggleCapitalAtCursor(connection)
            QuickAction.NORMALISE -> normaliseField(connection)
            // Sent as arrow key events.
            QuickAction.CURSOR_LEFT -> {
                resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            }
            QuickAction.CURSOR_RIGHT -> {
                resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            // The timestamp clears the context for the next word.
            QuickAction.TIMESTAMP -> {
                finishComposing(connection)
                connection.commitText(
                    TimestampPattern.format(preferences.timestampPattern, ZonedDateTime.now(), Locale.getDefault()),
                    1,
                )
                checkpointField()
                previousWord1 = null
                previousWord2 = null
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
        while (end > 0 && !isWordCharacter(before[end - 1].code)) {
            end--
        }
        var start = end
        while (start > 0 && isWordCharacter(before[start - 1].code)) {
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
        val range = SentenceCase.wordAt(text, extracted.selectionEnd, ::isWordCharacter) ?: return
        val word = text.substring(range.first, range.last + 1)
        val toggled = SentenceCase.toggleInitial(word)
        if (toggled == word) {
            return
        }
        connection.beginBatchEdit()
        finishComposing(connection)
        val first = base + range.first
        connection.setSelection(first, first + 1)
        connection.commitText(toggled.substring(0, 1), 1)
        connection.setSelection(base + extracted.selectionStart, base + extracted.selectionEnd)
        connection.endBatchEdit()
        pendingCorrection = null
        checkpointField()
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
        finishComposing(connection)
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
        pendingCorrection = null
        checkpointField()
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
        val clip = ClipData.newPlainText(null, text)
        clipboardManager?.setPrimaryClip(clip)
        // The chip is built from the clip in hand, not read back from the clipboard.
        withdrawnClip = null
        refreshClipboardChip(clip)
    }

    /** Opens the clipboard history as a panel of cards, read and decoded off the main thread. */
    private fun offerClipboardHistory() {
        if (privateMode) {
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
        val view = host
        view?.setClipboardPanelVisible(false)
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
        if (composing.isNotEmpty()) {
            return composing.toString()
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

    override fun onPrivateRevealToggled() {
        if (!privateMode) {
            return
        }
        privateReveal = !privateReveal
        host?.suggestionStrip?.privateReveal = privateReveal
        refreshPrivateReveal()
    }

    /** Re-reads a private field's text into the strip while it is being shown. */
    private fun refreshPrivateReveal() {
        val strip = host?.suggestionStrip ?: return
        if (!privateMode || !privateReveal) {
            return
        }
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(PRIVATE_REVEAL_CHARS, 0) ?: ""
        val after = connection.getTextAfterCursor(PRIVATE_REVEAL_CHARS, 0) ?: ""
        strip.privateText = before.toString() + after.toString()
    }

    override fun onClipboardPanelClosed() {
        host?.setClipboardPanelVisible(false)
        refreshContextFromEditor()
        requestSuggestions()
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
        // The editor's own caret, read with the text.
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        val caret = if (extracted != null && extracted.selectionEnd >= 0) {
            extracted.startOffset + extracted.selectionEnd
        } else {
            selectionEnd
        }
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
        if (privateMode || !preferences.clipboardSuggestion) {
            strip.clipboardChip = null
            shownClipSignature = null
            return
        }
        val description = clip?.description
        if (clip == null || clip.itemCount == 0 || description == null ||
            clipSignature(clip) == withdrawnClip || isSensitiveClip(clip)
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
            // Empties the clipboard by writing an empty clip, and removes the chip.
            clipboardManager?.setPrimaryClip(ClipData.newPlainText(null, ""))
            host?.suggestionStrip?.clipboardChip = null
        }
        if (preferences.clipboardDeleteAfterUse) {
            // Deletes the history entry with this text, unless it is pinned.
            scope.launch(Dispatchers.IO) { DataGraph.clipboard.deleteIfUnpinned(text) }
        }
        refreshContextFromEditor()
        requestSuggestions()
    }

    /**
     * Hands an image to the editor through commitContent, when its contentMimeTypes accept the
     * type; otherwise does nothing.
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
        // Grants the editor read access to the URI for this insertion.
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
            // An image is remembered by its URI.
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
        const val DEFAULT_ALPHABETIC_LAYOUT = "qwerty"
        const val SYMBOLS_LAYOUT = "symbols"
        const val SYMBOLS_NUMPAD_LEFT_LAYOUT = "symbols_numpad_left"
        const val SYMBOLS_NUMPAD_RIGHT_LAYOUT = "symbols_numpad_right"
        const val SYMBOLS_SHIFT_LAYOUT = "symbols_shift"
        const val NUMPAD_LAYOUT = "numpad"
        /** The `plus`-only tier B weights asset (keyboard/src/plus/assets/). */
        const val SWIPE_MODEL_ASSET = "model.bkw"

        const val PAGE_ALPHABETIC = 0
        const val PAGE_SYMBOLS = 1
        const val PAGE_SYMBOLS_SHIFT = 2
        const val PAGE_NUMPAD = 3
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"

        /** Shift held, as a key event carries it, for a capital typed into a terminal. */
        const val SHIFT_META =
            android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON

        /** The settings activity's extras: a screen to open on, and a clip that screen edits. */
        const val SETTINGS_EXTRA_SCREEN = "com.borderkeys.settings.SCREEN"
        const val SETTINGS_EXTRA_CLIP_ID = "com.borderkeys.settings.CLIP_ID"
        const val SETTINGS_SCREEN_CLIPBOARD = "Clipboard"

        /** An obsolete snapshot of the personal model, deleted at start. */
        const val LEGACY_USER_MODEL_SNAPSHOT = "user_model.bku"

        /** How long [flushLearningBeforeDestroy] waits for the database. */
        const val FINAL_FLUSH_TIMEOUT_MILLIS = 2_000L

        /** Quick actions that do not change the field and refresh no suggestions. */
        val NO_REFRESH_QUICK_ACTIONS = setOf(QuickAction.CLIPBOARD_HISTORY, QuickAction.COMPOSE)

        /** The preferences file where [maybeDecayPersonalDictionary] records its last run. */
        const val DECAY_PREFS = "personal_dictionary_decay"
        const val DECAY_LAST_SWEEP_AT = "last_sweep_at"

        /** The shortest interval between two [maybeDecayPersonalDictionary] runs. */
        const val DECAY_SWEEP_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

        const val DOUBLE_TAP_MILLIS = 400L

        const val CONTEXT_WINDOW_CHARS = 64

        /** How much of the text before the caret an emoji search reads its word from. */
        const val SEARCH_QUERY_CHARS = 32

        /** How much of a private field's text, either side of the caret, the strip can show. */
        const val PRIVATE_REVEAL_CHARS = 256

        /** How much of the field [checkpointField] and [restoreFieldVersion] read. */
        const val FIELD_HISTORY_CHARS = 20_000

        /** The longest gap between two spaces that become a full stop. */
        const val DOUBLE_SPACE_MILLIS = 1200L

        /** The characters a swiped word follows without a space; see [spaceBeforeSwipedWord]. */
        const val SWIPE_NO_SPACE_AFTER = "([{\"'/-_@#\n"

        /** How much of a copied text the chip shows. */
        const val CHIP_PREVIEW_CHARS = 24

        /** How many cards the history panel holds. */
        const val MAX_CLIPBOARD_CARDS = 40

        /** How far either side of the cursor "the line" is looked for. */
        const val LINE_WINDOW_CHARS = 1024

        const val MIN_LEARNED_LENGTH = 2

        const val GESTURE_DECODING_NOTICE_MILLIS = 50L

        /** The share of a decode, per mille, rank one must hold for [decodeWasDecisive]. */
        const val DECISIVE_SHARE_PER_MILLE = 500f

        /** How far, in pixels, the text field must move to close an open ring. */
        const val EDITOR_MOVE_DISMISS_PX = 8f
        const val MAX_CLIP_LENGTH = 20_000
        const val MAX_INLINE_SUGGESTIONS = 5
        const val MIN_CHIP_WIDTH_DP = 120
        const val BLUR_RADIUS_DP = 24f
        const val CHIP_PADDING_PX = 12
    }
}
