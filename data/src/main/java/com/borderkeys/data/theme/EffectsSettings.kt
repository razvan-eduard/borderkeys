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
    val enabled: Boolean = true,
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
) {
    /** Whether a pressed key's highlight fades. */
    val keyPressAnimated: Boolean get() = enabled && keyPress

    /** Whether the assistant's colours move. */
    val assistantColoursAnimated: Boolean get() = enabled && assistantColours

    /** Whether the draft box moves. */
    val draftBoxAnimated: Boolean get() = enabled && draftBoxMotion

    /** Whether a pasted photo rises out of its chip. */
    val photoLampAnimated: Boolean get() = enabled && photoLamp

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
}

/** The moments the keyboard has something to say. */
enum class EffectEvent {
    SwipeAccepted,
    LearnedWord,
    AutocorrectApplied,
    CorrectionReverted,
    SuggestionPicked,
}
