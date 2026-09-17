// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import kotlin.math.max
import kotlin.math.min

/**
 * A fixed-size pool of particles, owned and driven entirely by whichever `View` wants them --
 * never a shared singleton, the same way [com.borderkeys.ime.KeyboardCanvasView] owns its own
 * key-press animation state independently of every other view in the process.
 *
 * The actual particle state and physics live in [ParticleSimulation]; this class adds the real
 * frame clock (one shared [Choreographer.FrameCallback] per instance, re-armed only while
 * something is live -- [com.borderkeys.ime.KeyboardCanvasView]'s own `scheduleFrame`/
 * `onAnimationFrame`/`animating` idiom, ported rather than reinvented) and lands the result on a
 * `Canvas`. Split specifically so the simulation itself stays plain-JVM testable -- see
 * [ParticleSimulation]'s own doc for why this half, not that one, is where `Choreographer` lives.
 */
class ParticleField(
    capacity: Int,
    private val onInvalidate: () -> Unit,
) {

    private val simulation = ParticleSimulation(capacity)

    var preset: ParticleEffectPreset
        get() = simulation.preset
        set(value) { simulation.preset = value }

    var speedMultiplier: Float
        get() = simulation.speedMultiplier
        set(value) { simulation.speedMultiplier = value }

    var densityMultiplier: Float
        get() = simulation.densityMultiplier
        set(value) { simulation.densityMultiplier = value }

    var widthMultiplier: Float
        get() = simulation.widthMultiplier
        set(value) { simulation.widthMultiplier = value }

    var primaryColor: Int = DEFAULT_COLOR
    var secondaryColor: Int = DEFAULT_COLOR

    /**
     * The exact shape a stroke should trace, for a region whose true outline
     * [ParticleSimulation]'s closed-form rect-perimeter/annular-wedge-perimeter primitives
     * cannot represent -- [ParticleGeometry.Exact], set only via [bind]. `null` (every region
     * this app actually has one of today) falls back to tracing
     * [ParticleSimulation.currentAmbientOutlineShape] as before.
     */
    private var strokePath: Path? = null

    /** Reused every frame [draw] traces a stroke into, never allocated there. Sized for the
     *  larger of the shapes [ParticleSimulation.currentAmbientOutlineShape] can fill (the
     *  annular-wedge case, 6 floats). */
    private val ambientShapeScratch = FloatArray(6)

    /** Reused by [drawAmbientStroke]'s [ParticleSimulation.AmbientOutlineShape
     *  .ANNULAR_WEDGE_PERIMETER] case to build the wedge's one closed contour (outer arc, radial
     *  edge, inner arc, radial edge) -- never allocated there. */
    private val wedgeStrokePath = Path()
    private val wedgeOuterBounds = RectF()
    private val wedgeInnerBounds = RectF()

    /** The hard gate. `false` clears every live particle, stops any ambient emission and cancels
     *  the frame callback immediately -- while off, [Choreographer.postFrameCallback] is never
     *  called at all, not merely drawing nothing. */
    var enabled: Boolean = false
        set(value) {
            if (field == value) {
                return
            }
            field = value
            if (!value) {
                cancel()
            }
        }

    val hasLiveParticles: Boolean get() = simulation.hasLiveParticles

    private var animating = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onFrame(frameTimeNanos) }

    /**
     * A one-shot burst spawned uniformly inside [geometry] -- the fill layer's press moment.
     * [burstMultiplier] times the preset's own burst count; the shape's own area scales it
     * further (see [ParticleSimulation.spawnBurstInRoundedRect]). `null`, or an
     * [ParticleGeometry.Exact] with no emitter fallback, spawns nothing.
     */
    fun burstIn(geometry: ParticleGeometry?, burstMultiplier: Int = 1) {
        if (!enabled) {
            return
        }
        val count = preset.burstCount * burstMultiplier
        when (val emitter = geometry?.emitter) {
            null -> return
            is ParticleGeometry.RoundedRect -> simulation.spawnBurstInRoundedRect(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx, count,
            )
            is ParticleGeometry.AnnularWedge -> simulation.spawnBurstInAnnularWedge(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg, count,
            )
        }
        scheduleFrame()
    }

    /**
     * Starts (or moves) a low-rate ambient trickle spawning uniformly inside [geometry] -- the
     * fill layer's held moment: the strip's applied chip while it is on screen, the ring's wedge
     * while the finger hovers it. `null` stops it, the same as [stopAmbient].
     */
    fun ambientIn(geometry: ParticleGeometry?) {
        if (!enabled) {
            return
        }
        when (val emitter = geometry?.emitter) {
            null -> simulation.stopAmbient()
            is ParticleGeometry.RoundedRect -> simulation.setAmbientRoundedRectInterior(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx,
            )
            is ParticleGeometry.AnnularWedge -> simulation.setAmbientAnnularWedgeInterior(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg,
            )
        }
        scheduleFrame()
    }

    fun stopAmbient() {
        simulation.stopAmbient()
    }

    /**
     * The outline layer's one entry point: trace [geometry]'s own perimeter -- dots walk it by
     * arc length, and a stroke style draws it -- until bound to something else or to `null`.
     * Reached only through [ParticleSurface] handing in a [ParticleElement]'s own
     * [ParticleElement.outlineGeometry], so the shape a stroke traces can never drift from the
     * shape the dots sit on or from what the element actually draws: all three are this one
     * value. The corner radius goes to the simulation rather than a field here, because that is
     * where spawn points are resolved, and [drawAmbientStroke] reads it back from the very same
     * place ([ParticleSimulation.currentAmbientOutlineShape]).
     *
     * `null` (nothing pressed/selected right now) stops the ambient the same moment a caller
     * would otherwise call [stopAmbient] directly.
     */
    fun bind(geometry: ParticleGeometry?) {
        if (!enabled) {
            return
        }
        strokePath = (geometry as? ParticleGeometry.Exact)?.path
        when (val emitter = geometry?.emitter) {
            null -> simulation.stopAmbient()
            is ParticleGeometry.RoundedRect -> simulation.setAmbientRectanglePerimeter(
                emitter.left, emitter.top, emitter.right, emitter.bottom, emitter.cornerRadiusPx,
            )
            is ParticleGeometry.AnnularWedge -> simulation.setAmbientAnnularWedgePerimeter(
                emitter.centerX, emitter.centerY, emitter.innerRadiusPx, emitter.outerRadiusPx,
                emitter.startDeg, emitter.sweepDeg,
            )
        }
        scheduleFrame()
    }

    /** Draws every live particle as a flat filled circle into [paint], mutating its colour in
     *  place per particle -- never allocating a `Paint`, matching how [Paint] is used everywhere
     *  else in this package's views. No-op while [enabled] is false.
     *
     *  When [ParticleEffectPreset.strokeWidthPx] is set (the outline "Fire"/"Wind" looks), also
     *  traces a real stroke along the current ambient shape first, under the particles -- see
     *  [ParticleSimulation.currentAmbientOutlineShape]. Every other preset leaves it at `0f` and
     *  draws exactly as before: pure particle scatter, no line. */
    fun draw(canvas: Canvas, paint: Paint) {
        if (!enabled) {
            return
        }
        if (preset.strokeWidthPx > 0f) {
            drawAmbientStroke(canvas, paint)
        }
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) {
                continue
            }
            val radius = simulation.radiusAt(slot)
            val life = simulation.lifeFractionAt(slot)
            if (radius <= 0f || life <= 0f) {
                continue
            }
            paint.color = simulation.colorAt(slot, primaryColor, secondaryColor)
            canvas.drawCircle(simulation.xAt(slot), simulation.yAt(slot), radius, paint)
        }
    }

    /** [paint] is the same shared, mutate-in-place instance every particle circle in this same
     *  frame draws with -- [com.borderkeys.ime.KeyboardCanvasView]'s own `paints.particlePaint`,
     *  reused across every field a view owns. Its style/width are saved and restored around the
     *  stroke so leaving it in [Paint.Style.STROKE] never bleeds into the very next
     *  [canvas.drawCircle] call, here or in whichever field draws after this one this frame.
     *
     *  [widthMultiplier] scales the stroke's own width here, exactly as it already scales a
     *  particle's radius -- the same "Width" slider is Outline's one thickness knob regardless
     *  of whether the current style draws circles, a line, or (Fire/Wind) both. Floored to
     *  [ParticleSimulation.MIN_LEGIBLE_SIZE_PX] so dragging Width to its minimum thins the line
     *  rather than fading it past the point of showing at all -- this method is only reached once
     *  [preset.strokeWidthPx] is already known positive, so the floor cannot conjure a stroke for
     *  a style that is not supposed to have one. */
    private fun drawAmbientStroke(canvas: Canvas, paint: Paint) {
        val strokeWidth = (preset.strokeWidthPx * widthMultiplier).coerceAtLeast(ParticleSimulation.MIN_LEGIBLE_SIZE_PX)
        val savedStyle = paint.style
        val savedWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = primaryColor
        val path = strokePath
        if (path != null) {
            canvas.drawPath(path, paint)
            paint.style = savedStyle
            paint.strokeWidth = savedWidth
            return
        }
        val shape = simulation.currentAmbientOutlineShape(ambientShapeScratch)
        if (shape == null) {
            paint.style = savedStyle
            paint.strokeWidth = savedWidth
            return
        }
        when (shape) {
            ParticleSimulation.AmbientOutlineShape.RECTANGLE_PERIMETER -> canvas.drawRoundRect(
                ambientShapeScratch[0], ambientShapeScratch[1], ambientShapeScratch[2], ambientShapeScratch[3],
                ambientShapeScratch[4], ambientShapeScratch[4], paint,
            )
            ParticleSimulation.AmbientOutlineShape.ANNULAR_WEDGE_PERIMETER -> {
                val centerX = ambientShapeScratch[0]
                val centerY = ambientShapeScratch[1]
                val innerRadius = ambientShapeScratch[2]
                val outerRadius = ambientShapeScratch[3]
                val start = ambientShapeScratch[4]
                val sweep = ambientShapeScratch[5]
                wedgeOuterBounds.set(centerX - outerRadius, centerY - outerRadius, centerX + outerRadius, centerY + outerRadius)
                wedgeInnerBounds.set(centerX - innerRadius, centerY - innerRadius, centerX + innerRadius, centerY + innerRadius)
                wedgeStrokePath.reset()
                wedgeStrokePath.arcTo(wedgeOuterBounds, start, sweep, true)
                wedgeStrokePath.arcTo(wedgeInnerBounds, start + sweep, -sweep, false)
                wedgeStrokePath.close()
                canvas.drawPath(wedgeStrokePath, paint)
            }
        }
        paint.style = savedStyle
        paint.strokeWidth = savedWidth
    }

    /**
     * The tight bounding box of every live particle, for
     * [com.borderkeys.ime.KeyboardCanvasView]'s own dirty-rect invalidation -- the one view among
     * this package's callers that must not fall back to a bare `invalidate()`. Returns false
     * (and leaves [out] untouched) when nothing is live.
     */
    fun computeLiveBounds(out: RectF): Boolean {
        var found = false
        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f
        for (slot in 0 until simulation.capacity) {
            if (!simulation.isLive(slot)) {
                continue
            }
            val x = simulation.xAt(slot)
            val y = simulation.yAt(slot)
            val r = simulation.radiusAt(slot)
            if (!found) {
                left = x - r
                top = y - r
                right = x + r
                bottom = y + r
                found = true
            } else {
                left = min(left, x - r)
                top = min(top, y - r)
                right = max(right, x + r)
                bottom = max(bottom, y + r)
            }
        }
        if (found) {
            out.set(left, top, right, bottom)
        }
        return found
    }

    /** Call from `onDetachedFromWindow`. [Choreographer] is a process-global singleton, not tied
     *  to a view's lifecycle -- nothing else would cancel a pending callback on detach. */
    fun cancel() {
        if (animating) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
        }
        simulation.clear()
    }

    private fun scheduleFrame() {
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onFrame(frameTimeNanos: Long) {
        val rawDeltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
        }
        lastFrameNanos = frameTimeNanos

        val stillAnimating = simulation.advance(rawDeltaSeconds)
        onInvalidate()

        if (stillAnimating) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            animating = false
        }
    }

    private companion object {
        const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    }
}
