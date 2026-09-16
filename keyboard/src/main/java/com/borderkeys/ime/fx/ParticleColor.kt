// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A particle's colour at a given age or life, packed ARGB, ready to hand straight to
 * `Paint.setColor`.
 *
 * Every function here is hand-rolled arithmetic -- no `android.graphics.Color` call anywhere.
 * `:keyboard`'s own `build.gradle.kts` turns on `unitTests.isReturnDefaultValues`, which makes
 * every `android.graphics.Color.*` call silently return `0` under plain JUnit -- a test built on
 * top of one would pass by testing nothing. [ParticleFieldTest] and this object's own tests run
 * as plain JVM tests specifically because none of this needs the framework at all.
 */
object ParticleColor {

    /** Linear interpolation, channel by channel, alpha included. */
    fun lerpArgb(from: Int, to: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val a = lerpChannel((from ushr 24) and 0xFF, (to ushr 24) and 0xFF, clamped)
        val r = lerpChannel((from ushr 16) and 0xFF, (to ushr 16) and 0xFF, clamped)
        val g = lerpChannel((from ushr 8) and 0xFF, (to ushr 8) and 0xFF, clamped)
        val b = lerpChannel(from and 0xFF, to and 0xFF, clamped)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** The "Glow"/"Waves" colour: fades from the secondary colour to the primary as life runs
     *  out -- opaque throughout, [com.borderkeys.ime.fx.ParticleField] applies the actual
     *  fade-to-nothing alpha uniformly on top of every preset. */
    fun crossfade(life: Float, primaryColor: Int, secondaryColor: Int): Int =
        lerpArgb(secondaryColor, primaryColor, life) or ALPHA_OPAQUE

    /**
     * The "Fire" colour: hot (the primary colour) while young, cooling to the secondary colour,
     * three stops keyed on [life] rather than a continuous lerp across the whole range -- a
     * flame's own colour holds near-white/near-primary for a while before it visibly starts to
     * cool.
     */
    fun thermalGradient(life: Float, primaryColor: Int, secondaryColor: Int): Int {
        val clamped = life.coerceIn(0f, 1f)
        return when {
            clamped >= HOT_THRESHOLD -> primaryColor or ALPHA_OPAQUE
            clamped >= COOL_THRESHOLD -> {
                val t = (clamped - COOL_THRESHOLD) / (HOT_THRESHOLD - COOL_THRESHOLD)
                lerpArgb(secondaryColor, primaryColor, t) or ALPHA_OPAQUE
            }
            else -> secondaryColor or ALPHA_OPAQUE
        }
    }

    /**
     * Hand-rolled HSV -> RGB, opaque. No `Triple`/destructuring: this runs once per particle
     * per frame for the "Rainbow" preset, and a small allocation there would be exactly the kind
     * of per-frame cost this whole package exists to avoid.
     */
    fun hsvToRgb(hueDeg: Float, saturation: Float, value: Float): Int {
        val h = normalizeDegrees(hueDeg) / 60f
        val sector = h.toInt().coerceIn(0, 5)
        val c = value * saturation
        val x = c * (1f - abs(h % 2f - 1f))
        val m = value - c
        var r = 0f
        var g = 0f
        var b = 0f
        when (sector) {
            0 -> { r = c; g = x; b = 0f }
            1 -> { r = x; g = c; b = 0f }
            2 -> { r = 0f; g = c; b = x }
            3 -> { r = 0f; g = x; b = c }
            4 -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        // r/g/b/m are 0..1 HSV fractions here, not 0..255 channel values yet -- channel() expects
        // the latter (see lerpChannel's own use of it), so the scale-up happens right here.
        val red = channel((r + m) * 255f)
        val green = channel((g + m) * 255f)
        val blue = channel((b + m) * 255f)
        return ALPHA_OPAQUE or (red shl 16) or (green shl 8) or blue
    }

    /**
     * The "Rainbow" colour: hue offset by [spawnX] so a field of particles reads as one moving
     * band of colour rather than every particle flashing the same hue in lockstep -- the same
     * "phase depends on where it spawned" trick [ParticleMotion.waveDriftY] uses for position.
     */
    fun hueCycle(spawnX: Float, ageSeconds: Float, hueOffsetDegPerPx: Float, degPerSecond: Float): Int =
        hsvToRgb(spawnX * hueOffsetDegPerPx + ageSeconds * degPerSecond, RAINBOW_SATURATION, RAINBOW_VALUE)

    /** The "Neon Pulse" colour: the particle never moves (see [ParticleMotion]'s own doc), so
     *  every bit of its visual interest is this alpha oscillation. */
    fun singleColorPulse(color: Int, ageSeconds: Float, frequencyHz: Float): Int {
        val t = (sin(ageSeconds * frequencyHz * TWO_PI) + 1f) / 2f
        val alpha = channel(MIN_PULSE_ALPHA + (MAX_PULSE_ALPHA - MIN_PULSE_ALPHA) * t)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun lerpChannel(from: Int, to: Int, t: Float): Int =
        channel(from + (to - from) * t)

    /** Rounds and clamps a single 0..255 channel -- shared by every function above so nothing
     *  here hands `Paint` an out-of-range byte. */
    private fun channel(value: Float): Int = value.toInt().coerceIn(0, 255)

    private fun normalizeDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d < 0f) d += 360f
        return d
    }

    private val TWO_PI = (2.0 * PI).toFloat()
    private const val ALPHA_OPAQUE = 0xFF shl 24
    private const val HOT_THRESHOLD = 0.6f
    private const val COOL_THRESHOLD = 0.3f
    private const val RAINBOW_SATURATION = 0.85f
    private const val RAINBOW_VALUE = 1f
    private const val MIN_PULSE_ALPHA = 60f
    private const val MAX_PULSE_ALPHA = 220f
}
