// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * The five selectable looks, ported from a sibling project's calendar "today" cell decoration and
 * retuned down for this project's own use: a brief burst on a key press or an accepted
 * suggestion, or a small ambient glow around a chip or wedge -- not an always-on background
 * decoration on screen for minutes at a time. Every number below is a tuned starting point, not a
 * requirement; adjust by eye against a real device.
 *
 * `:keyboard` has no dependency on `:data`, so this deliberately takes and returns a raw `Int` in
 * [forSetting] rather than `ParticleEffectsSettings`' own type -- the mapping (0=None, 1=Fire,
 * 2=Glow, 3=Waves, 4=Rainbow, 5=Neon Pulse) matches `ParticleEffectsSettings.FILL_*` by
 * convention, not by a shared reference. If either list is ever reordered, the other must move
 * with it. This is Fill's own catalogue -- Outline has its own, separate, three-preset catalogue,
 * see [ParticleOutlineStylePresets].
 */
object ParticleEffectPresets {

    val FIRE = ParticleEffectPreset(
        motion = ParticleMotionKind.RISE_AND_SHRINK,
        color = ParticleColorKind.THERMAL_GRADIENT,
        spawnRatePerSecond = 14f,
        maxParticles = 20,
        burstCount = 10,
        minRadiusPx = 2f,
        maxRadiusPx = 5f,
        lifetimeSeconds = 1.0f,
        riseSpeedPxPerSecond = 90f,
        driftPxPerSecond = 18f,
        shrinkPerSecond = 0.55f,
    )

    val GLOW = ParticleEffectPreset(
        motion = ParticleMotionKind.PULSE_IN_PLACE,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 3f,
        maxParticles = 8,
        burstCount = 6,
        minRadiusPx = 5f,
        maxRadiusPx = 10f,
        lifetimeSeconds = 1.4f,
        pulseAmplitudePx = 6f,
        pulseFrequencyHz = 0.8f,
    )

    val WAVES = ParticleEffectPreset(
        motion = ParticleMotionKind.WAVE_DRIFT,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 8f,
        maxParticles = 18,
        burstCount = 8,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 1.1f,
        pulseAmplitudePx = 14f,
        waveWavelengthPx = 60f,
        waveSpeedRadPerSecond = 3f,
    )

    val RAINBOW = ParticleEffectPreset(
        motion = ParticleMotionKind.PULSE_IN_PLACE,
        color = ParticleColorKind.HUE_CYCLE,
        spawnRatePerSecond = 10f,
        maxParticles = 16,
        burstCount = 8,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 1.1f,
        pulseAmplitudePx = 3f,
        pulseFrequencyHz = 1.4f,
        hueOffsetDegPerPx = 1.5f,
        hueDegPerSecond = 90f,
    )

    val NEON_PULSE = ParticleEffectPreset(
        motion = ParticleMotionKind.STATIC_FLICKER,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 5f,
        maxParticles = 10,
        burstCount = 6,
        minRadiusPx = 4f,
        maxRadiusPx = 7f,
        lifetimeSeconds = 0.9f,
        pulseFrequencyHz = 2.5f,
    )

    /** [preset] is `ParticleEffectsSettings.FILL_*` by convention -- see this object's own doc
     *  for why that is a convention rather than a shared reference. `FILL_NONE`/an out-of-range
     *  value fall back to [GLOW] rather than crashing, the same defensiveness
     *  `ParticleFillLayer.sanitised()` already applies to the stored setting itself; in practice
     *  a caller never asks for a preset when the type is None -- see [applyParticleLayer]'s own
     *  gating. */
    fun forSetting(preset: Int): ParticleEffectPreset = when (preset) {
        1 -> FIRE
        2 -> GLOW
        3 -> WAVES
        4 -> RAINBOW
        5 -> NEON_PULSE
        else -> GLOW
    }
}
