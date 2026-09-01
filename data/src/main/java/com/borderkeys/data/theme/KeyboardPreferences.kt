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
        fun learningSpeedFactor(speed: Int): Float = when (speed) {
            LEARNING_CAUTIOUS -> 0.35f
            LEARNING_IMMEDIATE -> 3f
            else -> 1f
        }

        /**
         * Below three the strip stops being a choice and becomes an announcement; above eight
         * the slots are narrower than a fingertip on any phone this runs on.
         */
        /** Longest language code accepted from the stored file: `pt-BR` and friends fit easily. */
        const val MAX_LANGUAGE_TAG = 16

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
