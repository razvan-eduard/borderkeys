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
import com.borderkeys.ime.fx.ParticleField
import com.borderkeys.theme.ThemePaints
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A ring of alternative words around a paused swipe's finger, plus a separate centre Cancel
 * button, for the single-stroke pick this whole feature exists for: pause opens it, the same
 * finger steers to a wedge or the centre without ever lifting, and lifting resolves whatever it
 * was last over. Sized to overlay the keyboard's own rect exactly (see `KeyboardHostView`), added
 * as that view's last child so it draws over everything else.
 *
 * This view **never receives its own touch events for the steering finger.** Android locked that
 * pointer to `KeyboardCanvasView` at its original `ACTION_DOWN`, before this view ever showed, and
 * it stays locked there for the pointer's whole lifetime -- lifting and re-pressing never happens
 * in this design. Steering is therefore *pushed in* from outside via [steerTo], and the resolution
 * a lift or a timeout produces is *read out* via [currentSelection], not discovered through this
 * view's own `onTouchEvent`.
 *
 * No new drawable assets: wedges are flat-filled arcs in this theme's own paints, the same
 * "canvas draws itself" style [KeyboardCanvasView]/[LanguageRevertPanelView] already use.
 */
@SuppressLint("ViewConstructor")
class RadialSuggestionMenuView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    /** What the finger is currently over, read by the caller at resolution time (a lift, or the
     *  pick-timeout elapsing while still held). */
    sealed interface Selection {
        data object None : Selection
        data object Cancel : Selection
        data class Word(val index: Int, val word: String) : Selection
    }

    /** Only used while [acceptsOwnTouches] -- see that property's own doc. */
    interface Listener {
        /** A fresh, independent tap resolved to [selection] -- a wedge, the centre button, or
         *  neither (tapped elsewhere, or the touch stream was cancelled). */
        fun onRadialTapResolved(selection: Selection)
    }

    var listener: Listener? = null

    private var words: List<String> = emptyList()
    private var anchorX = 0f
    private var anchorY = 0f
    private var currentSelection: Selection = Selection.None

    /**
     * Whether this view now handles its own touch stream, rather than being steered by
     * `KeyboardCanvasView` pushing coordinates in.
     *
     * `false` for the whole single-stroke phase (pause through the original lift): the steering
     * finger is locked to `KeyboardCanvasView` from its own `ACTION_DOWN`, before this view ever
     * showed, so touches never reach here regardless of this flag -- see the class doc.
     *
     * `true` only when [com.borderkeys.data.theme.KeyboardPreferences.radialLiftKeepsOpen] is on
     * and a lift resolved to nothing (neither a wedge nor Cancel): the original pointer is gone,
     * so a *new*, independent tap is the only way anything more can happen, and this is what
     * lets this view actually receive and act on that new touch stream itself.
     */
    var acceptsOwnTouches: Boolean = false

    /** One (start, sweep) pair per word, in [words]' own index order -- computed once per
     *  [show], not per frame or per touch. See [recomputeWedgeBoundaries]'s own doc for why
     *  these are not evenly spaced. */
    private var wedgeStartDeg = FloatArray(0)
    private var wedgeSweepDeg = FloatArray(0)
    private val wedgeInnerBounds = RectF()
    private val wedgeOuterBounds = RectF()
    private val wedgePath = Path()

    /** Multiplies [OUTER_RADIUS_ROWS] -- set from [com.borderkeys.data.theme.KeyboardPreferences
     *  .radialMenuSize] via [com.borderkeys.data.theme.KeyboardPreferences.radialSizeScale]. */
    var sizeScale: Float = 1f

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Opens the ring, live and steerable immediately -- there is no separate lower-fidelity
     *  phase any more, see this class's own doc for why. [words] beyond [MAX_WEDGES] are
     *  dropped; the setting that bounds `radialSuggestionCount` already keeps this from
     *  happening in practice. */
    fun show(anchorX: Float, anchorY: Float, words: List<String>) {
        this.words = words.take(MAX_WEDGES)
        currentSelection = Selection.None
        recomputeWedgeBoundaries()
        // Clamped to this view's own bounds, always -- regardless of which anchor mode chose
        // anchorX/anchorY, or whether a swipe simply ended a pixel from the edge. The ring is
        // never allowed to reach past the edge it would otherwise cross, which is also exactly
        // how "tangent to the left/right edge" is implemented: that mode hands in the edge
        // itself (0 or the full width) and lets this same clamp pull it in to touch, not cross.
        val outer = outerRadius()
        this.anchorX = if (width > 0) {
            anchorX.coerceIn(outer, (width - outer).coerceAtLeast(outer))
        } else {
            anchorX
        }
        this.anchorY = if (height > 0) {
            anchorY.coerceIn(outer, (height - outer).coerceAtLeast(outer))
        } else {
            anchorY
        }
        invalidate()
    }

    /** Clears the menu. Idempotent -- every dismiss path in `BorderKeysService` calls this
     *  unconditionally, whether or not anything was actually showing. */
    fun hide() {
        if (words.isEmpty()) {
            return
        }
        words = emptyList()
        currentSelection = Selection.None
        acceptsOwnTouches = false
        invalidate()
    }

    /** Whether crossing into a new wedge or the centre button ticks -- mirrors the keyboard's
     *  own `hapticEnabled` preference, since this is feedback for a touch this view never
     *  actually receives itself (see this class's own doc for why). */
    var hapticEnabled: Boolean = true

    /** An ambient glow on whichever wedge is currently highlighted, plus a bigger burst on the
     *  one actually picked -- exposed non-private so [BorderKeysService] can push the user's
     *  particle-effect settings directly, the same way [hapticEnabled] already is. */
    val fillParticles = ParticleField(FILL_PARTICLE_POOL_CAPACITY) { onParticlesInvalidated() }

    /** Traces the highlighted wedge's own arc while [steerTo] hovers it -- see
     *  [ParticleField.setAmbientArc]. No burst equivalent: [celebrate] only bursts
     *  [fillParticles], letting this keep tracing the picked wedge as-is underneath it. */
    val outlineParticles = ParticleField(OUTLINE_PARTICLE_POOL_CAPACITY) { onParticlesInvalidated() }

    /**
     * Set by [KeyboardHostView.setRadialMenuVisible] when it would otherwise hide this view
     * while a [celebrate] burst is still animating. [onParticlesInvalidated] flips this view to
     * [GONE] itself once the burst finishes, rather than the caller cutting it off mid-flight --
     * and [KeyboardHostView.setRadialMenuVisible] clears it again immediately if the ring is
     * reused before that happens, so a stale deferred hide can never fire after the fact.
     */
    var pendingHide: Boolean = false

    private fun onParticlesInvalidated() {
        if (pendingHide && !fillParticles.hasLiveParticles && !outlineParticles.hasLiveParticles) {
            pendingHide = false
            visibility = GONE
        }
        invalidate()
    }

    /** Whether a [celebrate] burst (or, in principle, the ambient glow) is still animating --
     *  read by [KeyboardHostView.setRadialMenuVisible] to decide whether hiding this view must
     *  wait for it to finish first. */
    fun hasLiveParticles(): Boolean = fillParticles.hasLiveParticles || outlineParticles.hasLiveParticles

    /**
     * A bigger, one-shot burst at the wedge [index] resolved to, and the same resolved-and-done
     * state [hide] used to leave this view in -- called by [BorderKeysService.closeRadialRing]
     * right at the moment a pick resolves. Clearing [words] here rather than leaving that to
     * [hide] matters specifically because of [pendingHide]: this view's own [visibility] now
     * stays [VISIBLE] for as long as the burst takes to finish, and without this the stale wedge
     * content would keep drawing underneath it for that whole stretch instead of just the burst
     * floating on its own over the keys.
     */
    fun celebrate(index: Int) {
        if (index !in words.indices) {
            return
        }
        val midRadius = (ringInnerRadius() + outerRadius()) / 2f
        val angleRad = Math.toRadians(wedgeCentreDegrees(index, words.size).toDouble())
        fillParticles.spawnBurstAtPoint(
            anchorX + (midRadius * cos(angleRad)).toFloat(),
            anchorY + (midRadius * sin(angleRad)).toFloat(),
            fillParticles.preset.burstCount * CELEBRATE_BURST_MULTIPLIER,
        )
        words = emptyList()
        currentSelection = Selection.None
        acceptsOwnTouches = false
        invalidate()
    }

    /** Steers the highlight to whatever [x]/[y] -- this view's own local pixels, the same space
     *  the anchor already is -- currently lands on. Called on every `onGestureSteered` from
     *  `BorderKeysService`, never from this view's own touch handling (there is none for the
     *  steering finger -- see this class's own doc). */
    fun steerTo(x: Float, y: Float) {
        val hit = hitTest(x, y)
        if (hit != currentSelection) {
            currentSelection = hit
            if (hapticEnabled) {
                // KEYBOARD_TAP, not CLOCK_TICK or CONTEXT_CLICK: the same click every key press
                // on the keyboard underneath already gives, so a wedge feels like a key rather
                // than announcing itself as a different kind of control.
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (hit is Selection.Word) {
                val midRadius = (ringInnerRadius() + outerRadius()) / 2f
                val angleRad = Math.toRadians(wedgeCentreDegrees(hit.index, words.size).toDouble())
                fillParticles.setAmbientPoint(
                    anchorX + (midRadius * cos(angleRad)).toFloat(),
                    anchorY + (midRadius * sin(angleRad)).toFloat(),
                )
                outlineParticles.setAmbientArc(
                    anchorX, anchorY, midRadius,
                    wedgeStartDeg[hit.index], wedgeSweepDeg[hit.index],
                )
            } else {
                fillParticles.stopAmbient()
                outlineParticles.stopAmbient()
            }
            invalidate()
        }
    }

    /** What the finger is over right now -- read once, at resolution (a lift, or the pick
     *  timeout), never polled on a timer. */
    fun currentSelection(): Selection = currentSelection

    /**
     * Handles a fresh, independent tap while [acceptsOwnTouches] -- the only case this view ever
     * sees its own touch stream at all, see that property's own doc. Deliberately the same shape
     * as [steerTo]/[currentSelection] (highlight follows the finger, resolution reads whatever it
     * last settled on) rather than a separate code path, so the two interactions feel identical
     * even though one is pushed in and the other is this view's own.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!acceptsOwnTouches) {
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
                return Selection.Word(index, words[index])
            }
        }
        return Selection.None
    }

    private fun outerRadius(): Float =
        (if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX) *
            OUTER_RADIUS_ROWS * sizeScale

    private fun ringInnerRadius(): Float = outerRadius() * RING_INNER_RADIUS_FRACTION

    private fun centerRadius(): Float = outerRadius() * CENTER_RADIUS_FRACTION

    /** Fills [wedgeStartDeg]/[wedgeSweepDeg] from [computeWedgeBoundaries], one call per [show]
     *  rather than per frame or per touch. */
    private fun recomputeWedgeBoundaries() {
        val n = words.size
        val (start, sweep) = computeWedgeBoundaries(n)
        wedgeStartDeg = start
        wedgeSweepDeg = sweep
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("RadialSuggestionMenuView.onDraw")
        try {
            // Not an early return on an empty list any more: celebrate() clears words the
            // instant a pick resolves, specifically so the wedges and scrim stop drawing right
            // away, while its own burst -- entirely independent of words -- keeps animating on
            // its own for as long as it takes. See pendingHide's own doc for why this view can
            // stay visible well after words is already empty.
            if (words.isNotEmpty()) {
                drawScrim(canvas)
                drawWedges(canvas)
                drawCancelButton(canvas)
            }
            fillParticles.draw(canvas, paints.particlePaint)
            outlineParticles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawScrim(canvas: Canvas) {
        val baseAlpha = paints.background.alpha
        paints.background.alpha = SCRIM_ALPHA
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
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
            val fill = if (currentSelection == Selection.Word(index, words[index])) {
                paints.accent
            } else {
                paints.keyFill
            }
            // A true annular slice (outer arc, radial line in, inner arc back, close) rather
            // than a full pie slice from the centre -- the centre is the separate Cancel button
            // now, with a deliberate gap before the ring even starts (see this class's own
            // doc), and a pie slice reaching all the way in would paint straight over both.
            val start = wedgeStartDeg[index]
            val sweep = wedgeSweepDeg[index]
            wedgePath.reset()
            wedgePath.arcTo(wedgeOuterBounds, start, sweep, true)
            wedgePath.arcTo(wedgeInnerBounds, start + sweep, -sweep, false)
            wedgePath.close()
            canvas.drawPath(wedgePath, fill)
            val midAngleRad = Math.toRadians(wedgeCentreDegrees(index, words.size).toDouble())
            val textX = anchorX + (midRadius * cos(midAngleRad)).toFloat()
            val textY = anchorY + (midRadius * sin(midAngleRad)).toFloat() +
                paints.labelBaselineOffsetPx
            canvas.drawText(words[index], textX, textY, paints.label)
        }
        paints.label.textAlign = previousAlign
        // The same "outline the keys" setting KeyboardCanvasView's own keys and
        // KeyboardHostView's own frame already gate their strokes on: the ring's own outer and
        // inner edges as two clean circles, plus one straight line per wedge boundary -- not
        // each wedge's own stroked path, which would double-draw both edges at every seam.
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
    }

    /** The centre Cancel button -- a small circle with an X, separated from the ring's own
     *  inner edge by the gap [RING_INNER_RADIUS_FRACTION]/[CENTER_RADIUS_FRACTION] leave between
     *  them. Not a wedge: no angular boundary math, just a circle. */
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
        fillParticles.cancel()
        outlineParticles.cancel()
    }

    companion object {
        /** More wedges than this and each one is narrower than a fingertip on any phone this
         *  runs on -- matches [com.borderkeys.data.theme.KeyboardPreferences.MAX_RADIAL_SUGGESTIONS],
         *  kept here too only as this view's own defensive ceiling. */
        const val MAX_WEDGES = 6

        const val DEFAULT_ROW_PX = 150f

        /** Sized for one preset's own burst count (10) at [CELEBRATE_BURST_MULTIPLIER] plus the
         *  ambient trickle running at the same time, not for [MAX_WEDGES] each celebrating at
         *  once -- only one wedge is ever resolved per gesture. */
        const val FILL_PARTICLE_POOL_CAPACITY = 20

        /** No burst on this layer, only one wedge's own arc trace at a time -- see
         *  [outlineParticles]'s own doc. */
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 16

        /** How much bigger [celebrate]'s burst is than [steerTo]'s own ambient trickle -- a
         *  deliberate, noticeably bigger moment for the one wedge actually picked. */
        const val CELEBRATE_BURST_MULTIPLIER = 2

        /** The ring's outer edge, in row heights. */
        const val OUTER_RADIUS_ROWS = 1.7f

        /** Where the wedge ring's own inner edge starts, as a fraction of the outer radius. */
        const val RING_INNER_RADIUS_FRACTION = 0.4f

        /** The centre Cancel button's radius, as a fraction of the outer radius -- deliberately
         *  smaller than [RING_INNER_RADIUS_FRACTION] so a visible gap separates the two, per the
         *  user's own spec ("cerc de x in cerc de radial menu cu un mic gap intre ele"). */
        const val CENTER_RADIUS_FRACTION = 0.22f

        /** The X mark's half-length, as a fraction of the centre button's own radius. */
        const val CANCEL_MARK_FRACTION = 0.45f

        /** How dark the scrim is, out of 255 -- matches the resize overlay's own wash in
         *  `KeyboardHostView`. */
        const val SCRIM_ALPHA = 200

        /** Up-right, up-left, right, left, down-right, down-left -- best thumb reach first. See
         *  [wedgeCentreDegrees]'s own doc for the angle convention. */
        val ERGONOMIC_ORDER_DEGREES = floatArrayOf(-45f, -135f, 0f, 180f, 45f, 135f)

        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d < 0f) d += 360f
            return d
        }

        /**
         * The ergonomic wedge order: best thumb reach first. Up-right and up-left diagonals are
         * the easiest arcs a thumb already doing the swiping can flick to; straight left/right
         * cost a little more; the lower diagonals need the least comfortable inward curl, so
         * they are last -- reserved for whichever alternatives are least likely to be picked.
         *
         * Degrees in [android.graphics.Canvas.drawArc]'s own convention: 0 is straight right,
         * increasing clockwise (screen y already grows downward, which is what makes this
         * clockwise without an extra sign flip). Pure and public specifically so it is testable
         * without a `View`/`Context` -- no Robolectric needed, plain JUnit4 like every other
         * pure function in this package.
         */
        fun wedgeCentreDegrees(rankIndex: Int, wedgeCount: Int): Float {
            if (wedgeCount <= 0) {
                return 0f
            }
            return ERGONOMIC_ORDER_DEGREES[rankIndex % ERGONOMIC_ORDER_DEGREES.size]
        }

        /**
         * The (start, sweep) boundary pair for each of [wedgeCount] wedges, indexed by rank --
         * pure, testable the same way [wedgeCentreDegrees] is.
         *
         * The ergonomic centres [wedgeCentreDegrees] returns are not evenly spaced around the
         * circle (that is the whole point -- some directions are easier to reach than others),
         * so each wedge's boundary is the angular midpoint to its two neighbours *by angle*, not
         * by rank -- a small Voronoi partition of the circle rather than a fixed `360 / count`
         * sweep. Drawing and hit-testing both read the same two arrays this returns, so what is
         * drawn and what is tappable can never disagree.
         */
        fun computeWedgeBoundaries(wedgeCount: Int): Pair<FloatArray, FloatArray> {
            val start = FloatArray(wedgeCount)
            val sweep = FloatArray(wedgeCount)
            if (wedgeCount <= 0) {
                return start to sweep
            }
            val bySortedAngle = (0 until wedgeCount)
                .map { rank -> rank to normalizeDegrees(wedgeCentreDegrees(rank, wedgeCount)) }
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
