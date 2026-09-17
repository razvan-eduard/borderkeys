// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * One whole look someone saved themselves, under a name they chose -- an [outline] and a [fill]
 * together, applied identically to every region at once so the keyboard reads as one coherent
 * theme rather than five independently-dialled-in ones. The same idea [CustomThemeEntry] already
 * is for a whole [KeyboardTheme], one level down: presets like Fire/Comet are this application's
 * own, fixed, per-layer building blocks; this is the user's own combination of them, saved once
 * and applied everywhere in one tap.
 *
 * Deliberately not region-scoped and not per-layer: an earlier shape saved Fill and Outline
 * separately and offered them per region, which answered "what does this one surface look like"
 * rather than the actual ask, "make the whole keyboard match."
 */
@Serializable
data class CustomEffectsPresetEntry(
    val id: String,
    val name: String,
    val outline: ParticleOutlineLayer,
    val fill: ParticleFillLayer,
    val createdAt: Long = 0L,
) {
    /** Read-time repair, the same reasoning as [CustomThemeEntry.sanitised]. */
    fun sanitised(): CustomEffectsPresetEntry = copy(
        name = name.take(MAX_NAME_LENGTH),
        outline = outline.sanitised(),
        fill = fill.sanitised(),
    )

    /** Whether every region in [settings] still shows exactly this pair -- what "this preset is
     *  the keyboard's current look" actually means, now that a preset is meant to reach every
     *  region: checking only whichever one region happened to be selected in the picker would
     *  say yes even after a different region had already drifted away from it. */
    fun matches(settings: ParticleEffectsSettings): Boolean {
        // Named locals, not the entry's own properties by their bare names: inside an extension
        // on ParticleRegionSettings those resolve to the *region's* outline and fill, so the
        // comparison read "region.outline == region.outline" and every saved preset matched
        // everything -- always selected, never any drift to report.
        val presetOutline = outline
        val presetFill = fill
        fun ParticleRegionSettings.matchesThis() = this.outline == presetOutline && this.fill == presetFill
        return settings.keyboard.matchesThis() &&
            settings.radial.matchesThis() &&
            settings.strip.matchesThis() &&
            settings.languageRevert.matchesThis() &&
            settings.quickActions.matchesThis()
    }

    /** This pair written to every region, each switched on, and this entry recorded as the
     *  applied preset -- [BuiltInEffectsPreset.appliedTo]'s counterpart, one pair for all five
     *  rather than one look per region. */
    fun appliedTo(settings: ParticleEffectsSettings): ParticleEffectsSettings {
        fun apply(region: ParticleRegionSettings) = region.copy(enabled = true, outline = outline, fill = fill)
        return settings.copy(
            keyboard = apply(settings.keyboard),
            radial = apply(settings.radial),
            strip = apply(settings.strip),
            languageRevert = apply(settings.languageRevert),
            quickActions = apply(settings.quickActions),
            appliedPresetId = id,
        )
    }

    companion object {
        /** Long enough for a real name, short enough a chip never has to wrap it. */
        const val MAX_NAME_LENGTH = 40
    }
}

/** The whole saved collection, as one DataStore file -- see [CustomThemeLibrary]'s own doc for
 *  why this is a file of its own rather than a list bolted onto [ParticleEffectsSettings]. */
@Serializable
data class CustomEffectsPresetLibrary(val presets: List<CustomEffectsPresetEntry> = emptyList()) {
    fun sanitised(): CustomEffectsPresetLibrary = copy(
        presets = presets.map { it.sanitised() }.take(MAX_CUSTOM_PRESETS),
    )

    companion object {
        /** Same reasoning as [CustomThemeLibrary.MAX_CUSTOM_THEMES]. */
        const val MAX_CUSTOM_PRESETS = 50
    }
}

/** The shape an earlier build wrote to `keyboard_custom_outline_presets.json` -- see
 *  [ThemeRepository.importLegacyOutlinePresets]. Kept only to read that file once. */
@Serializable
internal data class LegacyOutlinePreset(
    val id: String,
    val name: String,
    val layer: ParticleOutlineLayer,
    val createdAt: Long = 0L,
)

@Serializable
internal data class LegacyOutlinePresetLibrary(val presets: List<LegacyOutlinePreset> = emptyList())
