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

    /** The outline's outward unit normal where the particle spawned, or zero for a particle
     *  spawned inside a shape or at a point. Two things read it: every outline particle is
     *  pushed out along it by its own radius so it sits tangent to the edge, wholly outside the
     *  element, and [ParticleMotionKind.OUTWARD] moves along it. */
    private val normalX = FloatArray(capacity)
    private val normalY = FloatArray(capacity)

    /** Reused by the perimeter spawns for [EmitterShape.roundedRectPerimeterSample] and friends
     *  -- x, y, normalX, normalY -- never allocated per spawn. */
    private val sampleScratch = FloatArray(4)

    var preset: ParticleEffectPreset = ParticleEffectPresets.GLOW
    var speedMultiplier: Float = 1f

    /** Scales how many particles an ambient trickle keeps alive and how fast a burst's own
     *  count is, both at once -- a denser trace along a perimeter reads as visually thicker
     *  without a separate axis just for that. */
    var densityMultiplier: Float = 1f

    /** Scales [ParticleEffectPreset.minRadiusPx]/[ParticleEffectPreset.maxRadiusPx] -- Outline's
     *  own separate thickness knob; Fill layers leave this at its harmless default of `1f`. */
    var widthMultiplier: Float = 1f

    private enum class AmbientKind {
        POINT, RECTANGLE, RECTANGLE_PERIMETER, ANNULAR_WEDGE_PERIMETER,
        ROUNDED_RECT_INTERIOR, ANNULAR_WEDGE_INTERIOR,
    }

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
    // POINT, X1/Y1 unused). ANNULAR_WEDGE_PERIMETER reads X0/Y0/X1/Y1 as
    // centerX/centerY/innerRadius/outerRadius -- reusing the same six floats rather than adding
    // a second set that would only ever be live for one kind at a time.
    private var ambientX0 = 0f
    private var ambientY0 = 0f
    private var ambientX1 = 0f
    private var ambientY1 = 0f
    private var ambientStartDeg = 0f
    private var ambientSweepDeg = 0f

    /** RECTANGLE_PERIMETER only: the corner radius the traced shape is actually drawn with, so
     *  both the spawn walk ([EmitterShape.roundedRectPerimeterX]) and the stroke
     *  [ParticleField] draws read the one value the caller gave -- the two can never disagree
     *  about where a corner is. `0f` is a sharp rectangle. */
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

    /** A random point along the perimeter is picked fresh on every spawn when the current
     *  [preset] does not travel ([ParticleEffectPreset.travelLoopsPerSecond] `<= 0f` -- Pulse,
     *  Sparkle); a preset that does travel (Comet) instead uses the one shared [ambientPhase01]
     *  this instance continuously advances in [advance]. [ambientPhase01] always resets to 0
     *  here regardless, so a later traveling call never inherits a stale position left over
     *  from wherever a previous ambient (on a different rect, or a non-traveling preset) last
     *  put it.
     *
     *  [cornerRadius] is the radius the shape is *drawn* with: spawn points walk the rounded
     *  outline itself, corners included, never the sharp bounding box -- see
     *  [EmitterShape.roundedRectPerimeterX] for why that distinction is visible. */
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

    /** The fill-layer counterpart of [setAmbientRectanglePerimeter]: an ambient trickle spawning
     *  uniformly *inside* the rounded rectangle -- see [EmitterShape.roundedRectInteriorX] for
     *  how the rounded corners are respected. */
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

    /** The fill-layer counterpart of [setAmbientAnnularWedgePerimeter]: an ambient trickle
     *  spawning uniformly by area inside the wedge -- see [EmitterShape.annularWedgeInteriorX]. */
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

    /** A one-shot burst spawned uniformly inside a rounded rectangle -- a key going down, a row
     *  picked. [count] is scaled by the shape's own area the same way an ambient's rate is (see
     *  [ambientExtentFactor]), so a big element gets a visibly bigger burst than a small one
     *  rather than the same ten dots lost in it. */
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

    /** Same idea as [setAmbientRectanglePerimeter], for the one shape in this app that is
     *  neither a rectangle nor a full circle -- the radial suggestion ring's own highlighted
     *  wedge. See [ParticleGeometry.AnnularWedge] for what each parameter means;
     *  [EmitterShape.annularWedgePerimeterX]/[EmitterShape.annularWedgePerimeterY] do the actual
     *  perimeter-length sampling this feeds into [spawnFromAmbient]. */
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

    /** Which shape [currentAmbientOutlineShape] just filled -- only the ambient kinds a drawn
     *  stroke ever traces; a plain point/rectangle *fill* is never asked to. */
    enum class AmbientOutlineShape { RECTANGLE_PERIMETER, ANNULAR_WEDGE_PERIMETER }

    /**
     * Fills [out] with the current ambient's own shape, for [ParticleField.draw] to trace as a
     * real stroke -- left/top/right/bottom/cornerRadius for
     * [AmbientOutlineShape.RECTANGLE_PERIMETER][0..4], or
     * centerX/centerY/innerRadius/outerRadius/startDeg/sweepDeg for
     * [AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER][0..5]. Returns null (leaving [out] untouched)
     * while ambient is off, or is a kind with no border to trace.
     *
     * A plain `FloatArray` out-parameter rather than an `android.graphics.RectF`/similar, the
     * same reasoning [ParticleField.computeLiveBounds] doesn't live here either -- see this
     * class's own doc on staying free of every `android.*` import so it stays plain-JVM
     * testable.
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

        // A preset's numbers describe a key-sized element; a shape several times that size gets
        // proportionally more dots, or a long outline reads as a handful of specks and a wide
        // wedge's fill as empty -- see ambientExtentFactor.
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

    private fun scaledCount(count: Int, extentFactor: Float = 1f): Int =
        (count * densityMultiplier * extentFactor).roundToInt().coerceAtLeast(1)

    /**
     * How much bigger than a reference key-sized element the current ambient shape is -- 1 for
     * anything that size or smaller, rising to [MAX_EXTENT_FACTOR] for the largest shapes this
     * app draws (the ring's wedge, a full strip row). Perimeter kinds compare their length to
     * [REFERENCE_PERIMETER_PX], interior kinds their area to [REFERENCE_AREA_PX]; a point has no
     * extent. Recomputed per frame from the shape's own numbers rather than cached per setter,
     * so it can never go stale against them -- a handful of multiplications, nothing more.
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
            // Pool full. Same policy as KeyboardCanvasView.startPress's own full-pool branch:
            // drop the newest rather than evict something already live.
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
     * Spawns one particle on the current perimeter ambient, at [sampleScratch]'s point and
     * normal, unless the preset's emission cone rejects that point -- in which case up to
     * [EMIT_CONE_TRIES] fresh random points are tried before giving up on this spawn. A
     * traveling preset (Comet) ignores the cone: its emission point is where the head is.
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

    /** A perimeter particle's origin is its spawn point pushed out along the outline's normal by
     *  its own radius, so the whole dot sits outside the element, tangent to the edge -- an
     *  outline effect is never seen inside; that is the fill layer's job. Zero for anything
     *  spawned inside a shape or at a point, where there is no normal. */
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

        /** A key-sized element's outline, roughly: every preset's spawn rate and particle cap
         *  were tuned against one, and [ambientExtentFactor] scales up from here. */
        const val REFERENCE_PERIMETER_PX = 400f

        /** A key-sized element's area, the fill layer's counterpart of [REFERENCE_PERIMETER_PX]. */
        const val REFERENCE_AREA_PX = 12_000f

        /** How many random outline points [spawnOnPerimeter] tries before conceding that the
         *  preset's emission cone has nothing to offer this frame -- a half-plane cone accepts
         *  roughly half the outline, so this is plenty. */
        const val EMIT_CONE_TRIES = 6

        /** The ceiling on [ambientExtentFactor]: past three keys' worth of outline or area, a
         *  shape gets no denser, so the largest surfaces stay an accent rather than a wall of
         *  dots -- and stay within every host's own pool capacity. */
        const val MAX_EXTENT_FACTOR = 3f

        /** Below this, a *shrinking* [ParticleMotionKind.RISE_AND_SHRINK] particle is treated as
         *  fully faded and its slot freed -- an end-of-life threshold, not a "still legible" one.
         *  See [MIN_LEGIBLE_SIZE_PX] for the floor that keeps a particle visible in the first
         *  place; the two stay far apart on purpose so a particle can still shrink through a wide
         *  range before this cull point ever triggers. */
        const val MIN_VISIBLE_RADIUS_PX = 0.5f

        /** Floor applied to a spawned particle's radius ([spawnAt]) and, via
         *  [ParticleField.drawAmbientStroke], an outline stroke's width -- both *after*
         *  [widthMultiplier] scales them down. [ParticleEffectsSettings.MIN_WIDTH] (0.5x) halving
         *  an already-small base radius (Sparkle's 1.5px) would otherwise shrink it to a
         *  sub-pixel speck that reads as "this style stopped working" rather than "this style is
         *  thinner now." Set to the smallest base radius any preset already uses at its own
         *  default (1x) width, so the floor never looks bigger than a preset's normal size --
         *  only the low end of the Width slider's range stops disappearing. */
        const val MIN_LEGIBLE_SIZE_PX = 1.5f
    }
}
