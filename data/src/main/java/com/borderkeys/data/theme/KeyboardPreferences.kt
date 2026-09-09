// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Behaviour the user can change, as opposed to appearance, which is [KeyboardTheme].
 *
 * Every default here is the conservative one. Anything that records more about the user than the
 * feature strictly needs starts off, and turning it on is an explicit act with an explanation
 * next to it -- which is the only honest way to ship a feature like [perAppLanguageMemory].
 */
@Serializable
data class KeyboardPreferences(
    /** Minutes an unpinned clipboard entry survives. */
    val clipboardRetentionMinutes: Int = 60,
    val clipboardEnabled: Boolean = true,

    /**
     * Whether copied images are remembered alongside copied text.
     *
     * Off, although the history around it is on. A screenshot is the most revealing thing a
     * clipboard holds -- a message, a balance, a code, a face -- and the difference between
     * remembering a word and remembering a picture of a screen is large enough that it should
     * be asked for rather than assumed.
     *
     * Turning it off deletes the images already remembered, because a switch that stops
     * collecting but keeps what it collected is not the switch anyone thought they turned off.
     */
    val clipboardImages: Boolean = false,
    /** Hard cap on unpinned history, independent of the retention window. */
    val clipboardMaxEntries: Int = 60,
    /** Whether confirmed words are written to the personal dictionary at all. */
    val learningEnabled: Boolean = true,

    /**
     * How readily what you write starts to outrank what the dictionary says.
     *
     * One of the LEARNING_ constants below. It does not change *what* is recorded -- every
     * confirmed word and pair is stored either way -- only how quickly the record starts
     * leading the suggestion strip.
     *
     * The reason it is a setting rather than a constant is that the right answer is a matter of
     * taste and nothing else. Someone who writes the same few phrases all day wants the first
     * repetition to count. Someone who writes about many things wants a keyboard that does not
     * rearrange itself around a sentence they wrote once. Neither is wrong, and picking one for
     * both is how a keyboard ends up feeling either stubborn or twitchy.
     */
    val learningSpeed: Int = LEARNING_BALANCED,
    val swipeEnabled: Boolean = true,
    /**
     * Off, and it stays off unless the user says otherwise.
     *
     * Remembering which languages are used in which app means storing a hash of the target
     * package name against learned weights. That is a behavioural profile, however small and
     * however local -- so it is opt-in, the hash is stored rather than the package name, and
     * Settings can delete it.
     */
    val perAppLanguageMemory: Boolean = false,
    val hapticFeedback: Boolean = true,

    /**
     * Whether a keypress makes a sound.
     *
     * Off. The system has its own keypress sound setting and this respects it when both are
     * on, but a keyboard that starts making noise on a phone that was quiet is a keyboard
     * someone has to go and switch off.
     */
    val keySound: Boolean = false,

    /**
     * Whether the first letter of a sentence is capitalised for you.
     *
     * On, and read from the editor rather than assumed: a field can ask for every sentence,
     * every word, or every character capitalised, and all three are honoured. A field that
     * asks for none of them -- a password, a URL -- gets none.
     */
    val autoCapitalise: Boolean = true,

    /**
     * Whether two spaces become a full stop and a space.
     *
     * On, and undone by the backspace that follows it, like any other substitution this
     * keyboard makes.
     */
    val doubleSpacePeriod: Boolean = true,

    /**
     * Whether a space is added after a full stop, comma or the rest of the sentence marks.
     *
     * On. It is the space you were going to type, and it is what makes removing one before the
     * next mark worth doing -- the two settings are halves of the same idea and both are here
     * so either can be switched off alone.
     */
    val spaceAfterPunctuation: Boolean = true,

    /**
     * Whether a space before a punctuation mark is removed when the mark is typed.
     *
     * On. "word ." is not something anyone means, and it is what a keyboard that adds spaces
     * after words produces if it does not also take them back.
     */
    val removeSpaceBeforePunctuation: Boolean = true,

    /**
     * Whether sliding along the space bar moves the cursor.
     *
     * On. It costs nothing when unused -- the slide has to travel further than a tap ever
     * does before it counts -- and it is the only way to place a cursor precisely without
     * covering the text with a finger.
     */
    val spaceCursorControl: Boolean = true,

    /**
     * The emoji used most recently, newest first.
     *
     * Stored with the settings rather than in the encrypted database: it is a list of pictures
     * someone sent, not of words they wrote, and the database exists for the second.
     */
    val emojiRecents: List<String> = emptyList(),

    /**
     * Whether the emoji key sits beside the space bar.
     *
     * On. Off removes it rather than hiding it, and the width it took goes back to the space
     * bar -- which is where it came from, and which is the key most worth having wide.
     */
    val emojiKey: Boolean = true,

    /**
     * Whether the globe key sits beside the space bar.
     *
     * Off. It cycles this keyboard's layouts, which is a thing some people do daily and most
     * never do at all, and it was also a second way into the panel -- which holding the enter
     * key now does. Holding the space bar cycles the layouts, so nothing is lost by giving the
     * key's width back to the space bar.
     */
    val languageKey: Boolean = false,

    // ---- size and position -------------------------------------------------------------
    //
    // A keyboard is the one part of the screen a person's thumb has to reach a hundred times a
    // minute, and whose right size depends on the hand holding the phone rather than on the
    // phone. These are the settings that let it be moved rather than endured.

    /** Multiplier on the row height. Larger keys, fewer of them on screen. */
    val heightScale: Float = 1f,
    /**
     * Fraction of the screen width the keyboard occupies. Only meaningful away from
     * [MODE_DOCKED], where a keyboard narrower than the screen would just leave a gap.
     */
    val widthScale: Float = 1f,
    /** One of the MODE_ constants below. */
    val positionMode: Int = MODE_DOCKED,
    /** How far the keyboard sits above the bottom edge, in dp. */
    val bottomOffsetDp: Float = 0f,
    /** Horizontal offset from centre, in dp. Floating mode only. */
    val horizontalOffsetDp: Float = 0f,

    /**
     * Whether the empty strip beside a narrowed keyboard offers an arrow to move it across.
     *
     * On, because the alternative for a left-handed moment on a right-handed setting is opening
     * settings with the hand that cannot reach. The arrow appears only where there is empty
     * space to put it, which is only when the keyboard has been narrowed.
     */
    val edgeArrows: Boolean = true,

    /**
     * Whether what shows through beside a narrowed keyboard is blurred.
     *
     * Off, and not because it looks worse. A background blur is composited by the system on
     * every frame the window is visible, which on a keyboard means most of the time the screen
     * is on, and it is the kind of cost that does not appear in any number this project
     * measures. Someone who wants it can have it; nobody gets it without asking.
     *
     * The system can also refuse: cross-window blur is disabled on low-end devices and in
     * battery saver, and asking for it there does nothing at all rather than falling back to
     * something slower.
     */
    val blurBehindKeyboard: Boolean = false,

    /**
     * The language the interface is written in, as a catalogue code, or empty to follow the
     * phone.
     *
     * Empty by default, and empty is not the same as "en": following the phone means a phone
     * later switched to a language BorderKeys ships picks it up, where a stored "en" would
     * stay English forever. What the phone asks for and what is shipped are reconciled by
     * LanguageResolution, which falls back to English only when nothing else matches.
     *
     * This is the interface language and nothing else. Which dictionaries predict words is a
     * separate setting on the Languages screen, because the two are genuinely independent:
     * plenty of people read an English interface while writing Romanian.
     */
    val uiLanguage: String = "",

    /**
     * How readily the keyboard stops offering words from the languages you are not writing in.
     *
     * On by default, and balanced rather than quick: a wrong guess is worse than a slow one,
     * because it removes words rather than adding them. Off is a real choice -- someone who
     * writes two languages inside one sentence is not served by the keyboard picking a side.
     *
     * What you have written yourself is never filtered by this. A phrase you repeat is
     * evidence about you, which outranks any guess about the sentence.
     */
    val languageLock: Int = LANGUAGE_LOCK_BALANCED,

    /**
     * Whether the first slot of the suggestion strip offers what is on the clipboard.
     *
     * Off by default, and not out of caution about the feature: the strip is glanced at while
     * typing, and putting something there that inserts text nobody just wrote is a change to
     * what that row means. Someone who wants it can have it.
     *
     * It takes one of the slots rather than adding one, so turning it on does not narrow every
     * target on the row. Suppressed entirely in a password field, along with everything else.
     */
    val clipboardSuggestion: Boolean = false,

    /**
     * Whether the clipboard is emptied after its content is inserted from the chip.
     *
     * Off by default: the clipboard belongs to the system and to every other app, and a
     * keyboard that quietly empties it is a keyboard that loses someone's copied text when
     * they meant to paste it twice. On, it is a reasonable hygiene setting for anyone who
     * copies things they would rather not leave lying there.
     */
    val clearClipboardAfterInsert: Boolean = false,

    /**
     * Whether the clipboard chip is withdrawn after it has been used, or once the keyboard has
     * been closed.
     *
     * On. Something copied is usually pasted once, and a chip that stays for the rest of the
     * session is a slot the suggestions could have had. It withdraws the *offer* only: what was
     * copied is still in the history panel, and the system clipboard is untouched -- the switch
     * for emptying that is the one below.
     */
    val clipboardSuggestionOnce: Boolean = true,

    /**
     * Whether inserting the current clipboard item also deletes it from the history panel, if it
     * is not pinned.
     *
     * Off by default, and separate from every other clipboard switch here: [clipboardSuggestionOnce]
     * only withdraws the chip's *offer*, [clearClipboardAfterInsert] only empties the *system*
     * clipboard, and the retention timer expires whatever is old regardless of whether it was
     * ever used. This is the one that removes the row itself, and only that one row -- the rest
     * of the history is untouched -- the moment the item is actually pasted. For a one-time code
     * or a password copied to hand off once: used, then gone, rather than sitting in an encrypted
     * table until its timer or a manual delete catches up with it.
     */
    val clipboardDeleteAfterUse: Boolean = false,

    /**
     * Whether the clipboard history is emptied when the keyboard closes.
     *
     * Off, and a much blunter instrument than the retention timer beside it: everything
     * unpinned goes the moment you leave the field, whether it was copied a second ago or an
     * hour. For someone who wants the history while they are writing and nothing afterwards.
     */
    val clearClipboardOnClose: Boolean = false,

    /**
     * Whether the Compose quick action can open the draft box at all.
     *
     * On. It costs nothing while the box is not open, and turning it off is for someone who
     * would rather the quick actions row not offer a way out to a separate screen at all.
     */
    val composerEnabled: Boolean = true,

    /**
     * The buttons on the draft box's control bar, in order, as [ComposerAction] ids.
     *
     * Ids rather than ordinals, and read back through the enum, for the same reason the quick
     * actions are: a bar written by a later build must open rather than fail.
     */
    val composerBar: List<Int> = ComposerAction.DEFAULT.map { it.id },

    /** Instructions the user wrote and kept, in the order they were saved. */
    val savedPrompts: List<SavedPrompt> = emptyList(),

    /** Whether the row of quick actions is shown at all. */
    val quickActionsEnabled: Boolean = false,

    /**
     * The actions on the bar, in order, as [QuickAction] ids.
     *
     * Ids rather than ordinals so that removing an action from the enum later does not turn
     * someone's saved bar into a different bar; an id this build does not know is dropped when
     * the list is read.
     */
    val quickActions: List<Int> = QuickAction.DEFAULT.map { it.id },

    /** Whether the bar starts open or as a single button that opens it. */
    val quickActionsMode: Int = QUICK_ACTIONS_COLLAPSED,

    /** Which edge the bar, and the button that stands in for it, sit against. */
    val quickActionsPlacement: Int = QUICK_ACTIONS_ABOVE_STRIP,

    /**
     * A permanent row of digits above the letters.
     *
     * Off by default. It costs about a fifth of the keyboard's height, and on a touch surface
     * key size is accuracy -- so it is a choice, and the digits are reachable by long press
     * either way.
     */
    val numberRow: Boolean = false,
    /** Switch to a numeric keypad automatically in numeric and phone fields. */
    val numericKeypad: Boolean = true,
    val showSuggestionStrip: Boolean = true,

    /**
     * How many suggestions the strip offers at once.
     *
     * Three by default, and more is not obviously better: the strip is a fixed width, so every
     * extra slot makes each one narrower and each target smaller. Eight fits on a phone only
     * because most words are short. The right number depends on how wide the screen is and how
     * accurate the person's thumb is, which is why it is a setting and not a constant.
     */
    val suggestionCount: Int = DEFAULT_SUGGESTIONS,

    /**
     * Whether a suggestion may be two words rather than one.
     *
     * Off by default. It offers "vreau să" where it would otherwise offer "vreau", but only from
     * phrases this person has written repeatedly -- never from the dictionary, because frequency
     * can chain any two common pairs into something grammatical and meaningless.
     *
     * The second word is held to twice the evidence of the first, so a two-word suggestion needs
     * about four repetitions where a one-word one needs two. Twice the evidence for twice the
     * guess: a wrong single word costs a glance, a wrong pair costs the glance and the suspicion
     * that the keyboard is inventing things.
     */
    val phraseSuggestions: Boolean = false,

    /**
     * Whether a delimiter applies the leading suggestion instead of committing what was typed.
     *
     * Off, and the default is the argument. A keyboard that rewrites what you wrote because it
     * has a better idea is the failure mode this project was written against, and the ordinary
     * behaviour -- space commits your letters, a suggestion is applied only when tapped -- is
     * the one that never surprises anyone.
     *
     * It is offered anyway because the objection to autocorrect is really an objection to
     * *irreversible* autocorrect: the correction lands, the sentence moves on, and undoing it
     * costs more keystrokes than typing it did. With [revertCorrectionOnBackspace] the next
     * backspace puts back exactly what was typed, so the cost of a wrong correction is one key.
     */
    val autoCorrectOnSpace: Boolean = false,

    /**
     * Whether the backspace immediately after an applied correction restores what was typed.
     *
     * Only meaningful with [autoCorrectOnSpace], and on by default because a correction that
     * cannot be taken back in one key is the thing worth refusing. Turning this off leaves
     * backspace deleting one character at a time, which is what it does everywhere else.
     */
    val revertCorrectionOnBackspace: Boolean = true,

    /**
     * The shortest word a delimiter will replace.
     *
     * Three by default: one- and two-letter words are where a correction is least likely to be
     * right and most annoying when it is not -- half the alphabet is one edit away from "a" or
     * "la", and the strip is full of them. Someone typing a language with a lot of short real
     * words can raise it; someone who wants every word considered can lower it to one.
     */
    val minCorrectionLength: Int = 3,

    /**
     * How much evidence an edit needs before it outranks a word spelled exactly as typed.
     *
     * 1.0 is the engine's own calibrated default. Below it, a correction needs a smaller
     * frequency gap to win -- more of what is typed gets corrected, including some that should
     * not have been. Above it, the gap has to be bigger -- fewer corrections, and the ones that
     * still happen are closer to certain. It does not change which words exist, only how
     * cautious the strip is about preferring one spelling over another.
     */
    val correctionStrictness: Float = DEFAULT_CORRECTION_STRICTNESS,

    /**
     * How far the text assistant's model may wander from the single most likely next word.
     *
     * `plus` only -- read by [com.borderkeys.assist.TextAssistService], not by anything in this
     * module. Kept here rather than in a separate store because it is a preference like any
     * other on this screen, not model state: it survives across models and is applied to
     * whichever one is loaded next.
     */
    val assistTemperature: Float = DEFAULT_ASSIST_TEMPERATURE,

    /** The nucleus (top-p) the same model samples from. See [assistTemperature]. */
    val assistTopP: Float = DEFAULT_ASSIST_TOP_P,

    /**
     * Whether the keyboard's palette follows the phone's wallpaper instead of the theme's own
     * stored colours.
     *
     * Off by default, and deliberately not a preset: turning it on does not overwrite the
     * colours in [KeyboardTheme] on disk, it only changes which ones the draw path reads for as
     * long as this stays on. Turning it back off is what gets the theme's own colours back
     * exactly as they were, not a preset applied on top of them. See
     * `com.borderkeys.theme.DynamicColors`, which is where the actual reading happens -- this
     * flag lives here rather than on [KeyboardTheme] because it is a mode, not a colour, and
     * [KeyboardTheme] is deliberately nothing but colours and shape.
     */
    val followSystemColors: Boolean = false,

    /**
     * Whether the keyboard switches between [KeyboardTheme] and the separate light theme on its
     * own, following the phone's own dark/light setting, rather than always showing whichever
     * one was picked by hand.
     *
     * [THEME_MODE_MANUAL] by default: the single stored theme, exactly as today. In
     * [THEME_MODE_AUTO_SYSTEM], [KeyboardTheme] is shown when the system is in dark mode and
     * `ThemeRepository.lightTheme` when it is not -- two themes a person can each customise on
     * their own screen, switched between rather than one theme algorithmically inverted, because
     * a keyboard's colours are a choice and dark-mode CSS tricks on somebody's carefully picked
     * palette produce a theme nobody picked. Composes with [followSystemColors]: which of the
     * two themes is showing is decided first, dynamic colours are layered on top of it second,
     * the same order the resolving code applies them in.
     */
    val themeMode: Int = THEME_MODE_MANUAL,
) {
    fun sanitised(): KeyboardPreferences = copy(
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
        themeMode = if (themeMode == THEME_MODE_AUTO_SYSTEM) THEME_MODE_AUTO_SYSTEM else THEME_MODE_MANUAL,
        clipboardRetentionMinutes = clipboardRetentionMinutes.coerceIn(1, 60 * 24 * 30),
        clipboardMaxEntries = clipboardMaxEntries.coerceIn(1, 1000),
        // Clamped for the same reason the theme's dimensions are: a file that parses is not a
        // file that makes sense, and a keyboard scaled to zero is one the user cannot reach the
        // settings through.
        heightScale = heightScale.coerceIn(MIN_HEIGHT_SCALE, MAX_HEIGHT_SCALE),
        widthScale = widthScale.coerceIn(MIN_WIDTH_SCALE, 1f),
        positionMode = if (positionMode in MODE_DOCKED..MODE_FLOATING) positionMode else MODE_DOCKED,
        suggestionCount = suggestionCount.coerceIn(MIN_SUGGESTIONS, MAX_SUGGESTIONS),
        learningSpeed = if (learningSpeed in LEARNING_CAUTIOUS..LEARNING_IMMEDIATE) {
            learningSpeed
        } else {
            LEARNING_BALANCED
        },
        bottomOffsetDp = bottomOffsetDp.coerceIn(0f, MAX_BOTTOM_OFFSET_DP),
        horizontalOffsetDp = horizontalOffsetDp.coerceIn(-160f, 160f),
        // A language code, not free text. Bounded so a corrupt file cannot carry an arbitrarily
        // long string into every lookup; an unknown code resolves to English anyway.
        uiLanguage = uiLanguage.take(MAX_LANGUAGE_TAG),
        languageLock = if (languageLock in LANGUAGE_LOCK_OFF..LANGUAGE_LOCK_STRICT) {
            languageLock
        } else {
            LANGUAGE_LOCK_BALANCED
        },
        // Read through the enum, which drops ids no build knows, then bounded: a stored file is
        // not a trusted file, and a bar of four hundred buttons is a bar with no buttons on it.
        quickActions = QuickAction.fromIds(quickActions).take(MAX_QUICK_ACTIONS).map { it.id },
        composerBar = ComposerAction.fromIds(composerBar).map { it.id },
        // Bounded on the way in as well as on the way out. These are written by the user, so
        // the file is as trustworthy as the rest of it -- which is to say bounded and read back
        // rather than trusted.
        savedPrompts = savedPrompts
            .filter { it.name.isNotBlank() && it.text.isNotBlank() }
            .map {
                it.copy(
                    name = it.name.take(SavedPrompt.MAX_NAME_CHARS),
                    text = it.text.take(SavedPrompt.MAX_TEXT_CHARS),
                )
            }
            .take(SavedPrompt.MAX_SAVED),
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
    )

    val isOneHanded: Boolean
        get() = positionMode == MODE_ONE_HANDED_LEFT || positionMode == MODE_ONE_HANDED_RIGHT

    /**
     * Moves to [mode], narrowing the keyboard the first time it leaves the dock.
     *
     * Without the narrowing, choosing "one-handed" while the width is still 100% changes nothing
     * at all: the mode is set, the keyboard is pushed to a side it already fills, and the
     * feature reads as broken. So the first departure from the dock also picks a width that a
     * thumb can cross, and every later change is left alone -- a user who has already set 70% or
     * deliberately gone back to 100% keeps what they chose.
     */
    fun withPositionMode(mode: Int): KeyboardPreferences {
        // "Effectively full width" rather than exactly 1, because the resize handles leave
        // whatever the finger stopped at -- 0.99 after a drag to the edge is a keyboard the
        // user thinks is full width, and it should still narrow when they go one-handed.
        val narrowing = positionMode == MODE_DOCKED && mode != MODE_DOCKED &&
            widthScale >= NEARLY_FULL_WIDTH
        // The width survives the move. It is one value across every mode now that the resize
        // handles honour it in the dock as well, so docking is a change of position and
        // nothing else; a keyboard that is too narrow is widened by dragging its edge.
        return copy(
            positionMode = mode,
            widthScale = if (narrowing) ONE_HANDED_WIDTH_SCALE else widthScale,
        )
    }

    companion object {
        /** Full width, flush with the bottom edge. What a keyboard normally is. */
        const val MODE_DOCKED = 0

        /**
         * Narrowed and pushed to one side, so every key is inside a thumb's arc.
         *
         * Left and right are separate modes rather than a handedness flag because people switch
         * hands: the setting is "where the keyboard is now", not "which hand you have".
         */
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2

        /** Lifted off the bottom edge and movable, for a large screen or a split view. */
        const val MODE_FLOATING = 3

        /**
         * Several repetitions before a word or phrase leads. For someone who writes about many
         * things and does not want the keyboard rearranged by one sentence.
         */
        const val LEARNING_CAUTIOUS = 0

        /** The default. A phrase written twice starts to lead. */
        const val LEARNING_BALANCED = 1

        /** The first time counts. For someone who writes the same things every day. */
        const val LEARNING_IMMEDIATE = 2

        /**
         * The multiplier each setting applies to how fast the personal model gains ground.
         *
         * One number rather than one per curve, so the setting means the same thing everywhere:
         * it scales both how quickly a word climbs and how many repetitions a phrase needs
         * before it leads. Two knobs that could disagree would be two knobs to explain.
         */
        /** Every dictionary is consulted for every word, whatever language the sentence is in. */
        const val LANGUAGE_LOCK_OFF = 0

        /** Waits for clear evidence -- roughly five or six telling words. */
        const val LANGUAGE_LOCK_PATIENT = 1

        /** Decides after about three words that belong to one language and no other. */
        const val LANGUAGE_LOCK_BALANCED = 2

        /** Decides on the first telling word. For someone who rarely mixes languages. */
        const val LANGUAGE_LOCK_QUICK = 3

        /**
         * Never offers a language that has not been identified.
         *
         * The others all consult every dictionary until they have decided. This one does not:
         * before anything is identified it uses the heaviest dictionary alone, and switches as
         * soon as the evidence says something else. Nothing is ever offered from a language
         * that has not been recognised in what is being written.
         */
        const val LANGUAGE_LOCK_STRICT = 4

        /**
         * How much one-sided evidence the engine wants before it stops consulting the other
         * dictionaries, or a value at or below zero to never stop.
         *
         * Evidence is counted in words that exactly one active dictionary knows, aged by 0.85
         * per word written. The numbers are therefore roughly "how many telling words", not
         * "how many words" -- most of a sentence belongs to several dictionaries at once and
         * counts for neither.
         */
        /** The bar is drawn in full, always. */
        const val QUICK_ACTIONS_FULL = 0

        /**
         * One button stands in for the bar and opens it.
         *
         * The default, because the bar competes for height with the keys, and height is
         * accuracy. Opening it costs a tap; leaving it open costs a row on every screen.
         */
        const val QUICK_ACTIONS_COLLAPSED = 1

        /** Above the suggestion strip, spanning the keyboard. */
        const val QUICK_ACTIONS_ABOVE_STRIP = 0

        /** Below the keys, against the bottom edge. */
        const val QUICK_ACTIONS_BELOW_KEYS = 1

        /** A column down the left of the keys, for a thumb that lives on that side. */
        const val QUICK_ACTIONS_LEFT = 2

        /** A column down the right. */
        const val QUICK_ACTIONS_RIGHT = 3

        /** How many actions the bar will hold before it starts dropping them. */
        const val MAX_QUICK_ACTIONS = 10

        /** How many recent emoji are kept: a row and a half on most phones. */
        const val MAX_EMOJI_RECENTS = 24

        /**
         * How long unpinned entries are kept, as the values a slider steps through.
         *
         * Steps rather than a range, because the useful span is fifteen minutes to a month and
         * a linear slider over 43,200 values cannot be aimed: every pixel would be about two
         * hours at the top and the bottom third would be unreachable. These are the answers
         * someone actually has to "how long".
         */
        val RETENTION_STEPS: List<Int> = listOf(
            15, 30, 60, 4 * 60, 12 * 60, 24 * 60,
            3 * 24 * 60, 7 * 24 * 60, 14 * 24 * 60, 30 * 24 * 60,
        )

        /**
         * How many entries are kept, as the values a slider steps through.
         *
         * The same reasoning: the difference between 60 and 61 is nothing, and the difference
         * between 10 and 200 is the whole decision.
         */
        val HISTORY_SIZE_STEPS: List<Int> = listOf(10, 20, 30, 50, 75, 100, 150, 200, 500)

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

        fun languageLockEvidence(lock: Int): Float = when (lock) {
            LANGUAGE_LOCK_OFF -> 0f
            LANGUAGE_LOCK_PATIENT -> 3.4f
            LANGUAGE_LOCK_QUICK, LANGUAGE_LOCK_STRICT -> 0.9f
            else -> 1.8f
        }

        /**
         * Whether an undecided detector falls back to one dictionary rather than to all of them.
         *
         * The difference between "wait until you know" and "never guess": every other setting
         * offers every language until it has decided, which is a sensible default and is
         * exactly what someone who writes one language does not want to see.
         */
        fun languageLockStrict(lock: Int): Boolean = lock == LANGUAGE_LOCK_STRICT

        fun learningSpeedFactor(speed: Int): Float = when (speed) {
            LEARNING_CAUTIOUS -> 0.35f
            LEARNING_IMMEDIATE -> 3f
            else -> 1f
        }

        /** Longest language code accepted from the stored file: `pt-BR` and friends fit easily. */
        const val MAX_LANGUAGE_TAG = 16

        /**
         * Below three the strip stops being a choice and becomes an announcement; above eight
         * the slots are narrower than a fingertip on any phone this runs on. [MAX_SUGGESTIONS]
         * doubles as the hard ceiling `SuggestionStripView`'s own fixed-size buffers are sized
         * for -- read from here rather than a second `8` typed in `:keyboard`, so a setting that
         * allowed more than the view can actually hold is a contradiction the compiler would
         * have to be told to create, not a number someone forgot to update twice.
         */
        const val MIN_SUGGESTIONS = 3
        const val MAX_SUGGESTIONS = 8
        const val DEFAULT_SUGGESTIONS = 3

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

        /**
         * The width the keyboard takes the first time it leaves the dock.
         *
         * Roughly a thumb's reach across a 6-inch phone held in one hand: narrow enough that the
         * far column is reachable, wide enough that the keys do not shrink below the touch
         * target the hit-testing assumes.
         */
        const val ONE_HANDED_WIDTH_SCALE = 0.82f

        /** Close enough to the full width that the user means the full width. */
        const val NEARLY_FULL_WIDTH = 0.98f
        const val MAX_BOTTOM_OFFSET_DP = 220f
    }
}

object KeyboardPreferencesSerializer : Serializer<KeyboardPreferences> {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

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
