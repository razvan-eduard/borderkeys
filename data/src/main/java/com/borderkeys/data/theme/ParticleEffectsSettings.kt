// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * One layer's own look inside a region -- Fill, the "inside particles" ambient/burst behaviour
 * every region already had before this file existed. [type] is one of
 * [ParticleEffectsSettings.FILL_NONE]..[ParticleEffectsSettings.FILL_NEON]. Defaults to
 * [ParticleEffectsSettings.FILL_GLOW] rather than [ParticleEffectsSettings.FILL_NONE] -- unlike
 * Outline, which is new and starts silent, Fill is what every region already showed before this
 * file existed, so a region switched on for the first time still shows something.
 *
 * [primaryColor]/[secondaryColor] default to a colour pair of [type]'s own -- see
 * [defaultFillPrimaryColor]/[defaultFillSecondaryColor] -- rather than one shared pair every
 * preset used to default to alike, which is what made "Custom" trigger on the first colour
 * touched no matter which preset: there was nothing preset-specific to still be matching.
 */
@Serializable
data class ParticleFillLayer(
    val type: Int = ParticleEffectsSettings.FILL_GLOW,
    val primaryColor: Int = defaultFillPrimaryColor(type),
    val secondaryColor: Int = defaultFillSecondaryColor(type),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
) {
    fun sanitised(): ParticleFillLayer = copy(
        type = if (type in ParticleEffectsSettings.FILL_NONE..ParticleEffectsSettings.FILL_NEON) {
            type
        } else {
            ParticleEffectsSettings.FILL_GLOW
        },
        speed = speed.coerceIn(ParticleEffectsSettings.MIN_SPEED, ParticleEffectsSettings.MAX_SPEED),
        density = density.coerceIn(ParticleEffectsSettings.MIN_DENSITY, ParticleEffectsSettings.MAX_DENSITY),
    )
}

/**
 * One layer's own look inside a region -- Outline, particles tracing the region's own border or
 * arc. Its own style catalogue, distinct from Fill's -- see
 * [com.borderkeys.ime.fx.ParticleOutlineStylePresets]. [type] is one of
 * [ParticleEffectsSettings.OUTLINE_NONE]..[ParticleEffectsSettings.OUTLINE_SPARKLE]. [width] has
 * no equivalent on [ParticleFillLayer] -- a fill has no notion of "thickness" beyond how many
 * particles it has, but an outline's visible trace does.
 *
 * [primaryColor]/[secondaryColor] default per [type] the same way [ParticleFillLayer]'s own do --
 * see [defaultOutlinePrimaryColor]/[defaultOutlineSecondaryColor].
 */
@Serializable
data class ParticleOutlineLayer(
    val type: Int = ParticleEffectsSettings.OUTLINE_NONE,
    val primaryColor: Int = defaultOutlinePrimaryColor(type),
    val secondaryColor: Int = defaultOutlineSecondaryColor(type),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
    val width: Float = ParticleEffectsSettings.DEFAULT_WIDTH,
) {
    fun sanitised(): ParticleOutlineLayer = copy(
        type = if (type in ParticleEffectsSettings.OUTLINE_NONE..ParticleEffectsSettings.OUTLINE_WIND) {
            type
        } else {
            ParticleEffectsSettings.OUTLINE_NONE
        },
        speed = speed.coerceIn(ParticleEffectsSettings.MIN_SPEED, ParticleEffectsSettings.MAX_SPEED),
        density = density.coerceIn(ParticleEffectsSettings.MIN_DENSITY, ParticleEffectsSettings.MAX_DENSITY),
        width = width.coerceIn(ParticleEffectsSettings.MIN_WIDTH, ParticleEffectsSettings.MAX_WIDTH),
    )
}

/**
 * Fire's hot young colour cooling to embers, matching [com.borderkeys.ime.fx.ParticleColor
 * .thermalGradient]'s own young-is-primary/old-is-secondary direction. Glow keeps the original
 * tuned amber-to-blue pair unchanged; the rest each get a colour identity of their own. `else`
 * (None, or a corrupt file's out-of-range value) falls back to Glow's own pair -- there is no
 * "None" look to speak of, and this is the one every install already shipped with.
 */
private fun defaultFillPrimaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.FILL_FIRE -> 0xFFFFC107.toInt()
    ParticleEffectsSettings.FILL_WAVES -> 0xFF4FC3F7.toInt()
    ParticleEffectsSettings.FILL_RAINBOW -> 0xFFEC407A.toInt()
    ParticleEffectsSettings.FILL_NEON -> 0xFFFF4081.toInt()
    else -> 0xFFFF9800.toInt()
}

/** See [defaultFillPrimaryColor]. [ParticleEffectsSettings.FILL_RAINBOW]'s pair is cosmetic
 *  only -- [com.borderkeys.ime.fx.ParticleColor.hueCycle] computes its colour from hue alone and
 *  never reads either field -- kept for the same reason [ParticleEffectsSettings.FILL_NEON]'s
 *  secondary is: so the picker's own swatches still show a deliberate pair rather than
 *  whatever was left over from a different preset. */
private fun defaultFillSecondaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.FILL_FIRE -> 0xFFBF360C.toInt()
    ParticleEffectsSettings.FILL_WAVES -> 0xFF01579B.toInt()
    ParticleEffectsSettings.FILL_RAINBOW -> 0xFF7E57C2.toInt()
    ParticleEffectsSettings.FILL_NEON -> 0xFF18FFFF.toInt()
    else -> 0xFF6EA8FE.toInt()
}

/** Comet's icy head fading into a deep trail -- the one pair [com.borderkeys.ime.fx
 *  .ParticleColor.crossfade] actually reads both halves of on this side, the same as Fire/Waves
 *  above. `else` (None, or out-of-range) falls back to the plain default pair every layer
 *  shared before per-preset colours existed -- moot in practice, since a None outline draws
 *  nothing to colour. */
private fun defaultOutlinePrimaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.OUTLINE_COMET -> 0xFFE1F5FE.toInt()
    ParticleEffectsSettings.OUTLINE_PULSE -> 0xFF7C4DFF.toInt()
    ParticleEffectsSettings.OUTLINE_SPARKLE -> 0xFFFFF59D.toInt()
    ParticleEffectsSettings.OUTLINE_FIRE -> 0xFFFF5722.toInt()
    ParticleEffectsSettings.OUTLINE_WIND -> 0xFFB3E5FC.toInt()
    else -> 0xFFFF9800.toInt()
}

/** See [defaultOutlinePrimaryColor]. Pulse and Sparkle both draw with
 *  [com.borderkeys.ime.fx.ParticleColor.singleColorPulse], which -- like [FILL_NEON] on the
 *  Fill side -- reads only the primary colour; their own secondary here is cosmetic, for the
 *  same reason [defaultFillSecondaryColor] gives Neon one anyway. */
private fun defaultOutlineSecondaryColor(type: Int): Int = when (type) {
    ParticleEffectsSettings.OUTLINE_COMET -> 0xFF0277BD.toInt()
    ParticleEffectsSettings.OUTLINE_PULSE -> 0xFFB388FF.toInt()
    ParticleEffectsSettings.OUTLINE_SPARKLE -> 0xFFFFFFFF.toInt()
    ParticleEffectsSettings.OUTLINE_FIRE -> 0xFFBF360C.toInt()
    ParticleEffectsSettings.OUTLINE_WIND -> 0xFFFFFFFF.toInt()
    else -> 0xFF6EA8FE.toInt()
}

/** Whether every knob of this layer besides its own [ParticleFillLayer.type] still matches what
 *  constructing a fresh layer of that same type would give -- the "Custom" detection a settings
 *  picker uses to decide whether to show a preset chip as selected at all. Compared against a
 *  fresh same-type instance rather than a hardcoded tuple, so a type that earns its own distinct
 *  canonical numbers later needs no change here. */
fun ParticleFillLayer.matchesPreset(): Boolean = this == ParticleFillLayer(type = type)

fun ParticleOutlineLayer.matchesPreset(): Boolean = this == ParticleOutlineLayer(type = type)

/**
 * This layer switched to [type]'s own canonical colours -- what a settings picker's type chip
 * should actually call, rather than the plain `copy(type = ...)` [matchesPreset]'s own doc talks
 * about: that preserves colours code-side because that is what makes "Custom" a real, sticky
 * state once a colour has genuinely been hand-picked, but calling it from a *picker* would leave
 * a freshly-clicked "Fire" chip still showing whatever the previous preset's colours were.
 * [speed]/[density] are still carried over, though -- those are a personal intensity dial layered
 * on top of any preset, not part of a preset's own identity, so picking a different look never
 * quietly resets how fast or how dense a person already set it.
 */
fun ParticleFillLayer.withPresetType(type: Int): ParticleFillLayer =
    ParticleFillLayer(type = type).copy(speed = speed, density = density)

/** See [ParticleFillLayer.withPresetType]. Also carries [ParticleOutlineLayer.width] over, the
 *  same reasoning as speed/density: an intensity dial, not part of what makes Comet Comet. */
fun ParticleOutlineLayer.withPresetType(type: Int): ParticleOutlineLayer =
    ParticleOutlineLayer(type = type).copy(speed = speed, density = density, width = width)

/** One region's whole particle configuration: whether it is on at all, and its two independent
 *  layers. [enabled] is this region's *own* master switch -- there is no longer one single
 *  global on/off across every region, only five independent ones. */
@Serializable
data class ParticleRegionSettings(
    val enabled: Boolean = false,
    val outline: ParticleOutlineLayer = ParticleOutlineLayer(),
    val fill: ParticleFillLayer = ParticleFillLayer(),
) {
    fun sanitised(): ParticleRegionSettings = copy(outline = outline.sanitised(), fill = fill.sanitised())
}

/**
 * Particle effects for every surface that has them, as its own top-level settings domain --
 * promoted out of [KeyboardPreferences]/[KeyboardTheme], which only ever held a handful of flat
 * fields for this in the first release. Five regions × two layers × their own colours/speed/
 * density(/width) is large and cohesive enough to deserve the same independence
 * [KeyboardPreferences]/[KeyboardTheme] already have from each other, not a growing pile of
 * fields bolted onto either.
 */
@Serializable
data class ParticleEffectsSettings(
    val keyboard: ParticleRegionSettings = ParticleRegionSettings(),
    val radial: ParticleRegionSettings = ParticleRegionSettings(),
    val strip: ParticleRegionSettings = ParticleRegionSettings(),
    val languageRevert: ParticleRegionSettings = ParticleRegionSettings(),
    val quickActions: ParticleRegionSettings = ParticleRegionSettings(),
    /**
     * The preset applied last -- a [BuiltInEffectsPreset.id] or a [CustomEffectsPresetEntry.id]
     * -- or empty when none has been. Stored beside the regions it was applied to, in the same
     * write, because it is what the picker's "which preset is this" question is actually
     * about: comparing the five regions against every preset only ever answered it while
     * nothing had been touched since, and the first slider moved left no chip selected and
     * nothing to say what it had drifted from. A preset that no longer exists (a saved one
     * deleted since) simply matches nothing.
     */
    val appliedPresetId: String = "",
) {
    /** A file that parses is not a file that makes sense -- the same reasoning
     *  [KeyboardPreferences.sanitised]/[KeyboardTheme.sanitised] already apply to their own
     *  fields, applied all the way down here so a corrupt or hand-edited file cannot escape the
     *  repository either. */
    fun sanitised(): ParticleEffectsSettings = copy(
        keyboard = keyboard.sanitised(),
        radial = radial.sanitised(),
        strip = strip.sanitised(),
        languageRevert = languageRevert.sanitised(),
        quickActions = quickActions.sanitised(),
        appliedPresetId = appliedPresetId.take(MAX_PRESET_ID_LENGTH),
    )

    companion object {
        /** [ParticleFillLayer.type] values -- None, Fire, Glow, Waves, Rainbow, Neon Pulse,
         *  matching [com.borderkeys.ime.fx.ParticleEffectPresets.forSetting]'s own order by
         *  convention: `:data` cannot reference `:keyboard`'s type, so the two lists agree only
         *  by staying in this order on both sides. */
        const val FILL_NONE = 0
        const val FILL_FIRE = 1
        const val FILL_GLOW = 2
        const val FILL_WAVES = 3
        const val FILL_RAINBOW = 4
        const val FILL_NEON = 5

        /** [ParticleOutlineLayer.type] values -- None, Comet, Pulse, Sparkle, matching
         *  [com.borderkeys.ime.fx.ParticleOutlineStylePresets.forSetting]'s own order by the
         *  same convention. */
        const val OUTLINE_NONE = 0
        const val OUTLINE_COMET = 1
        const val OUTLINE_PULSE = 2
        const val OUTLINE_SPARKLE = 3

        /** A real stroke traced along the region's own shape, embers drifting up off it -- see
         *  [com.borderkeys.ime.fx.ParticleOutlineStylePresets.FIRE]. */
        const val OUTLINE_FIRE = 4

        /** Same idea as [OUTLINE_FIRE], particles blown sideways off the line instead of rising
         *  -- see [com.borderkeys.ime.fx.ParticleOutlineStylePresets.WIND]. */
        const val OUTLINE_WIND = 5

        /** Room for a UUID with change; a hand-edited file cannot smuggle a novel in. */
        const val MAX_PRESET_ID_LENGTH = 64

        /** The range every layer's [ParticleFillLayer.speed]/[ParticleOutlineLayer.speed] is
         *  clamped to. */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
        const val DEFAULT_SPEED = 1f

        /** The range every layer's density is clamped to. */
        const val MIN_DENSITY = 0.5f
        const val MAX_DENSITY = 2f
        const val DEFAULT_DENSITY = 1f

        /** The range [ParticleOutlineLayer.width] is clamped to. */
        const val MIN_WIDTH = 0.5f
        const val MAX_WIDTH = 2f
        const val DEFAULT_WIDTH = 1f
    }
}
