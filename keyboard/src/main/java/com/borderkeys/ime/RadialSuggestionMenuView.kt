// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Trace
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.borderkeys.ime.fx.AnnularWedgeElement
import com.borderkeys.ime.fx.ParticleElement
import com.borderkeys.ime.fx.ParticleGeometry
import com.borderkeys.ime.fx.ParticleSurface
import com.borderkeys.ime.fx.RoundedRectElement
import com.borderkeys.theme.ThemePaints
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A ring of alternative words around a paused swipe's finger, with a Cancel button at its centre,
 * drawn over the whole host. The steering finger's touches go to [KeyboardCanvasView]: steering is
 * pushed in through [steerTo] and the pick read through [currentSelection]. Only while
 * [acceptsOwnTouches] does this view handle touches itself.
 */
@SuppressLint("ViewConstructor")
class RadialSuggestionMenuView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    /** What the finger is over, read when the pick resolves. */
    sealed interface Selection {
        data object None : Selection
        data object Cancel : Selection
        data class Word(val index: Int, val word: String) : Selection
    }

    /** Called only while [acceptsOwnTouches]. */
    interface Listener {
        /** A tap that started on the ring resolved to [selection]. */
        fun onRadialTapResolved(selection: Selection)

        /** A touch landed outside the ring while it waited for a tap; the touch is consumed. */
        fun onRadialDismissed()
    }

    var listener: Listener? = null

    private var words: List<String> = emptyList()

    /** The wedge holding the word already composing in the field, outlined, or -1. */
    private var trustedIndex: Int = -1

    /** [words] as the [Selection.Word] each wedge resolves to, built in [show]. */
    private var wordSelections: List<Selection.Word> = emptyList()
    private var anchorX = 0f
    private var anchorY = 0f

    /**
     * The room above the keys ([KeyboardHostView.reserveScreenAbove]): nothing is drawn there, and
     * taps there reach this view.
     */
    var topInset: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    private var currentSelection: Selection = Selection.None

    /**
     * Whether this view handles its own touches: on after a lift that picked nothing, while
     * [com.borderkeys.data.theme.KeyboardPreferences.radialLiftKeepsOpen] is on.
     */
    var acceptsOwnTouches: Boolean = false

    /** Each word's wedge as a start angle and a sweep, from [computeWedgeBoundaries]. */
    private var wedgeStartDeg = FloatArray(0)
    private var wedgeSweepDeg = FloatArray(0)
    private val wedgeInnerBounds = RectF()
    private val wedgeOuterBounds = RectF()
    private val wedgePath = Path()

    /** The trusted wedge's path, stroked after the seams. */
    private val trustedPath = Path()

    /** Multiplies [OUTER_RADIUS_ROWS]; from
     *  [com.borderkeys.data.theme.KeyboardPreferences.radialSizeScale]. */
    var sizeScale: Float = 1f

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /**
     * Opens the ring at ([anchorX], [anchorY]), kept inside this view below [topInset]; words past
     * [MAX_WEDGES] are dropped.
     */
    fun show(anchorX: Float, anchorY: Float, words: List<String>, trustedIndex: Int = -1) {
        this.words = words.take(MAX_WEDGES)
        this.trustedIndex = if (trustedIndex in this.words.indices) trustedIndex else -1
        wordSelections = this.words.mapIndexed { index, word -> Selection.Word(index, word) }
        currentSelection = Selection.None
        recomputeWedgeBoundaries()
        // An anchor on an edge makes the ring touch that edge.
        val outer = outerRadius()
        this.anchorX = if (width > 0) {
            anchorX.coerceIn(outer, (width - outer).coerceAtLeast(outer))
        } else {
            anchorX
        }
        this.anchorY = if (height > 0) {
            anchorY.coerceIn(topInset + outer, (height - outer).coerceAtLeast(topInset + outer))
        } else {
            anchorY
        }
        particles.hold(ringElement)
        invalidate()
    }

    /** Moves an open ring by [dy], kept inside this view. */
    fun shiftBy(dy: Float) {
        if (words.isEmpty() || dy == 0f) {
            return
        }
        val outer = outerRadius()
        anchorY = if (height > 0) {
            (anchorY + dy).coerceIn(topInset + outer, (height - outer).coerceAtLeast(topInset + outer))
        } else {
            anchorY + dy
        }
        invalidate()
    }

    /** Clears the ring, its highlight, the tap-only mode and the held particles. Idempotent. */
    fun hide() {
        words = emptyList()
        wordSelections = emptyList()
        currentSelection = Selection.None
        acceptsOwnTouches = false
        ownTouchStreamActive = false
        particles.release()
        invalidate()
    }

    /** Whether crossing into a new wedge or the centre button ticks. */
    var hapticEnabled: Boolean = true

    /** The [HapticFeedbackConstants] class a wedge plays, the same as the keys'. */
    var hapticConstant: Int = HapticFeedbackConstants.KEYBOARD_TAP

    /**
     * The ring's particle layers: held in the wedge or button under the finger, and a bigger burst
     * in the picked wedge ([celebrate]).
     */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { onParticlesInvalidated() }

    /** The highlighted wedge and the centre button, as particle shapes. */
    private val wedgeElement = AnnularWedgeElement()
    private val cancelElement = RoundedRectElement()

    /** The open ring as a particle shape: its outline is the outer circle, its fill the annulus. */
    private val ringElement = object : ParticleElement() {
        override val geometry: ParticleGeometry?
            get() = if (words.isEmpty()) null else ParticleGeometry.circle(anchorX, anchorY, outerRadius())

        override val fillGeometry: ParticleGeometry?
            get() = if (words.isEmpty()) {
                null
            } else {
                ParticleGeometry.AnnularWedge(anchorX, anchorY, ringInnerRadius(), outerRadius(), 0f, 360f)
            }
    }

    /** Set while hiding waits for a [celebrate] burst to finish; the view then hides itself. */
    var pendingHide: Boolean = false

    private fun onParticlesInvalidated() {
        if (pendingHide && !particles.hasLiveParticles) {
            pendingHide = false
            visibility = GONE
        }
        invalidate()
    }

    /** Whether a burst or the held glow is still animating. */
    fun hasLiveParticles(): Boolean = particles.hasLiveParticles

    /** A bigger burst in the wedge at [index], then [hide]. */
    fun celebrate(index: Int) {
        if (index !in words.indices) {
            hide()
            return
        }
        setWedgeElement(index)
        particles.celebrate(wedgeElement, CELEBRATE_BURST_MULTIPLIER)
        hide()
    }

    /** Points [wedgeElement] at wedge [index]'s shape. */
    private fun setWedgeElement(index: Int) {
        wedgeElement.set(
            anchorX, anchorY, ringInnerRadius(), outerRadius(), wedgeStartDeg[index], wedgeSweepDeg[index],
        )
    }

    /** Moves the highlight to what ([x], [y]) lands on, in this view's pixels. */
    fun steerTo(x: Float, y: Float) {
        val hit = hitTest(x, y)
        if (hit != currentSelection) {
            currentSelection = hit
            if (hapticEnabled) {
                performHapticFeedback(hapticConstant)
            }
            // Particles are held in whatever is highlighted.
            when (hit) {
                is Selection.Word -> {
                    setWedgeElement(hit.index)
                    particles.hold(wedgeElement)
                }
                Selection.Cancel -> {
                    cancelElement.setCircle(anchorX, anchorY, centerRadius())
                    particles.hold(cancelElement)
                }
                Selection.None -> particles.hold(ringElement)
            }
            invalidate()
        }
    }

    /** What the finger is over now. */
    fun currentSelection(): Selection = currentSelection

    /** Whether the touch stream now down started on the ring; decided at its `ACTION_DOWN`. */
    private var ownTouchStreamActive = false

    /**
     * Handles a touch while [acceptsOwnTouches]: one that starts on the ring steers it and
     * resolves on lift; one that starts elsewhere dismisses the ring and is consumed.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsOwnTouches) {
            return false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ownTouchStreamActive = isWithinRing(event.x, event.y, anchorX, anchorY, outerRadius())
            if (!ownTouchStreamActive) {
                listener?.onRadialDismissed()
                return true
            }
        }
        if (!ownTouchStreamActive) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                steerTo(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP -> {
                listener?.onRadialTapResolved(currentSelection)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                listener?.onRadialTapResolved(Selection.None)
                return true
            }
        }
        return false
    }

    private fun hitTest(x: Float, y: Float): Selection {
        val dx = x - anchorX
        val dy = y - anchorY
        val distance = hypot(dx, dy)
        if (distance <= centerRadius()) {
            return Selection.Cancel
        }
        if (words.isEmpty() || distance < ringInnerRadius() || distance > outerRadius()) {
            return Selection.None
        }
        val angleDeg = normalizeDegrees(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
        for (index in words.indices) {
            val start = normalizeDegrees(wedgeStartDeg[index])
            val delta = normalizeDegrees(angleDeg - start)
            if (delta < wedgeSweepDeg[index]) {
                return wordSelections[index]
            }
        }
        return Selection.None
    }

    private fun outerRadius(): Float =
        (if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX) *
            OUTER_RADIUS_ROWS * sizeScale

    private fun ringInnerRadius(): Float = outerRadius() * RING_INNER_RADIUS_FRACTION

    private fun centerRadius(): Float = outerRadius() * CENTER_RADIUS_FRACTION

    /**
     * Whether the wedges are mirrored across the vertical axis, so the ring reads the way the
     * language on the keys does: the best rank moves from the upper right to the upper left.
     */
    var rightToLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                recomputeWedgeBoundaries()
                invalidate()
            }
        }

    /** Fills [wedgeStartDeg] and [wedgeSweepDeg] for the current words. */
    private fun recomputeWedgeBoundaries() {
        val n = words.size
        val (start, sweep) = computeWedgeBoundaries(n, rightToLeft)
        wedgeStartDeg = start
        wedgeSweepDeg = sweep
    }

    /** The centre angle of the wedge at [rank], mirrored when the ring is. */
    private fun wedgeCentre(rank: Int): Float = wedgeCentreDegrees(rank, words.size, rightToLeft)

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("RadialSuggestionMenuView.onDraw")
        try {
            // Nothing is drawn above topInset, particles included.
            if (topInset > 0f) {
                canvas.clipRect(0f, topInset, width.toFloat(), height.toFloat())
            }
            // A burst keeps animating after the words are cleared.
            if (words.isNotEmpty()) {
                drawScrim(canvas)
                drawWedges(canvas)
                drawCancelButton(canvas)
            }
            particles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawScrim(canvas: Canvas) {
        val baseAlpha = paints.background.alpha
        paints.background.alpha = SCRIM_ALPHA
        // Over the keyboard only, below topInset.
        canvas.drawRect(0f, topInset, width.toFloat(), height.toFloat(), paints.background)
        paints.background.alpha = baseAlpha
    }

    private fun drawWedges(canvas: Canvas) {
        val outer = outerRadius()
        val inner = ringInnerRadius()
        wedgeOuterBounds.set(anchorX - outer, anchorY - outer, anchorX + outer, anchorY + outer)
        wedgeInnerBounds.set(anchorX - inner, anchorY - inner, anchorX + inner, anchorY + inner)
        val midRadius = (inner + outer) / 2f
        val previousAlign = paints.label.textAlign
        paints.label.textAlign = Paint.Align.CENTER
        for (index in words.indices) {
            val fill = if (currentSelection == wordSelections[index]) {
                paints.accent
            } else {
                paints.keyFill
            }
            // An annular slice: the outer arc, then the inner arc back.
            val start = wedgeStartDeg[index]
            val sweep = wedgeSweepDeg[index]
            wedgePath.reset()
            wedgePath.arcTo(wedgeOuterBounds, start, sweep, true)
            wedgePath.arcTo(wedgeInnerBounds, start + sweep, -sweep, false)
            wedgePath.close()
            canvas.drawPath(wedgePath, fill)
            if (index == trustedIndex) {
                // Stroked after the seams.
                trustedPath.reset()
                trustedPath.addPath(wedgePath)
            }
            val midAngleRad = Math.toRadians(wedgeCentre(index).toDouble())
            val textX = anchorX + (midRadius * cos(midAngleRad)).toFloat()
            val textY = anchorY + (midRadius * sin(midAngleRad)).toFloat() +
                paints.labelBaselineOffsetPx
            canvas.drawText(words[index], textX, textY, paints.label)
        }
        paints.label.textAlign = previousAlign
        // With key outlines on: the ring's two circles and one line per wedge boundary.
        if (paints.showKeyBorders) {
            canvas.drawCircle(anchorX, anchorY, outer, paints.keyStroke)
            canvas.drawCircle(anchorX, anchorY, inner, paints.keyStroke)
            for (index in words.indices) {
                val angleRad = Math.toRadians(wedgeStartDeg[index].toDouble())
                val edgeX = anchorX + (inner * cos(angleRad)).toFloat()
                val edgeY = anchorY + (inner * sin(angleRad)).toFloat()
                val outerEdgeX = anchorX + (outer * cos(angleRad)).toFloat()
                val outerEdgeY = anchorY + (outer * sin(angleRad)).toFloat()
                canvas.drawLine(edgeX, edgeY, outerEdgeX, outerEdgeY, paints.keyStroke)
            }
        }
        // The word already in the field: its wedge outlined heavier, over the seams.
        if (trustedIndex >= 0) {
            val previousWidth = paints.appliedHighlight.strokeWidth
            paints.appliedHighlight.strokeWidth = previousWidth * TRUSTED_STROKE_SCALE
            canvas.drawPath(trustedPath, paints.appliedHighlight)
            paints.appliedHighlight.strokeWidth = previousWidth
        }
    }

    /** The centre Cancel button: a circle with an X. */
    private fun drawCancelButton(canvas: Canvas) {
        val radius = centerRadius()
        val fill = if (currentSelection == Selection.Cancel) paints.accent else paints.modifierKeyFill
        canvas.drawCircle(anchorX, anchorY, radius, fill)
        if (paints.showKeyBorders) {
            canvas.drawCircle(anchorX, anchorY, radius, paints.keyStroke)
        }
        val mark = radius * CANCEL_MARK_FRACTION
        canvas.drawLine(anchorX - mark, anchorY - mark, anchorX + mark, anchorY + mark, paints.label)
        canvas.drawLine(anchorX - mark, anchorY + mark, anchorX + mark, anchorY - mark, paints.label)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        particles.cancel()
    }

    companion object {
        /** The most wedges; same as
         *  [com.borderkeys.data.theme.KeyboardPreferences.MAX_RADIAL_SUGGESTIONS]. */
        const val MAX_WEDGES = 6

        /** How much heavier the trusted word's wedge is outlined than the seams. */
        const val TRUSTED_STROKE_SCALE = 3f

        const val DEFAULT_ROW_PX = 150f

        /** The most fill particles alive at once. */
        const val FILL_PARTICLE_POOL_CAPACITY = 80

        /** The most outline particles alive at once. */
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

        /** [celebrate]'s burst, as a multiple of a press's. */
        const val CELEBRATE_BURST_MULTIPLIER = 2

        /** The ring's outer edge, in row heights. */
        const val OUTER_RADIUS_ROWS = 1.7f

        /** Where the wedge ring's inner edge starts, as a fraction of the outer radius. */
        const val RING_INNER_RADIUS_FRACTION = 0.4f

        /** The centre button's radius, as a fraction of the outer radius, leaving a gap inside
         *  [RING_INNER_RADIUS_FRACTION]. */
        const val CENTER_RADIUS_FRACTION = 0.22f

        /** The X mark's half-length, as a fraction of the centre button's radius. */
        const val CANCEL_MARK_FRACTION = 0.45f

        /** How dark the scrim is, out of 255. */
        const val SCRIM_ALPHA = 200

        /** Wedge centres by rank: up-right, up-left, right, left, down-right, down-left. */
        val ERGONOMIC_ORDER_DEGREES = floatArrayOf(-45f, -135f, 0f, 180f, 45f, 135f)

        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d < 0f) d += 360f
            return d
        }

        /** Whether ([x], [y]) is within [radius] of ([centerX], [centerY]). */
        fun isWithinRing(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float): Boolean =
            hypot(x - centerX, y - centerY) <= radius

        /**
         * The centre angle of the wedge at [rankIndex], from [ERGONOMIC_ORDER_DEGREES], mirrored
         * for [rightToLeft]. Degrees as [android.graphics.Canvas.drawArc] takes them: 0 is right,
         * increasing clockwise.
         */
        fun wedgeCentreDegrees(rankIndex: Int, wedgeCount: Int, rightToLeft: Boolean = false): Float {
            if (wedgeCount <= 0) {
                return 0f
            }
            val centre = ERGONOMIC_ORDER_DEGREES[rankIndex % ERGONOMIC_ORDER_DEGREES.size]
            return if (rightToLeft) 180f - centre else centre
        }

        /**
         * The (start, sweep) of each of [wedgeCount] wedges, by rank: each boundary lies halfway
         * to the neighbouring centre by angle. Drawing and hit-testing both read these.
         */
        fun computeWedgeBoundaries(wedgeCount: Int, rightToLeft: Boolean = false): Pair<FloatArray, FloatArray> {
            val start = FloatArray(wedgeCount)
            val sweep = FloatArray(wedgeCount)
            if (wedgeCount <= 0) {
                return start to sweep
            }
            val bySortedAngle = (0 until wedgeCount)
                .map { rank -> rank to normalizeDegrees(wedgeCentreDegrees(rank, wedgeCount, rightToLeft)) }
                .sortedBy { it.second }
            for (i in bySortedAngle.indices) {
                val (rank, centre) = bySortedAngle[i]
                val prevCentre = bySortedAngle[(i - 1 + wedgeCount) % wedgeCount].second
                val nextCentre = bySortedAngle[(i + 1) % wedgeCount].second
                val prevGap = if (wedgeCount == 1) 360f else normalizeDegrees(centre - prevCentre)
                val nextGap = if (wedgeCount == 1) 360f else normalizeDegrees(nextCentre - centre)
                start[rank] = centre - prevGap / 2f
                sweep[rank] = prevGap / 2f + nextGap / 2f
            }
            return start to sweep
        }
    }
}
