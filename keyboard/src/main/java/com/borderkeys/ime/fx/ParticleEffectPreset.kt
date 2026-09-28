// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

/**
 * Which [ParticleMotion] function moves a particle. [OUTWARD] leaves the spawn point along the
 * outline's outward normal, drifting along the emit direction and shrinking.
 */
enum class ParticleMotionKind { RISE_AND_SHRINK, PULSE_IN_PLACE, WAVE_DRIFT, STATIC_FLICKER, OUTWARD }

/** Which [ParticleColor] function colours a particle. */
enum class ParticleColorKind { THERMAL_GRADIENT, CROSSFADE, HUE_CYCLE, SINGLE_COLOR_PULSE }

/**
 * One named look as numbers: its motion and colour functions, spawn rate and cap, size, lifetime,
 * and the tuning parameters those functions read.
 */
data class ParticleEffectPreset(
    val motion: ParticleMotionKind,
    val color: ParticleColorKind,
    /** Ambient spawn rate. Bursts ignore this and spawn [burstCount] particles at once instead. */
    val spawnRatePerSecond: Float,
    /** The cap an ambient does not spawn past; a burst may exceed it. */
    val maxParticles: Int,
    val burstCount: Int,
    val minRadiusPx: Float,
    val maxRadiusPx: Float,
    val lifetimeSeconds: Float,
    val riseSpeedPxPerSecond: Float = 0f,
    val driftPxPerSecond: Float = 0f,
    val shrinkPerSecond: Float = 0f,
    /** How far PULSE_IN_PLACE and WAVE_DRIFT swing. */
    val pulseAmplitudePx: Float = 0f,
    /** The frequency of PULSE_IN_PLACE's motion and SINGLE_COLOR_PULSE's colour. */
    val pulseFrequencyHz: Float = 0f,
    val waveWavelengthPx: Float = 0f,
    val waveSpeedRadPerSecond: Float = 0f,
    val hueOffsetDegPerPx: Float = 0f,
    val hueDegPerSecond: Float = 0f,
    /** Loops per second a travelling emission point makes around the outline; 0 for none. */
    val travelLoopsPerSecond: Float = 0f,
    /** The width of a stroke along the ambient shape, under the particles; 0 for none. */
    val strokeWidthPx: Float = 0f,
    /** [ParticleMotionKind.OUTWARD] only: how fast a particle leaves the outline along its
     *  outward normal. */
    val outwardSpeedPxPerSecond: Float = 0f,
    /**
     * An outline preset's emission direction, a unit vector in screen coordinates (`0, -1` is up):
     * [ParticleMotionKind.OUTWARD] drifts along it, and with [emitConeCos] above `-1` particles
     * spawn only where the outline's normal lies within that cone of it. `0, 0` for none.
     */
    val emitDirectionX: Float = 0f,
    val emitDirectionY: Float = 0f,
    /** Minimum dot product between an outline point's outward normal and the emit direction for
     *  a particle to spawn there -- `-1` accepts the whole outline, `0` the half facing the
     *  emit direction, higher a narrower cone. */
    val emitConeCos: Float = -1f,
)
