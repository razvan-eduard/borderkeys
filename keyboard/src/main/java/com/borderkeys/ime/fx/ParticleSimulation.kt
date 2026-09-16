// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The particle pool's actual state and physics -- everything in [ParticleField] except owning a
 * real frame clock and landing on a `Canvas`.
 *
 * Split out on purpose so it stays plain-JVM testable: `:keyboard`'s `build.gradle.kts` turns on
 * `unitTests.isReturnDefaultValues`, under which an unmocked `android.*` call returns a default
 * rather than throwing -- for `android.view.Choreographer.getInstance()` (a static method
 * returning an object type) that default is `null`, and this project's tests do not reach for
 * Robolectric to work around that (see [RadialSuggestionMenuViewTest] for the existing plain-
 * JUnit4 convention). [ParticleField] is therefore the only thing here that ever imports
 * `android.view.Choreographer`; this class does not import anything from `android.*` at all.
 *
 * A particle is `spawnX`/`spawnY`/`ageSeconds`/`spawnRadius` and nothing else -- [ParticleMotion]
 * is closed-form in age, so there is no "current position" to carry between frames the way the
 * sibling project this was ported from carries one (as an immutable data class, replaced via
 * `.copy()` every frame). Nothing here allocates past construction.
 */
class ParticleSimulation(val capacity: Int) {

    private val liveFlags = BooleanArray(capacity)
    private val spawnX = FloatArray(capacity)
    private val spawnY = FloatArray(capacity)
    private val ageSeconds = FloatArray(capacity)
    private val spawnRadius = FloatArray(capacity)

    var preset: ParticleEffectPreset = ParticleEffectPresets.GLOW
    var speedMultiplier: Float = 1f

    /** Scales how many particles an ambient trickle keeps alive and how fast a burst's own
     *  count is, both at once -- a denser trace along a perimeter reads as visually thicker
     *  without a separate axis just for that. */
    var densityMultiplier: Float = 1f

    /** Scales [ParticleEffectPreset.minRadiusPx]/[ParticleEffectPreset.maxRadiusPx] -- Outline's
     *  own separate thickness knob; Fill layers leave this at its harmless default of `1f`. */
    var widthMultiplier: Float = 1f

    private enum class AmbientKind { POINT, RECTANGLE, RECTANGLE_PERIMETER, ARC }

    var ambientActive: Boolean = false
        private set
    private var ambientKind = AmbientKind.POINT

    /** 0..1, wrapping -- a traveling emission point's current position along the perimeter/arc,
     *  advanced in [advance] at [ParticleEffectPreset.travelLoopsPerSecond]. Whether the current
     *  [preset] travels at all rather than re-rolling a random point on every spawn is read
     *  directly off `preset.travelLoopsPerSecond > 0f` wherever this is used -- not a separate
     *  flag a caller has to remember to pass, since the preset already knows. */
    private var ambientPhase01 = 0f

    // POINT/RECTANGLE/RECTANGLE_PERIMETER read these as left/top/right/bottom (or x/y for
    // POINT, X1/Y1 unused). ARC reads X0/Y0/X1 as centerX/centerY/radius (Y1 unused) -- reusing
    // the same four floats rather than adding a second set that would only ever be live for one
    // kind at a time.
    private var ambientX0 = 0f
    private var ambientY0 = 0f
    private var ambientX1 = 0f
    private var ambientY1 = 0f
    private var ambientStartDeg = 0f
    private var ambientSweepDeg = 0f
    private var spawnAccumulator = 0f

    val liveCount: Int get() = liveFlags.count { it }
    val hasLiveParticles: Boolean get() = liveCount > 0

    fun spawnBurstAtPoint(x: Float, y: Float, count: Int) {
        repeat(scaledCount(count)) { spawnAt(x, y) }
    }

    fun spawnBurstInRectangle(left: Float, top: Float, right: Float, bottom: Float, count: Int) {
        repeat(scaledCount(count)) {
            spawnAt(
                EmitterShape.rectangleX(left, right, Random.nextFloat()),
                EmitterShape.rectangleY(top, bottom, Random.nextFloat()),
            )
        }
    }

    fun spawnBurstOnRing(centerX: Float, centerY: Float, radius: Float, insetFraction: Float, count: Int) {
        repeat(scaledCount(count)) {
            val angle = Random.nextFloat()
            val r = radius * (1f - insetFraction * Random.nextFloat())
            spawnAt(EmitterShape.ringX(centerX, r, angle), EmitterShape.ringY(centerY, r, angle))
        }
    }

    fun setAmbientPoint(x: Float, y: Float) {
        ambientActive = true
        ambientKind = AmbientKind.POINT
        ambientX0 = x
        ambientY0 = y
    }

    fun setAmbientRectangle(left: Float, top: Float, right: Float, bottom: Float) {
        ambientActive = true
        ambientKind = AmbientKind.RECTANGLE
        ambientX0 = left
        ambientY0 = top
        ambientX1 = right
        ambientY1 = bottom
    }

    /** A random point along the perimeter is picked fresh on every spawn when the current
     *  [preset] does not travel ([ParticleEffectPreset.travelLoopsPerSecond] `<= 0f` -- Pulse,
     *  Sparkle); a preset that does travel (Comet) instead uses the one shared [ambientPhase01]
     *  this instance continuously advances in [advance]. [ambientPhase01] always resets to 0
     *  here regardless, so a later traveling call never inherits a stale position left over
     *  from wherever a previous ambient (on a different rect, or a non-traveling preset) last
     *  put it. */
    fun setAmbientRectanglePerimeter(left: Float, top: Float, right: Float, bottom: Float) {
        ambientActive = true
        ambientKind = AmbientKind.RECTANGLE_PERIMETER
        ambientPhase01 = 0f
        ambientX0 = left
        ambientY0 = top
        ambientX1 = right
        ambientY1 = bottom
    }

    /** Same shape as [setAmbientRectanglePerimeter], for the one circular region (the radial
     *  ring's own wedge arc). [startDeg]/[sweepDeg] are [android.graphics.Canvas.drawArc]'s own
     *  convention, matching [EmitterShape.arcX]/[EmitterShape.arcY]. */
    fun setAmbientArc(centerX: Float, centerY: Float, radius: Float, startDeg: Float, sweepDeg: Float) {
        ambientActive = true
        ambientKind = AmbientKind.ARC
        ambientPhase01 = 0f
        ambientX0 = centerX
        ambientY0 = centerY
        ambientX1 = radius
        ambientStartDeg = startDeg
        ambientSweepDeg = sweepDeg
    }

    fun stopAmbient() {
        ambientActive = false
    }

    fun clear() {
        for (slot in 0 until capacity) {
            liveFlags[slot] = false
        }
        spawnAccumulator = 0f
        ambientActive = false
    }

    /**
     * Advances every live particle and the ambient spawn accumulator by [rawDeltaSeconds] of
     * real time. Returns whether the caller should keep asking for frames (something is still
     * live, or the ambient emitter is still active) -- exactly the condition
     * [ParticleField]'s own frame-scheduling decision needs.
     *
     * [rawDeltaSeconds] reaches the spawn accumulator unclamped, so a stall (the view not
     * drawing for a while) does not thicken the ambient trickle's long-run rate -- only
     * [MAX_DELTA_SECONDS] of it reaches age (and a traveling ambient's own phase advance), so
     * already-live particles do not jump by the whole gap in one frame.
     */
    fun advance(rawDeltaSeconds: Float): Boolean {
        val scaledDeltaSeconds = min(rawDeltaSeconds, MAX_DELTA_SECONDS) * speedMultiplier

        if (ambientActive && preset.travelLoopsPerSecond > 0f) {
            ambientPhase01 = (ambientPhase01 + preset.travelLoopsPerSecond * scaledDeltaSeconds) % 1f
            if (ambientPhase01 < 0f) {
                ambientPhase01 += 1f
            }
        }

        val effectiveMaxParticles = (preset.maxParticles * densityMultiplier).roundToInt().coerceIn(1, capacity)
        if (ambientActive && liveCount < effectiveMaxParticles) {
            spawnAccumulator += preset.spawnRatePerSecond * densityMultiplier * rawDeltaSeconds
            while (spawnAccumulator >= 1f && liveCount < effectiveMaxParticles) {
                spawnAccumulator -= 1f
                spawnFromAmbient()
            }
        }

        for (slot in 0 until capacity) {
            if (!liveFlags[slot]) {
                continue
            }
            val nextAge = ageSeconds[slot] + scaledDeltaSeconds
            val stillVisible = nextAge < preset.lifetimeSeconds && radiusAt(slot, nextAge) > MIN_VISIBLE_RADIUS_PX
            if (stillVisible) {
                ageSeconds[slot] = nextAge
            } else {
                liveFlags[slot] = false
            }
        }

        return hasLiveParticles || ambientActive
    }

    fun isLive(slot: Int): Boolean = liveFlags[slot]

    fun lifeFractionAt(slot: Int): Float = 1f - (ageSeconds[slot] / preset.lifetimeSeconds).coerceIn(0f, 1f)

    fun xAt(slot: Int): Float = xAt(slot, ageSeconds[slot])

    fun yAt(slot: Int): Float = yAt(slot, ageSeconds[slot])

    fun radiusAt(slot: Int): Float = radiusAt(slot, ageSeconds[slot])

    /** Every preset's own colour function returns its "natural" colour -- opaque, or (Neon
     *  Pulse) already carrying its own flicker alpha -- and the particle's life is applied
     *  uniformly on top of all of them here, so every preset fades out at the end of its life
     *  without each colour function having to remember to do that itself. */
    fun colorAt(slot: Int, primaryColor: Int, secondaryColor: Int): Int {
        val life = lifeFractionAt(slot)
        val age = ageSeconds[slot]
        val base = when (preset.color) {
            ParticleColorKind.THERMAL_GRADIENT -> ParticleColor.thermalGradient(life, primaryColor, secondaryColor)
            ParticleColorKind.CROSSFADE -> ParticleColor.crossfade(life, primaryColor, secondaryColor)
            ParticleColorKind.HUE_CYCLE ->
                ParticleColor.hueCycle(spawnX[slot], age, preset.hueOffsetDegPerPx, preset.hueDegPerSecond)
            ParticleColorKind.SINGLE_COLOR_PULSE ->
                ParticleColor.singleColorPulse(primaryColor, age, preset.pulseFrequencyHz)
        }
        val baseAlpha = (base ushr 24) and 0xFF
        val alpha = (baseAlpha * life).toInt().coerceIn(0, 255)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun scaledCount(count: Int): Int = (count * densityMultiplier).roundToInt().coerceAtLeast(1)

    private fun spawnAt(x: Float, y: Float) {
        val slot = liveFlags.indexOf(false)
        if (slot < 0) {
            // Pool full. Same policy as KeyboardCanvasView.startPress's own full-pool branch:
            // drop the newest rather than evict something already live.
            return
        }
        liveFlags[slot] = true
        spawnX[slot] = x
        spawnY[slot] = y
        ageSeconds[slot] = 0f
        val minR = preset.minRadiusPx * widthMultiplier
        val maxR = preset.maxRadiusPx * widthMultiplier
        spawnRadius[slot] = minR + (maxR - minR) * Random.nextFloat()
    }

    private fun spawnFromAmbient() {
        when (ambientKind) {
            AmbientKind.POINT -> spawnAt(ambientX0, ambientY0)
            AmbientKind.RECTANGLE -> spawnAt(
                EmitterShape.rectangleX(ambientX0, ambientX1, Random.nextFloat()),
                EmitterShape.rectangleY(ambientY0, ambientY1, Random.nextFloat()),
            )
            AmbientKind.RECTANGLE_PERIMETER -> {
                val phase = if (preset.travelLoopsPerSecond > 0f) ambientPhase01 else Random.nextFloat()
                spawnAt(
                    EmitterShape.rectanglePerimeterX(ambientX0, ambientY0, ambientX1, ambientY1, phase),
                    EmitterShape.rectanglePerimeterY(ambientX0, ambientY0, ambientX1, ambientY1, phase),
                )
            }
            AmbientKind.ARC -> {
                val phase = if (preset.travelLoopsPerSecond > 0f) ambientPhase01 else Random.nextFloat()
                spawnAt(
                    EmitterShape.arcX(ambientX0, ambientX1, ambientStartDeg, ambientSweepDeg, phase),
                    EmitterShape.arcY(ambientY0, ambientX1, ambientStartDeg, ambientSweepDeg, phase),
                )
            }
        }
    }

    private fun xAt(slot: Int, age: Float): Float = when (preset.motion) {
        ParticleMotionKind.RISE_AND_SHRINK -> ParticleMotion.riseAndShrinkX(spawnX[slot], age, preset.driftPxPerSecond)
        else -> spawnX[slot]
    }

    private fun yAt(slot: Int, age: Float): Float = when (preset.motion) {
        ParticleMotionKind.RISE_AND_SHRINK ->
            ParticleMotion.riseAndShrinkY(spawnY[slot], age, preset.riseSpeedPxPerSecond)
        ParticleMotionKind.PULSE_IN_PLACE ->
            ParticleMotion.pulseInPlaceY(spawnY[slot], age, preset.pulseAmplitudePx, preset.pulseFrequencyHz)
        ParticleMotionKind.WAVE_DRIFT ->
            ParticleMotion.waveDriftY(
                spawnX[slot], spawnY[slot], age,
                preset.pulseAmplitudePx, preset.waveWavelengthPx, preset.waveSpeedRadPerSecond,
            )
        ParticleMotionKind.STATIC_FLICKER -> spawnY[slot]
    }

    private fun radiusAt(slot: Int, age: Float): Float = if (preset.motion == ParticleMotionKind.RISE_AND_SHRINK) {
        ParticleMotion.riseAndShrinkRadius(spawnRadius[slot], age, preset.shrinkPerSecond)
    } else {
        spawnRadius[slot]
    }

    companion object {
        const val MAX_DELTA_SECONDS = 0.1f
        const val MIN_VISIBLE_RADIUS_PX = 0.5f
    }
}
