// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * One layer's own look inside a region -- Fill, the "inside particles" ambient/burst behaviour
 * every region already had before this file existed. [type] is one of
 * [ParticleEffectsSettings.FILL_FIRE]..[ParticleEffectsSettings.FILL_NEON].
 */
@Serializable
data class ParticleFillLayer(
    val type: Int = ParticleEffectsSettings.FILL_GLOW,
    val primaryColor: Int = 0xFFFF9800.toInt(),
    val secondaryColor: Int = 0xFF6EA8FE.toInt(),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
) {
    fun sanitised(): ParticleFillLayer = copy(
        type = if (type in ParticleEffectsSettings.FILL_FIRE..ParticleEffectsSettings.FILL_NEON) {
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
 */
@Serializable
data class ParticleOutlineLayer(
    val type: Int = ParticleEffectsSettings.OUTLINE_NONE,
    val primaryColor: Int = 0xFFFF9800.toInt(),
    val secondaryColor: Int = 0xFF6EA8FE.toInt(),
    val speed: Float = ParticleEffectsSettings.DEFAULT_SPEED,
    val density: Float = ParticleEffectsSettings.DEFAULT_DENSITY,
    val width: Float = ParticleEffectsSettings.DEFAULT_WIDTH,
) {
    fun sanitised(): ParticleOutlineLayer = copy(
        type = if (type in ParticleEffectsSettings.OUTLINE_NONE..ParticleEffectsSettings.OUTLINE_SPARKLE) {
            type
        } else {
            ParticleEffectsSettings.OUTLINE_NONE
        },
        speed = speed.coerceIn(ParticleEffectsSettings.MIN_SPEED, ParticleEffectsSettings.MAX_SPEED),
        density = density.coerceIn(ParticleEffectsSettings.MIN_DENSITY, ParticleEffectsSettings.MAX_DENSITY),
        width = width.coerceIn(ParticleEffectsSettings.MIN_WIDTH, ParticleEffectsSettings.MAX_WIDTH),
    )
}

/** Whether every knob of this layer besides its own [ParticleFillLayer.type] still matches what
 *  constructing a fresh layer of that same type would give -- the "Custom" detection a settings
 *  picker uses to decide whether to show a preset chip as selected at all. Compared against a
 *  fresh same-type instance rather than a hardcoded tuple, so a type that earns its own distinct
 *  canonical numbers later needs no change here. */
fun ParticleFillLayer.matchesPreset(): Boolean = this == ParticleFillLayer(type = type)

fun ParticleOutlineLayer.matchesPreset(): Boolean = this == ParticleOutlineLayer(type = type)

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
    )

    companion object {
        /** [ParticleFillLayer.type] values -- Fire, Glow, Waves, Rainbow, Neon Pulse, matching
         *  [com.borderkeys.ime.fx.ParticleEffectPresets.forSetting]'s own order by convention:
         *  `:data` cannot reference `:keyboard`'s type, so the two lists agree only by staying
         *  in this order on both sides. */
        const val FILL_FIRE = 0
        const val FILL_GLOW = 1
        const val FILL_WAVES = 2
        const val FILL_RAINBOW = 3
        const val FILL_NEON = 4

        /** [ParticleOutlineLayer.type] values -- None, Comet, Pulse, Sparkle, matching
         *  [com.borderkeys.ime.fx.ParticleOutlineStylePresets.forSetting]'s own order by the
         *  same convention. */
        const val OUTLINE_NONE = 0
        const val OUTLINE_COMET = 1
        const val OUTLINE_PULSE = 2
        const val OUTLINE_SPARKLE = 3

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
