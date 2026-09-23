// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * How often an effect plays for the same word.
 *
 * Carries an [id] and is persisted as that id, never as its name or its ordinal -- the same rule
 * [QuickAction] and [ComposerAction] already follow, and for the same reason: a value no build
 * knows is read back as the default rather than failing the whole file. A backup exists to cross
 * versions, so an enum written into one has to survive a later build that reordered or dropped a
 * case.
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
 * One event's effect: how it moves, what colour it is, and how often it plays.
 *
 * [style] is an `EffectStyle` name rather than the enum itself, because `:data` does not depend
 * on `:effects` and a stored setting outlives the build that wrote it: a style removed in a
 * later version reads back as an empty string here rather than as a crash, and an empty string
 * already means off.
 *
 * [colour] is an ARGB value, or 0 for the theme's own label colour -- the same "nothing chosen"
 * convention the particle layers use for their swatches.
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

        /** What the accepted-word animation has always used, and what a new effect starts as. */
        const val DEFAULT_STYLE = "RiseFade"

        /** [EffectFrequency.DEFAULT]'s id, spelled out because a constructor default may not
         *  read a companion of a type declared below it. Pinned by EffectsSettingsTest. */
        const val DEFAULT_FREQUENCY_ID = 2
    }
}

/**
 * Every effect the keyboard can play, and the one switch that silences all of them.
 *
 * Separate from [ParticleEffectsSettings]: particles decorate a *surface* -- the keys, the ring,
 * the strip -- while these are about an *event*, and say what happened rather than where. They
 * share the colour swatches and nothing else.
 *
 * Only [swipeAccepted] is on by default, because it is the one that already shipped. The rest
 * start off: an effect on a frequent event is a decision someone should make deliberately, not
 * something that appears after an update.
 */
@Serializable
data class EffectsSettings(
    val enabled: Boolean = true,
    val swipeAccepted: EffectSetting = EffectSetting(style = EffectSetting.DEFAULT_STYLE),
    val learnedWord: EffectSetting = EffectSetting(),
    val autocorrectApplied: EffectSetting = EffectSetting(),
    val correctionReverted: EffectSetting = EffectSetting(),
    val suggestionPicked: EffectSetting = EffectSetting(),
) {
    /** The setting for [event], or null when this build does not know that name. */
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

/**
 * The moments the keyboard has something to say.
 *
 * An enum rather than a map key, so adding one is a compiler error everywhere it has to be
 * handled -- the settings row, the two accessors above -- rather than a card that silently never
 * appears.
 */
enum class EffectEvent {
    SwipeAccepted,
    LearnedWord,
    AutocorrectApplied,
    CorrectionReverted,
    SuggestionPicked,
}
