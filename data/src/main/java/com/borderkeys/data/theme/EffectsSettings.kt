// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * How often an effect plays for the same word, stored by [id]; an id this build does not know
 * reads back as the default.
 */
enum class EffectFrequency(val id: Int) {

    /** Only the first time this keyboard has anything to say about that word. */
    FirstTime(1),

    /** Every time it happens. */
    EveryTime(2);

    companion object {
        /** The frequency [id] names, or null when this build has no such case. */
        fun fromId(id: Int): EffectFrequency? = entries.firstOrNull { it.id == id }

        /** What an effect does when nothing has been chosen, and when the stored id is unknown. */
        val DEFAULT = EveryTime
    }
}

/**
 * One event's effect: how it moves, what colour it is, and how often it plays. [style] is an
 * `EffectStyle` name, empty for off; [colour] is an ARGB value, or 0 for the theme's label colour.
 */
@Serializable
data class EffectSetting(
    val style: String = OFF,
    val frequencyId: Int = DEFAULT_FREQUENCY_ID,
    val colour: Int = 0,
) {
    /** Whether this event shows anything at all. */
    val enabled: Boolean get() = style.isNotEmpty()

    /** [frequencyId] resolved, falling back when the file names a case this build dropped. */
    val frequency: EffectFrequency
        get() = EffectFrequency.fromId(frequencyId) ?: EffectFrequency.DEFAULT

    /** [frequency]'s counterpart: the same field, written. */
    fun withFrequency(frequency: EffectFrequency): EffectSetting = copy(frequencyId = frequency.id)

    companion object {
        /** No style chosen: the event passes silently. */
        const val OFF = ""

        /** The style a new effect starts as. */
        const val DEFAULT_STYLE = "RiseFade"

        /** [EffectFrequency.DEFAULT]'s id, as a constant; EffectsSettingsTest checks they agree. */
        const val DEFAULT_FREQUENCY_ID = 2
    }
}

/**
 * Every animation the app plays, and [enabled], the one switch that stops all of them. Of the
 * event effects only [swipeAccepted] is on by default.
 */
@Serializable
data class EffectsSettings(
    /** Read only when [mode] is absent, from a file written before it: off, or as Android says. */
    val enabled: Boolean = true,
    /** [MODE_OFF], [MODE_SYSTEM] or [MODE_ON]; null in a file written before it existed. */
    val mode: Int? = null,
    val swipeAccepted: EffectSetting = EffectSetting(style = EffectSetting.DEFAULT_STYLE),
    val learnedWord: EffectSetting = EffectSetting(),
    val autocorrectApplied: EffectSetting = EffectSetting(),
    val correctionReverted: EffectSetting = EffectSetting(),
    val suggestionPicked: EffectSetting = EffectSetting(),
    /** Whether a photo pasted from a strip chip rises out of it in a lamp's shape, fading. */
    val photoLamp: Boolean = false,
    /** Whether a pressed key's highlight fades in and out rather than switching at once. */
    val keyPress: Boolean = true,
    /** Whether the assistant's colours move, on the home title and around the draft box. */
    val assistantColours: Boolean = true,
    /** Whether the draft box moves: version slides, the working pulse, hints and scrollbar fades. */
    val draftBoxMotion: Boolean = true,
    /** Whether a label too long for its button scrolls past, on the bar and the quick panel. */
    val scrollingLabels: Boolean = true,
    /** How fast every animation runs, [DEFAULT_SPEED] the default; clamped on read. */
    val speed: Float = DEFAULT_SPEED,
) {
    /** Whether anything moves: never, as Android's own animation setting says, or always. */
    val animationMode: Int
        get() = mode ?: if (enabled) MODE_SYSTEM else MODE_OFF

    /** Whether the page's switches apply at all: the mode is not off. */
    val anyOn: Boolean
        get() = animationMode != MODE_OFF

    /** Whether animations play now, given whether Android's own are [systemAnimated]. */
    fun plays(systemAnimated: Boolean): Boolean = when (animationMode) {
        MODE_OFF -> false
        MODE_ON -> true
        else -> systemAnimated
    }

    /** The setting for [event]. */
    fun forEvent(event: EffectEvent): EffectSetting = when (event) {
        EffectEvent.SwipeAccepted -> swipeAccepted
        EffectEvent.LearnedWord -> learnedWord
        EffectEvent.AutocorrectApplied -> autocorrectApplied
        EffectEvent.CorrectionReverted -> correctionReverted
        EffectEvent.SuggestionPicked -> suggestionPicked
    }

    /** [forEvent]'s counterpart: the same field, written. */
    fun withEvent(event: EffectEvent, setting: EffectSetting): EffectsSettings = when (event) {
        EffectEvent.SwipeAccepted -> copy(swipeAccepted = setting)
        EffectEvent.LearnedWord -> copy(learnedWord = setting)
        EffectEvent.AutocorrectApplied -> copy(autocorrectApplied = setting)
        EffectEvent.CorrectionReverted -> copy(correctionReverted = setting)
        EffectEvent.SuggestionPicked -> copy(suggestionPicked = setting)
    }

    companion object {
        /** [mode] values: nothing moves; as Android's own animation setting says; always. */
        const val MODE_OFF = 0
        const val MODE_SYSTEM = 1
        const val MODE_ON = 2

        const val DEFAULT_SPEED = 1f
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        /** The speed dial's stops between its ends: a quarter apart. */
        const val SPEED_STEPS = 14
    }
}

/** The moments the keyboard has something to say. */
enum class EffectEvent {
    SwipeAccepted,
    LearnedWord,
    AutocorrectApplied,
    CorrectionReverted,
    SuggestionPicked,
}
