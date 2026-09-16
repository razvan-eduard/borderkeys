// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/** Which [ParticleMotion] function moves a particle -- resolved once per particle per frame by
 *  [ParticleField], not stored per particle. */
enum class ParticleMotionKind { RISE_AND_SHRINK, PULSE_IN_PLACE, WAVE_DRIFT, STATIC_FLICKER }

/** Which [ParticleColor] function colours a particle. */
enum class ParticleColorKind { THERMAL_GRADIENT, CROSSFADE, HUE_CYCLE, SINGLE_COLOR_PULSE }

/**
 * One named look -- Fire, Glow, Waves, Rainbow, Neon Pulse -- as plain numbers, not code: which
 * motion and colour function apply, how fast particles spawn and how many may be alive at once,
 * their size and lifetime, and whichever of the motion/colour tuning parameters that particular
 * combination actually reads (the rest are left at their default `0f`, harmless because the
 * `when` in [ParticleField] never reaches a branch that would use them).
 *
 * See [ParticleEffectPresets] for the five concrete values -- retuned down from the calendar
 * "today" cell decoration this was ported from, for a brief keyboard burst or a small ambient
 * glow rather than an always-on background decoration.
 */
data class ParticleEffectPreset(
    val motion: ParticleMotionKind,
    val color: ParticleColorKind,
    /** Ambient spawn rate. Bursts ignore this and spawn [burstCount] particles at once instead. */
    val spawnRatePerSecond: Float,
    /** Soft cap the ambient spawn accumulator will not spawn past. A burst may briefly exceed
     *  this by up to [burstCount] - 1 -- deliberately: a burst is a deliberate moment, not
     *  something worth dropping particles over. */
    val maxParticles: Int,
    val burstCount: Int,
    val minRadiusPx: Float,
    val maxRadiusPx: Float,
    val lifetimeSeconds: Float,
    val riseSpeedPxPerSecond: Float = 0f,
    val driftPxPerSecond: Float = 0f,
    val shrinkPerSecond: Float = 0f,
    /** Amplitude for both PULSE_IN_PLACE and WAVE_DRIFT -- the same "how far it swings" knob
     *  either way. */
    val pulseAmplitudePx: Float = 0f,
    /** Frequency for PULSE_IN_PLACE's motion and for SINGLE_COLOR_PULSE's colour -- the two
     *  presets that actually oscillate on a clock rather than drift or cycle. */
    val pulseFrequencyHz: Float = 0f,
    val waveWavelengthPx: Float = 0f,
    val waveSpeedRadPerSecond: Float = 0f,
    val hueOffsetDegPerPx: Float = 0f,
    val hueDegPerSecond: Float = 0f,
    /** Full loops per second a *traveling* ambient emission point advances around its
     *  perimeter/arc, before the layer's own speed multiplier scales it -- see
     *  [ParticleSimulation]'s own ambient-phase doc. Only meaningful for a preset whose ambient
     *  is spawned traveling (the outline "Comet" look); every other preset leaves this at its
     *  harmless default. */
    val travelLoopsPerSecond: Float = 0f,
)
