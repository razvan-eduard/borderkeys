// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * The fill layer's looks. [forSetting] takes a `ParticleEffectsSettings.FILL_*` value: 1 Fire,
 * 2 Glow, 3 Waves, 4 Rainbow, 5 Neon Pulse.
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

    /** The preset for a `ParticleEffectsSettings.FILL_*` [preset]; [GLOW] for any other value. */
    fun forSetting(preset: Int): ParticleEffectPreset = when (preset) {
        1 -> FIRE
        2 -> GLOW
        3 -> WAVES
        4 -> RAINBOW
        5 -> NEON_PULSE
        else -> GLOW
    }
}
