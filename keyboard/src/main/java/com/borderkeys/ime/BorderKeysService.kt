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
    ExplainPanelView.Listener,
    LanguageRevertPanelView.Listener,
    RadialSuggestionMenuView.Listener,
    PredictionEngine.ResultListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Where learning is written to the database -- deliberately not [scope].
     *
     * [scope] is cancelled in [onDestroy], and a write to the personal dictionary is the one
     * piece of work here that has to outlive the moment it was started: cancelling it between
     * the word table and the pair table leaves a half-recorded batch, and cancelling it before
     * it started loses the batch outright. Nothing on this scope runs longer than a transaction,
     * and nothing here ever needs to stop it.
     */
    private val learningJob = SupervisorJob()
    private val learningScope = CoroutineScope(learningJob + Dispatchers.IO)

    /**
     * Serialises [loadDictionaries]: the start-up load and a reload from [observeLanguagePacks]
     * both run on the IO dispatcher, and a pack switched in Settings while the first load is
     * still hashing must queue behind it rather than map the same files twice at once.
     */
    private val dictionaryLoad = kotlinx.coroutines.sync.Mutex()
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

    /** The field is a password box: its text is never sent to the engine (see
     *  [dictionaryAllowed]), on top of everything [privateMode] already switches off. */
    private var passwordField = false

    /** Whether the current field holds an e-mail or web address, where this keyboard adds no
     *  space of its own: none after a mark, none after a picked word, none in front of a swiped
     *  one, and no full stop from two spaces. See [AddressField]. */
    private var addressField = false

    /**
     * Whether the current field is a terminal -- see [TerminalField]. Every key then types
     * straight through: a character is committed the moment it is pressed and never composed,
     * a backspace deletes one character, a swiped word is committed whole, and nothing is
     * corrected or learned. The strip still completes the word: the letters typed since the
     * last delimiter are kept in [terminalWord], which is what a picked word replaces.
     */
    private var terminalField = false
    private val terminalWord = StringBuilder()

    /**
     * Whether words from the dictionaries may be offered in this field, and whether a gesture
     * may compose one into it. **The single gate for the suggestion strip, for autocorrect, for
     * swipe typing and for the ring a swipe opens** -- everything that puts a dictionary word in
     * front of the person or into their text asks this one question and gets one answer.
     *
     * Only a password says no. Nothing typed into one reaches the engine at all, so there is
     * nothing to suggest, nothing to correct and nothing a swipe could resolve against.
     *
     * Swipe used to answer it differently, with a longer list of its own -- e-mail, web address,
     * number, phone, date -- and that could not survive being written down beside this one: the
     * strip was already offering dictionary words in every one of those fields, because a
     * password is the only thing that silences it. A browser's address bar is where the
     * disagreement shows. It declares itself a URL field and is one, but it is also the search
     * box people type sentences into, so the keyboard suggested words for a gesture it then
     * refused to decode. Android gives a field no way to say "an address or a search", so the
     * two decisions are one question rather than a guess.
     *
     * The keys type in every field regardless; this has only ever governed what the dictionaries
     * are allowed to say. What is kept *about* the person -- learning, the clipboard, the
     * personal dictionary behind a suggestion, the assistant -- is the other gate, [privateMode],
     * which a field can ask for without being a password.
     */
    private val dictionaryAllowed: Boolean get() = !passwordField
    private var previousWord1: String? = null
    private var previousWord2: String? = null

    /**
     * Whether anything *about this person* may be read or written while this field is open: no
     * learning, no clipboard history, no personal dictionary behind a suggestion, no assistant.
     *
     * The second of the two gates a field is put through, and the wider one -- an application
     * asks for it with `IME_FLAG_NO_PERSONALIZED_LEARNING` without the field being a password,
     * and a password field is always in it as well. It is a security requirement rather than a
     * preference, so nothing can switch it off; see [PrivateMode].
     *
     * Deliberately *not* the same question as [dictionaryAllowed]. A field that asks to be
     * forgotten still gets the dictionaries -- suggestions, autocorrect, swipe and the ring all
     * work in it -- because what the shipped word lists know is not something about the person
     * typing. Only a password closes both.
     */
    private var privateMode = false

    /** Whether the strip shows a private field's text, at the user's request, this field. */
    private var privateReveal = false
    private var preferences = KeyboardPreferences()
    private var particleEffects = ParticleEffectsSettings()

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

    /** Whether the phone is rotated into landscape right now -- the one read every placement
     *  and sizing call goes through, so "which orientation's values apply" is answered once. */
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

    /**
     * The tags of the packs the engine was last told to consult, heaviest first -- the same list
     * [loadDictionaries] hands to `setActiveLanguages`. Written on the IO dispatcher, read on
     * the main thread, hence volatile.
     */
    @Volatile
    private var activeLanguageTags: List<String> = emptyList()

    /**
     * The offensive-word lists of the enabled packs, merged and folded -- see [OffensiveWords].
     * Loaded with the packs whether or not the switch is on, so flipping it costs a refresh of
     * the blocked set and the personal model, never a file read on the main thread.
     */
    private var offensiveWords: Set<String> = emptySet()

    /** The emoji panel's keywords for the languages switched on; see [EmojiKeywords]. */
    private var emojiKeywords: Map<String, List<String>> = emptyMap()

    /** Apostrophe spellings for the languages switched on -- see [Contractions]. Rebuilt with
     *  the pack list, because which entries survive depends on which languages are enabled. */
    private var contractions: Map<String, String> = emptyMap()

    /** The in-flight tier B load, held so a quick off-on-off supersedes rather than races. */
    private var swipeModelJob: kotlinx.coroutines.Job? = null

    /**
     * The language the engine currently considers the conversation written in, refreshed after
     * every completed word -- see [writingInFrench]. Null until the evidence has settled.
     */
    private var dominantLanguageTag: String? = null

    /** Which page is on screen. The numeric one is chosen by the field, not by the user. */
    private var page = PAGE_ALPHABETIC

    private var shiftState = ShiftState.OFF

    /** Control and alt from the modifier row, each armed for the next key. */
    private var controlArmed = false
    private var altArmed = false

    /**
     * Set when the user pressed shift themselves, cleared by the character it applied to.
     *
     * Without it the automatic state overwrites a deliberate press the moment the caret moves,
     * which is every time a character is typed.
     */
    private var shiftHeldByUser = false

    /**
     * Whether the word now composing started with a capital letter the user typed themselves --
     * shift physically pressed for that one character, never auto-capitalise's own doing.
     *
     * Unlike [shiftHeldByUser], which [handleCharacter] resets after every character, this is
     * set once, only when [composing] is empty and about to receive its first character, and
     * then left alone for the rest of the word -- it has to survive past that one keystroke to
     * reach whichever `recordLearned` call eventually learns the finished word. Read there as
     * evidence that this word is plausibly a name; see `UserModel::learn`'s own doc for what it
     * becomes once learned.
     */
    private var composingCapitalisedByUser = false

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
     * Whether the word currently composing came from a swipe rather than typed letters. A swipe
     * is a whole word: a letter typed straight after it starts the *next* word, with the space
     * a swipe implies, rather than extending the swiped one -- "hello" then "w" is "hello w",
     * not "hellow". Cleared the moment the swiped word is finished, edited (a backspace into it
     * means the user is correcting it, so further letters do extend it) or replaced.
     */
    private var composingFromGesture = false

    /**
     * Whether the swiped word now composing was preceded by a space this class inserted itself
     * -- see [spaceBeforeSwipedWord]. Read by [cancelRadialGesture], which has to take that
     * space back too: cancelling a swipe means the text reads as if it never happened, and a
     * stray trailing space is not that.
     */
    private var swipeAutoSpaceInserted = false

    /**
     * The user turned off a caps lock that auto-shift had put on for a field asking for
     * capitals everywhere. Auto-shift would put it straight back on the next caret echo, so the
     * release is honoured until the next letter is typed -- one lower-case word at a time, on
     * purpose, in a field that wants capitals.
     */
    private var userReleasedAutoLock = false

    /**
     * A commit of this class's own is about to be echoed back by the editor as a caret move
     * that leaves the composing region empty -- a delimiter, a picked suggestion. That echo is
     * indistinguishable, by its numbers alone, from a tap that moved the caret somewhere new,
     * and re-deriving everything from the editor for it costs a second engine round trip and a
     * text read per keystroke. Set right before such a commit, spent by the very next selection
     * report, and cleared by any key press so a report that never came cannot mute a real move.
     */
    private var ownEditPending = false

    /**
     * Whether this gesture's pause-time preview already composed a word. Read by [onGesture]
     * when the stroke resumed after that preview opened no ring: the full stroke decodes then,
     * and the preview's own guess is discarded rather than committed as a word of its own.
     */
    private var previewComposedThisGesture = false

    /**
     * Where the text field sat on screen when the ring opened -- the editor's own view matrix
     * translation, from [onUpdateCursorAnchorInfo] -- or NaN before the first report. A change
     * means the page scrolled under the ring, which is the one signal a tap elsewhere in the
     * app produces, and closes it when [KeyboardPreferences.radialCloseOnEditorMove] is on.
     */
    private var ringEditorOriginX = Float.NaN
    private var ringEditorOriginY = Float.NaN

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

    /** When the last swipe was lifted, for the debug timing line in onGestureCandidates. */
    private var gestureLiftedAt = 0L

    /** Uptime of the keystroke the engine was last asked about, for the strip latency figure. */
    private var suggestionsRequestedAt = 0L

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }
    /** The leading suggestion, kept so the delimiter path can apply it. */
    private var topSuggestion: String? = null

    /** Whether [topSuggestion] is a name -- see AutoCorrection.matchCase's own doc for what
     *  that changes about how it gets capitalised. */
    private var topSuggestionIsProperNoun: Boolean = false

    /** "Maria's" for the "marias" being typed, or null -- see Engine::possessiveFor. */
    private var possessiveSuggestion: String? = null

    /** Whether the word being typed is a regular inflection of a word the dictionaries hold
     *  that the offered correction is not built on -- see WordStems.shields. */
    private var queryIsInflection = false

    /**
     * The word [topSuggestion] is actually an answer about.
     *
     * Set alongside [topSuggestion] in [onSuggestions], and passed to
     * [AutoCorrection.correctionFor] -- see that parameter's own doc for why a delimiter can be
     * typed before the answer for the word just finished has arrived at all, and what trusting
     * [topSuggestion] anyway would have corrected the word being committed to instead.
     */
    private var suggestionQuery: String = ""

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
        /** The word before [contextWord], for the same reason -- see [recordLearned]. */
        val grandContextWord: String?,
        /** [composingCapitalisedByUser] at the moment [typed] was finished, carried alongside
         *  it: that field moves on to the next word long before this correction is confirmed or
         *  reverted, so whichever spelling ends up learned needs its own copy of the answer. */
        val deliberateCapital: Boolean,
        /** Whether surviving the next keystroke teaches the dictionary [corrected]. False for a
         *  text shortcut's expansion, which is several words and the user's own already. */
        val learn: Boolean = true,
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

    /** Which words look wrong once the conversation's language has moved on -- see its own doc.
     *  Scoped to the session the same way [fieldHistory] is, and reset alongside it. */
    private val languageSwitchCorrector = LanguageSwitchCorrector()

    /**
     * Whether the word being composed belongs to a sentence rather than to an address, a path
     * or a name a machine will read -- see [RunningText].
     *
     * Decided once, as the word's first letter arrives, and then reused. The strip asks whether
     * a correction exists on every keystroke, so deciding it there would put an editor round
     * trip on the hot path; the character this depends on cannot change while the word is being
     * typed anyway, since it sits in front of it.
     */
    private var composingIsRunningText = true

    /** Whether the radial ring is open right now -- see its own doc. Not reset per field the
     *  way [languageSwitchCorrector] is: it has no state that could survive a field switch
     *  anyway, since a gesture never spans two fields. */
    private val swipeRadialController = SwipeRadialController()

    /**
     * Where a swipe paused, in [KeyboardCanvasView]'s own local pixel space -- which is exactly
     * [KeyboardHostView.radialSuggestionMenu]'s own local space too, since that view is laid out
     * to [KeyboardCanvasView]'s identical rect (see `KeyboardHostView.onLayout`). Set from
     * [onGesturePaused]: the decode answering it arrives later, asynchronously, as a plain word
     * list with no coordinates of its own to anchor with.
     */
    private var lastGestureX = 0f
    private var lastGestureY = 0f

    /** What [syncDebugRing]'s sample ring offers -- placeholders, never typed by anyone. */
    private val DEBUG_RING_WORDS = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot")

    /** The pause-time decode's rank #1 -- what [resolveRadialRing] applies when the steering
     *  finger lifted with neither a wedge nor the centre Cancel button touched and
     *  [KeyboardPreferences.radialTimeoutDefault] says to apply rather than cancel. Set in
     *  [onGesturePreviewCandidates], read once at resolution and never relied on afterwards. */
    private var radialTopWord: String? = null

    /**
     * As much of a decode as the ring has room for, empty when no ring is warranted.
     *
     * The strip is handed the same `words` and `count` untouched, so both surfaces show one
     * ranking in one order and neither sorts anything of its own -- which is why a wedge index
     * and a strip index name the same word, and [resolveRadialSelection] can hand one straight
     * to [onSuggestionPicked]. The only difference is how many fit and how they are painted.
     *
     * A list because that is what the ring's own API takes; this is the single place the decode
     * changes shape. Rank one leads it because that is the word already composing in the field,
     * and a ring without it is a set of choices that cannot include keeping what you have -- the
     * centre X discards it, and only a tap outside keeps it. A decode of one offers nothing to
     * choose between and opens no ring at all; the empty list is what onRingOpened refuses on.
     */
    private fun ringWedges(candidates: List<Candidate>): List<String> =
        if (candidates.size < 2) {
            emptyList()
        } else {
            candidates.take(preferences.radialSuggestionCount).map { it.text }
        }

    /** Which wedge carries the word already in the field. Rank one leads every ring this class
     *  opens, so the strip's outlined chip and the ring's outlined wedge are the same word. */
    private val trustedWedgeIndex = 0

    /**
     * Whether the decode settled the word rather than leaving a choice.
     *
     * `Engine::normaliseGestureScores` softmaxes the candidates into shares per mille that sum
     * to 1000, so rank one clearing [DECISIVE_SHARE_PER_MILLE] means the decoder found it more
     * likely than every alternative put together. That, not merely ranking first -- which a
     * sorted list makes true by construction -- is what a swipe with nothing left to ask about
     * looks like.
     */
    private fun decodeWasDecisive(candidates: List<Candidate>): Boolean =
        candidates.size < 2 || candidates.first().share >= DECISIVE_SHARE_PER_MILLE

    /**
     * Resolves the ring directly, unconditionally -- a real lift is the only thing that ever
     * gets to consider [KeyboardPreferences.radialLiftKeepsOpen] (see [resolveRadialRing]'s own
     * doc); nothing else does, on purpose. Applies or cancels, never merely waits again -- the
     * pick-timeout's own path, the one way the ring resolves without an actual lift on it to
     * read. Everything else that ends the ring without a lift is a *dismissal*, not a
     * resolution -- see [dismissRadialMenu].
     */
    private fun forceResolveRadialRing() {
        val selection = host?.radialSuggestionMenu?.currentSelection()
            ?: RadialSuggestionMenuView.Selection.None
        closeRadialRing((selection as? RadialSuggestionMenuView.Selection.Word)?.index)
        resolveRadialSelection(selection)
    }

    /**
     * How long the real menu waits, after the ring opens, before applying the top candidate (or
     * cancelling) on its own -- see [forceResolveRadialRing]'s own doc for why it always
     * resolves rather than merely waiting again. Armed only in [onGesturePreviewCandidates], for
     * [KeyboardPreferences.radialPickTimeoutMillis] after the ring opens, and only with
     * [KeyboardPreferences.radialLiftKeepsOpen] off. Cancelled by real steering movement
     * ([onGestureSteered]), an actual resolution, or [dismissRadialMenu]. One instance, like
     * every other scheduled callback in this class -- see [gestureDecodingRunnable]'s own doc.
     */
    private val radialTimeoutRunnable = Runnable { forceResolveRadialRing() }

    /**
     * Bumped every time [resetFieldHistory] runs -- a new field, a new generation.
     *
     * [languageSwitchCorrector]'s checks cross two async round trips to the prediction thread and
     * back (see [checkLanguageSwitch]); a field switch in between must not let an answer meant
     * for the field the user just left edit the one they are in now. Captured at the start of a
     * check, compared before the edit lands.
     */
    private var fieldGeneration = 0

    /** The word the strip is currently asking about, between the hold and the answer. */
    private var pendingForget: String? = null

    /** The composing text the strip was about when [pendingForget] was held down. */
    private var pendingExplainQuery: String = ""

    private var clipboardManager: ClipboardManager? = null
    private var clipboardListenerRegistered = false

    // ---- lifecycle ---------------------------------------------------------------------------

    /**
     * Listens for the wallpaper changing, so a keyboard following the system colours redraws.
     *
     * The platform call, not ContextCompat's. The app manifest removes the signature permission
     * ContextCompat needs to emulate RECEIVER_NOT_EXPORTED below API 33 (see the comment there:
     * "no permissions at all" has to be true as printed), and without that permission
     * ContextCompat throws on API 30-32 -- which took the whole input method down in [onCreate]
     * on exactly the devices minSdk exists for. ACTION_WALLPAPER_CHANGED is a protected system
     * broadcast that only the platform can send, so on 30-32 the plain registration is already
     * unspoofable; from 33 the platform demands an explicit flag for every dynamic receiver and
     * gets one.
     */
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
        // Before anything that draws. The stored language is read on this thread because the
        // service has nothing to show until it is known, and it is one small file read at
        // process start rather than something on the typing path.
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
            // A keyboard that cannot start is one the user cannot replace without already
            // having another keyboard installed, so nothing on this path is allowed to be
            // fatal. Failing to open the database costs suggestions; it must not cost typing.
            //
            // This is not hypothetical: a schema change without a version bump made Room refuse
            // to open the database, and the whole input method died on start with it.
            runCatching { loadDictionaries() }
                .onFailure { error -> degradeWithoutDictionaries(error) }
            File(filesDir, LEGACY_USER_MODEL_SNAPSHOT).delete()
        }
        // Nothing of tier B is read here. The weights are loaded only once the preference asks
        // for them -- see applySwipeModel, driven from observeSettings -- and this engine is new,
        // so whatever a previous service instance had loaded is gone with it.
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        observeSettings()
        observeLanguagePacks()
        observeDictionaryEdits()
    }

    /**
     * Reloads the personal model after an edit made by hand on the Personal dictionary screen
     * -- a word or a phrase forgotten, a word blocked or unblocked, a file imported -- so what
     * the strip offers agrees with what the screen shows without a restart. Under the same lock
     * as the start-up load, so an edit during that load queues behind it.
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
     * Turns tier B on or off, which means loading or freeing two and a half megabytes of weights
     * rather than only setting a flag -- see PredictionEngine.setSwipeModelEnabled.
     *
     * Everything about the experimental swipe model is driven from here, from the one preference,
     * rather than being pushed at every field the way the engine's other settings are. That is
     * what makes the switch take effect on a keyboard that is already open: it used to be applied
     * only in onStartInputView, so flipping it while the "Try it here" box had focus did nothing
     * until the field was touched again.
     *
     * A failed load is permanent for this installation ([KeyboardPreferences.swipeModelFailed]).
     * A `model.bkw` that will not parse is a property of the installed build, so retrying it on
     * every start would re-read the whole file to fail again; the settings screen shows a
     * disabled row asking the user to report it instead. A `core` build never reaches any of
     * this, because the preference cannot be turned on there -- the row does not exist.
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
                // Answers once the weights are in and the model has been run once, so the
                // screen stops showing a spinner only when the next swipe really is tier B's.
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
     * Writes off tier B for this installation: the engine is told to drop it, the switch is
     * turned back off so the stored preference cannot disagree with what is running, and the
     * fault is recorded so the settings screen can keep the option disabled after a restart.
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
     * Loads what the engine needs before the first keystroke: the language packs the user has
     * enabled, their personal dictionary, and the words they have refused.
     *
     * Every enabled pack is re-hashed first. The files are in private storage, but private is a
     * statement about other applications -- not about a restore that substituted one or a
     * filesystem that corrupted it. A pack that fails switches itself off rather than being
     * mapped.
     */
    private suspend fun loadDictionaries() {
        dictionaryLoad.withLock {
            val repository = DataGraph.languagePacks
            // Repaired before the integrity sweep runs, not after: the sweep switches off
            // anything whose file no longer matches, and a pack that has been switched off is a
            // pack the repair would never look at again.
            reinstallOutdatedBundledPacks(repository)
            repository.verifyEnabled()

            // Heaviest first -- the DAO orders them so -- and cut to what the engine has slots
            // for. Settings holds the count at the limit, so the cut is a backstop for a
            // database restored or edited past it. It used to be no backstop at all: the bridge
            // refused more tags than slots outright, and a fifth enabled pack left the keyboard
            // with no prediction, no correction and no swipe, and nothing on screen to say why.
            val everyEnabled = repository.enabledPacks()
            val enabled = everyEnabled.take(LanguagePackRepository.MAX_ENABLED)
            if (enabled.size < everyEnabled.size) {
                android.util.Log.w(
                    "BorderKeys",
                    "${everyEnabled.size} packs enabled, loading the ${enabled.size} heaviest",
                )
            }

            // The accent overlays follow the enabled packs: turn a language on and its
            // diacritics appear on the letter keys, turn it off and they are gone. Built here,
            // on the same list, so the two can never disagree.
            accentOverlays = AccentOverlays.merge(enabled.map { AccentOverlays.load(assets, it.tag) })
            accentSignature = enabled.joinToString(",") { it.tag }
            activeLanguageTags = enabled.map { it.tag }
            offensiveWords = OffensiveWords.merge(enabled.map { OffensiveWords.load(assets, it.tag) })
            emojiKeywords = EmojiKeywords.load(assets, enabled.map { it.tag })
            // Built from the same list, and against it: an entry is dropped when one of the
            // languages that calls its typed spelling an ordinary word is also switched on.
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

            // The final set reaches the engine before the files do, and again after. The engine
            // closes whatever it has open that is not named, which is what frees a slot for a
            // pack switched on in another's place; a tag that is named and already open keeps
            // its slot and is replaced in place by the load below. Sent even when the set is
            // empty: switching every pack off has to reach the engine as "nothing", not leave
            // the last set active, which is what skipping the call for an empty list used to do.
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
     * Pushes what must never be suggested or learned: the words the user refused, and the
     * offensive-word list while its switch is on. One set, folded here ([WordFold]), handed to
     * the engine's candidate filter and to the learning buffer alike.
     */
    private suspend fun refreshBlockedWords(dictionary: DictionaryRepository) {
        val refused = HashSet<String>()
        dictionary.blockedWordSet().mapTo(refused) { WordFold.fold(it) }
        if (preferences.blockOffensiveWords) {
            refused.addAll(offensiveWords)
        }
        engine.setBlockedWords(refused)
        learning.setBlockedWords(refused)
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
        // With the offensive-word switch on, the personal model is loaded without those words,
        // and without any pair or triple that touches one. Nothing is deleted: the rows stay in
        // the database and are back the moment the switch is turned off. This is what keeps a
        // word learned before the switch was on out of the strip -- and out of the two-word
        // phrases the engine builds from the personal model alone, which the candidate filter
        // could not see inside "holy shit". New ones the learning buffer refuses on its own.
        val hidden = if (preferences.blockOffensiveWords) offensiveWords else emptySet()
        fun shown(word: String) = hidden.isEmpty() || WordFold.fold(word) !in hidden
        engine.loadUserWords(
            dictionary.topWords().filter { shown(it.word) }.map { it.decayed(now) },
        )
        // After the words, never before: a pair names two words, and the model resolves those
        // names against what it already holds.
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
                    // repeating that work would map every pack a second time for nothing. The
                    // layout is still redrawn once, in case a view was created before that run
                    // finished building the accent overlays.
                    val first = previous == null
                    previous = signature
                    if (first) {
                        host?.let { showPage(page) }
                        return@collect
                    }
                    // Off the main thread: this re-hashes every enabled pack and maps them,
                    // and it used to run right here on Main.immediate -- a switch toggled in
                    // Settings had the input method's own thread reading a hundred megabytes.
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
                // The theme store failing is not a reason to have no keyboard either; the
                // defaults are perfectly usable colours.
                android.util.Log.e("BorderKeys", "settings unavailable, using defaults", error)
            }.collect { (newTheme, newLightTheme, newPreferences, newParticleEffects) ->
                theme = newTheme
                lightTheme = newLightTheme
                val wasForcingDebugRing = preferences.debugForceRadialRing
                val offensiveSwitchFlipped =
                    preferences.blockOffensiveWords != newPreferences.blockOffensiveWords
                // Same shape, and the same reason: the first emission counts as a flip when the
                // stored value differs from the default this service seeded itself with, which
                // is what loads tier B at start for someone who had already turned it on.
                val swipeModelFlipped =
                    preferences.experimentalSwipeModelEnabled !=
                        newPreferences.experimentalSwipeModelEnabled
                preferences = newPreferences
                if (swipeModelFlipped) {
                    applySwipeModel(newPreferences.experimentalSwipeModelEnabled)
                }
                particleEffects = newParticleEffects
                if (offensiveSwitchFlipped) {
                    // The switch changes what the blocked set and the personal model hold, not
                    // what is stored: both are rebuilt from the database, under the same lock
                    // the start-up load takes, so a flip during that load queues behind it --
                    // including the first emission here, which is what applies a stored "on"
                    // after a load that ran on the defaults.
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
                        // Open while the settings application changed something: the panel shows
                        // what is stored, so it follows rather than holding a stale copy.
                        pushQuickSettingsState(view)
                    }
                    view.keyboard.hapticEnabled = newPreferences.hapticFeedback
                    view.keyboard.soundEnabled = newPreferences.keySound
                    view.keyboard.hapticConstant = HapticStrength.constantFor(newPreferences.hapticStrength)
                    view.keyboard.keyPopupEnabled = newPreferences.keyPopup
                    view.suggestionStrip.hapticConstant = view.keyboard.hapticConstant
                    view.radialSuggestionMenu.hapticConstant = view.keyboard.hapticConstant
                    view.emojiPanel.hapticConstant = view.keyboard.hapticConstant
                    view.keyboard.spaceCursorEnabled = newPreferences.spaceCursorControl
                    view.keyboard.holdHintsEnabled = newPreferences.longPressHints
                    view.keyboard.longPressDelayMillis = newPreferences.longPressMillis.toLong()
                    view.keyboard.radialMenuEnabled = newPreferences.radialMenuEnabled
                    view.keyboard.radialPauseDwellMillis =
                        newPreferences.radialPauseDwellMillis.toLong()
                    view.keyboard.radialMinPathLetters = newPreferences.radialMinPathLetters
                    view.radialSuggestionMenu.sizeScale =
                        KeyboardPreferences.radialSizeScale(newPreferences.radialMenuSize)
                    view.radialSuggestionMenu.hapticEnabled = newPreferences.hapticFeedback
                    view.radialBlurBackground = newPreferences.radialBlurBackground
                    view.suggestionStrip.hapticEnabled = newPreferences.hapticFeedback
                    view.suggestionStrip.visibleLimit = newPreferences.suggestionCount
                    applyQuickActions(view)
                    refreshClipboardChip()
                    if (wasForcingDebugRing && !newPreferences.debugForceRadialRing) {
                        // Switched off: a forced ring still open goes with it.
                        closeDebugRing()
                    }
                    syncDebugRing()
                    view.keyboard.swipeEnabled = newPreferences.swipeEnabled && dictionaryAllowed
                    view.suggestionStripEnabled = newPreferences.showSuggestionStrip
                    // Auto-capitalise toggled while the keyboard is up takes effect now, not
                    // at the next caret move.
                    applyAutoShift()
                    // The number row is a layout change, not a colour change, so it has to be
                    // applied even when the paints are unchanged.
                    showPage(page)
                    view.fullWidthBackground = resolvedTheme.fullWidthBackground
                    view.navigationBarBackground = resolvedTheme.navigationBarBackground
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
     * A tap in the editor itself -- the framework's own "the user clicked the text view"
     * signal, sent on the tap's lift regardless of whether the caret ended up anywhere new.
     * [onUpdateSelection] below only ever hears about a tap that *moved* the caret; one that
     * lands right where the caret already was (the end of the word just swiped, typically)
     * produces no selection update at all, and without this the ring would sit there through
     * exactly the tap the user meant to close it with. Same outcome as any other touch outside
     * the ring: close it, leave the text alone.
     *
     * [onUpdateEditorToolType] is the API 34 replacement the framework prefers to call; the
     * deprecated [onViewClicked] still arrives on older releases and from editors that only
     * know the old path. Both funnel into the same idempotent dismiss, so hearing about one
     * tap twice costs nothing.
     */
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
        refreshPrivateReveal()
        // newSelEnd > 0 means there is text before the caret; the extracted-text path below
        // covers a caret at zero with text after it.
        updateEditorEmpty(newSelEnd > 0)
        val view = host ?: return
        val hasSelection = newSelEnd > newSelStart
        if (view.suggestionStrip.actionMode) {
            // Never the assistant's doing any more -- see below. What is left of actionMode
            // (rejecting a suggestion, "Forget / Cancel") is dismissed the same way selecting
            // text dismisses anything else stale on the strip -- and the word it was asking
            // about goes with it, so a later tap cannot forget it by accident.
            view.suggestionStrip.clear()
            pendingForget = null
        }
        if (!hasSelection) {
            // The caret moved. If our own edit moved it the composing region already agrees with
            // where it is, and re-deriving would be work for the same answer; if something else
            // moved it -- a tap into the middle of a sentence, an arrow key, a backspace out of
            // one word and into another -- then the word under the caret has changed and the
            // strip is describing a word the user has left. Re-deriving is what keeps it live.
            //
            // That same "something else moved it" is also a tap the ring never saw: while it is
            // open (steered live, or kept open and waiting for a fresh tap), a touch that lands
            // in the editor itself -- a different window this class has no view in at all --
            // reaches here only as a caret that jumped somewhere our own composing text does not
            // account for. Dismissed, the same as a tap outside the ring on the keyboard: the
            // ring closes and the swiped word stays exactly as it is, neither committed nor
            // lost.
            //
            // Only when the caret genuinely moved, though. Opening the ring composes the top
            // word first, and the editor echoes that very edit back here as a caret move a few
            // milliseconds later -- before anyone has had a chance to touch anything. Treating
            // that echo as a tap closed the ring the moment it opened, every time, which is
            // what "keep the ring open" failing to keep it open actually was. composingMatchesCaret
            // reads the editor's *current* text, not the update's own numbers, so even an echo
            // that arrives late (the previous word's finishComposingText landing after the next
            // ring already opened) still reads as "nothing moved". The one case this gives up:
            // an editor that ends the composition on a tap landing exactly where the preview
            // already ends leaves the ring open -- harmless now, since the next touch outside
            // it closes it without doing anything else.
            val caretMatches = composingMatchesCaret(newSelEnd)
            if (caretMatches) {
                // Only when the strip is not already about this exact word: the echo of our own
                // keystroke arrives after handleCharacter has already asked, and asking twice
                // per letter is wasted work -- and after a swipe it would replace the swipe's
                // own alternatives on the strip with completions of the swiped word.
                if (lastQuery != composing.toString()) {
                    requestSuggestions()
                }
            } else if (ownEditPending) {
                // The echo of a delimiter or a pick this class just committed: the caret is
                // exactly where that commit left it, the context was already refreshed and the
                // engine already asked. Nothing to re-derive, and no tap to read into it.
                ownEditPending = false
            } else if (terminalField) {
                // A terminal's caret says nothing about the word being typed: the letters
                // since the last delimiter are what the keyboard itself kept.
                dismissRadialMenu()
            } else {
                dismissRadialMenu()
                adoptWordAtCaret()
            }
            // Shift is derived from the text before the caret, so moving the caret is exactly
            // when it has to be looked at again.
            applyAutoShift()
        } else {
            // A real selection is even less ambiguous than a moved caret -- nothing this class
            // does on its own ever leaves a range selected, so this is always something else.
            dismissRadialMenu()
        }
        // A selection used to turn the strip into three assistant buttons here. It no longer
        // does anything: the strip is corrections and predictions, never anything else -- the
        // assistant is reached through the draft box now, opened deliberately from the quick
        // action or (for a selection outside any field this keyboard is bound to) from the
        // system's own text-selection menu, never by the mere act of selecting text.
    }

    override fun onActionPicked(index: Int) {
        // The "forget this word" question: forget, cancel, and in a debuggable build, explain.
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

    override fun onCreateInputView(): View {
        // Built in code. LayoutInflater would parse XML and reflect to construct three views,
        // every time the keyboard is shown in a new editor.
        paints.update(effectiveTheme(), resources.displayMetrics, activePlacement().heightScale, this)
        val view = KeyboardHostView(this, paints, strings)
        applyPlacement(view, preferences)
        applyParticleSettings(view, particleEffects)
        view.keyboard.listener = this
        view.keyboard.hapticEnabled = preferences.hapticFeedback
        view.keyboard.swipeEnabled = preferences.swipeEnabled
        view.keyboard.soundEnabled = preferences.keySound
        view.keyboard.hapticConstant = HapticStrength.constantFor(preferences.hapticStrength)
        view.keyboard.keyPopupEnabled = preferences.keyPopup
        view.suggestionStrip.hapticConstant = view.keyboard.hapticConstant
        view.radialSuggestionMenu.hapticConstant = view.keyboard.hapticConstant
        view.emojiPanel.hapticConstant = view.keyboard.hapticConstant
        view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
        view.keyboard.holdHintsEnabled = preferences.longPressHints
        view.keyboard.longPressDelayMillis = preferences.longPressMillis.toLong()
        view.keyboard.radialMenuEnabled = preferences.radialMenuEnabled
        view.keyboard.radialPauseDwellMillis = preferences.radialPauseDwellMillis.toLong()
        view.keyboard.radialMinPathLetters = preferences.radialMinPathLetters
        view.radialSuggestionMenu.sizeScale =
            KeyboardPreferences.radialSizeScale(preferences.radialMenuSize)
        view.radialSuggestionMenu.hapticEnabled = preferences.hapticFeedback
        view.radialBlurBackground = preferences.radialBlurBackground
        view.keyboard.setLayout(composedLayout(alphabeticLayout))
        view.suggestionStrip.listener = this
        view.suggestionStrip.hapticEnabled = preferences.hapticFeedback
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
        passwordField = info != null && PrivateMode.isPasswordField(info.inputType)
        addressField = info != null && AddressField.isAddress(info.inputType)
        terminalField = TerminalField.isTerminal(info)
        terminalWord.setLength(0)
        learning.enabled = preferences.learningEnabled && !privateMode
        // The other half of not learning here: nothing already learned is offered either. A
        // password field never reaches the engine at all (see requestSuggestions), but a field
        // that merely asked for no personalised learning still asks for suggestions, and they
        // used to come from the personal dictionary like anywhere else.
        engine.setPersonalModelEnabled(!privateMode)
        engine.setLearningSpeed(
            KeyboardPreferences.learningSpeedFactor(preferences.learningSpeed),
        )
        engine.setCorrectionStrictness(preferences.correctionStrictness)
        engine.setLanguageLock(
            KeyboardPreferences.languageLockEvidence(preferences.languageLock),
            KeyboardPreferences.languageLockStrict(preferences.languageLock),
        )
        engine.setPreferredLanguage(preferences.preferredLanguageTag)
        // Only when there is a preference to give a look in. The verdict reached in the last
        // field is otherwise inherited by this one, and a preferred language that never gets
        // consulted after the first field of a session is not the setting anyone asked for.
        //
        // Deliberately conditional rather than unconditional: with no preferred language, keeping
        // the verdict across fields is what the keyboard has always done, and changing that for
        // everyone is not part of this setting.
        if (preferences.preferredLanguageTag.isNotEmpty()) {
            engine.resetLanguageEvidence()
        }
        engine.setPhraseSuggestions(preferences.phraseSuggestions)
        // The experimental swipe model is deliberately *not* re-pushed here the way the settings
        // above are. It owns two and a half megabytes that are loaded and freed as the preference
        // changes (applySwipeModel), so re-asserting "on" at every field would claim the model is
        // active while the weights were freed and never reloaded. observeSettings owns it.
        if (privateMode) {
            learning.discard()
        }

        privateReveal = false
        host?.let { view ->
            view.suggestionStrip.privateMode = privateMode
            view.suggestionStrip.privateReveal = false
            view.suggestionStrip.privateText = null
            view.suggestionStrip.clear()
            view.suggestionStrip.hapticEnabled = preferences.hapticFeedback
            view.keyboard.hapticEnabled = preferences.hapticFeedback
            view.keyboard.swipeEnabled = preferences.swipeEnabled && dictionaryAllowed
            view.suggestionStripEnabled = preferences.showSuggestionStrip
            view.keyboard.soundEnabled = preferences.keySound
            view.keyboard.spaceCursorEnabled = preferences.spaceCursorControl
            applyParticleSettings(view, particleEffects)
        }
        showPage(pageFor(info))
        // A fresh field starts from nothing: a caps lock left on in the last one -- which
        // applyAutoShift would otherwise leave standing, respecting it as the user's own -- must
        // not follow into a password box or a search bar.
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
        // Posted, not called: on the first field of a session the keyboard has not been laid
        // out yet at this point, and the ring needs its size to be centred on it.
        host?.post { syncDebugRing() }

        host?.setClipboardPanelVisible(false)
        host?.setEmojiPanelVisible(false)
        registerClipboardListener()
        refreshClipboardChip()
        pushKeyGeometry()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The keyboard is going away -- the user tapped something in the app that took it down,
        // or left the field. That is the one signal the IME gets for "touched outside the
        // keyboard", and a ring waiting for a tap must not outlive it. Closing keeps the swiped
        // word as it is, the same as any other dismissal.
        dismissRadialMenu()
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

    /** Same reasoning as [onFinishInputView]: the window hiding for any reason ends the ring. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        dismissRadialMenu()
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

    /**
     * Redraws with "follow the wallpaper" colours when the wallpaper itself changes.
     *
     * [observeSettings] refreshes on every emission from [DataGraph.themes] -- but a wallpaper
     * change is not one: the stored theme and preferences are unchanged, only what
     * [DynamicColors] reads off the system is, and nothing there is a Flow this service can
     * collect from. Without this, dynamic colour only caught up the next time something else
     * happened to recreate the view (a fresh editor after the keyboard had been gone a while),
     * which read as "wallpaper colours are broken" rather than "stale until the next rebuild".
     */
    private val wallpaperChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (preferences.followSystemColors) {
                refreshTheme()
            }
        }
    }

    /** Re-applies [effectiveTheme] to what is on screen without anything in the DataStore having
     *  changed -- see [wallpaperChangedReceiver]'s own doc for the one case that needs this. */
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

    /**
     * Picks up the other orientation's size and position when the phone rotates.
     *
     * A `Service` -- which an input method is one of, under the framework's own IME window --
     * is not torn down on a configuration change the way an `Activity` can be, so nothing else
     * would ever notice the phone turned: [onCreateInputView] reads [activePlacement] fresh,
     * but only runs for a view that did not already exist. Re-applies placement and the row
     * height everything else already reacts to, the same pair [refreshTheme] uses for a theme
     * that changed with nothing in the store changing.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = host ?: return
        applyPlacement(view, preferences)
        refreshTheme()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wallpaperChangedReceiver) }
        unregisterClipboardListener()
        // The platform's own onDestroy finishes the current input first -- onFinishInput
        // records the word being composed and asks for fresh suggestions -- and it has to run
        // while the engine is still alive: with the engine shut down before it, as it used to
        // be, that request went to a prediction thread that had already quit, one dead-thread
        // warning per destroy. Everything of ours comes after it.
        super.onDestroy()
        // The engine goes below, and tier B's weights with it, so nothing is loaded any more
        // whatever the preference says. A settings screen still open reads this.
        SwipeModelLoad.set(SwipeModelLoad.State.Off)
        flushLearningBeforeDestroy()
        // Zeroes the handle under a lock before freeing, so a request already in flight
        // completes against a live engine and anything after it sees zero and returns.
        engine.shutdown()
        scope.cancel()
        host = null
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
        val landscape = isLandscape()
        updatePreferences { current ->
            current.withPlacement(landscape) { placement ->
                when (placement.positionMode) {
                    KeyboardPreferences.MODE_ONE_HANDED_LEFT ->
                        placement.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_RIGHT)
                    KeyboardPreferences.MODE_ONE_HANDED_RIGHT ->
                        placement.copy(positionMode = KeyboardPreferences.MODE_ONE_HANDED_LEFT)
                    KeyboardPreferences.MODE_FLOATING ->
                        // Floating has no side, so the arrow mirrors the offset instead.
                        placement.copy(horizontalOffsetDp = -placement.horizontalOffsetDp)
                    else -> placement
                }
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

    /**
     * Pushes the user's per-region particle-effect settings onto every view that owns a pair of
     * [com.borderkeys.ime.fx.ParticleField]s -- one place that knows the whole list of five
     * regions, rather than five call sites at either site below that could drift out of sync
     * with each other. Each region's own colours now live on [ParticleEffectsSettings] directly,
     * not on the theme -- unlike every other paint, a particle's colour is a per-layer setting
     * a user dials in independently of the rest of the theme, not something [ThemePaints.update]
     * has any reason to recompile.
     */
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

    /**
     * Moves the caret by [steps] characters, from a slide along the space bar.
     *
     * Through setSelection rather than by sending arrow keys: an editor that treats an arrow
     * key as navigation between fields would jump out of the text entirely, and the caret
     * position is something we can ask for and set exactly.
     */
    override fun onCursorNudge(steps: Int) {
        // A second finger dragging the space bar mid-steer is "something else happening", the
        // same as a key press -- the ring goes, the swiped word stays.
        dismissRadialMenu()
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
        if (!preferences.swipeEnabled || !dictionaryAllowed) {
            return
        }
        radialTopWord = null
        // A fresh swipe completing is one of the "something else is happening now" moments a
        // stray ring should not survive. Rare: a ring waiting for a tap consumes every touch
        // outside itself (see RadialSuggestionMenuView.onTouchEvent), so a new swipe cannot
        // normally even start under one -- this is the safety net for a second pointer's swipe
        // mid-steer. Also invalidates a previous gesture's pause-time decode if it is somehow
        // still in flight, so a late answer can never compose text or open a ring for a gesture
        // that already ended.
        dismissRadialMenu()
        engine.cancelPendingPreview()
        // Set here as well as in onGesturePaused: a confident, no-pause swipe never calls that,
        // but onGestureCandidates below still needs an anchor point if radialLiftKeepsOpen opens
        // a ring for it after all.
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        if (previewComposedThisGesture) {
            // The pause-time preview composed a guess but opened no ring, and the stroke went
            // on: this decode of the whole stroke supersedes that guess, which is taken back
            // rather than committed as a word of its own.
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

    /**
     * Finishes and learns the word in progress before a swipe replaces the composing region --
     * a swipe starts a new word, and leaving the previous one composing would make the decoded
     * word replace it. Shared by a completed swipe and a paused one, which used to skip this
     * and lose the typed word under the swiped one.
     */
    private fun finishWordBeforeSwipe() {
        val connection = currentInputConnection ?: return
        if (composing.isEmpty()) {
            return
        }
        val contextWord = previousWord1
        val grandContextWord = previousWord2
        // The editor echoes the end of the composing region as a caret report before the decode
        // comes back; left to onUpdateSelection, that echo re-adopted the word just finished as
        // the composing region again, and the swiped word's own space then replaced it -- "hel"
        // plus a swipe came out as " word", the typed letters gone.
        ownEditPending = true
        connection.beginBatchEdit()
        val finished = finishComposing(connection)
        connection.endBatchEdit()
        if (finished != null) {
            recordLearned(finished, contextWord, grandContextWord, composingCapitalisedByUser)
        }
    }

    /**
     * The finger paused mid-swipe -- the one pause this gesture gets, and the trigger for the
     * only decode it gets too (see [RadialSuggestionMenuView]'s own doc for why the trajectory
     * is already frozen by the time this fires). No effect unless
     * [KeyboardPreferences.radialMenuEnabled] -- checked here, not in [KeyboardCanvasView],
     * because arming the pause-dwell timer at all is already gated by the view's own
     * `radialMenuEnabled` mirror of the same preference; this is the belt to that braces.
     */
    override fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
        if (!preferences.radialMenuEnabled || !dictionaryAllowed) {
            host?.keyboard?.resumeGestureCapture()
            return
        }
        // Nothing from an earlier gesture may be applied by this one's lift -- see
        // resolveRadialSelection: with no word to apply, an inconclusive lift cancels.
        radialTopWord = null
        if (previewComposedThisGesture) {
            // A second pause on the same stroke: the first one's preview opened no ring and is
            // still composing. It is a guess this decode supersedes, not a word to keep.
            previewComposedThisGesture = false
            cancelRadialGesture()
        } else {
            // The word in progress is finished first, exactly as a completed swipe finishes
            // it: the preview composes over the composing region, and used to erase a
            // half-typed word ("hel" + a paused swipe left just the swiped word). The context
            // words the decode is given are then the finished word's own.
            finishWordBeforeSwipe()
        }
        if (count > 0) {
            lastGestureX = xs[count - 1]
            lastGestureY = ys[count - 1]
        }
        steerLeftPausePoint = false
        engine.decodeGesturePreview(xs, ys, timestamps, count, previousWord1, previousWord2)
    }

    /** Whether the steering finger has moved past the touch slop since the pause -- before
     *  that, a held-still finger's own jitter must not steer, least of all onto the centre
     *  Cancel button the ring opens directly under it. */
    private var steerLeftPausePoint = false

    /**
     * Steering, still on the same touch-down that paused -- forwarded straight to the ring,
     * which already knows its own anchor and geometry. No decode happens here; the trajectory
     * was already frozen and decoded at the pause.
     */
    override fun onGestureSteered(x: Float, y: Float) {
        val view = host ?: return
        // A finger held still still reports moves -- the driver's own jitter -- and the ring
        // opens with its centre Cancel button exactly under the pause point. Until the finger
        // has genuinely left that point, nothing steers, so a pause-and-lift stays "nothing
        // chosen" rather than a cancel nobody asked for.
        if (!steerLeftPausePoint) {
            val slop = view.keyboard.touchSlopPx
            if (hypot(x - lastGestureX, y - lastGestureY) <= slop) {
                return
            }
            steerLeftPausePoint = true
        }
        // Canvas-local coordinates, like every gesture point; the ring is laid out across the
        // whole host, so they are offset by the keyboard's own origin -- the same offset
        // radialAnchor applies to the anchor itself.
        val menu = view.radialSuggestionMenu
        val before = menu.currentSelection()
        menu.steerTo(x + view.keyboard.left, y + view.keyboard.top)
        // The pick-timeout is a passivity guard, not a hard deadline: it exists for someone who
        // pauses and then genuinely does nothing, not for someone actively steering while they
        // decide. Real steering -- the highlight actually changing -- cancels it outright and it
        // is never re-armed; jitter that changes nothing leaves it running.
        if (menu.currentSelection() != before) {
            view.removeCallbacks(radialTimeoutRunnable)
        }
    }

    /** The finger lifted while the ring was open. Reads whatever [onGestureSteered] last
     *  settled on and acts on it -- see [resolveRadialRing]. */
    override fun onGestureRingResolved() {
        resolveRadialRing()
    }

    /** The touch stream was interrupted while the ring was open -- always discards, never
     *  guesses at a resolution an interruption cannot actually tell us. */
    override fun onGestureRingCancelled() {
        closeRadialRing()
        cancelRadialGesture()
    }

    /**
     * A fresh, independent tap on the ring resolved while it was kept open after a lift -- see
     * [KeyboardPreferences.radialLiftKeepsOpen] and [resolveRadialRing]'s own doc for how it got
     * into that state. A wedge applies its word (with the same burst a steered pick gets) and
     * the centre X cancels, exactly as at a lift. Neither -- a tap lifted in the dead zone
     * between the two, or a cancelled stream -- only closes the ring: in tap mode the ring is
     * modal, and the only touches that *do* anything are a wedge and the X; everything else,
     * inside the ring or out, is a dismissal that leaves the swiped word as it is. Falling
     * through to [KeyboardPreferences.radialTimeoutDefault] here, the way a lift's dead zone
     * does, would commit (with a space) or delete the word from a tap that was aimed at
     * nothing.
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

    /** A touch landed outside the ring while it was waiting for a tap -- see
     *  [RadialSuggestionMenuView.onTouchEvent]. The touch was consumed there; all that is left
     *  to do is close the ring and leave the text alone. */
    override fun onRadialDismissed() {
        dismissRadialMenu()
        // Optionally the keyboard goes with it -- KeyboardPreferences.radialOutsideTapHidesKeyboard.
        if (preferences.radialOutsideTapHidesKeyboard) {
            requestHideSelf(0)
        }
    }

    /**
     * The editor reporting where its text sits on screen -- requested only while a ring is open
     * (see [closeRadialRing]/[watchEditorWhileRingOpen]). The first report is the baseline; a
     * later one with the view moved means the page scrolled under the ring, which is the one
     * signal a tap elsewhere in the app produces, and closes it when
     * [KeyboardPreferences.radialCloseOnEditorMove] is on.
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
     * While a ring is open the keyboard's window reaches the top of the screen -- see
     * [KeyboardHostView.reserveScreenAbove] -- and this is what keeps the app from noticing:
     * the top of the keyboard it is told about stays where the keys actually are, below the
     * transparent room, so nothing it laid out moves; and the whole window is touchable, so a
     * tap in that room comes here, to the ring's own outside-tap rule, rather than to the app.
     * The keyboard's window is otherwise only as tall as the keyboard, which is why no setting
     * on the window alone could ever have caught such a tap.
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

    /** A real ring, open: the debug ring is meant to survive every tap and never takes over the
     *  screen. */
    private fun ringOwnsWholeScreen(): Boolean =
        swipeRadialController.state == SwipeRadialController.State.OPEN && !debugRingOpen

    /** Grows the window to the top of the screen while a ring is open and gives the room back
     *  when it closes -- see [onComputeInsets]. */
    private fun refreshTouchableArea() {
        host?.reserveScreenAbove = ringOwnsWholeScreen()
    }

    /** Starts (or stops) the editor's cursor-anchor reports for [onUpdateCursorAnchorInfo]. A
     *  request the editor does not support simply never reports; nothing else depends on it. */
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
     * The pause-time decode's answer. The top candidate composes immediately -- exactly as a
     * confident, no-pause swipe already does in [onGestureCandidates] below -- so there is live
     * feedback in the field while the ring is still open to steer away from it. Everything from
     * rank #2 on becomes the ring's wedges; rank #1 never gets a wedge of its own because it is
     * already what composing, or the pick-timeout, or a release in the dead zone all agree on --
     * see [resolveRadialRing].
     */
    override fun onGesturePreviewCandidates(candidates: List<Candidate>) {
        val view = host ?: return
        val connection = currentInputConnection ?: return
        if (candidates.isEmpty() || terminalField) {
            // Nothing to show: the stroke goes back to plain capture, so its lift decodes the
            // whole gesture instead of resolving a ring that never existed. A terminal cannot
            // show the ring's preview either, since the preview is composing text.
            view.keyboard.resumeGestureCapture()
            return
        }
        // Cased and separated exactly as a confident swipe's own candidates are in
        // onGestureCandidates -- one word, whichever way it arrived.
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

        // A pause is a deliberate request for the ring, so it opens whatever the trusted-word
        // setting says -- that setting only decides whether rank one is one of the wedges.
        val wedgeWords = ringWedges(cased)
        if (!swipeRadialController.onRingOpened(wedgeWords)) {
            // Too few alternatives to make a ring worth showing. The top candidate is composing
            // above as a live preview, and the stroke goes back to plain capture: if the finger
            // lifts now, the whole gesture is decoded once more and its own best word replaces
            // the preview (onGesture discards it first, via previewComposedThisGesture); if the
            // finger moves on, the trail keeps drawing and the rest of the word is captured
            // rather than steering a ring nobody can see.
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
        // Not armed at all with radialLiftKeepsOpen on: that setting means the ring never
        // resolves on its own anywhere in its lifetime, not just after an inconclusive lift --
        // see its own doc. Someone who has paused and is genuinely holding still, deciding, does
        // not get timed out either.
        if (!preferences.radialLiftKeepsOpen) {
            view.postDelayed(radialTimeoutRunnable, preferences.radialPickTimeoutMillis.toLong())
        }
    }

    /**
     * Where the ring should be centred, per [KeyboardPreferences.radialMenuAnchor].
     *
     * The tangent modes hand back the keyboard's own edge (`0f` or its full width) rather than
     * anything inset from it -- [RadialSuggestionMenuView.show]'s own clamp already pulls
     * whatever it is given back inside the view by exactly [RadialSuggestionMenuView]'s outer
     * radius, so handing it the true edge is what makes the ring land tangent to that edge
     * rather than merely near it. That same clamp is also the answer to "what if the swipe ends
     * right at the edge": [FINGER] mode is clamped by it too, so the ring can never be pushed off
     * the host's own bounds regardless of where the gesture actually happened.
     *
     * Every coordinate here is a `keyboard`-local one -- [lastGestureX]/[lastGestureY] come
     * straight from touch coordinates on that view, and [KeyboardPreferences.RADIAL_ANCHOR_CENTER]
     * measures against its own width/height. [RadialSuggestionMenuView] itself, though, is laid
     * out across the entire host -- above all else on the z axis only means something if its own
     * canvas actually reaches everywhere a sibling could be drawing, not only the rect `keyboard`
     * itself occupies (see [KeyboardHostView.onLayout]'s own comment on this). `keyboard.left`/
     * `keyboard.top` are exactly the gap between the two origins, so every X/Y is offset by them
     * here, once, rather than asking each caller of this function to know the two views disagree
     * about where zero is.
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
     * Reads the ring's [RadialSuggestionMenuView.currentSelection] at an actual lift and either
     * resolves it or, if it is inconclusive and [KeyboardPreferences.radialLiftKeepsOpen] is on,
     * hands off to a fresh tap instead of resolving at all yet.
     *
     * "Inconclusive" means neither a wedge nor the centre Cancel button was touched -- the dead
     * zone. With the setting off (default), that resolves immediately via
     * [resolveRadialSelection], exactly as a wedge or Cancel would. With it on, the original
     * pointer is gone (it just lifted), so the only way anything more can happen is a genuinely
     * new touch: the ring stays open, switches to [RadialSuggestionMenuView.acceptsOwnTouches],
     * and nothing is armed to resolve it later -- [radialLiftKeepsOpen] means no clock, ever, so
     * it waits for that fresh tap for as long as it takes.
     */
    private fun resolveRadialRing() {
        val view = host ?: return
        previewComposedThisGesture = false
        val selection = view.radialSuggestionMenu.currentSelection()
        // Only a ring that is actually open can be kept open: a pause whose decode found too
        // few alternatives never showed one, and switching a hidden view to tap mode would
        // leave it eating the next word's touches.
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
     * A wedge applies that word. The centre Cancel button discards everything -- the *only*
     * deliberate way to cancel, by design. Neither (the steering finger lifted in the dead zone,
     * or timed out from it) falls through to [KeyboardPreferences.radialTimeoutDefault]: apply
     * rank #1 (the default -- passivity is never destructive unless the user chose otherwise) or
     * cancel, matching what a deliberate Cancel would have done. Shared by every way the ring
     * can *resolve*: an actual lift, a wedge tapped after [KeyboardPreferences.radialLiftKeepsOpen]
     * kept it open, and the pick-timeout elapsing. A dismissal (see [dismissRadialMenu]) never
     * comes through here.
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
     * [KeyboardPreferences.debugForceRadialRing]: opens a sample ring, tap-only, whenever one is
     * wanted and none is open -- on every new field, and the moment the toggle is switched on.
     * Whatever closes a real ring closes this one too (a tap outside it, the centre X); it comes
     * back on the next field. Never in a release build, whatever the stored preference says.
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

    /** Whether the ring currently open is [syncDebugRing]'s sample rather than a real gesture's.
     *  A debug ring ignores every dismissal (field focus, caret moves and stray taps would
     *  otherwise close it the instant it opened) and comes straight back after a wedge or the
     *  X resolves it -- only the toggle itself ([closeDebugRing]) ends it. */
    private var debugRingOpen = false

    private fun closeDebugRing() {
        if (!debugRingOpen) {
            return
        }
        debugRingOpen = false
        closeRadialRing()
    }

    /**
     * Cancels the pick-timeout, tells the controller, clears the ring and hides it -- the cleanup
     * every path off the ring shares, regardless of what it resolved to or how it got there.
     *
     * [celebrateIndex] is the wedge a `Selection.Word` resolved to, or null for every other way
     * off the ring (Cancel, a timeout that fell through to cancelling, a dismissal). Only a real
     * pick gets the burst; every path clears the view's own wedges, tap mode and ambient glow
     * ([RadialSuggestionMenuView.hide], which [RadialSuggestionMenuView.celebrate] ends in too)
     * -- and both must run before [KeyboardHostView.setRadialMenuVisible] below, since that call
     * reads whether anything is still animating to decide whether to hide the view immediately
     * or defer until it finishes. Leaving the ambient glow running there is exactly what used to
     * keep a tapped ring on screen forever: the deferred hide waited for particles that an
     * emitter nobody stopped kept replacing.
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
        }
        if (debugRingOpen) {
            // Resolved by a wedge or the X: back on the next frame, for as long as the debug
            // toggle wants it -- see syncDebugRing.
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
     * Discards whatever the paused swipe tentatively composed -- no commit, no learning, as if
     * the gesture had never happened. [InputConnection.finishComposingText] alone would not do
     * this: it turns composing text into permanent committed text, which is the opposite of a
     * cancel -- the composing region has to be emptied first.
     *
     * The X is a decision, so it deletes the word whether or not the word is still composing.
     * A caret change the editor reported while the ring was open ends the composing region and
     * hands the word back as ordinary committed text ([adoptWordAtCaret]); with nothing left to
     * un-compose, the word is deleted from the field directly -- see [deleteWordBeforeCaret].
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
            // The space this class put in front of the swiped word goes with it -- see
            // swipeAutoSpaceInserted's own doc. Checked against the text rather than trusted
            // blindly, the same way every other deletion here is.
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
        // The swipe spent a one-shot shift when it composed; with the word gone, the sentence
        // start it was capitalising is a sentence start again.
        applyAutoShift()
        requestSuggestions()
    }

    /**
     * Deletes the run of word characters touching the caret, by the same definition of "word
     * character" a composing word is built from ([isWordCharacter]) and [adoptWordAtCaret] reads
     * a word back with -- so the run deleted is exactly the region a still-composing word would
     * have covered. A caret with no word in front of it deletes nothing.
     */
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
     * Cases every swipe candidate the way typed letters would come out under the current shift
     * -- capitalised for a one-shot shift (a sentence start, or the shift key pressed before the
     * swipe), shouted under caps lock, untouched otherwise -- then spends a one-shot shift the
     * way [handleCharacter] does for a first letter. Without this a swipe at the start of a
     * sentence produced a lower-case word and left shift armed for the first letter typed
     * afterwards, capitalising that one instead. Never lower-cases. A name comes back from the
     * decoder lower-case with [properNoun] set -- the trie stores it that way, exactly as the
     * typed path receives it -- so it is capitalised here, before shift is considered. This used
     * to assume the decoder had already done that, and no swiped name was ever capitalised.
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
        // Casing is what makes two candidates one word, so it is what has to notice. The engine
        // already refuses a word it has (Engine::offerCandidate compares the text, which is how
        // it catches the same word reaching it from two languages), but it compares what the
        // packs hold: "Lennox" and "lennox" fold to one key, each pack keeps whichever spelling
        // was commoner in its own corpus, and both carry the name flag. Two different words
        // arrive, the capital goes on both, and the ring drew "Lennox" twice.
        return cased.distinctBy { it.text }
    }

    /**
     * Inserts the space a swiped word needs in front of it, if what precedes the caret is
     * something a word does not run straight on from -- letters, digits or a closing mark.
     * Nothing after whitespace, an empty field, or an opener (a bracket, a quote, a slash, a
     * hyphen, an apostrophe: "l'" + swipe "homme" is "l'homme"). Two swipes in a row used to
     * land as one run of letters, and a swipe after a half-typed word glued itself onto it.
     * Remembered in [swipeAutoSpaceInserted] so a cancelled swipe can take it back.
     */
    private fun spaceBeforeSwipedWord(connection: InputConnection) {
        swipeAutoSpaceInserted = false
        // An address has no space in it anywhere.
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
     * The decoded candidates for a confident, no-pause swipe. The first one is committed
     * immediately, as composing text, and the rest go to the strip -- the user does not wait for
     * a confirmation. No ring shows here by default: a hesitation never happened, so there is
     * nothing to offer alternatives about beyond what the strip already does for any word.
     *
     * With [KeyboardPreferences.radialLiftKeepsOpen] on, though, the ring shows anyway, tap-only,
     * the same [RadialSuggestionMenuView.acceptsOwnTouches] mode an inconclusive lift on a paused
     * gesture already uses -- there was never a pause to open it live and steerable before this
     * lift, but that setting means offering the alternatives without a clock attached whenever
     * there is any way to, not only after a pause.
     */
    override fun onGestureCandidates(candidates: List<Candidate>) {
        host?.removeCallbacks(gestureDecodingRunnable)
        val view = host
        view?.suggestionStrip?.decoding = false
        if (candidates.isEmpty()) {
            // Nothing decoded: the row goes back to predictions for whatever is before the
            // caret, not to a blank it would otherwise sit in until the next keystroke.
            view?.suggestionStrip?.clear()
            requestSuggestions()
            return
        }
        val connection = currentInputConnection ?: return
        // Every candidate follows shift exactly as typed letters would -- a swipe at a sentence
        // start is capitalised, one under caps lock is shouted -- and the swipe then spends a
        // one-shot shift the way a first letter does. See caseSwipedWords.
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
        // The strip is now about this word: its alternatives, not completions of it. lastQuery
        // is what onUpdateSelection's echo of this very edit compares against to decide whether
        // to ask the engine again -- set here so it does not, and the alternatives stay up.
        // None of the chips is "the typed word" or "the correction a delimiter would apply".
        lastQuery = best
        suggestionQuery = best
        knownQuery = best
        topSuggestion = best
        topSuggestionIsProperNoun = cased.first().isProperNoun
        // One ranking, both surfaces: the strip shows as much of it as it has slots for and the
        // ring as much as it has wedges for, and neither reorders anything.
        view?.suggestionStrip?.let { strip ->
            strip.typedIndex = -1
            strip.appliedIndex = -1
            strip.setSuggestions(cased)
        }

        if (view != null && preferences.radialMenuEnabled && preferences.radialLiftKeepsOpen) {
            radialTopWord = best
            // A swipe rank one wins outright is not a question worth asking: the word is already
            // written, and every wedge would be a word the decode put well behind it.
            // RADIAL_TRUSTED_AUTO_APPLY keeps it and says so instead of opening that ring. A
            // close decode is still a question, and still gets its ring.
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
        // Backspace is the one key that gets to look at the pending correction; every other key
        // settles it. Doing this here rather than in each handler is what keeps a correction
        // from surviving three words and then being undone by a backspace that meant something
        // else entirely. The radial menu follows the same rule, one line below: backspace gets
        // its own call in handleDelete() itself, because it also has to run before the deletion
        // it dismisses for -- rather than beside it.
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
     * A caret key from the modifier row -- an arrow, home, end, page up or page down -- sent
     * as the hardware key, selecting when shift is held. The word being typed is finished
     * first; the editor's echo of that finish is this keyboard's own edit, and only the caret
     * the key then moves is read back.
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
     * Escape, tab, or a character under control or alt: the word being typed is committed
     * first, then the key goes out. The editor's echo of the commit is this keyboard's own
     * edit; whatever the key itself changes comes back as a report of its own.
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
     * The shift bits for a hardware key: set only while the user holds shift, not for the
     * capital auto-shift armed. With [spend], a one-shot shift is spent by the key as a letter
     * spends it; the arrows leave it standing, so one shift covers a whole selection.
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
        if (code == KeyCodes.SHIFT) {
            lockShift()
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
            applyAutoShift()
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
        KeyboardStats.keystrokes++
        KeyboardStats.input(android.os.SystemClock.uptimeMillis())
        // A character under an armed control or alt is the hardware key that carries it, not
        // text; a character no plain key carries releases the modifiers and types as usual.
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
        // A terminal shows what is committed and never what is composing, so the key types
        // straight through -- see typeIntoTerminal.
        if (terminalField) {
            typeIntoTerminal(connection, shifted)
            return
        }
        // A word *begins* with a letter. The apostrophe and the hyphen belong inside one --
        // "don't", "aşa-zis" -- which is why [isWordCharacter] counts them, but a leading one is
        // punctuation the user opened with, and on a phone an apostrophe is the quote mark most
        // people reach for. Counting it as the first letter of a word pulled it into the
        // composing region, and autocorrect then replaced the region wholesale: typing
        // 'cuvant and pressing space committed cuvânt, with the opening quote silently gone.
        // Treated as a delimiter instead, it commits on its own and the word starts after it.
        val letter = if (composing.isEmpty()) {
            Character.isLetter(shifted)
        } else {
            isWordCharacter(shifted)
        }
        // A one-shot shift is spent by the letter it capitalised and by nothing else: shift,
        // space, letter is a capital, the way every keyboard does it. Spending it on the space
        // used to make the sequence unrecoverable -- and, combined with the swallowed
        // habit-space below, ate the capital auto-shift had armed after every sentence mark.
        if (letter && shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        // Read before the reset just below, and only at the first letter of a fresh word --
        // see composingCapitalisedByUser's own doc for why it has to be captured here rather
        // than wherever the word eventually finishes.
        val heldByUser = shiftHeldByUser
        if (composing.isEmpty() && letter) {
            composingCapitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            // One read, here, for the same reason the capital above is captured here: this is
            // the only moment the answer is both knowable and stable for the whole word.
            val ahead = connection.getTextBeforeCursor(1, 0)
            composingIsRunningText = ahead.isNullOrEmpty() || !RunningText.isMark(ahead[0])
        }
        if (letter) {
            // The letter spent it. A delimiter leaves it standing, so the shift the user pressed
            // still reaches the first letter after the space.
            shiftHeldByUser = false
            userReleasedAutoLock = false
        }

        if (letter) {
            pendingAutoSpace = false
            if (composingFromGesture) {
                // The swiped word is a whole word; this letter begins the next one. Finished
                // and learned exactly as a delimiter would finish it, then separated by the
                // space a swipe implies -- see composingFromGesture's own doc.
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
                // A fresh word: whether this first letter is a deliberate capital is decided
                // now, the same as the capture above did for an empty composing region -- and
                // by the same rule: only a shift the user pressed counts, never auto-shift's.
                composingCapitalisedByUser = heldByUser && Character.isUpperCase(shifted)
            }
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
        // Everything that can claim the word -- the user's own shortcut, the apostrophe map,
        // a name's possessive, the capital a language always writes, autocorrect -- is asked in
        // one place, in one order, with each rewrite's own gate: see WordCommit. A rewrite that
        // is not autocorrect's takes the same path as a correction -- committed in place of the
        // typed word, revertible with the backspace straight after -- and is never learned as a
        // word.
        val outcome = commitOutcome(typed)
        val rewrite = outcome.isRewrite
        val correction = outcome.text
        // Captured before anything commits: finishComposing and the correction branch both
        // advance previousWord1 to the word being written now.
        val contextWord = previousWord1
        val grandContextWord = previousWord2

        // The space we just added ourselves, typed again out of habit. Swallowed once, kept
        // swallowing, or kept -- KeyboardPreferences.autoSpaceHabit's three answers -- and the
        // window for the two-spaces rule is not opened by it either.
        //
        // Asked of the text and not only of pendingAutoSpace: the flag outlives whatever it was
        // remembering, so a caret moved elsewhere, an emoji or a paste committed over the space,
        // or a backspace that removed it all used to leave this eating a space the user really
        // wanted. See HabitSpace, which is where the rule is tested.
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
            // Nothing was committed, so no caret echo will re-derive shift for this keystroke:
            // done here, or the capital armed after the sentence mark is lost to the very space
            // habit types next.
            shiftAfterDelimiter(heldByUser)
            return
        }

        // Two spaces in quick succession end the sentence instead. Only after a word
        // character, so it never fires on an empty line or after punctuation that already
        // ended one, and only inside the window -- two spaces a minute apart are two spaces.
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
            // The space after the full stop is one this keyboard added: a third space typed
            // out of the same habit is treated like any other habit-space.
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
        // "word ." is not something anyone means. A space before a sentence mark is taken back
        // before the mark lands, which is what makes adding one after a mark safe: the pair of
        // settings is one idea, and either half alone would be worse than neither. Except in
        // French, where the space before "!", "?", ";" and ":" is the typography, not a typo.
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
            // commitText replaces the composing region, which is the whole point: the letters
            // that are on screen become the correction in one edit. Calling finishComposingText
            // first would *commit* them and leave the correction appended to what was typed,
            // which is what the first version of this did.
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
            // Learning waits until the correction survives the next keystroke. Recording it
            // here would teach the personal dictionary a word the user is about to reject, and
            // the whole point of the revert is that rejecting it is expected.
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
        // A word either side of a sentence mark is not a pair anyone meant: "Salut" and "Ce"
        // from "Salut. Ce faci" would otherwise become exactly as real a bigram as two words
        // from the same thought, purely because finishComposing (above) advances previousWord1
        // for every delimiter alike. Cleared after learning runs -- the word just finished still
        // learns its own pair against whatever came before it -- so only the context this
        // delimiter would otherwise hand to the *next* word is the part that is dropped.
        if (isSentenceEndingPunctuation(shifted)) {
            previousWord1 = null
            previousWord2 = null
        }
        checkpointField()
        shiftAfterDelimiter(heldByUser, justCommitted = delimiter)
        requestSuggestions()
        // Which language the engine now thinks this is, kept for the punctuation rules that
        // differ by language -- see writingInFrench. One cheap answer per completed word, and
        // not gated on the language-switch setting the check below is.
        engine.dominantLanguageTag { tag -> dominantLanguageTag = tag }
        if (preferences.languageSwitchCorrectionMode != KeyboardPreferences.LANGUAGE_SWITCH_OFF) {
            checkLanguageSwitch()
        }
    }

    /**
     * Records where [correction] just landed, so [checkLanguageSwitch] can find it again if the
     * conversation's language turns out to have been misjudged when it was applied.
     *
     * Read from the cursor rather than assumed to be at the end of the field: a correction made
     * while editing back inside earlier text is exactly as eligible as one made at the very end.
     */
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
     * Asks whether the conversation's language just changed and, if so, whether that leaves any
     * recently-applied correction looking wrong -- see [LanguageSwitchCorrector]'s own doc for
     * why the decision lives there and only the InputConnection/native-call plumbing lives here.
     *
     * Two async round trips to the prediction thread, both stamped with [fieldGeneration]: a
     * field switch in the meantime must drop the answer rather than apply it to the wrong field.
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

    /** The field's text between two offsets, or null if either is out of range right now -- the
     *  drift guard [checkLanguageSwitch] and [applyLanguageSwitchReplacements] both need, since
     *  the text may have moved on since it was recorded. */
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

    /** Where the caret is, or what is selected, in the same offsets [textAt] and
     *  [recordLanguageSwitchFlag] already speak -- null when the editor will not say, which is
     *  a reason to leave the caret alone rather than to guess at one. */
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
     * Closes the ring and touches nothing else: the swiped word stays composing exactly as it
     * was, nothing is committed, nothing is deleted. Per the user's own spec, this is what every
     * way off the ring that is not a wedge or the centre X does -- a touch outside the ring on
     * the keyboard ([onRadialDismissed]), a tap into the editor itself ([onUpdateSelection]), a
     * key pressed or a gesture completed by another pointer mid-steer, a field switch. The X
     * is the *only* thing that deletes the word ([cancelRadialGesture]); a dismissal that did
     * the same used to mean the first stray tap after every swipe threw that swipe away.
     *
     * Also tells [KeyboardCanvasView] to forget the steering stroke if one is still down: the
     * ring it was steering is gone, so its eventual lift must neither resolve against an empty
     * ring nor type the key it started on -- see [KeyboardCanvasView.abandonRingStroke]. A
     * no-op there, and here, when nothing is open at all, which is most of the time.
     */
    private fun dismissRadialMenu() {
        if (swipeRadialController.state != SwipeRadialController.State.OPEN || debugRingOpen) {
            return
        }
        closeRadialRing()
        host?.keyboard?.abandonRingStroke()
    }

    /**
     * Edits the field for each replacement, right-to-left as [LanguageSwitchCorrector.resolve]
     * already ordered them, then one [checkpointField] for the whole batch -- one Undo reverts
     * all of it together, not word by word. Each replacement is re-verified against live text
     * immediately before its own edit: more time has passed since [checkLanguageSwitch] read it
     * than between two statements in the same function, and a stale offset must be skipped, not
     * trusted.
     */
    private fun applyLanguageSwitchReplacements(replacements: List<LanguageSwitchCorrector.Replacement>) {
        val connection = currentInputConnection ?: return
        val applied = ArrayList<LanguageSwitchCorrector.Replacement>(replacements.size)
        connection.beginBatchEdit()
        // Flushed first, and only once: whatever the user is presently in the middle of typing
        // is not one of [replacements] (those are all already-committed words), but it does sit
        // in the one composing region InputConnection allows, which setComposingRegion below is
        // about to claim for an older word instead.
        finishComposing(connection)
        // Read after that flush, so it is where the caret actually rests rather than where it
        // was before the composing region was committed.
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
        // setComposingText leaves the caret at the end of the word it just wrote, which is a
        // word the user typed past some time ago. Put it back where they are writing, moved
        // only by however much the text before it grew or shrank.
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
        if (!pending.learn) {
            return
        }
        recordLearned(
            pending.corrected, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital,
        )
    }

    /**
     * What a delimiter would write in place of [typed], and why -- the whole decision, from
     * the user's own shortcuts down to autocorrect's answer, with every gate applied. See
     * [WordCommit], which is where it can be tested without an editor, an input connection and
     * a dictionary.
     *
     * Asked on every keystroke as well as at the delimiter, to decide whether the strip should
     * outline the word it is about to write, so nothing here may read the editor:
     * [composingIsRunningText] was settled once, as the word began -- the mistake HabitSpace's
     * own comment records.
     */
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

    /**
     * The word the strip outlines: what a delimiter would write, when that is one word the row
     * can carry. A text shortcut is the user's own rule and may be a sentence, so it is not
     * outlined; everything else the delimiter writes is.
     */
    private fun outlinedCommit(typed: String): String? {
        val outcome = commitOutcome(typed)
        return if (outcome.kind == WordCommit.Kind.SHORTCUT) null else outcome.text
    }

    /** Whether [pending]'s correction and its delimiter are still the text right before the
     *  caret -- the one state in which the strip offers the typed word back. */
    private fun correctionBeforeCaret(pending: PendingCorrection): Boolean {
        val committed = pending.corrected + pending.delimiter
        val before = currentInputConnection?.getTextBeforeCursor(committed.length, 0)
            ?: return false
        return before.toString() == committed
    }

    /**
     * Puts back exactly what was typed, if the last thing that happened was a correction.
     *
     * Deletes the correction and its delimiter and writes the original in their place, in one
     * batch edit so the editor sees a single change rather than a deletion followed by a
     * reinsertion. Returns false when there is nothing to revert, and the caller then does what
     * backspace normally does. [viaBackspace] is whether the backspace key asked, which the
     * revert-on-backspace setting governs; a tap on the strip's typed chip is not governed by it.
     */
    private fun revertCorrection(connection: InputConnection, viaBackspace: Boolean = true): Boolean {
        val pending = pendingCorrection ?: return false
        pendingCorrection = null
        if (viaBackspace && !preferences.revertCorrectionOnBackspace) {
            // Backspace is an ordinary backspace, so this is the correction being accepted the
            // same way any other key would accept it. Dropping it unlearned instead would make
            // the setting quietly change what the dictionary remembers.
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
            // The cursor moved, or something else edited the field. Reverting blind would
            // delete text nobody asked us to touch, so the correction stands and is accepted.
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
        // Reverting is the user asserting that what they typed is a word.
        recordLearned(
            pending.typed, pending.contextWord, pending.grandContextWord,
            pending.deliberateCapital, asserted = true,
        )
        // And the word they rejected is unlearned. A correction is only offered that strongly
        // because something taught it -- often this dictionary, from an earlier typo confirmed
        // by accident -- and rejecting it is the clearest statement available that it should
        // not have been. Harmless when the word came from the language pack instead: there is
        // then nothing personal to forget, and the pack is not touched.
        // Forgotten if this device taught it, never blocked. Blocking is for the prompt above,
        // where the user was asked and answered; a backspace is one keystroke and the commonest
        // way to undo anything. Blocking on it deleted a language-pack word outright -- "where",
        // reverted once, stopped being offered at all and there was nothing on screen to say so.
        forgetWord(pending.corrected, blockWhenNotPersonal = false)
        playEffect(EffectEvent.CorrectionReverted, pending.typed)
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
        languageSwitchCorrector.reset()
        fieldGeneration++
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
        // The diff's offsets are into the extracted window, which starts at startOffset into
        // the field -- zero for any field short enough to fit whole, and not zero for a long
        // one, where forgetting it (as this used to) put every history step at the wrong place.
        val boundary = extracted.startOffset + span.deleteFrom + span.deleteCount
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
        // Unconditional, before anything else -- a second pointer's backspace mid-steer closes
        // the ring and then deletes as it normally would; see dismissRadialMenu's own doc for
        // why closing never touches the swiped word itself.
        dismissRadialMenu()
        val connection = currentInputConnection ?: return
        if (terminalField) {
            deleteInTerminal(connection)
            return
        }
        val hasSelection = selectionEnd > selectionStart
        // A selection is what backspace deletes, all of it, before anything else is considered.
        // deleteSurroundingText would not do it: it deletes *around* the selection and leaves
        // the selected text exactly where it was, which reads as the key having done nothing.
        if (hasSelection) {
            composing.setLength(0)
            composingFromGesture = false
            // Learned, not dropped: a correction that survived to a later, unrelated edit was
            // accepted -- see revertCorrection's own reasoning on what dropping it would mean.
            confirmPendingCorrection()
            connection.commitText("", 1)
            refreshContextFromEditor()
            applyAutoShift()
            requestSuggestions()
            return
        }
        // A swiped word can be taken back whole -- see KeyboardPreferences.swipeBackspaceDeletesWord.
        if (composingFromGesture && preferences.swipeBackspaceDeletesWord && composing.isNotEmpty()) {
            cancelRadialGesture()
            applyAutoShift()
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
            applyAutoShift()
            requestSuggestions()
            return
        }
        if (composing.isNotEmpty()) {
            // Backspacing into a swiped word means the user is correcting it, so from here on
            // typed letters extend it like any typed word -- see composingFromGesture's doc.
            composingFromGesture = false
            // A surrogate pair is one character to the user and two to the buffer.
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
        // Re-derived here, not left to the caret echo: the strip's predictions are cased by
        // whatever shift state they find when they arrive, and the echo can lose that race.
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
        // A field can declare a real action (Send, Done, Go...) and still want Enter to be a
        // plain newline -- a chat-style compose box with its own send button is the usual
        // reason, and IME_FLAG_NO_ENTER_ACTION is how it says so. ENTER_KEY_AUTO, the default,
        // respects that; ENTER_KEY_FORCE_ACTION overrides it (there is still nothing to force
        // where hasAction is false); ENTER_KEY_FORCE_NEWLINE never performs an action at all.
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
        // Enter is a harder break than any sentence mark typed mid-line -- see
        // isSentenceEndingPunctuation's own doc for why this stops a bigram forming across it,
        // whichever of the two branches above actually ran.
        previousWord1 = null
        previousWord2 = null
        requestSuggestions()
    }

    /**
     * The bookkeeping a committed newline needs, shared by [handleEnter] and the quick-action
     * bar's own newline button so neither can drift from the other. Every other path that
     * commits text re-derives shift from what is now before the cursor; a newline is
     * sentenceEndsBeforeCursor's own first check, and skipping it is why a line just started
     * stayed lower-case. The spacing state is a line's own: a space this keyboard added at the
     * end of the previous line must not swallow the first one typed on the next.
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
        // Two taps inside the window lock, from whatever state the first tap found -- an
        // auto-lit shift at a sentence start included, which used to need a third tap because
        // the first one only ever turned it off.
        val doubleTap = now - lastShiftPressAt < DOUBLE_TAP_MILLIS
        val releasedAutoLock = shiftState == ShiftState.LOCKED && autoLockedShift
        shiftState = when {
            shiftState == ShiftState.LOCKED -> ShiftState.OFF
            doubleTap -> ShiftState.LOCKED
            shiftState == ShiftState.ON -> ShiftState.OFF
            else -> ShiftState.ON
        }
        lastShiftPressAt = now
        // Pressed deliberately, so the automatic state stops having an opinion until the next
        // letter consumes it.
        shiftHeldByUser = shiftState != ShiftState.OFF
        autoLockedShift = false
        userReleasedAutoLock = releasedAutoLock
        host?.keyboard?.shiftState = shiftState
        // The strip cases its predictions by the shift state at the moment they arrive, so a
        // shift pressed afterwards has to ask again -- otherwise a chip tapped next commits the
        // case the strip was showing, not the one the key now promises.
        requestSuggestions()
    }

    /** Holding shift locks it -- the third way in besides the double tap, and the one people
     *  find without being told. */
    private fun lockShift() {
        shiftState = ShiftState.LOCKED
        shiftHeldByUser = true
        autoLockedShift = false
        userReleasedAutoLock = false
        host?.keyboard?.shiftState = shiftState
        requestSuggestions()
    }

    /**
     * Puts a page on screen, applying the number-row setting to the alphabetic one.
     *
     * The number row is composed rather than authored into a second copy of every layout: two
     * assets per language that differ by one row is two assets to keep in step, and they would
     * drift the first time a key moved.
     */
    /**
     * Applies the layout settings that compose rather than replace: the accent overlays, the
     * digits on the top row or in a row of their own, the emoji key and the globe key.
     *
     * One place, because the pages are set from four of them and a page that forgot one was how
     * the number row used to disappear when the symbols page came back. The accent and digit
     * steps look only at letter keys, so they pass a symbols page through untouched.
     */
    private fun composedLayout(layout: KeyboardLayout, allowNumberRow: Boolean = true): KeyboardLayout {
        var result = layout
        if (preferences.accentedCharacters && accentOverlays.isNotEmpty()) {
            result = result.withAccents(accentOverlays, accentSignature)
        }
        // The top letter row's corner hint is a digit or a symbol, never an accent -- the
        // accents sit behind it in the long-press strip. Digits when there is no number row;
        // once the number row has taken them, the row shifts to a layer of symbols instead,
        // the way a hardware number row does.
        //
        // [allowNumberRow] is false for the numpad-symbols pages: their numpad block already
        // carries the digits, so a row of them above it would be the same digits twice.
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

    /** The number-and-symbols page the [KeyboardPreferences.symbolsNumberPosition] setting asks for. */
    private fun symbolsPage(): KeyboardLayout = when (preferences.symbolsNumberPosition) {
        KeyboardPreferences.SYMBOLS_NUMBER_LEFT -> symbolsNumpadLeftLayout
        KeyboardPreferences.SYMBOLS_NUMBER_RIGHT -> symbolsNumpadRightLayout
        else -> symbolsLayout
    }

    private fun showPage(next: Int) {
        page = next
        // The symbol pages carry an emoji key too, so they compose the same way. Only the
        // numeric keypad is left alone: it has neither a space bar nor room for one.
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

    /** The language tag of the input-method subtype the globe key last selected, or "und". */
    private fun currentSubtypeTag(): String {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        val tag = manager?.currentInputMethodSubtype?.languageTag
        return if (tag.isNullOrBlank()) "und" else tag
    }

    /**
     * The `layouts/<id>.json` asset [subtype]'s `layout=` extra value names, or
     * [DEFAULT_ALPHABETIC_LAYOUT] when it carries none.
     *
     * The two subtypes shipped before alternate layouts existed wrote `layout=ro_qwerty` /
     * `layout=en_qwerty` -- a language-prefixed name for the one physical layout that existed
     * then, from back when nothing read this value at all (method.xml's own comment explains
     * why it was written anyway). Treated as a synonym for "qwerty" here rather than a distinct
     * id some non-existent `ro_qwerty.json` would have to exist for, so a phone with one of
     * those two subtypes already enabled keeps typing QWERTY exactly as before -- this method
     * being read for the first time must not silently change what an existing install does.
     */
    private fun layoutIdFromSubtype(subtype: android.view.inputmethod.InputMethodSubtype?): String {
        val pair = subtype?.extraValue?.split(",")?.firstOrNull { it.startsWith("layout=") }
            ?: return DEFAULT_ALPHABETIC_LAYOUT
        val id = pair.removePrefix("layout=")
        return if (id.isEmpty() || id.endsWith("_qwerty")) DEFAULT_ALPHABETIC_LAYOUT else id
    }

    /**
     * The globe key, or the system's own language/input switcher, chose a different subtype --
     * reload the letter layout it names and redraw whichever page is currently showing.
     *
     * [showPage] already does exactly this work for every other reason the alphabetic layout
     * changes (a symbols/numpad toggle), so it is reused rather than duplicated here; it is a
     * no-op for [alphabeticLayout] specifically unless [page] is already [PAGE_ALPHABETIC].
     */
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
        // withPositionMode rather than copy: leaving the dock for the first time also narrows the
        // keyboard, or the mode changes nothing visible and reads as broken.
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
        // A string class name, exactly like android:settingsActivity in method.xml. It is the
        // only reference from :keyboard towards :settings, and it creates no compile-time edge.
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
        // A ring waiting for a tap is about the word this pick has just settled by other means,
        // so it goes with it. Without this the ring stayed open over a word that was already
        // committed and no longer composing, and its X -- which discards the composing word --
        // found nothing to discard and silently did nothing.
        dismissRadialMenu()
        if (terminalField) {
            pickIntoTerminal(connection, word)
            return
        }
        // The typed chip, while a correction is pending, is the word that correction replaced:
        // tapping it puts the word back, the same revert as a backspace.
        val pending = pendingCorrection
        if (pending != null && index == host?.suggestionStrip?.typedIndex &&
            word == pending.typed && composing.isEmpty()
        ) {
            // Put back while the correction is still right before the caret; otherwise the
            // correction stands and the tap does nothing more.
            revertCorrection(connection, viaBackspace = false)
            host?.suggestionStrip?.clear()
            requestSuggestions()
            return
        }
        playEffect(EffectEvent.SuggestionPicked, word)
        // Tapping a suggestion is one of the "every other key settles it" cases onKey's own
        // comment describes -- it just does not arrive through onKey. A correction left pending
        // past this point would still be sitting there for a later, unrelated backspace to find
        // and act on, exactly the bug pendingCorrection's own "alive for exactly one keystroke"
        // doc promises cannot happen.
        confirmPendingCorrection()
        // Read before the commit, for the same reason as everywhere else: what is being learned
        // is that this word followed the one already in the text, not that it followed itself.
        val contextWord = previousWord1
        val grandContextWord = previousWord2
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
        // Whether the pick replaces a word -- one being typed, or one the caret sits in -- or
        // is a prediction inserted at the caret with nothing typed and nothing adopted.
        val replacesWord = composing.isNotEmpty() || lastQuery.isNotEmpty()
        if (composing.isEmpty() && lastQuery.isNotEmpty()) {
            val before = connection.getTextBeforeCursor(lastQuery.length, 0)
            if (before != null && before.toString() == lastQuery) {
                connection.deleteSurroundingText(lastQuery.length, 0)
            }
        }
        composing.setLength(0)
        composing.append(word)
        // A pick that replaces a word replaces the *whole* word the caret sits in, not just the
        // part before the caret: the composing region adoptWordAtCaret marks stops at the caret
        // (so typing still inserts there). A prediction leaves the word after the caret alone.
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
        // The space that follows a picked word is only added where there is not one already:
        // picking a suggestion for a word the caret merely sits in, mid-sentence, would
        // otherwise leave "word  next" with two spaces. At the end of the field, or before
        // anything that is not whitespace, the space is what lets typing carry straight on.
        // A *space*, not any whitespace: a line break after the caret is the end of the line,
        // not a separator that is already there, and a word picked before one still needs its
        // own space or the next letter runs into it.
        val nextChar = after?.getOrNull(tail)
        // Never in an address field: an e-mail or a URL has no space in it anywhere.
        val space = if (!preferences.spaceAfterSuggestion || addressField || nextChar == ' ') "" else " "
        ownEditPending = true
        connection.commitText(word + space, 1)
        connection.endBatchEdit()
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        // The space is this keyboard's own: a habit-space typed next is treated like one after
        // punctuation. Nothing to arm when no space was added.
        pendingAutoSpace = space.isNotEmpty()
        // A pick consumes shift exactly the way typing the word's first letter would have: a
        // one-shot shift pressed before picking a prediction at a sentence start was spent on
        // that word (the strip already showed it capitalised) and must not carry over to the
        // first letter of the next one. Then re-derived from the text now before the caret.
        if (shiftState == ShiftState.ON) {
            shiftState = ShiftState.OFF
            host?.keyboard?.shiftState = shiftState
        }
        shiftHeldByUser = false

        // A tap is the user choosing the word on purpose: the count goes up as for any commit,
        // and the word is asserted. The chip carrying the word they typed takes the same path.
        // A two-word suggestion is two words chosen, not one long one. Learning it whole
        // would put "vreau sa" in the personal dictionary as a single entry, which would then be
        // offered as a completion of "vr" and never match anything the user typed.
        val words = word.split(' ').filter { it.isNotEmpty() }
        var previous = contextWord
        var grandPrevious = grandContextWord
        for (part in words) {
            recordLearned(part, previous, grandPrevious, asserted = true)
            grandPrevious = previous
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
        checkpointField()
        applyAutoShift()
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
     * Writes [text] to a terminal: each character as the key that carries it, shift held for
     * a capital, and as text only where no plain key carries it. A terminal performs its
     * deletions as key events of its own, and key events keep their order with those; text
     * written directly lands ahead of any deletion still queued.
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
            val meta = if (Character.isUpperCase(code)) TERMINAL_SHIFT_META else 0
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
     * Replaces the letters typed so far with [word], and the space after it if one is wanted.
     * A word that carries on from the letters typed has only its remainder written; any other
     * word takes the letters back first, key event by key event, so it lands after them.
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
     * Forgets a word and cuts the chains it was part of.
     *
     * Both halves, and the second is the point. Deleting the word alone would leave the pairs
     * that name it, so it would go on being predicted after the word before it -- forgotten from
     * the dictionary and still suggested, which reads as the button not working. Deleting the
     * pairs on both sides cuts the chain at that word: what came before it still leads to it no
     * longer, and what came after is no longer reached through it. The head of the chain is
     * untouched, because it is evidence about other words.
     */
    private fun forgetWord(word: String, blockWhenNotPersonal: Boolean = true) {
        scope.launch {
            val dictionary = DataGraph.dictionary
            // The repository suspends on its own dispatcher; the reloads only post to the
            // prediction thread, so there is nothing here to move off the main thread.
            //
            // The chip label is re-cased for the row ("Hello" at a sentence start), so the
            // personal entry is looked up without regard to case and deleted under its own
            // spelling. A word the personal dictionary does not hold came from a language pack,
            // which cannot be edited: it is blocked instead, under the pack's own lower-case
            // spelling, so the prompt does something for every word it is shown for.
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
        if (suggestionsRequestedAt != 0L) {
            KeyboardStats.suggestionMillis.add(
                (android.os.SystemClock.uptimeMillis() - suggestionsRequestedAt).toDouble(),
            )
            suggestionsRequestedAt = 0L
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
        // The engine's own answer to "what did you mean", not the strip's answer to "what are
        // you writing". They used to be the same value, and that was the defect: a word merely
        // carrying on from the typed letters -- "tehran" for "teh" -- would take first place and
        // autocorrect, reading first place, would offer nothing at all. Both questions are
        // answered by one search; only the ranking was ever shared. See Engine::bestCorrection.
        // The word a delimiter would commit, asked of the result rather than reconstructed from
        // it: the engine marked which candidate its corrections heap settled on.
        val marked = candidates.firstOrNull { it.isCorrection }
        topSuggestion = marked?.text
        topSuggestionIsProperNoun = marked?.isProperNoun == true
        // "Maria's" for "marias" -- the possessive of a name the corpus never wrote with an
        // apostrophe, which the bundled maps therefore cannot carry. Kept beside the correction
        // rather than mixed into it: it is a rewrite of a different kind and the delimiter
        // decides between them.
        possessiveSuggestion = possessive
        queryIsInflection = inflection
        suggestionQuery = query
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
        val cased = candidates.map { candidate ->
            val word = candidate.text
            candidate.copy(
                text = if (lastQuery.isNotEmpty()) {
                    AutoCorrection.matchCase(
                        lastQuery, word, candidate.isProperNoun && preferences.capitaliseNames,
                    )
                } else {
                    // LOCKED wins over the proper-noun override for the same reason
                    // matchCase's own all-caps-typed check wins over it below: caps lock is a
                    // deliberate, stronger instruction than "capitalise this one name" and a
                    // name typed under it should read "ANA", not "Ana". Absent that, a name is
                    // still capitalised regardless of shiftState -- "ana" offered with nothing
                    // typed yet is "Ana", not whatever the next keystroke's shift state alone
                    // would have produced. The final branch forces lower case rather than
                    // leaving word untouched, for the same reason AutoCorrection.matchCase's own
                    // final branch does: a personal-dictionary word keeps whatever case it was
                    // last committed in, which can be capitalised from an earlier sentence start
                    // that has nothing to do with where the next word is about to land.
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
        // With the strip off there is no row to arrange: the answer above (topSuggestion,
        // knownQuery) is all autocorrect needs, and it was recorded before this point.
        if (!preferences.showSuggestionStrip) {
            return
        }
        val strip = host?.suggestionStrip ?: return
        // Arranged for the slots that actually hold words: the clipboard chip takes one, and
        // the arrangement used to be told the full count, so the outlined correction could sit
        // in a slot the chip had pushed off screen while space still applied it.
        // The word itself, not whether there is one: the row outlines what a delimiter will
        // actually commit, and that comes from the corrections heap rather than from the ranked
        // candidates this row is built from. Handing over a boolean left the row free to outline
        // whatever happened to rank second while space committed something else entirely.
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
        lastQuery = composing.toString()
        if (privateMode) {
            refreshPrivateReveal()
        }
        // A password never leaves its field: nothing typed into one is sent to the engine, so
        // nothing can be predicted, corrected or learned from it. The strip's own setting is
        // not a reason to skip the request -- autocorrect needs the answer whether or not a row
        // is drawn from it, and used to switch off silently with the strip.
        if (!dictionaryAllowed) {
            return
        }
        suggestionsRequestedAt = android.os.SystemClock.uptimeMillis()
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
        // A field switch is the one moment a decode still in flight for the field being left
        // really could arrive late -- invalidated here, pause-time and lift-time both, so it can
        // never compose text or open a ring in the new field it lands in instead.
        engine.cancelPendingPreview()
        engine.cancelPendingGesture()
        previewComposedThisGesture = false
        pendingCorrection = null
        pendingForget = null
        // A new field starts with typed == "" and no in-flight request could ever answer for
        // it, so correctionFor's own suggestionQuery guard already refuses these -- but only by
        // coincidence, the same shape a real bug had earlier. Reset explicitly so that stays
        // true on purpose rather than by accident.
        topSuggestion = null
        topSuggestionIsProperNoun = false
        suggestionQuery = ""
        knownQuery = ""
        queryIsInflection = false
        composing.setLength(0)
        terminalWord.setLength(0)
        // Back to ordinary writing until the next word says otherwise: whatever stood in front
        // of the last one belongs to a field, or a caret position, that has been left behind.
        composingIsRunningText = true
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        // A space this keyboard added is only ever behind *this* caret, in *this* field. A new
        // field, a cursor jump and a cancelled gesture all end that, so the habit-space rule
        // stops applying with them -- the same reason the two flags above are cleared here.
        pendingAutoSpace = false
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
     * Makes the word the caret is sitting in the one the strip is about -- and, when the caret
     * is genuinely inside that word rather than having only landed beside it, the one typing
     * continues.
     *
     * Marks a composing region on the adopted word: without one, a correction just reverted and
     * its trailing delimiter deleted by hand leaves the caret sitting after real, plain-committed
     * text with nothing marking it as part of a word in progress -- so the very next letter typed
     * starts a new composing run of its own, one character long, while the text on screen reads
     * as a single continuous word. The engine is then asked about "inta" for a caret that reads
     * "suferinta", and corrects the fragment nobody was asking about on its own terms.
     * setComposingRegion marks the *existing* text as composing without touching it -- unlike
     * setComposingText, which would insert the adopted word a second time -- so typing forward
     * extends the same word setComposingText already expects to be replacing, the ordinary path
     * every other composing word already takes.
     *
     * A caret that only landed beside a word -- one that followed a delimiter, or reached an
     * empty field -- gets no composing region, because there is no word to extend from there;
     * [partial] is empty exactly when that is true, and nothing is marked for an empty region.
     */
    private fun adoptWordAtCaret() {
        composing.setLength(0)
        composingFromGesture = false
        swipeAutoSpaceInserted = false
        // Whatever the previous word's first letter was is not evidence about this one.
        composingCapitalisedByUser = false
        // The pending correction stays. Whether it can still be put back is decided where it
        // is acted on, against the text before the caret: revertCorrection for the backspace,
        // correctionBeforeCaret for the strip's typed-word chip.
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
        // One read. The run of word characters touching the caret is the word being asked
        // about -- by the same definition of "word character" every typed word already uses
        // (isWordCharacter), so "user@example" adopts "example" exactly as typing it would have
        // composed, rather than the whole address -- and the words before it are its context.
        var start = before.length
        while (start > 0 && isWordCharacter(before[start - 1].code)) {
            start--
        }
        // ...and then forward off any apostrophe or hyphen the run opens with, because a word
        // begins with a letter. Without this the two definitions disagree: handleCharacter
        // refuses to start a word on a quote, so a caret placed after 'cuvant would adopt
        // 'cuvant while typing the same characters composes cuvant, and the correction applied
        // to the adopted region would eat the quote that typing had just been taught to keep.
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
     * The one or two words ending at [end] in [before], each null in place of reading through a
     * sentence mark rather than across it -- [handleCharacter]'s own reset (see
     * [isSentenceEndingPunctuation]) keeps this true while a word is actually being typed, and a
     * caret move or a deletion re-derives context straight from the editor's text instead of
     * from that running state, so it has to hold here too: without it, "Salut. Ce" read back
     * from the text after a caret move would still hand "Ce" a context word "Salut" never meant
     * to be one.
     */
    private fun contextWordsBefore(before: CharSequence, end: Int): Pair<String?, String?> {
        fun wordEndingAt(limit: Int): Pair<String, Int>? {
            var index = limit
            while (index > 0 && !isWordCharacter(before[index - 1].code)) {
                // A line break is a harder stop than any sentence mark -- handleEnter's own
                // rule, kept here too so a caret move or a deletion re-reading the text does not
                // quietly hand the first word of a line the last word of the one above as
                // context.
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

    /** True when what precedes the single trailing space is a word character. */
    private fun endsWithWordCharacterBeforeSpace(connection: InputConnection): Boolean {
        val before = connection.getTextBeforeCursor(2, 0) ?: return false
        // A digit counts: "in 2026  " ends a sentence exactly as "in June  " does.
        return before.length == 2 && before[1] == ' ' &&
            (isWordCharacter(before[0].code) || before[0].isDigit())
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

    /**
     * Ends a sentence rather than merely a clause -- unlike [isTightPunctuation], which a comma
     * or a colon satisfies too. Deliberately narrower: "mere, pere" is one thought a shopping
     * list habit should still learn as a pair, the way "Salut. Ce faci" is two that should not.
     */
    private fun isSentenceEndingPunctuation(code: Int): Boolean =
        code == '.'.code || code == '!'.code || code == '?'.code

    // Which words each event has already shown an effect for, so "first time" means the first
    // time. Per run rather than stored: the setting is about not repeating the same flourish
    // while someone is writing, not a record of what they have ever typed -- and a set that
    // outlived the session would be one more thing remembering their words for no reason.
    private val effectsShown = HashMap<EffectEvent, MutableSet<String>>()

    /**
     * Plays [event]'s effect for [word], if the user asked for one.
     *
     * Every decision the settings express is taken here rather than at each call site: whether
     * effects are on at all, whether this event has a style, how often it repeats, and what
     * colour it is. A caller only has to say what happened.
     */
    private fun playEffect(event: EffectEvent, word: String) {
        val settings = preferences.effects
        if (!settings.enabled || word.isEmpty()) {
            return
        }
        val setting = settings.forEvent(event)
        // An unknown name is a style this build no longer has: nothing plays, rather than the
        // wrong thing playing -- see EffectSetting.style for why the name is stored and not the
        // enum.
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

    /** Re-derives shift after a delimiter, unless caps lock is on or the user pressed shift
     *  themselves ([heldByUser], captured before the keystroke touched the flag) -- their
     *  decision stands until a letter spends it. [justCommitted] is what this keystroke wrote,
     *  for an editor that has not caught up yet -- see [applyAutoShift]. */
    private fun shiftAfterDelimiter(heldByUser: Boolean, justCommitted: String = "") {
        if (shiftState == ShiftState.LOCKED || heldByUser) {
            return
        }
        applyAutoShift(justCommitted)
    }

    /** Whether [code] is a mark French sets off with a space before it -- "!", "?", ";" and
     *  ":" -- and the text is French, so that space is left where it is. */
    private fun isFrenchSpacedPunctuation(code: Int): Boolean =
        (code == '!'.code || code == '?'.code || code == ';'.code || code == ':'.code) &&
            writingInFrench()

    /**
     * Whether the text being written is French: the language the engine currently considers
     * dominant, or, before it has decided, the only language enabled at all.
     *
     * Not the input-method subtype. That is the *layout* the globe key picked -- AZERTY happens
     * to carry an fr-FR tag and QWERTZ a de-DE one -- and keying this on it meant French spacing
     * for everything typed on an AZERTY and never for French typed on the QWERTY, which is how
     * most of it is.
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
     * Sets shift from what the editor asked for and what is already written.
     *
     * Derived rather than remembered. The previous version set the state when the field opened
     * and after a delimiter it had typed itself, which meant shift never came back after
     * deleting to the start of a field, moving the caret there, or pasting -- and never came on
     * at all in a field that asks for capitals on every word rather than every sentence.
     *
     * A shift the user pressed is left alone. Deciding for them immediately after they decided
     * for themselves is the one thing worse than not deciding at all.
     *
     * [justCommitted] is the text this keyboard wrote a moment ago -- a full stop and its
     * space -- when the call comes straight after the commit. An editor with an asynchronous
     * input connection answers the caps-mode and text-before-caret questions from the state
     * it had *before* that commit, and read that way "salut" ends no sentence: the capital
     * armed after every full stop was lost in exactly those apps, and only came back if the
     * editor's own caret echo arrived later to re-derive it. Appended to whatever the editor
     * reports, the answer is the same whether it has caught up or not: "salut. " and
     * "salut. . " both end a sentence, "salut, , " does not.
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
     * What shift should be here, from the field's request and the text before the cursor.
     *
     * The decision itself lives in [AutoShift], pure and tested on its own; this is the thin
     * Android-facing half, reading the current target and asking the platform's own
     * [InputConnection.getCursorCapsMode] rather than walking the text before the cursor by
     * hand -- the same computation the framework and every other IME already do, correctly
     * handling word/sentence boundaries and an empty field without this class re-deriving them.
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
     * Records a confirmed word, and the pair and triple it makes with the words before it.
     *
     * [contextWord] and [grandContextWord] are passed rather than read from [previousWord1]/
     * [previousWord2] because by the time a caller gets here, those fields have usually already
     * been advanced to describe the word just committed: `finishComposing` (or the correction
     * branch's own inline reassignment) sets them as part of ending the composing region, before
     * this function ever runs. Reading them here produced a pair -- and a triple, and the
     * argument this function hands to the native model -- of a word with itself, which the pair
     * store rejects but the trigram store and the native model do not, so both were quietly
     * fed corrupted context on nearly every word typed. Both have to be captured before the
     * commit, at the same point every caller already captures [contextWord] alone.
     *
     * [deliberateCapital] is whether [word]'s first letter is upper case because the user
     * pressed shift for it themselves -- see [composingCapitalisedByUser]'s own doc. A caller
     * whose word never went through per-character typing at all (a swipe, or a tap on a strip
     * suggestion that was already re-cased for display) passes `false`: neither one is the user
     * manually pressing shift for a letter, so neither is evidence either way.
     *
     * [asserted] is whether the user chose the word on purpose rather than typed past it: a
     * tap on the strip, including on the word they typed themselves, or a correction put back
     * -- see `UserWord.asserted`.
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
        // The input-method subtype the globe key last landed on, not a claim about the word's
        // language. There is one QWERTY layout now -- the accents come from the enabled packs,
        // not from a per-language layout -- so the subtype is the only thing the user chose
        // that is visible here. It is still just a tag: nothing on this path can know which
        // language a *typed* word belongs to when several packs are active at once, which is
        // why the settings screen says "typed on" rather than naming a language.
        val locale = currentSubtypeTag()
        val now = System.currentTimeMillis()
        contextWord?.let { learning.recordPair(it, word, now) }
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
                contextWord, grandContextWord,
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
     * Writes the buffered learning to the database.
     *
     * Never on a keystroke. An INSERT is a transaction, a disk write and an encryption pass, and
     * one of those on the path of a key press would spend the whole two-millisecond budget.
     */
    private fun flushLearning() {
        val batch = drainLearning() ?: return
        learningScope.launch {
            // A write that fails is a batch lost, which is bad; an exception nobody catches on
            // this scope would reach the process's uncaught handler and take the input method
            // down with it, which is worse.
            runCatching { persistLearning(batch) }
                .onFailure { error -> android.util.Log.e("BorderKeys", "learning flush failed", error) }
        }
    }

    /**
     * The last flush, from [onDestroy]: waits for the write instead of handing it off.
     *
     * [flushLearning] gives its batch to a coroutine and returns, which is right on a keystroke
     * and was wrong at the end of the service's life -- there the next line is `scope.cancel()`,
     * and a flush that had not reached the database yet went with it, so the last few minutes of
     * typing were learned in memory and never on disk. Bounded, because a database that does not
     * answer must not hold the input method's main thread on the way out.
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
                    // A flush that already left -- onFinishInput's, a moment ago inside
                    // super.onDestroy() -- is still writing on learningScope; it is waited for
                    // the same as whatever is left here.
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

    /**
     * The pack files [LanguagePackInspector] has accepted in this process, as `path:sha256`.
     *
     * The inspection reads and checksums the whole file, and it used to run on every pass of
     * [repairBundledPacks] -- once at start and again on every change to the pack list -- for
     * files whose hash beside it had just proved unchanged. A pack that passed once passes
     * again until its bytes change, and the hash in the key is what notices when they do.
     */
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
            // Anything the running build cannot read, for any reason: a format it does not
            // know, a file that no longer matches its recorded hash, a file that is gone. All
            // three end the same way for a pack that came from inside the application -- the
            // current one is in assets, so it is copied over whatever is there. So is a pack
            // this build ships a different edition of, which is how a fixed dictionary reaches
            // an existing install at all -- the copy itself is intact, so nothing else here
            // would notice. The edition is read off the content CRC in each pack's header, the
            // shipped one against the installed one: the word count and size
            // BundledDictionaries records are checked too, but a list whose words only gained
            // or lost name flags has compiled to the same count and, by alignment, the same
            // size, and only the CRC told the two apart.
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
                "replaced the bundled ${entry.tag} pack: unreadable by this build, or an older edition than it ships",
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
        val placement = activePlacement()
        view.setPlacement(
            placement.positionMode,
            draggedWidth,
            (placement.bottomOffsetDp * resources.displayMetrics.density).toInt(),
            (placement.horizontalOffsetDp * resources.displayMetrics.density).toInt(),
        )
        view.relayoutForNewMetrics()
    }

    /** Writes the size the finger stopped at, once, to whichever orientation was on screen
     *  while it was dragged. */
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

    private fun applyQuickActions(view: KeyboardHostView) {
        val bar = view.quickActions
        if (!preferences.quickActionsEnabled || privateMode) {
            bar.visibility = View.GONE
            return
        }
        // Compose's own button goes with Compose. Switching the feature off has to take away
        // every way to reach it, not just the screen that explains it -- a button that does
        // nothing is the worst of both. A custom macro can never contain COMPOSE in the first
        // place (it is not QuickAction.macroEligible), so no matching check is needed for it.
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
     * Runs one of the bar's buttons: a single [QuickAction], or -- for a
     * [QuickActionBarItem.Custom] -- every step of its macro in order, through the same
     * per-action code a single tap already uses.
     *
     * Suggestions are refreshed once after the whole tap, not once per step: a macro's steps are
     * not independent taps a user watched happen one at a time, and refreshing between them would
     * ask the engine about a half-finished edit for no one to see.
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
        // Neither of these changes the field: CLIPBOARD_HISTORY only opens a panel and defers
        // the actual edit to a later callback, and COMPOSE leaves the field for another Activity
        // entirely (or does nothing at all, when it bails out before that) -- refreshing
        // suggestions for either would be asking the engine about an edit that never happened.
        // A macro can never contain either (neither is QuickAction.macroEligible), so this only
        // ever matters for a lone builtin tap, which is what singleOrNull() checks for.
        if (steps.singleOrNull() !in NO_REFRESH_QUICK_ACTIONS) {
            refreshContextFromEditor()
            requestSuggestions()
        }
    }

    /**
     * One button's worth of work against [connection].
     *
     * Everything here goes through InputConnection rather than through key events: an editor
     * that handles selection its own way -- a code editor, a rich text field -- gets the
     * platform's own idea of "select all" rather than our idea of which keys mean that.
     */
    private fun runQuickAction(connection: InputConnection, action: QuickAction) {
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
                afterNewlineCommitted()
                // The same hard break handleEnter draws: no bigram forms across a line.
                previousWord1 = null
                previousWord2 = null
            }
            QuickAction.SWITCH_LAYOUT -> switchLanguage()
            QuickAction.SETTINGS -> openSettings()
            QuickAction.COMPOSE -> {
                if (privateMode || !preferences.composerEnabled) return
                // A selection seeds the box with just that; nothing selected seeds it with the
                // whole field, so "open the draft box" on a field already written in does not
                // start from a blank one.
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
            // As the arrow keys a hardware keyboard would send, not as setSelection: the editor
            // then moves the caret its own way -- past a surrogate pair, out of a selection,
            // across a line in a web view -- rather than by our count of characters.
            QuickAction.CURSOR_LEFT -> {
                resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            }
            QuickAction.CURSOR_RIGHT -> {
                resetComposing()
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            // Not a word: the pair and triple context ends here, as it does at a line break.
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
        // No refresh here: onQuickAction does it once for the whole tap, per its own doc, and
        // a second (or, for a macro, an n-th) engine round trip per step bought nothing.
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
     * Flips the first letter of the word at the cursor (see [SentenceCase.wordAt]) and puts
     * the selection back exactly where it was, so pressing again flips it back.
     *
     * Read from the extracted text rather than the before/after windows: its selection
     * offsets are the editor's own, which is what [InputConnection.setSelection] needs to
     * land on one character in the middle of a word without disturbing the caret. The one
     * character is replaced through a selection rather than a delete-and-commit, so an
     * editor that watches its text sees a single character change and nothing move.
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
     * Rewrites the field with every sentence capitalised ([SentenceCase.capitaliseSentences])
     * and the selection where it was -- the rewrite never changes the length, so the same
     * offsets still mean the same place. Only the span that actually differs is touched,
     * the same way [restoreFieldVersion] applies a history step.
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
        // A copy is a new offer, the same as onClipboardChanged treats one, and the chip is
        // built from the clip in hand rather than read back from the clipboard: on some devices
        // the clipboard service applies the write after this call returns, and the change
        // listener is not delivered for a clip the keyboard set itself -- so a read-back here
        // still saw the previous clip (or the one just withdrawn) and the chip never appeared,
        // while a copy made in the app reached refreshClipboardChip through onStartInputView
        // and worked.
        withdrawnClip = null
        refreshClipboardChip(clip)
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
            val view = host ?: return@launch
            // Read and decoded off the main thread, both: a thumbnail is a bitmap decode, and
            // it used to happen inside setEntries, on the thread that draws the keys.
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
    /** Opens the emoji grid searched by the word at hand, or closes it. */
    private fun toggleEmojiPanel() {
        val view = host ?: return
        val show = !view.emojiPanelVisible
        view.setEmojiPanelVisible(show)
        if (show) {
            view.emojiPanel.query = searchWordAtCaret()
        }
    }

    /** The word an emoji is looked up by: the one being typed, else the letters before the
     *  caret. */
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
        // The strip was hidden while the panel was up; fill it for where the cursor actually is.
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
        // The editor's own caret, not the tracked one: an editor that reports its selection
        // late leaves selectionEnd behind the text just read.
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

    /** Whether the copying app marked the clip sensitive -- a password manager's credential.
     *  The flag exists from Android 13; below that nothing marks a clip. */
    private fun isSensitiveClip(clip: ClipData): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return clip.description?.extras
            ?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, false) == true
    }

    /**
     * Rebuilds the chip that offers what is on the clipboard.
     *
     * Reads the clip rather than the history, because what someone means by "what I copied" is
     * the last thing they copied, not the last thing this keyboard happened to record. The
     * label is built here and handed to the view as a finished string: the strip draws, it does
     * not decide what to say.
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
        if (preferences.clipboardDeleteAfterUse) {
            // By content, not by an id kept from wherever this text was captured -- this reads
            // straight from the system clipboard, never from a row in the history table, so
            // there is no id to have kept. A no-op if it was never remembered at all (history
            // switched off) or is pinned -- deleteIfUnpinned already refuses both on its own.
            scope.launch(Dispatchers.IO) { DataGraph.clipboard.deleteIfUnpinned(text) }
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
        if (isSensitiveClip(clip)) {
            return
        }

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
        // Nothing to style a request for without a field. Not gated on private mode, and it
        // used to be, oddly, the other way round -- refusing only when the field was *not*
        // private and absent: the chips come from the autofill service, which is exactly
        // where a password manager's own suggestions belong in a password field, and their
        // text never passes through this keyboard (see above).
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
        const val DEFAULT_ALPHABETIC_LAYOUT = "qwerty"
        const val SYMBOLS_LAYOUT = "symbols"
        const val SYMBOLS_NUMPAD_LEFT_LAYOUT = "symbols_numpad_left"
        const val SYMBOLS_NUMPAD_RIGHT_LAYOUT = "symbols_numpad_right"
        const val SYMBOLS_SHIFT_LAYOUT = "symbols_shift"
        const val NUMPAD_LAYOUT = "numpad"
        /** `plus`-only asset (keyboard/src/plus/assets/); absent, harmlessly, in `core`. */
        const val SWIPE_MODEL_ASSET = "model.bkw"

        const val PAGE_ALPHABETIC = 0
        const val PAGE_SYMBOLS = 1
        const val PAGE_SYMBOLS_SHIFT = 2
        const val PAGE_NUMPAD = 3
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"

        /** The settings activity's extras: a screen to open on, and a clip that screen edits. */
        /** Shift held, as a key event carries it, for a capital typed into a terminal. */
        const val TERMINAL_SHIFT_META =
            android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        const val SETTINGS_EXTRA_SCREEN = "com.borderkeys.settings.SCREEN"
        const val SETTINGS_EXTRA_CLIP_ID = "com.borderkeys.settings.CLIP_ID"
        const val SETTINGS_SCREEN_CLIPBOARD = "Clipboard"
        /**
         * The file the native personal model used to be written into on every flush and never
         * read back from -- the Room tables were always the copy it was rebuilt from at start.
         * Deleted at start on any install that still has one.
         */
        const val LEGACY_USER_MODEL_SNAPSHOT = "user_model.bku"

        /** How long [flushLearningBeforeDestroy] waits for the database before giving up. */
        const val FINAL_FLUSH_TIMEOUT_MILLIS = 2_000L

        /** Neither changes the field, so neither is worth a suggestions refresh afterward --
         *  see onQuickAction's own comment. */
        val NO_REFRESH_QUICK_ACTIONS = setOf(QuickAction.CLIPBOARD_HISTORY, QuickAction.COMPOSE)

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

        /** How much of the text before the caret an emoji search reads its word from. */
        const val SEARCH_QUERY_CHARS = 32

        /** How much of a private field's text, either side of the caret, the strip can show. */
        const val PRIVATE_REVEAL_CHARS = 256

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

        /** What a swiped word runs straight on from without a space in between -- openers and
         *  joiners, see [spaceBeforeSwipedWord]. Everything else gets a space. */
        const val SWIPE_NO_SPACE_AFTER = "([{\"'/-_@#\n"

        /** How much of a copied text the chip shows before it stops being a label. */
        const val CHIP_PREVIEW_CHARS = 24

        /** How many cards the history panel holds. Beyond this, scrolling stops being reading. */
        const val MAX_CLIPBOARD_CARDS = 40

        /** How far either side of the cursor "the line" is looked for. */
        const val LINE_WINDOW_CHARS = 1024

        const val MIN_LEARNED_LENGTH = 2

        const val GESTURE_DECODING_NOTICE_MILLIS = 50L

        /** The share of a decode rank one has to hold for [decodeWasDecisive] to call it
         *  settled: more than half of a distribution summing to 1000. */
        const val DECISIVE_SHARE_PER_MILLE = 500f

        /** How far the text field must move on screen, in pixels, before that reads as the page
         *  scrolling under an open ring rather than a layout settling by a pixel. */
        const val EDITOR_MOVE_DISMISS_PX = 8f
        const val MAX_CLIP_LENGTH = 20_000
        const val MAX_INLINE_SUGGESTIONS = 5
        const val MIN_CHIP_WIDTH_DP = 120
        const val BLUR_RADIUS_DP = 24f
        const val CHIP_PADDING_PX = 12
    }
}
