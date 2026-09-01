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
        bottomOffsetDp = bottomOffsetDp.coerceIn(0f, MAX_BOTTOM_OFFSET_DP),
        horizontalOffsetDp = horizontalOffsetDp.coerceIn(-160f, 160f),
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
