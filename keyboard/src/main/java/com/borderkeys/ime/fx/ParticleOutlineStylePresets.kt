// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * The outline layer's styles. Each particle spawns on the traced shape's edge, sits outside it
 * along the edge's normal, and moves outward.
 */
object ParticleOutlineStylePresets {

    /** A point travelling around the outline, trailing particles that drift outward and fade. */
    val COMET = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 40f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 3.5f,
        lifetimeSeconds = 0.4f,
        travelLoopsPerSecond = 0.6f,
        outwardSpeedPxPerSecond = 12f,
    )

    /** Particles all around the outline, pulsing in one colour as they drift outward. */
    val PULSE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 12f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 1.2f,
        pulseFrequencyHz = 0.8f,
        outwardSpeedPxPerSecond = 14f,
    )

    /** [PULSE] tuned to twinkle: shorter lives, more spawns, faster flicker and outward dart. */
    val SPARKLE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.SINGLE_COLOR_PULSE,
        spawnRatePerSecond = 26f,
        maxParticles = 28,
        burstCount = 0,
        minRadiusPx = 1.5f,
        maxRadiusPx = 3f,
        lifetimeSeconds = 0.45f,
        pulseFrequencyHz = 3.5f,
        outwardSpeedPxPerSecond = 30f,
    )

    /** A stroke along the outline, with a few embers rising off it. */
    val FIRE = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.THERMAL_GRADIENT,
        spawnRatePerSecond = 16f,
        maxParticles = 20,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4.5f,
        lifetimeSeconds = 0.7f,
        // Embers spawn only on the upward-facing parts of the outline, and rise.
        outwardSpeedPxPerSecond = 20f,
        driftPxPerSecond = 60f,
        emitDirectionX = 0f,
        emitDirectionY = -1f,
        emitConeCos = 0.05f,
        shrinkPerSecond = 0.7f,
        strokeWidthPx = 2.5f,
    )

    /** A stroke along the outline, with particles blown sideways off it in the region's colours. */
    val WIND = ParticleEffectPreset(
        motion = ParticleMotionKind.OUTWARD,
        color = ParticleColorKind.CROSSFADE,
        spawnRatePerSecond = 18f,
        maxParticles = 22,
        burstCount = 0,
        minRadiusPx = 2f,
        maxRadiusPx = 4f,
        lifetimeSeconds = 0.8f,
        // Spawns only on the right-facing parts of the outline, and drifts right.
        outwardSpeedPxPerSecond = 12f,
        driftPxPerSecond = 40f,
        emitDirectionX = 1f,
        emitDirectionY = 0f,
        emitConeCos = 0.05f,
        shrinkPerSecond = 0.5f,
        strokeWidthPx = 2f,
    )

    /** The preset for a `ParticleEffectsSettings.OUTLINE_*` [type]; [PULSE] for any other value. */
    fun forSetting(type: Int): ParticleEffectPreset = when (type) {
        1 -> COMET
        2 -> PULSE
        3 -> SPARKLE
        4 -> FIRE
        5 -> WIND
        else -> PULSE
    }
}
