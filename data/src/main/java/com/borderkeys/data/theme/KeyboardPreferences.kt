// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import com.borderkeys.data.ClipboardExclusions
import com.borderkeys.data.PackageNames
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.io.OutputStream

/** Behaviour the user can change, as opposed to appearance, which is [KeyboardTheme]. */
@Serializable
data class KeyboardPreferences(
    /** Minutes an unpinned clipboard entry survives. */
    val clipboardRetentionMinutes: Int = 60,
    val clipboardEnabled: Boolean = true,

    /**
     * Whether copied images are remembered alongside copied text; turning it off deletes the
     * images already remembered.
     */
    val clipboardImages: Boolean = false,

    /** The largest copied image kept, in megabytes; a larger one is not remembered. */
    val clipboardImageMaxMb: Int = DEFAULT_CLIPBOARD_IMAGE_MAX_MB,

    /** Whether other apps' text-selection menu offers Keep privately with BorderKeys. */
    val privateCopyInTextMenu: Boolean = false,

    /** What a short drag off a key does, by key and direction; see [KeyFlick]. */
    val keyFlicks: List<KeyFlick> = emptyList(),

    /** Layouts the user wrote; see [CustomLayout]. */
    val customLayouts: List<CustomLayout> = emptyList(),

    /** The layout each subtype draws, by the subtype's own layout id, where it is not its own. */
    val subtypeLayouts: Map<String, String> = emptyMap(),

    /** Whether a drag along backspace selects, character by character, for the lift to delete. */
    val backspaceSlideSelects: Boolean = true,

    /** Whether the space bar held still becomes a joystick for the caret. */
    val spaceTrackpoint: Boolean = true,

    /** How fast the joystick moves, in percent of its default pace. */
    val trackpointSpeed: Int = DEFAULT_TRACKPOINT_SPEED,

    /** How far a press must travel, as a fraction of the key's diagonal, to be a flick. */
    val flickMinFraction: Float = DEFAULT_FLICK_MIN_FRACTION,

    /** How far a press may travel, as a fraction of the key's diagonal, and still be a flick. */
    val flickMaxFraction: Float = DEFAULT_FLICK_MAX_FRACTION,

    /**
     * Whether the keyboard-picker key switches back to the previous keyboard at once, its hold
     * opening the picker; off, a tap opens the picker.
     */
    val pickerKeySwitchesBack: Boolean = false,

    /** The voice keyboard the voice key last switched to, by input method id. */
    val voiceKeyboardId: String = "",

    /** The voice keyboards that were enabled when [voiceKeyboardId] was chosen, as [VoiceInput.signature] has them. */
    val voiceKeyboardSet: String = "",
    /** Hard cap on unpinned history, independent of the retention window. */
    val clipboardMaxEntries: Int = 60,

    /**
     * Packages, beyond the password managers and code apps [ClipboardExclusions] knows, whose
     * copies the history never keeps. Sanitised on read: trimmed, shaped like package names,
     * each once, bounded.
     */
    val clipboardExcludedPackages: List<String> = emptyList(),

    /**
     * Packages, beyond the terminals the keyboard knows, typed into as terminals: every key goes
     * straight through. Sanitised on read like [clipboardExcludedPackages].
     */
    val terminalPackages: List<String> = emptyList(),
    /** Whether confirmed words are written to the personal dictionary at all. */
    val learningEnabled: Boolean = true,

    /**
     * Whether the built-in list of offensive words is kept out of suggestions, corrections and
     * learning. A word typed letter by letter is never touched. The lists are per language, in
     * the keyboard's assets, and follow the languages that are turned on.
     */
    val blockOffensiveWords: Boolean = false,

    /**
     * How quickly what you write starts to outrank what the dictionary says, one of the LEARNING_
     * constants below. What is recorded is the same either way.
     */
    val learningSpeed: Int = LEARNING_BALANCED,

    /**
     * How many learned words are kept, a multiple of [LEARNED_WORD_LIMIT_STEP]; past it, the words
     * used least are forgotten with the phrases they are in.
     */
    val learnedWordLimit: Int = DEFAULT_LEARNED_WORD_LIMIT,

    /**
     * Whether where the taps land on each key is learned, and the learned patterns used to tell
     * which key was meant; only while [learningEnabled] is on, and never in a private field.
     */
    val heatmapEnabled: Boolean = true,

    /** How far the heatmap moves a substitution's cost from the default tap model's. */
    val heatmapWeight: Float = DEFAULT_HEATMAP_WEIGHT,

    /** How many taps a key needs before its pattern counts. */
    val heatmapMinTaps: Int = DEFAULT_HEATMAP_MIN_TAPS,

    /** After how many days a tap counts half as much. */
    val heatmapHalfLifeDays: Int = DEFAULT_HEATMAP_HALF_LIFE_DAYS,
    val swipeEnabled: Boolean = true,

    /**
     * Whether the backspace pressed right after a swiped word removes the whole word (and the
     * space the swipe put in front of it) rather than its last letter.
     */
    val swipeBackspaceDeletesWord: Boolean = false,

    /** Reserved and read by nothing; kept so an older stored file still parses. */
    val perAppLanguageMemory: Boolean = false,
    val hapticFeedback: Boolean = true,

    /**
     * How firm the keypress vibration is, as a platform feedback class: [HAPTIC_SYSTEM] (the
     * phone's own keyboard tap, the default), [HAPTIC_LIGHT], [HAPTIC_MEDIUM] or [HAPTIC_STRONG].
     * Clamped on read.
     */
    val hapticStrength: Int = HAPTIC_SYSTEM,

    /**
     * Which touches vibrate while [hapticFeedback] is on: a key, a pick on the strip or in a
     * panel, the swipe ring.
     */
    val hapticKeys: Boolean = true,
    val hapticSuggestions: Boolean = true,
    val hapticRing: Boolean = true,

    /** Whether a keypress makes a sound. */
    val keySound: Boolean = false,

    /**
     * Whether the first letter of a sentence is capitalised for you, as the editor asks: every
     * sentence, every word or every character. A field that asks for none gets none.
     */
    val autoCapitalise: Boolean = true,

    /**
     * Whether the first letter of a sentence is capitalised even in a field that never asked for
     * it. No effect while [autoCapitalise] is off; a password field is left alone.
     */
    val forceCapitaliseSentences: Boolean = false,

    /**
     * Whether a word the dictionaries flag as a name is offered capitalised wherever it lands,
     * rather than only at the start of a sentence or after a shift. Governs the capital only.
     */
    val capitaliseNames: Boolean = true,

    /** Whether two spaces become a full stop and a space; the backspace that follows undoes it. */
    val doubleSpacePeriod: Boolean = true,

    /** Whether a space is added after a full stop, comma or the rest of the sentence marks. */
    val spaceAfterPunctuation: Boolean = true,

    /** Whether that space is added when the mark follows a digit. */
    val spaceInsideNumbers: Boolean = false,

    /** What the keyboard shows when it has news: a word learned, a correction applied. */
    val effects: EffectsSettings = EffectsSettings(),

    /**
     * Whether picking a suggestion from the strip also puts a space after the word. Never a
     * second space: one already there is left alone.
     */
    val spaceAfterSuggestion: Boolean = true,

    /**
     * What a space typed straight after one this keyboard added itself does -- after a
     * sentence mark, a picked suggestion, a swiped word, or the double-space full stop.
     * [AUTO_SPACE_SWALLOW_FIRST] (default) drops that one habitual space and keeps any after
     * it; [AUTO_SPACE_SWALLOW_ALL] keeps dropping spaces until something else is typed;
     * [AUTO_SPACE_KEEP] never drops one. Clamped to a valid value on read.
     */
    val autoSpaceHabit: Int = AUTO_SPACE_SWALLOW_FIRST,

    /** Whether a space before a punctuation mark is removed when the mark is typed. */
    val removeSpaceBeforePunctuation: Boolean = true,

    /** Whether sliding along the space bar moves the cursor. */
    val spaceCursorControl: Boolean = true,

    /** The emoji used most recently, newest first, stored here rather than in the database. */
    val emojiRecents: List<String> = emptyList(),

    /** Whether the emoji key sits beside the space bar; off gives its width to the space bar. */
    val emojiKey: Boolean = true,

    /** Whether the globe key, which cycles the layouts, sits beside the space bar. */
    val languageKey: Boolean = false,

    // ---- size and position -------------------------------------------------------------

    /** Multiplier on the row height. */
    val heightScale: Float = 1f,
    /** Fraction of the screen width the keyboard occupies. */
    val widthScale: Float = 1f,
    /** One of the MODE_ constants below. */
    val positionMode: Int = MODE_DOCKED,
    /** How far the keyboard sits above the bottom edge, in dp. */
    val bottomOffsetDp: Float = 0f,
    /** Horizontal offset from centre, in dp. Floating mode only. */
    val horizontalOffsetDp: Float = 0f,

    /**
     * Landscape's own height, width, position, offsets and split gap; the fields above are
     * portrait's.
     */
    val landscape: KeyboardPlacement = KeyboardPlacement(),

    /** Whether the empty strip beside a narrowed keyboard offers an arrow to move it across. */
    val edgeArrows: Boolean = true,

    /**
     * Whether what shows through beside a narrowed keyboard is blurred. The system refuses on
     * low-end devices and in battery saver.
     */
    val blurBehindKeyboard: Boolean = false,

    /**
     * The language the interface is written in, as a catalogue code, or empty to follow the
     * phone, resolved by LanguageResolution with English as the fallback. The dictionaries are a
     * separate setting.
     */
    val uiLanguage: String = "",

    /**
     * How readily the keyboard stops offering words from the languages you are not writing in,
     * one of the LANGUAGE_LOCK_ constants. What you have written yourself is never filtered.
     */
    val languageLock: Int = LANGUAGE_LOCK_BALANCED,

    /**
     * The language tag detection starts from before anything has been recognised, or empty for
     * none. Once [languageLock]'s evidence names a language, that one answers instead, and the
     * other dictionaries still offer words that match the typed letters more closely. A tag naming
     * a pack that is gone or switched off behaves as none.
     */
    val preferredLanguageTag: String = "",

    /**
     * Whether the language detected carries into the next field and through a restart of the
     * keyboard; off, every field starts undecided. With a [preferredLanguageTag], every field
     * starts from that one instead.
     */
    val rememberDetectedLanguage: Boolean = true,

    /**
     * What happens to a correction already applied once [languageLock]'s evidence decides the
     * conversation was in a different language: nothing ([LANGUAGE_SWITCH_OFF]), the word offered
     * back ([LANGUAGE_SWITCH_ASK]) or edited ([LANGUAGE_SWITCH_AUTO_APPLY]). An edit lands on the
     * field's undo history.
     */
    val languageSwitchCorrectionMode: Int = LANGUAGE_SWITCH_OFF,

    /**
     * Whether swipe typing is decoded by the trained neural model (tier B) instead of the
     * geometric one (tier A). No effect in a `core` build, which has no tier B.
     */
    val experimentalSwipeModelEnabled: Boolean = true,

    /**
     * Set by the keyboard when tier B's weights could not be read or were not valid; disables the
     * option above on this installation. Cleared by reinstalling and by a backup restore.
     */
    val swipeModelFailed: Boolean = false,

    /**
     * Whether pausing mid-swipe shows a preview near the finger that lifting turns into a menu: a
     * tap picks a candidate, or the top one applies itself after [radialPickTimeoutMillis].
     */
    val radialMenuEnabled: Boolean = false,

    /**
     * How many words the ring offers at once. Clamped to [MIN_RADIAL_SUGGESTIONS]..
     * [MAX_RADIAL_SUGGESTIONS] on read.
     */
    val radialSuggestionCount: Int = DEFAULT_RADIAL_SUGGESTIONS,

    /**
     * How long the finger has to hold still mid-swipe before the preview shows, in milliseconds.
     * Clamped to [MIN_RADIAL_PAUSE_DWELL_MILLIS]..[MAX_RADIAL_PAUSE_DWELL_MILLIS] on read.
     */
    val radialPauseDwellMillis: Int = DEFAULT_RADIAL_PAUSE_DWELL_MILLIS,

    /**
     * How far a swipe must have travelled, as its bounding-box diagonal in key widths, before the
     * pause-dwell timer is armed. Clamped to [MIN_RADIAL_MIN_PATH_LETTERS]..
     * [MAX_RADIAL_MIN_PATH_LETTERS] on read; `0` removes the guard.
     */
    val radialMinPathLetters: Float = DEFAULT_RADIAL_MIN_PATH_LETTERS,

    /**
     * How long the real menu waits, after a lift, before applying the top candidate on its own.
     * Clamped to [MIN_RADIAL_PICK_TIMEOUT_MILLIS]..[MAX_RADIAL_PICK_TIMEOUT_MILLIS] on read.
     */
    val radialPickTimeoutMillis: Int = DEFAULT_RADIAL_PICK_TIMEOUT_MILLIS,

    /**
     * Where the ring is centred: [RADIAL_ANCHOR_FINGER] (default) where the swipe paused or
     * ended, [RADIAL_ANCHOR_CENTER] the middle of the keyboard, [RADIAL_ANCHOR_TANGENT_LEFT]/
     * [RADIAL_ANCHOR_TANGENT_RIGHT] one side, following the gesture vertically. An unrecognised
     * value reads as [RADIAL_ANCHOR_FINGER].
     */
    val radialMenuAnchor: Int = RADIAL_ANCHOR_FINGER,

    /**
     * How big the ring is drawn: [RADIAL_SIZE_SMALL], [RADIAL_SIZE_MEDIUM] or [RADIAL_SIZE_LARGE].
     * An unrecognised value reads as [RADIAL_SIZE_MEDIUM].
     */
    val radialMenuSize: Int = RADIAL_SIZE_MEDIUM,

    /**
     * What happens if the ring is resolved, by a release or the pick timeout, with neither a
     * wedge nor the centre Cancel button touched: [RADIAL_TIMEOUT_APPLY_TOP] (default) applies
     * rank #1, [RADIAL_TIMEOUT_CANCEL] discards. An unrecognised value reads as
     * [RADIAL_TIMEOUT_APPLY_TOP].
     */
    val radialTimeoutDefault: Int = RADIAL_TIMEOUT_APPLY_TOP,

    /**
     * What the ring does with rank #1, which is already composing in the field:
     * [RADIAL_TRUSTED_CHIP] (default) gives it an outlined wedge of its own,
     * [RADIAL_TRUSTED_AUTO_APPLY] closes the ring and keeps that word. An unrecognised value
     * reads as [RADIAL_TRUSTED_CHIP].
     */
    val radialTrustedWord: Int = RADIAL_TRUSTED_CHIP,

    /**
     * How a completed swipe offers its alternatives when nothing is chosen at lift.
     *
     * Off (default): resolves immediately. A paused gesture lifted in the dead zone applies
     * [radialTimeoutDefault]; a gesture without a pause shows no ring.
     *
     * On: the ring is shown, or kept open, until a tap on a word or the centre Cancel button.
     * While it waits, a touch anywhere else only closes it and leaves the swiped word as it is.
     */
    val radialLiftKeepsOpen: Boolean = false,

    /**
     * Whether the keys behind the ring are blurred while it is open, on API 31+. The ring's scrim
     * dims them either way.
     */
    val radialBlurBackground: Boolean = true,

    /**
     * Whether a tap outside the ring, on the keyboard, also takes the keyboard down. A tap in the
     * text field, and the keyboard being hidden, always close the ring.
     */
    val radialOutsideTapHidesKeyboard: Boolean = false,

    /** Whether the ring closes when the text field moves on screen while it is open. */
    val radialCloseOnEditorMove: Boolean = true,

    /**
     * Debug builds only: keeps a sample ring open on every field. A release build ignores it.
     */
    val debugForceRadialRing: Boolean = false,

    /**
     * Whether the first slot of the suggestion strip offers what is on the clipboard, taking one
     * of the slots. Suppressed in a password field.
     */
    val clipboardSuggestion: Boolean = false,

    /** Whether the system clipboard is emptied after its content is inserted from the chip. */
    val clearClipboardAfterInsert: Boolean = false,

    /**
     * Whether the clipboard chip is withdrawn after it has been used, or once the keyboard has
     * been closed. The history and the system clipboard are untouched.
     */
    val clipboardSuggestionOnce: Boolean = true,

    /**
     * Whether inserting the current clipboard item also deletes its row from the history panel,
     * if it is not pinned.
     */
    val clipboardDeleteAfterUse: Boolean = false,

    /** Whether the unpinned clipboard history is emptied when the keyboard closes. */
    val clearClipboardOnClose: Boolean = false,

    /** Whether the Compose quick action can open the draft box. */
    val composerEnabled: Boolean = true,

    /**
     * The buttons on the draft box's control bar, in order, as [ComposerAction] ids or
     * [customActions] ids, whose ranges do not overlap ([CustomAction.nextId]). Resolved by
     * [ComposerBar.resolve]; an id this build does not know is dropped.
     */
    val composerBar: List<Int> = ComposerAction.DEFAULT.map { it.id },

    /** The draft box's own text size, one of the `COMPOSER_TEXT_SIZE_*` steps below. */
    val composerTextSize: Int = COMPOSER_TEXT_SIZE_MEDIUM,

    /**
     * Whether a selection in the draft box grows out to whole words before an action runs on it;
     * off sends the selection exactly as it was made.
     */
    val composerSnapSelectionToWords: Boolean = true,

    /**
     * Instructions the user wrote and kept, in the order they were saved, each with a stable id
     * so it can be pinned onto [composerBar]. Stored as `savedPrompts`.
     */
    @SerialName("savedPrompts") val customActions: List<CustomAction> = emptyList(),

    /** Whether the row of quick actions is shown at all. */
    val quickActionsEnabled: Boolean = false,

    /**
     * The actions on the bar, in order, as [QuickAction] ids or [customQuickActions] ids, whose
     * ranges do not overlap ([CustomQuickAction.nextId]). Resolved by [QuickActionBar.resolve];
     * an id this build does not know is dropped when the list is read.
     */
    val quickActions: List<Int> = QuickAction.DEFAULT.map { it.id },

    /**
     * Macros the user built for the quick-action bar -- each an ordered list of [QuickAction]
     * (or other custom action) ids, addressable by its own stable id so one can be pinned onto
     * [quickActions] as a real button. See [CustomQuickAction] for the shape and
     * [QuickActionBar.flatten] for how a macro turns into the steps that actually run.
     */
    val customQuickActions: List<CustomQuickAction> = emptyList(),

    /**
     * Words that expand into longer text when a delimiter follows them -- see [TextShortcut].
     * Sanitised on read: a trigger with whitespace in it, an empty expansion, or a second
     * shortcut for the same trigger (compared without case) is dropped.
     */
    val textShortcuts: List<TextShortcut> = emptyList(),

    /** Whether the bar starts open or as a single button that opens it. */
    val quickActionsMode: Int = QUICK_ACTIONS_COLLAPSED,

    /** Which edge the bar, and the button that stands in for it, sit against. */
    val quickActionsPlacement: Int = QUICK_ACTIONS_ABOVE_STRIP,

    /**
     * How thick the bar is: [QUICK_ACTIONS_SIZE_DEFAULT], or a step past it, each a little
     * thicker with more space around a slightly smaller button.
     */
    val quickActionsSize: Int = QUICK_ACTIONS_SIZE_DEFAULT,

    /**
     * Whether each button on the bar also says what it is, in small text under its icon. A bar
     * down a side stays icons-only.
     */
    val quickActionsLabels: Boolean = false,

    /**
     * What the date-and-time quick action writes, as a [TimestampPattern]. Sanitised on read:
     * a pattern that cannot write a moment is replaced by [TimestampPattern.DEFAULT].
     */
    val timestampPattern: String = TimestampPattern.DEFAULT,

    /** A permanent row of digits above the letters. */
    val numberRow: Boolean = false,

    /** A row of hardware keys: escape, tab, control, alt and the arrows. */
    val modifierRow: Boolean = false,

    /**
     * Where the modifier row sits: [MODIFIER_ROW_ABOVE] the letters, under the strip, or
     * [MODIFIER_ROW_BELOW] the keyboard, under the space row.
     */
    val modifierRowPosition: Int = MODIFIER_ROW_ABOVE,

    /**
     * The keys on the modifier row, left to right, by the names [ModifierRowKeys] lists. Read
     * through [ModifierRowKeys.sanitised]; an empty result shows the default row.
     */
    val modifierRowKeys: List<String> = ModifierRowKeys.DEFAULT,

    /** Whether the feature tour shown after setup has been dismissed for good. */
    val featuresTourSeen: Boolean = false,

    /**
     * Where the digits sit on the number-and-symbols page: [SYMBOLS_NUMBER_RIGHT] and
     * [SYMBOLS_NUMBER_LEFT] put them in a 3x3 block beside the symbols, with backspace and enter
     * down the right edge; [SYMBOLS_NUMBER_TOP] puts them in a row above the symbols.
     */
    val symbolsNumberPosition: Int = SYMBOLS_NUMBER_RIGHT,

    /**
     * The diacritics merged onto the letter keys' long press, taken from the enabled language
     * packs. On, a Romanian pack puts ă, â on `a`; off, the letter keys carry only their
     * symbols. The base layout has none of its own -- see the `accents/` assets.
     */
    val accentedCharacters: Boolean = true,

    /** The small character drawn in a key's corner showing what its long press would type. */
    val longPressHints: Boolean = true,

    /**
     * Whether a pressed key shows itself enlarged above the finger while it is held. Letters,
     * digits and symbols only.
     */
    val keyPopup: Boolean = true,

    /**
     * How long a key must be held before the long press fires, in milliseconds. Clamped to
     * [MIN_LONG_PRESS_MILLIS]..[MAX_LONG_PRESS_MILLIS] on read.
     */
    val longPressMillis: Int = DEFAULT_LONG_PRESS_MILLIS,

    /**
     * What Enter does. [ENTER_KEY_AUTO] runs the field's declared action (Send, Done, Go...)
     * unless the field set `IME_FLAG_NO_ENTER_ACTION`; [ENTER_KEY_FORCE_ACTION] runs the action
     * wherever one exists, that flag included, else writes a newline; [ENTER_KEY_FORCE_NEWLINE]
     * always writes a newline.
     */
    val enterKeyBehavior: Int = ENTER_KEY_AUTO,

    /** Switch to a numeric keypad automatically in numeric and phone fields. */
    val numericKeypad: Boolean = true,
    val showSuggestionStrip: Boolean = true,

    /** How many suggestions the strip offers at once. */
    val suggestionCount: Int = DEFAULT_SUGGESTIONS,

    /**
     * Whether a suggestion may be two words rather than one, taken only from phrases this person
     * has written repeatedly, never from the dictionary. The second word needs twice the evidence
     * of the first.
     */
    val phraseSuggestions: Boolean = false,

    /** Whether a delimiter applies the leading suggestion instead of committing what was typed. */
    val autoCorrectOnSpace: Boolean = false,

    /**
     * Whether the backspace immediately after an applied correction restores what was typed. Only
     * meaningful with [autoCorrectOnSpace].
     */
    val revertCorrectionOnBackspace: Boolean = true,

    /**
     * How far a correction may be from what was typed before a delimiter applies it, counted in
     * single-letter edits (a letter missing, added, wrong, or two swapped) after case and accents
     * are set aside. [CORRECTION_DISTANCE_STRICT] allows one edit; [CORRECTION_DISTANCE_NORMAL]
     * (default) one edit, or two in a word of eight letters or more; [CORRECTION_DISTANCE_LOOSE]
     * two edits always. A ceiling on top of [correctionStrictness], which only ranks. Clamped to
     * a valid value on read.
     */
    val correctionDistance: Int = CORRECTION_DISTANCE_NORMAL,

    /** The shortest word a delimiter will replace. */
    val minCorrectionLength: Int = 3,

    /**
     * How much evidence an edit needs before it outranks a word spelled exactly as typed. 1.0 is
     * the engine's calibrated default; below it a smaller frequency gap wins, above it a bigger
     * one is needed.
     */
    val correctionStrictness: Float = DEFAULT_CORRECTION_STRICTNESS,

    /**
     * How far the text assistant's model may wander from the single most likely next word. `plus`
     * only, read by [com.borderkeys.assist.TextAssistService] for whichever model is loaded.
     */
    val assistTemperature: Float = DEFAULT_ASSIST_TEMPERATURE,

    /** The nucleus (top-p) the same model samples from. See [assistTemperature]. */
    val assistTopP: Float = DEFAULT_ASSIST_TOP_P,

    /**
     * The imported model, by file name, that translation runs on -- empty to run it on the
     * active model like everything else. Only has an effect with more than one model imported;
     * a name that no longer matches an imported model is ignored. See
     * [com.borderkeys.data.assist.AssistCategory].
     */
    val assistTranslateModel: String = "",

    /** The model, by file name, that correcting, rewriting and summarising run on. Empty for the
     *  active model. See [assistTranslateModel]. */
    val assistWriteModel: String = "",

    /**
     * Whether the keyboard's palette follows the phone's wallpaper instead of the theme's stored
     * colours, which stay untouched. Read by `com.borderkeys.theme.DynamicColors`.
     */
    val followSystemColors: Boolean = false,

    /**
     * [THEME_MODE_MANUAL] (default) always shows [KeyboardTheme]; [THEME_MODE_AUTO_SYSTEM] shows
     * it in dark mode and `ThemeRepository.lightTheme` otherwise. Resolved before
     * [followSystemColors] is applied.
     */
    val themeMode: Int = THEME_MODE_MANUAL,
) {
    fun sanitised(): KeyboardPreferences {
        // Portrait's six fields, clamped by KeyboardPlacement.
        val portrait = placementFor(isLandscape = false).sanitised()
        // Before copy(...), whose arguments read the original properties: composerBar is
        // checked against this.
        val sanitisedCustomActions = CustomAction.backfillLegacyIds(customActions)
            .filter { it.name.isNotBlank() && it.instruction.isNotBlank() }
            .map {
                it.copy(
                    name = it.name.take(CustomAction.MAX_NAME_CHARS),
                    instruction = it.instruction.take(CustomAction.MAX_INSTRUCTION_CHARS),
                )
            }
            .take(CustomAction.MAX_CUSTOM_ACTIONS)
        // Before copy(...): quickActions is checked against this.
        val sanitisedCustomQuickActions = customQuickActions
            .filter { it.name.isNotBlank() }
            .map { it.copy(name = it.name.take(CustomQuickAction.MAX_NAME_CHARS)) }
            .take(CustomQuickAction.MAX_CUSTOM_QUICK_ACTIONS)
            .let { candidates ->
                candidates.map { candidate ->
                    candidate.copy(
                        steps = QuickActionBar.sanitisedSteps(candidate.id, candidate.steps, candidates),
                    )
                }
            }
        // One shortcut per trigger, the first wins, compared without case.
        val seenTriggers = HashSet<String>()
        val sanitisedTextShortcuts = textShortcuts
            .map { it.copy(trigger = it.trigger.trim(), expansion = it.expansion.trim().take(TextShortcut.MAX_EXPANSION_CHARS)) }
            .filter { TextShortcut.isValidTrigger(it.trigger) && it.expansion.isNotEmpty() }
            .filter { seenTriggers.add(it.trigger.lowercase()) }
            .take(TextShortcut.MAX_SHORTCUTS)
        return copy(
            hapticStrength = if (hapticStrength in HAPTIC_LIGHT..HAPTIC_SYSTEM) hapticStrength else HAPTIC_SYSTEM,
            textShortcuts = sanitisedTextShortcuts,
            clipboardExcludedPackages = PackageNames.sanitised(clipboardExcludedPackages),
            terminalPackages = PackageNames.sanitised(terminalPackages),
        minCorrectionLength = minCorrectionLength.coerceIn(MIN_CORRECTION_LENGTH, MAX_CORRECTION_LENGTH),
        correctionStrictness = if (correctionStrictness > 0f) {
            correctionStrictness.coerceIn(MIN_CORRECTION_STRICTNESS, MAX_CORRECTION_STRICTNESS)
        } else {
            DEFAULT_CORRECTION_STRICTNESS
        },
        assistTemperature = if (assistTemperature > 0f) {
            assistTemperature.coerceIn(MIN_ASSIST_TEMPERATURE, MAX_ASSIST_TEMPERATURE)
        } else {
            DEFAULT_ASSIST_TEMPERATURE
        },
        assistTopP = if (assistTopP > 0f) {
            assistTopP.coerceIn(MIN_ASSIST_TOP_P, MAX_ASSIST_TOP_P)
        } else {
            DEFAULT_ASSIST_TOP_P
        },
        assistTranslateModel = assistTranslateModel.take(MAX_MODEL_FILE_NAME_CHARS),
        assistWriteModel = assistWriteModel.take(MAX_MODEL_FILE_NAME_CHARS),
        keyFlicks = KeyFlick.sanitised(keyFlicks),
        customLayouts = CustomLayout.sanitised(customLayouts),
        subtypeLayouts = CustomLayout.sanitisedChoices(subtypeLayouts),
        trackpointSpeed = trackpointSpeed.coerceIn(MIN_TRACKPOINT_SPEED, MAX_TRACKPOINT_SPEED),
        flickMinFraction = flickMinFraction.coerceIn(MIN_FLICK_MIN_FRACTION, MAX_FLICK_MIN_FRACTION),
        flickMaxFraction = flickMaxFraction.coerceIn(MIN_FLICK_MAX_FRACTION, MAX_FLICK_MAX_FRACTION),
        voiceKeyboardId = voiceKeyboardId.take(MAX_INPUT_METHOD_ID_CHARS),
        voiceKeyboardSet = voiceKeyboardSet.take(MAX_INPUT_METHOD_SET_CHARS),
        themeMode = if (themeMode == THEME_MODE_AUTO_SYSTEM) THEME_MODE_AUTO_SYSTEM else THEME_MODE_MANUAL,
        clipboardRetentionMinutes = clipboardRetentionMinutes.coerceIn(1, 60 * 24 * 30),
        clipboardMaxEntries = clipboardMaxEntries.coerceIn(1, 1000),
        clipboardImageMaxMb = clipboardImageMaxMb.coerceIn(MIN_CLIPBOARD_IMAGE_MAX_MB, MAX_CLIPBOARD_IMAGE_MAX_MB),
        // The portrait placement comes from `portrait` above.
        heightScale = portrait.heightScale,
        widthScale = portrait.widthScale,
        positionMode = portrait.positionMode,
        symbolsNumberPosition = if (symbolsNumberPosition in SYMBOLS_NUMBER_TOP..SYMBOLS_NUMBER_RIGHT) {
            symbolsNumberPosition
        } else {
            SYMBOLS_NUMBER_RIGHT
        },
        longPressMillis = longPressMillis.coerceIn(MIN_LONG_PRESS_MILLIS, MAX_LONG_PRESS_MILLIS),
        enterKeyBehavior = if (enterKeyBehavior in ENTER_KEY_AUTO..ENTER_KEY_FORCE_NEWLINE) {
            enterKeyBehavior
        } else {
            ENTER_KEY_AUTO
        },
        suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTIONS, MAX_SUGGESTIONS),
        radialSuggestionCount = radialSuggestionCount.coerceIn(
            MIN_RADIAL_SUGGESTIONS, MAX_RADIAL_SUGGESTIONS,
        ),
        radialPauseDwellMillis = radialPauseDwellMillis.coerceIn(
            MIN_RADIAL_PAUSE_DWELL_MILLIS, MAX_RADIAL_PAUSE_DWELL_MILLIS,
        ),
        radialMinPathLetters = radialMinPathLetters.coerceIn(
            MIN_RADIAL_MIN_PATH_LETTERS, MAX_RADIAL_MIN_PATH_LETTERS,
        ),
        radialPickTimeoutMillis = radialPickTimeoutMillis.coerceIn(
            MIN_RADIAL_PICK_TIMEOUT_MILLIS, MAX_RADIAL_PICK_TIMEOUT_MILLIS,
        ),
        radialMenuAnchor = if (radialMenuAnchor in RADIAL_ANCHOR_FINGER..RADIAL_ANCHOR_TANGENT_RIGHT) {
            radialMenuAnchor
        } else {
            RADIAL_ANCHOR_FINGER
        },
        radialMenuSize = if (radialMenuSize in RADIAL_SIZE_SMALL..RADIAL_SIZE_LARGE) {
            radialMenuSize
        } else {
            RADIAL_SIZE_MEDIUM
        },
        autoSpaceHabit = if (autoSpaceHabit in AUTO_SPACE_SWALLOW_FIRST..AUTO_SPACE_KEEP) {
            autoSpaceHabit
        } else {
            AUTO_SPACE_SWALLOW_FIRST
        },
        correctionDistance = if (correctionDistance in CORRECTION_DISTANCE_STRICT..CORRECTION_DISTANCE_LOOSE) {
            correctionDistance
        } else {
            CORRECTION_DISTANCE_NORMAL
        },
        radialTimeoutDefault = if (radialTimeoutDefault in
            RADIAL_TIMEOUT_APPLY_TOP..RADIAL_TIMEOUT_CANCEL
        ) {
            radialTimeoutDefault
        } else {
            RADIAL_TIMEOUT_APPLY_TOP
        },
        radialTrustedWord = if (radialTrustedWord in
            RADIAL_TRUSTED_CHIP..RADIAL_TRUSTED_AUTO_APPLY
        ) {
            radialTrustedWord
        } else {
            RADIAL_TRUSTED_CHIP
        },
        learningSpeed = if (learningSpeed in LEARNING_CAUTIOUS..LEARNING_IMMEDIATE) {
            learningSpeed
        } else {
            LEARNING_BALANCED
        },
        heatmapWeight = if (heatmapWeight.isNaN()) {
            DEFAULT_HEATMAP_WEIGHT
        } else {
            heatmapWeight.coerceIn(MIN_HEATMAP_WEIGHT, MAX_HEATMAP_WEIGHT)
        },
        learnedWordLimit = (learnedWordLimit.coerceIn(MIN_LEARNED_WORD_LIMIT, MAX_LEARNED_WORD_LIMIT) /
            LEARNED_WORD_LIMIT_STEP) * LEARNED_WORD_LIMIT_STEP,
        heatmapMinTaps = (heatmapMinTaps.coerceIn(MIN_HEATMAP_MIN_TAPS, MAX_HEATMAP_MIN_TAPS) /
            HEATMAP_MIN_TAPS_STEP) * HEATMAP_MIN_TAPS_STEP,
        heatmapHalfLifeDays = heatmapHalfLifeDays.coerceIn(
            MIN_HEATMAP_HALF_LIFE_DAYS, MAX_HEATMAP_HALF_LIFE_DAYS,
        ),
        bottomOffsetDp = portrait.bottomOffsetDp,
        horizontalOffsetDp = portrait.horizontalOffsetDp,
        landscape = landscape.sanitised(),
        uiLanguage = uiLanguage.take(MAX_LANGUAGE_TAG),
        preferredLanguageTag = preferredLanguageTag.take(MAX_LANGUAGE_TAG),
        languageLock = if (languageLock in LANGUAGE_LOCK_OFF..LANGUAGE_LOCK_STRICT) {
            languageLock
        } else {
            LANGUAGE_LOCK_BALANCED
        },
        languageSwitchCorrectionMode =
            if (languageSwitchCorrectionMode in LANGUAGE_SWITCH_OFF..LANGUAGE_SWITCH_AUTO_APPLY) {
                languageSwitchCorrectionMode
            } else {
                LANGUAGE_SWITCH_OFF
            },
        // Read through both id spaces, which drops ids neither knows, then bounded.
        quickActions =
            QuickActionBar.sanitisedIds(quickActions, sanitisedCustomQuickActions).take(MAX_QUICK_ACTIONS),
        customQuickActions = sanitisedCustomQuickActions,
        timestampPattern = TimestampPattern.sanitised(timestampPattern),
        composerBar = ComposerBar.sanitisedIds(composerBar, sanitisedCustomActions),
        composerTextSize =
            if (composerTextSize in COMPOSER_TEXT_SIZE_SMALL..COMPOSER_TEXT_SIZE_LARGE) {
                composerTextSize
            } else {
                COMPOSER_TEXT_SIZE_MEDIUM
            },
        customActions = sanitisedCustomActions,
        emojiRecents = emojiRecents.filter { it.isNotEmpty() }.take(MAX_EMOJI_RECENTS),
        quickActionsMode = if (quickActionsMode in QUICK_ACTIONS_FULL..QUICK_ACTIONS_COLLAPSED) {
            quickActionsMode
        } else {
            QUICK_ACTIONS_COLLAPSED
        },
        quickActionsPlacement =
            if (quickActionsPlacement in QUICK_ACTIONS_ABOVE_STRIP..QUICK_ACTIONS_RIGHT) {
                quickActionsPlacement
            } else {
                QUICK_ACTIONS_ABOVE_STRIP
            },
        quickActionsSize =
            if (quickActionsSize in QUICK_ACTIONS_SIZE_DEFAULT..QUICK_ACTIONS_SIZE_HUGE) {
                quickActionsSize
            } else {
                QUICK_ACTIONS_SIZE_DEFAULT
            },
        )
    }

    val isOneHanded: Boolean
        get() = positionMode == MODE_ONE_HANDED_LEFT || positionMode == MODE_ONE_HANDED_RIGHT

    /**
     * The size/position values [isLandscape] selects: portrait's flat fields packed into a
     * [KeyboardPlacement], or [landscape].
     */
    fun placementFor(isLandscape: Boolean): KeyboardPlacement = if (isLandscape) {
        landscape
    } else {
        KeyboardPlacement(heightScale, widthScale, positionMode, bottomOffsetDp, horizontalOffsetDp)
    }

    /**
     * The appearance and layout fields, on the defaults for everything else: what the keyboard
     * may read before the user's first unlock. See [LockedAppearance].
     */
    fun forLockedStart(): KeyboardPreferences = KeyboardPreferences(
        themeMode = themeMode,
        followSystemColors = followSystemColors,
        heightScale = heightScale,
        widthScale = widthScale,
        positionMode = positionMode,
        bottomOffsetDp = bottomOffsetDp,
        horizontalOffsetDp = horizontalOffsetDp,
        landscape = landscape,
        numberRow = numberRow,
        hapticFeedback = hapticFeedback,
        hapticStrength = hapticStrength,
        hapticKeys = hapticKeys,
        hapticSuggestions = hapticSuggestions,
        hapticRing = hapticRing,
        keySound = keySound,
        keyPopup = keyPopup,
        longPressMillis = longPressMillis,
        uiLanguage = uiLanguage,
    )

    /** [transform] applied to whichever orientation's placement [isLandscape] selects, written
     *  back to portrait's flat fields or to [landscape] -- the other half of [placementFor]. */
    fun withPlacement(
        isLandscape: Boolean,
        transform: (KeyboardPlacement) -> KeyboardPlacement,
    ): KeyboardPreferences {
        val updated = transform(placementFor(isLandscape))
        return if (isLandscape) {
            copy(landscape = updated)
        } else {
            copy(
                heightScale = updated.heightScale,
                widthScale = updated.widthScale,
                positionMode = updated.positionMode,
                bottomOffsetDp = updated.bottomOffsetDp,
                horizontalOffsetDp = updated.horizontalOffsetDp,
            )
        }
    }

    /**
     * Moves [isLandscape]'s placement to [mode]. A full-width keyboard leaving the dock narrows to
     * [ONE_HANDED_WIDTH_SCALE]; otherwise the width is kept.
     */
    fun withPositionMode(mode: Int, isLandscape: Boolean = false): KeyboardPreferences =
        withPlacement(isLandscape) { placement ->
            val narrowing = placement.positionMode == MODE_DOCKED && mode != MODE_DOCKED &&
                placement.widthScale >= NEARLY_FULL_WIDTH
            placement.copy(
                positionMode = mode,
                widthScale = if (narrowing) ONE_HANDED_WIDTH_SCALE else placement.widthScale,
            )
        }

    companion object {
        /** Full width, flush with the bottom edge. */
        const val MODE_DOCKED = 0

        /** Narrowed and pushed to one side. */
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2

        /** Lifted off the bottom edge and movable. */
        const val MODE_FLOATING = 3

        /** Several repetitions before a word or phrase leads. */
        const val LEARNING_CAUTIOUS = 0

        /** The default. A phrase written twice starts to lead. */
        const val LEARNING_BALANCED = 1

        /** The first time counts. */
        const val LEARNING_IMMEDIATE = 2

        /** [learnedWordLimit]'s range, step and default. */
        const val MIN_LEARNED_WORD_LIMIT = 1_000
        const val MAX_LEARNED_WORD_LIMIT = 50_000
        const val LEARNED_WORD_LIMIT_STEP = 1_000
        const val DEFAULT_LEARNED_WORD_LIMIT = 20_000

        /** [heatmapWeight]'s range and default. */
        const val MIN_HEATMAP_WEIGHT = 0.5f
        const val MAX_HEATMAP_WEIGHT = 2.0f
        const val DEFAULT_HEATMAP_WEIGHT = 1.0f

        /** [heatmapMinTaps]'s range, step and default. */
        const val MIN_HEATMAP_MIN_TAPS = 10
        const val MAX_HEATMAP_MIN_TAPS = 100
        const val HEATMAP_MIN_TAPS_STEP = 10
        const val DEFAULT_HEATMAP_MIN_TAPS = 30

        /** [heatmapHalfLifeDays]' range and default. */
        const val MIN_HEATMAP_HALF_LIFE_DAYS = 7
        const val MAX_HEATMAP_HALF_LIFE_DAYS = 180
        const val DEFAULT_HEATMAP_HALF_LIFE_DAYS = 30

        /** Every dictionary is consulted for every word, whatever language the sentence is in. */
        const val LANGUAGE_LOCK_OFF = 0

        /** Waits for clear evidence -- roughly five or six telling words. */
        const val LANGUAGE_LOCK_PATIENT = 1

        /** Decides after about three words that belong to one language and no other. */
        const val LANGUAGE_LOCK_BALANCED = 2

        /** Decides on the first telling word. */
        const val LANGUAGE_LOCK_QUICK = 3

        /**
         * Never offers a language that has not been identified: until one is, the heaviest
         * dictionary answers alone.
         */
        const val LANGUAGE_LOCK_STRICT = 4

        /** Corrections already applied are never revisited, whatever [languageLock] later
         *  decides about the sentence they were part of. */
        const val LANGUAGE_SWITCH_OFF = 0

        /** Offers an affected word back, without touching the field until it is tapped. */
        const val LANGUAGE_SWITCH_ASK = 1

        /** Edits an affected word immediately -- still one step on the field's own undo history. */
        const val LANGUAGE_SWITCH_AUTO_APPLY = 2

        /** The bar is drawn in full, always. */
        const val QUICK_ACTIONS_FULL = 0

        /** One button stands in for the bar and opens it. The default. */
        const val QUICK_ACTIONS_COLLAPSED = 1

        /** Above the suggestion strip, spanning the keyboard. */
        const val QUICK_ACTIONS_ABOVE_STRIP = 0

        /** Below the keys, against the bottom edge. */
        const val QUICK_ACTIONS_BELOW_KEYS = 1

        /** A column down the left of the keys. */
        const val QUICK_ACTIONS_LEFT = 2

        /** A column down the right. */
        const val QUICK_ACTIONS_RIGHT = 3

        /** How many actions the bar will hold before it starts dropping them. */
        const val MAX_QUICK_ACTIONS = 10

        /** [quickActionsSize] values, thinnest first. */
        const val QUICK_ACTIONS_SIZE_DEFAULT = 0

        const val QUICK_ACTIONS_SIZE_SMALL = 1

        const val QUICK_ACTIONS_SIZE_MEDIUM = 2

        const val QUICK_ACTIONS_SIZE_HUGE = 3

        /** A model file name longer than any real one; a stored value past it is truncated. */
        const val MAX_MODEL_FILE_NAME_CHARS = 255

        const val DEFAULT_TRACKPOINT_SPEED = 100
        const val MIN_TRACKPOINT_SPEED = 50
        const val MAX_TRACKPOINT_SPEED = 200

        const val DEFAULT_FLICK_MIN_FRACTION = 0.28f
        const val MIN_FLICK_MIN_FRACTION = 0.10f
        const val MAX_FLICK_MIN_FRACTION = 0.60f
        const val DEFAULT_FLICK_MAX_FRACTION = 1.41f
        const val MIN_FLICK_MAX_FRACTION = 0.50f
        const val MAX_FLICK_MAX_FRACTION = 2.00f

        /** An input method id is `package/class`; a set of them is a few joined. */
        const val MAX_INPUT_METHOD_ID_CHARS = 300
        const val MAX_INPUT_METHOD_SET_CHARS = 3_000

        const val COMPOSER_TEXT_SIZE_SMALL = 0
        const val COMPOSER_TEXT_SIZE_MEDIUM = 1
        const val COMPOSER_TEXT_SIZE_LARGE = 2

        /** [modifierRowPosition] values. */
        const val MODIFIER_ROW_ABOVE = 0
        const val MODIFIER_ROW_BELOW = 1

        /** Digits as a row above the symbols. */
        const val SYMBOLS_NUMBER_TOP = 0

        /** Digits as a number pad down the left of the symbols. */
        const val SYMBOLS_NUMBER_LEFT = 1

        /** Digits as a number pad down the right of the symbols. */
        const val SYMBOLS_NUMBER_RIGHT = 2

        const val DEFAULT_LONG_PRESS_MILLIS = 380

        /** The keypress vibration classes; see [hapticStrength]. */
        const val HAPTIC_LIGHT = 0
        const val HAPTIC_MEDIUM = 1
        const val HAPTIC_STRONG = 2
        const val HAPTIC_SYSTEM = 3

        /** Follow the field: its action, unless it flagged Enter to stay a newline regardless. */
        const val ENTER_KEY_AUTO = 0

        /** The field's action, every time one exists -- ignoring a no-enter-action flag. */
        const val ENTER_KEY_FORCE_ACTION = 1

        /** A newline, every time, whatever the field asked for. */
        const val ENTER_KEY_FORCE_NEWLINE = 2

        /** The range [longPressMillis] is clamped to. */
        const val MIN_LONG_PRESS_MILLIS = 150
        const val MAX_LONG_PRESS_MILLIS = 700

        /** How many recent emoji are kept. */
        const val MAX_EMOJI_RECENTS = 24

        /** How long unpinned entries are kept, in minutes, as the values a slider steps through. */
        val RETENTION_STEPS: List<Int> = listOf(
            15, 30, 60, 4 * 60, 12 * 60, 24 * 60,
            3 * 24 * 60, 7 * 24 * 60, 14 * 24 * 60, 30 * 24 * 60,
        )

        /** How many entries are kept, as the values a slider steps through. */
        val HISTORY_SIZE_STEPS: List<Int> = listOf(10, 20, 30, 50, 75, 100, 150, 200, 500)

        const val DEFAULT_CLIPBOARD_IMAGE_MAX_MB = 10
        const val MIN_CLIPBOARD_IMAGE_MAX_MB = 1
        const val MAX_CLIPBOARD_IMAGE_MAX_MB = 50

        /** The image size slider's stops, in megabytes. */
        val IMAGE_SIZE_STEPS: List<Int> = listOf(1, 2, 5, 10, 20, 50)

        /** The step nearest [value], for putting a stored number back on a slider. */
        fun nearestStep(steps: List<Int>, value: Int): Int {
            var best = 0
            for (index in steps.indices) {
                if (kotlin.math.abs(steps[index] - value) <
                    kotlin.math.abs(steps[best] - value)
                ) {
                    best = index
                }
            }
            return best
        }

        /**
         * How much one-sided evidence [lock] wants before the other dictionaries stop being
         * consulted, or zero to never stop. Evidence is counted in words exactly one active
         * dictionary knows, decayed per word written.
         */
        fun languageLockEvidence(lock: Int): Float = when (lock) {
            LANGUAGE_LOCK_OFF -> 0f
            LANGUAGE_LOCK_PATIENT -> 3.4f
            LANGUAGE_LOCK_QUICK, LANGUAGE_LOCK_STRICT -> 0.9f
            else -> 1.8f
        }

        /** Whether an undecided detector falls back to one dictionary instead of all of them. */
        fun languageLockStrict(lock: Int): Boolean = lock == LANGUAGE_LOCK_STRICT

        /**
         * The multiplier a [learningSpeed] applies to how fast the personal model gains ground,
         * for words and phrases alike.
         */
        fun learningSpeedFactor(speed: Int): Float = when (speed) {
            LEARNING_CAUTIOUS -> 0.35f
            LEARNING_IMMEDIATE -> 3f
            else -> 1f
        }

        /** How much [radialMenuSize] scales the ring's base radius. */
        fun radialSizeScale(size: Int): Float = when (size) {
            RADIAL_SIZE_SMALL -> 0.75f
            RADIAL_SIZE_LARGE -> 1.3f
            else -> 1f
        }

        /** Longest language code accepted from the stored file. */
        const val MAX_LANGUAGE_TAG = 16

        /**
         * The range [suggestionCount] is clamped to; [MAX_SUGGESTIONS] also sizes
         * `SuggestionStripView`'s buffers.
         */
        const val MIN_SUGGESTIONS = 3
        const val MAX_SUGGESTIONS = 8
        const val DEFAULT_SUGGESTIONS = 3

        /** The range [radialSuggestionCount] is clamped to. */
        const val MIN_RADIAL_SUGGESTIONS = 3
        const val MAX_RADIAL_SUGGESTIONS = 6
        const val DEFAULT_RADIAL_SUGGESTIONS = 5

        /**
         * The range [radialPauseDwellMillis] is clamped to, in milliseconds; every bound is a
         * multiple of 100, the settings screen's step. At `0` the ring opens as soon as
         * [radialMinPathLetters] is crossed.
         */
        const val MIN_RADIAL_PAUSE_DWELL_MILLIS = 0
        const val MAX_RADIAL_PAUSE_DWELL_MILLIS = 2000
        const val DEFAULT_RADIAL_PAUSE_DWELL_MILLIS = 200

        /** The range [radialMinPathLetters] is clamped to; `0` means no minimum. */
        const val MIN_RADIAL_MIN_PATH_LETTERS = 0f
        const val MAX_RADIAL_MIN_PATH_LETTERS = 3f
        const val DEFAULT_RADIAL_MIN_PATH_LETTERS = 1f

        /** The range [radialPickTimeoutMillis] is clamped to, in milliseconds, multiples of 100. */
        const val MIN_RADIAL_PICK_TIMEOUT_MILLIS = 500
        const val MAX_RADIAL_PICK_TIMEOUT_MILLIS = 3000
        const val DEFAULT_RADIAL_PICK_TIMEOUT_MILLIS = 1200

        /** [radialMenuAnchor] values. */
        const val RADIAL_ANCHOR_FINGER = 0
        const val RADIAL_ANCHOR_CENTER = 1
        const val RADIAL_ANCHOR_TANGENT_LEFT = 2
        const val RADIAL_ANCHOR_TANGENT_RIGHT = 3

        /** [radialMenuSize] values. */
        const val RADIAL_SIZE_SMALL = 0
        const val RADIAL_SIZE_MEDIUM = 1
        const val RADIAL_SIZE_LARGE = 2

        /** [autoSpaceHabit] values. */
        const val AUTO_SPACE_SWALLOW_FIRST = 0
        const val AUTO_SPACE_SWALLOW_ALL = 1
        const val AUTO_SPACE_KEEP = 2

        /** [correctionDistance] values. */
        const val CORRECTION_DISTANCE_STRICT = 0
        const val CORRECTION_DISTANCE_NORMAL = 1
        const val CORRECTION_DISTANCE_LOOSE = 2

        /** [radialTimeoutDefault] values. */
        const val RADIAL_TIMEOUT_APPLY_TOP = 0
        const val RADIAL_TIMEOUT_CANCEL = 1

        /** [radialTrustedWord] values. */
        const val RADIAL_TRUSTED_CHIP = 0
        const val RADIAL_TRUSTED_AUTO_APPLY = 1

        /** The range [minCorrectionLength] is clamped to. */
        const val MIN_CORRECTION_LENGTH = 1
        const val MAX_CORRECTION_LENGTH = 5

        /** The range [correctionStrictness] is clamped to, and the value "Reset" restores. */
        const val MIN_CORRECTION_STRICTNESS = 0.5f
        const val MAX_CORRECTION_STRICTNESS = 2.0f
        const val DEFAULT_CORRECTION_STRICTNESS = 1.0f

        /** The range [assistTemperature] is clamped to, and the value "Reset" restores. */
        const val MIN_ASSIST_TEMPERATURE = 0.1f
        const val MAX_ASSIST_TEMPERATURE = 1.5f
        const val DEFAULT_ASSIST_TEMPERATURE = 0.3f

        /** The range [assistTopP] is clamped to, and the value "Reset" restores. */
        const val MIN_ASSIST_TOP_P = 0.1f
        const val MAX_ASSIST_TOP_P = 1.0f
        const val DEFAULT_ASSIST_TOP_P = 0.9f

        /** [themeMode] values. */
        const val THEME_MODE_MANUAL = 0
        const val THEME_MODE_AUTO_SYSTEM = 1

        const val MIN_HEIGHT_SCALE = 0.65f
        const val MAX_HEIGHT_SCALE = 1.6f
        const val MIN_WIDTH_SCALE = 0.55f

        /** The width a full-width keyboard takes when it leaves the dock. */
        const val ONE_HANDED_WIDTH_SCALE = 0.82f

        /** A width treated as full. */
        const val NEARLY_FULL_WIDTH = 0.98f
        const val MAX_BOTTOM_OFFSET_DP = 220f

        /** A floating keyboard's own reach either side of centre, in dp. */
        const val MIN_HORIZONTAL_OFFSET_DP = -160f
        const val MAX_HORIZONTAL_OFFSET_DP = 160f
    }
}

object KeyboardPreferencesSerializer : Serializer<KeyboardPreferences> {

    private val json = PERSISTED_JSON

    override val defaultValue: KeyboardPreferences = KeyboardPreferences()

    override suspend fun readFrom(input: InputStream): KeyboardPreferences {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) {
            return defaultValue
        }
        return try {
            json.decodeFromString(KeyboardPreferences.serializer(), bytes.decodeToString())
                .sanitised()
        } catch (error: SerializationException) {
            throw CorruptionException("the keyboard preferences file could not be parsed", error)
        } catch (error: IllegalArgumentException) {
            throw CorruptionException("the keyboard preferences file is not valid UTF-8", error)
        }
    }

    override suspend fun writeTo(t: KeyboardPreferences, output: OutputStream) {
        output.write(json.encodeToString(KeyboardPreferences.serializer(), t).encodeToByteArray())
    }
}
