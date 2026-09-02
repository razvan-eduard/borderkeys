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
) {
    fun sanitised(): KeyboardPreferences = copy(
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
        languageLock = if (languageLock in LANGUAGE_LOCK_OFF..LANGUAGE_LOCK_QUICK) {
            languageLock
        } else {
            LANGUAGE_LOCK_BALANCED
        },
        // Read through the enum, which drops ids no build knows, then bounded: a stored file is
        // not a trusted file, and a bar of four hundred buttons is a bar with no buttons on it.
        quickActions = QuickAction.fromIds(quickActions).take(MAX_QUICK_ACTIONS).map { it.id },
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
        val narrowing = positionMode == MODE_DOCKED && mode != MODE_DOCKED && widthScale == 1f
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
            LANGUAGE_LOCK_QUICK -> 0.9f
            else -> 1.8f
        }

        fun learningSpeedFactor(speed: Int): Float = when (speed) {
            LEARNING_CAUTIOUS -> 0.35f
            LEARNING_IMMEDIATE -> 3f
            else -> 1f
        }

        /** Longest language code accepted from the stored file: `pt-BR` and friends fit easily. */
        const val MAX_LANGUAGE_TAG = 16

        /**
         * Below three the strip stops being a choice and becomes an announcement; above eight
         * the slots are narrower than a fingertip on any phone this runs on.
         */
        const val MIN_SUGGESTIONS = 3
        const val MAX_SUGGESTIONS = 8
        const val DEFAULT_SUGGESTIONS = 3

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
