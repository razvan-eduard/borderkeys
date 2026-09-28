// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The particle pool's state and physics: [ParticleField] without the frame clock and the canvas,
 * and nothing from `android.*`. A particle is its spawn point, age and radius, since
 * [ParticleMotion] is closed-form in age. Nothing allocates past construction.
 */
class ParticleSimulation(val capacity: Int) {

    private val liveFlags = BooleanArray(capacity)
    private val spawnX = FloatArray(capacity)
    private val spawnY = FloatArray(capacity)
    private val ageSeconds = FloatArray(capacity)
    private val spawnRadius = FloatArray(capacity)

    /**
     * The outline's outward unit normal where the particle spawned, zero inside a shape or at a
     * point: an outline particle is pushed out along it by its radius, and
     * [ParticleMotionKind.OUTWARD] moves along it.
     */
    private val normalX = FloatArray(capacity)
    private val normalY = FloatArray(capacity)

    /** Scratch for a perimeter sample: x, y, normalX, normalY. */
    private val sampleScratch = FloatArray(4)

    var preset: ParticleEffectPreset = ParticleEffectPresets.GLOW
    var speedMultiplier: Float = 1f

    /** Scales how many particles an ambient keeps alive and how many a burst spawns. */
    var densityMultiplier: Float = 1f

    /** Scales [ParticleEffectPreset.minRadiusPx] and [ParticleEffectPreset.maxRadiusPx]. */
    var widthMultiplier: Float = 1f

    private enum class AmbientKind {
        POINT, RECTANGLE, RECTANGLE_PERIMETER, ANNULAR_WEDGE_PERIMETER,
        ROUNDED_RECT_INTERIOR, ANNULAR_WEDGE_INTERIOR,
    }

    var ambientActive: Boolean = false
        private set
    private var ambientKind = AmbientKind.POINT

    /**
     * A travelling emission point's position along the outline, 0..1 and wrapping, advanced in
     * [advance] at [ParticleEffectPreset.travelLoopsPerSecond].
     */
    private var ambientPhase01 = 0f

    // Rectangle kinds: left, top, right, bottom (a point: x, y). Wedge kinds: centerX, centerY,
    // innerRadius, outerRadius, then the start and sweep angles.
    private var ambientX0 = 0f
    private var ambientY0 = 0f
    private var ambientX1 = 0f
    private var ambientY1 = 0f
    private var ambientStartDeg = 0f
    private var ambientSweepDeg = 0f

    /** The rounded rectangle's corner radius; `0f` is a sharp rectangle. */
    private var ambientCornerRadius = 0f
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

    /**
     * An ambient along a rounded rectangle's outline, corners included: a random point per spawn,
     * or the travelling point for a preset that travels, which starts again from 0.
     */
    fun setAmbientRectanglePerimeter(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float = 0f) {
        ambientActive = true
        ambientKind = AmbientKind.RECTANGLE_PERIMETER
        ambientPhase01 = 0f
        ambientX0 = left
        ambientY0 = top
        ambientX1 = right
        ambientY1 = bottom
        ambientCornerRadius = cornerRadius.coerceAtLeast(0f)
    }

    /** An ambient spawning uniformly inside a rounded rectangle. */
    fun setAmbientRoundedRectInterior(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float = 0f) {
        ambientActive = true
        ambientKind = AmbientKind.ROUNDED_RECT_INTERIOR
        ambientPhase01 = 0f
        ambientX0 = left
        ambientY0 = top
        ambientX1 = right
        ambientY1 = bottom
        ambientCornerRadius = cornerRadius.coerceAtLeast(0f)
    }

    /** An ambient spawning uniformly by area inside an annular wedge. */
    fun setAmbientAnnularWedgeInterior(
        centerX: Float,
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
    ) {
        ambientActive = true
        ambientKind = AmbientKind.ANNULAR_WEDGE_INTERIOR
        ambientPhase01 = 0f
        ambientX0 = centerX
        ambientY0 = centerY
        ambientX1 = innerRadius
        ambientY1 = outerRadius
        ambientStartDeg = startDeg
        ambientSweepDeg = sweepDeg
    }

    /** A burst spawned uniformly inside a rounded rectangle, [count] scaled by its area. */
    fun spawnBurstInRoundedRect(left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float, count: Int) {
        val factor = extentFactorForArea(EmitterShape.roundedRectArea(left, top, right, bottom, cornerRadius))
        repeat(scaledCount(count, factor)) {
            spawnAt(
                EmitterShape.roundedRectInteriorX(left, top, right, bottom, cornerRadius, Random.nextFloat(), Random.nextFloat()),
                EmitterShape.roundedRectInteriorY(left, top, right, bottom, cornerRadius, Random.nextFloat(), Random.nextFloat()),
            )
        }
    }

    /** [spawnBurstInRoundedRect]'s equivalent for the ring's wedge. */
    fun spawnBurstInAnnularWedge(
        centerX: Float,
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
        count: Int,
    ) {
        val factor = extentFactorForArea(EmitterShape.annularWedgeArea(innerRadius, outerRadius, sweepDeg))
        repeat(scaledCount(count, factor)) {
            val u = Random.nextFloat()
            val v = Random.nextFloat()
            spawnAt(
                EmitterShape.annularWedgeInteriorX(centerX, innerRadius, outerRadius, startDeg, sweepDeg, u, v),
                EmitterShape.annularWedgeInteriorY(centerY, innerRadius, outerRadius, startDeg, sweepDeg, u, v),
            )
        }
    }

    /** An ambient along an annular wedge's outline; see [ParticleGeometry.AnnularWedge]. */
    fun setAmbientAnnularWedgePerimeter(
        centerX: Float,
        centerY: Float,
        innerRadius: Float,
        outerRadius: Float,
        startDeg: Float,
        sweepDeg: Float,
    ) {
        ambientActive = true
        ambientKind = AmbientKind.ANNULAR_WEDGE_PERIMETER
        ambientPhase01 = 0f
        ambientX0 = centerX
        ambientY0 = centerY
        ambientX1 = innerRadius
        ambientY1 = outerRadius
        ambientStartDeg = startDeg
        ambientSweepDeg = sweepDeg
    }

    fun stopAmbient() {
        ambientActive = false
    }

    /** The ambient kinds with an outline to trace. */
    enum class AmbientOutlineShape { RECTANGLE_PERIMETER, ANNULAR_WEDGE_PERIMETER }

    /**
     * Fills [out] with the current ambient's outline: left, top, right, bottom and corner radius
     * for [AmbientOutlineShape.RECTANGLE_PERIMETER]; centerX, centerY, innerRadius, outerRadius,
     * startDeg and sweepDeg for [AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER]. Null, leaving
     * [out] untouched, while no ambient with an outline is active.
     */
    fun currentAmbientOutlineShape(out: FloatArray): AmbientOutlineShape? {
        if (!ambientActive) {
            return null
        }
        return when (ambientKind) {
            AmbientKind.RECTANGLE_PERIMETER -> {
                out[0] = ambientX0
                out[1] = ambientY0
                out[2] = ambientX1
                out[3] = ambientY1
                out[4] = ambientCornerRadius
                AmbientOutlineShape.RECTANGLE_PERIMETER
            }
            AmbientKind.ANNULAR_WEDGE_PERIMETER -> {
                out[0] = ambientX0
                out[1] = ambientY0
                out[2] = ambientX1
                out[3] = ambientY1
                out[4] = ambientStartDeg
                out[5] = ambientSweepDeg
                AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER
            }
            else -> null
        }
    }

    fun clear() {
        for (slot in 0 until capacity) {
            liveFlags[slot] = false
        }
        spawnAccumulator = 0f
        ambientActive = false
    }

    /**
     * Advances every live particle and the ambient spawn accumulator by [rawDeltaSeconds]. The
     * accumulator takes the whole delta; ages and the travelling phase take at most
     * [MAX_DELTA_SECONDS] of it. Returns whether anything is live or the ambient is active.
     */
    fun advance(rawDeltaSeconds: Float): Boolean {
        val scaledDeltaSeconds = min(rawDeltaSeconds, MAX_DELTA_SECONDS) * speedMultiplier

        if (ambientActive && preset.travelLoopsPerSecond > 0f) {
            ambientPhase01 = (ambientPhase01 + preset.travelLoopsPerSecond * scaledDeltaSeconds) % 1f
            if (ambientPhase01 < 0f) {
                ambientPhase01 += 1f
            }
        }

        // A shape larger than a key gets proportionally more particles.
        val extent = ambientExtentFactor()
        val effectiveMaxParticles = (preset.maxParticles * densityMultiplier * extent).roundToInt().coerceIn(1, capacity)
        if (ambientActive && liveCount < effectiveMaxParticles) {
            spawnAccumulator += preset.spawnRatePerSecond * densityMultiplier * extent * rawDeltaSeconds
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

    /** The preset's colour for [slot], its alpha scaled by the particle's remaining life. */
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

    private fun scaledCount(count: Int, extentFactor: Float = 1f): Int =
        (count * densityMultiplier * extentFactor).roundToInt().coerceAtLeast(1)

    /**
     * How much bigger than a key-sized element the current ambient shape is, from 1 to
     * [MAX_EXTENT_FACTOR]: its perimeter against [REFERENCE_PERIMETER_PX], or its area against
     * [REFERENCE_AREA_PX].
     */
    private fun ambientExtentFactor(): Float = when (ambientKind) {
        AmbientKind.POINT -> 1f
        AmbientKind.RECTANGLE -> extentFactorForArea((ambientX1 - ambientX0) * (ambientY1 - ambientY0))
        AmbientKind.RECTANGLE_PERIMETER -> extentFactorForLength(
            EmitterShape.roundedRectPerimeterLength(ambientX0, ambientY0, ambientX1, ambientY1, ambientCornerRadius),
        )
        AmbientKind.ROUNDED_RECT_INTERIOR -> extentFactorForArea(
            EmitterShape.roundedRectArea(ambientX0, ambientY0, ambientX1, ambientY1, ambientCornerRadius),
        )
        AmbientKind.ANNULAR_WEDGE_PERIMETER -> extentFactorForLength(
            EmitterShape.annularWedgePerimeterLength(ambientX1, ambientY1, ambientSweepDeg),
        )
        AmbientKind.ANNULAR_WEDGE_INTERIOR -> extentFactorForArea(
            EmitterShape.annularWedgeArea(ambientX1, ambientY1, ambientSweepDeg),
        )
    }

    private fun extentFactorForLength(lengthPx: Float): Float =
        (lengthPx / REFERENCE_PERIMETER_PX).coerceIn(1f, MAX_EXTENT_FACTOR)

    private fun extentFactorForArea(areaPx: Float): Float =
        (areaPx / REFERENCE_AREA_PX).coerceIn(1f, MAX_EXTENT_FACTOR)

    private fun spawnAt(x: Float, y: Float, nx: Float = 0f, ny: Float = 0f) {
        val slot = liveFlags.indexOf(false)
        if (slot < 0) {
            // With the pool full, the new particle is dropped.
            return
        }
        liveFlags[slot] = true
        spawnX[slot] = x
        spawnY[slot] = y
        normalX[slot] = nx
        normalY[slot] = ny
        ageSeconds[slot] = 0f
        val minR = (preset.minRadiusPx * widthMultiplier).coerceAtLeast(MIN_LEGIBLE_SIZE_PX)
        val maxR = (preset.maxRadiusPx * widthMultiplier).coerceAtLeast(MIN_LEGIBLE_SIZE_PX)
        spawnRadius[slot] = minR + (maxR - minR) * Random.nextFloat()
    }

    /**
     * Spawns one particle on the current perimeter ambient, trying up to [EMIT_CONE_TRIES] random
     * points for one inside the preset's emission cone; a travelling preset ignores the cone.
     */
    private fun spawnOnPerimeter(sample: (Float) -> Unit) {
        val traveling = preset.travelLoopsPerSecond > 0f
        val coned = !traveling && preset.emitConeCos > -1f &&
            (preset.emitDirectionX != 0f || preset.emitDirectionY != 0f)
        val tries = if (coned) EMIT_CONE_TRIES else 1
        repeat(tries) {
            sample(if (traveling) ambientPhase01 else Random.nextFloat())
            val nx = sampleScratch[2]
            val ny = sampleScratch[3]
            if (!coned || nx * preset.emitDirectionX + ny * preset.emitDirectionY >= preset.emitConeCos) {
                spawnAt(sampleScratch[0], sampleScratch[1], nx, ny)
                return
            }
        }
    }

    private fun spawnFromAmbient() {
        when (ambientKind) {
            AmbientKind.POINT -> spawnAt(ambientX0, ambientY0)
            AmbientKind.RECTANGLE -> spawnAt(
                EmitterShape.rectangleX(ambientX0, ambientX1, Random.nextFloat()),
                EmitterShape.rectangleY(ambientY0, ambientY1, Random.nextFloat()),
            )
            AmbientKind.RECTANGLE_PERIMETER -> spawnOnPerimeter { phase ->
                EmitterShape.roundedRectPerimeterSample(
                    ambientX0, ambientY0, ambientX1, ambientY1, ambientCornerRadius, phase, sampleScratch,
                )
            }
            AmbientKind.ANNULAR_WEDGE_PERIMETER -> spawnOnPerimeter { phase ->
                EmitterShape.annularWedgePerimeterSample(
                    ambientX0, ambientY0, ambientX1, ambientY1, ambientStartDeg, ambientSweepDeg, phase, sampleScratch,
                )
            }
            AmbientKind.ROUNDED_RECT_INTERIOR -> {
                val u = Random.nextFloat()
                val v = Random.nextFloat()
                spawnAt(
                    EmitterShape.roundedRectInteriorX(ambientX0, ambientY0, ambientX1, ambientY1, ambientCornerRadius, u, v),
                    EmitterShape.roundedRectInteriorY(ambientX0, ambientY0, ambientX1, ambientY1, ambientCornerRadius, u, v),
                )
            }
            AmbientKind.ANNULAR_WEDGE_INTERIOR -> {
                val u = Random.nextFloat()
                val v = Random.nextFloat()
                spawnAt(
                    EmitterShape.annularWedgeInteriorX(ambientX0, ambientX1, ambientY1, ambientStartDeg, ambientSweepDeg, u, v),
                    EmitterShape.annularWedgeInteriorY(ambientY0, ambientX1, ambientY1, ambientStartDeg, ambientSweepDeg, u, v),
                )
            }
        }
    }

    /** A particle's origin: its spawn point pushed out along its normal by its radius. */
    private fun originX(slot: Int): Float = spawnX[slot] + normalX[slot] * spawnRadius[slot]

    private fun originY(slot: Int): Float = spawnY[slot] + normalY[slot] * spawnRadius[slot]

    private fun xAt(slot: Int, age: Float): Float = when (preset.motion) {
        ParticleMotionKind.RISE_AND_SHRINK -> ParticleMotion.riseAndShrinkX(originX(slot), age, preset.driftPxPerSecond)
        ParticleMotionKind.OUTWARD -> ParticleMotion.outwardX(
            originX(slot), normalX[slot], preset.emitDirectionX, age,
            preset.outwardSpeedPxPerSecond, preset.driftPxPerSecond,
        )
        else -> originX(slot)
    }

    private fun yAt(slot: Int, age: Float): Float = when (preset.motion) {
        ParticleMotionKind.RISE_AND_SHRINK ->
            ParticleMotion.riseAndShrinkY(originY(slot), age, preset.riseSpeedPxPerSecond)
        ParticleMotionKind.OUTWARD -> ParticleMotion.outwardY(
            originY(slot), normalY[slot], preset.emitDirectionY, age,
            preset.outwardSpeedPxPerSecond, preset.driftPxPerSecond,
        )
        ParticleMotionKind.PULSE_IN_PLACE ->
            ParticleMotion.pulseInPlaceY(originY(slot), age, preset.pulseAmplitudePx, preset.pulseFrequencyHz)
        ParticleMotionKind.WAVE_DRIFT ->
            ParticleMotion.waveDriftY(
                originX(slot), originY(slot), age,
                preset.pulseAmplitudePx, preset.waveWavelengthPx, preset.waveSpeedRadPerSecond,
            )
        ParticleMotionKind.STATIC_FLICKER -> originY(slot)
    }

    private fun radiusAt(slot: Int, age: Float): Float =
        if (preset.motion == ParticleMotionKind.RISE_AND_SHRINK || preset.motion == ParticleMotionKind.OUTWARD) {
            ParticleMotion.riseAndShrinkRadius(spawnRadius[slot], age, preset.shrinkPerSecond)
        } else {
            spawnRadius[slot]
        }

    companion object {
        const val MAX_DELTA_SECONDS = 0.1f

        /** A key-sized element's perimeter, the presets' rates and caps are for. */
        const val REFERENCE_PERIMETER_PX = 400f

        /** A key-sized element's area, the presets' rates and caps are for. */
        const val REFERENCE_AREA_PX = 12_000f

        /** How many random outline points [spawnOnPerimeter] tries for one inside the cone. */
        const val EMIT_CONE_TRIES = 6

        /** The most [ambientExtentFactor] scales a shape's particle count by. */
        const val MAX_EXTENT_FACTOR = 3f

        /** The radius below which a shrinking particle is freed. */
        const val MIN_VISIBLE_RADIUS_PX = 0.5f

        /** The smallest particle radius and outline stroke width after [widthMultiplier]. */
        const val MIN_LEGIBLE_SIZE_PX = 1.5f
    }
}
