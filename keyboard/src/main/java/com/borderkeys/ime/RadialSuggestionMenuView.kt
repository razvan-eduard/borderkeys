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
 * The one exception is the tap-only phase [acceptsOwnTouches] describes: once the original
 * pointer is gone and the ring is waiting for a fresh tap, it is modal. A wedge or the centre X
 * acts; a touch anywhere else on the host only closes the ring, leaving the text exactly as it
 * was and typing nothing -- see [onTouchEvent]'s own doc.
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
        /** A fresh, independent tap that started on the ring resolved to [selection] -- a wedge,
         *  the centre button, or neither (lifted in the dead zone, or the touch stream was
         *  cancelled). */
        fun onRadialTapResolved(selection: Selection)

        /** A fresh touch landed outside the ring while it was waiting for a tap: close it and
         *  leave the text alone. The touch itself is consumed here and never reaches the keys
         *  underneath -- see [onTouchEvent]'s own doc. */
        fun onRadialDismissed()
    }

    var listener: Listener? = null

    private var words: List<String> = emptyList()

    /** The wedge holding the word already composing in the field, or -1 when the ring carries
     *  only alternatives. Outlined the way the strip outlines the chip that acts on its own. */
    private var trustedIndex: Int = -1

    /**
     * [words] as the [Selection.Word] each wedge resolves to, built once in [show]: [hitTest]
     * runs on every steer and [drawWedges] on every frame, and both used to allocate a fresh
     * selection per wedge to compare or return -- the one thing a draw path here may not do.
     */
    private var wordSelections: List<Selection.Word> = emptyList()
    private var anchorX = 0f
    private var anchorY = 0f

    /**
     * The room above the keys this view is laid out over but must leave alone -- see
     * [KeyboardHostView.reserveScreenAbove]. Nothing is drawn there: no scrim, no ring; the
     * ring is kept below it exactly as it was kept inside the keyboard before the room existed.
     * Taps there still reach this view, which is the whole point of the room.
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

    /** The trusted wedge's own path, kept from the loop that builds it so it can be stroked
     *  after the seams. Reused, so the draw path allocates nothing. */
    private val trustedPath = Path()

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
    fun show(anchorX: Float, anchorY: Float, words: List<String>, trustedIndex: Int = -1) {
        this.words = words.take(MAX_WEDGES)
        this.trustedIndex = if (trustedIndex in this.words.indices) trustedIndex else -1
        wordSelections = this.words.mapIndexed { index, word -> Selection.Word(index, word) }
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
            anchorY.coerceIn(topInset + outer, (height - outer).coerceAtLeast(topInset + outer))
        } else {
            anchorY
        }
        // Alive from the moment it opens, not only once a wedge is hovered -- see ringElement.
        particles.hold(ringElement)
        invalidate()
    }

    /**
     * Moves an open ring down (or up) by [dy], re-clamped to this view's bounds -- for the host
     * growing or shrinking above the keys while the ring is showing (see
     * [KeyboardHostView.reserveScreenAbove]): the ring was anchored to where the keys were, and
     * the keys just moved. Nothing to do while no ring is showing.
     */
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

    /**
     * Clears the menu: wedges, highlight, the tap-only mode and both ambient emitters. Idempotent
     * -- `BorderKeysService.closeRadialRing` calls this on every way off the ring except a real
     * pick, which goes through [celebrate] instead. Never an early return on empty [words]: the
     * flags and emitters can be stale on their own after a close, and leaving an ambient running
     * is exactly what would keep [pendingHide] waiting forever (see its own doc).
     */
    fun hide() {
        words = emptyList()
        wordSelections = emptyList()
        currentSelection = Selection.None
        acceptsOwnTouches = false
        ownTouchStreamActive = false
        particles.release()
        invalidate()
    }

    /** Whether crossing into a new wedge or the centre button ticks -- mirrors the keyboard's
     *  own `hapticEnabled` preference, since this is feedback for a touch this view never
     *  actually receives itself (see this class's own doc for why). */
    var hapticEnabled: Boolean = true

    /** The class the keys play -- see [HapticStrength] -- so a wedge feels like a key. */
    var hapticConstant: Int = HapticFeedbackConstants.KEYBOARD_TAP

    /** Both particle layers for the ring: whichever wedge (or the centre button) the finger is
     *  currently over is *held* -- an ambient glow inside it, its own real outline traced -- and
     *  the wedge actually picked gets a bigger burst ([celebrate]). Exposed non-private so
     *  [BorderKeysService] can push the user's particle-effect settings directly, the same way
     *  [hapticEnabled] already is. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { onParticlesInvalidated() }

    /** The highlighted wedge and the centre button, as the elements the engine reads their
     *  shapes from -- the exact annular slice [drawWedges] paints and the exact circle
     *  [drawCancelButton] paints. See [com.borderkeys.ime.fx.ParticleElement]. */
    private val wedgeElement = AnnularWedgeElement()
    private val cancelElement = RoundedRectElement()

    /**
     * The ring itself, held for as long as it is open with nothing highlighted -- so a ring
     * waiting for a tap is alive, not inert. Its outline is its outer circle only, so particles
     * radiate outward off the ring rather than inward into the gap around the centre button;
     * its fill is the full annulus the wedges occupy. The one element in this app whose two
     * regions differ, and exactly what [com.borderkeys.ime.fx.ParticleElement]'s two `open`
     * properties exist for.
     */
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

    /**
     * Set by [KeyboardHostView.setRadialMenuVisible] when it would otherwise hide this view
     * while a [celebrate] burst is still animating. [onParticlesInvalidated] flips this view to
     * [GONE] itself once the burst finishes, rather than the caller cutting it off mid-flight --
     * and [KeyboardHostView.setRadialMenuVisible] clears it again immediately if the ring is
     * reused before that happens, so a stale deferred hide can never fire after the fact.
     */
    var pendingHide: Boolean = false

    private fun onParticlesInvalidated() {
        if (pendingHide && !particles.hasLiveParticles) {
            pendingHide = false
            visibility = GONE
        }
        invalidate()
    }

    /** Whether a [celebrate] burst (or, in principle, the ambient glow) is still animating --
     *  read by [KeyboardHostView.setRadialMenuVisible] to decide whether hiding this view must
     *  wait for it to finish first. */
    fun hasLiveParticles(): Boolean = particles.hasLiveParticles

    /**
     * A bigger, one-shot burst at the wedge [index] resolved to, then the same resolved-and-done
     * state [hide] leaves this view in -- called by [BorderKeysService.closeRadialRing] right at
     * the moment a pick resolves. Clearing everything here matters specifically because of
     * [pendingHide]: this view's own [visibility] stays [VISIBLE] for as long as the burst takes
     * to finish, and without this the stale wedge content would keep drawing underneath it for
     * that whole stretch instead of just the burst floating on its own over the keys -- and the
     * ambient glow [steerTo] started would keep the burst company forever, so the deferred hide
     * would never actually fire. Spawned before [hide] so the burst survives the clear.
     */
    fun celebrate(index: Int) {
        if (index !in words.indices) {
            hide()
            return
        }
        setWedgeElement(index)
        particles.celebrate(wedgeElement, CELEBRATE_BURST_MULTIPLIER)
        hide()
    }

    /** Points [wedgeElement] at wedge [index]'s own real shape -- the same annular slice
     *  [drawWedges] paints from the same two boundary arrays. */
    private fun setWedgeElement(index: Int) {
        wedgeElement.set(
            anchorX, anchorY, ringInnerRadius(), outerRadius(), wedgeStartDeg[index], wedgeSweepDeg[index],
        )
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
                // The same class every key press on the keyboard underneath already gives, so a
                // wedge feels like a key rather than announcing itself as a different kind of
                // control.
                performHapticFeedback(hapticConstant)
            }
            // Whatever is highlighted is held: the engine fills its interior and traces its
            // outline from the one shape the element declares -- the wedge's own annular slice,
            // or the centre button's own circle -- so the glow, the dots and the stroke can
            // never disagree with the highlight drawn underneath them.
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

    /** What the finger is over right now -- read once, at resolution (a lift, or the pick
     *  timeout), never polled on a timer. */
    fun currentSelection(): Selection = currentSelection

    /** Whether the touch stream currently down started close enough to this ring to be *for*
     *  it -- decided once, at that stream's own `ACTION_DOWN`, and remembered for its `MOVE`/
     *  `UP` rather than re-checked, so a drag that wanders outside mid-gesture does not suddenly
     *  let go of a touch that started as a real attempt to steer. See [onTouchEvent]'s own doc
     *  for why this decision has to exist at all. */
    private var ownTouchStreamActive = false

    /**
     * Handles a fresh, independent touch while [acceptsOwnTouches] -- the only case this view
     * ever sees its own touch stream at all, see that property's own doc.
     *
     * A touch that starts on the ring steers it: deliberately the same shape as
     * [steerTo]/[currentSelection] (highlight follows the finger, resolution reads whatever it
     * last settled on) rather than a separate code path, so the two interactions feel identical
     * even though one is pushed in and the other is this view's own. [ownTouchStreamActive]
     * remembers that decision from the stream's own `ACTION_DOWN`, so a drag that wanders
     * outside mid-gesture does not suddenly let go of a touch that started as a real steer.
     *
     * A touch that starts anywhere else is a dismissal, and is *consumed*: this view is laid out
     * across the entire host (see [com.borderkeys.ime.KeyboardHostView.onLayout]'s own call
     * site), so `return true` at that `ACTION_DOWN` is what keeps the key underneath from being
     * pressed. The ring waiting for a tap is modal by design -- per the user's own spec, tapping
     * outside it closes it without inserting anything, the same as the centre X except that the
     * swiped word already composing in the field is left exactly as it is. Letting the touch
     * through instead (the earlier design) meant every stray tap both typed a letter *and*, via
     * `onKey`'s safety-net dismiss, threw the swiped word away. The listener closes the ring
     * synchronously inside [Listener.onRadialDismissed], which drops [acceptsOwnTouches], so the
     * rest of that stream -- still routed here, since this view claimed its down -- is ignored
     * by the guard at the top rather than needing its own bookkeeping.
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
            // Nothing above the keys, particles included -- see topInset.
            if (topInset > 0f) {
                canvas.clipRect(0f, topInset, width.toFloat(), height.toFloat())
            }
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
            particles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawScrim(canvas: Canvas) {
        val baseAlpha = paints.background.alpha
        paints.background.alpha = SCRIM_ALPHA
        // Over the keyboard only, never the room above it: the app there stays exactly as it was.
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
            if (index == trustedIndex) {
                // Kept for after the separators below: this is the wedge's own boundary drawn
                // heavier, and a separator drawn over it would cut it back to the weight of
                // every other seam.
                trustedPath.reset()
                trustedPath.addPath(wedgePath)
            }
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
        // The word already in the field: its own slice, outlined more than the others. Last, so
        // the seams the loop above draws do not cut it back to their own weight, and on the
        // wedge's real boundary rather than a shape floating inside it -- the ring is already
        // made of outlines, and one more outline that follows nothing reads as a mistake.
        if (trustedIndex >= 0) {
            val previousWidth = paints.appliedHighlight.strokeWidth
            paints.appliedHighlight.strokeWidth = previousWidth * TRUSTED_STROKE_SCALE
            canvas.drawPath(trustedPath, paints.appliedHighlight)
            paints.appliedHighlight.strokeWidth = previousWidth
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
        particles.cancel()
    }

    companion object {
        /** More wedges than this and each one is narrower than a fingertip on any phone this
         *  runs on -- matches [com.borderkeys.data.theme.KeyboardPreferences.MAX_RADIAL_SUGGESTIONS],
         *  kept here too only as this view's own defensive ceiling. */
        const val MAX_WEDGES = 6

        /** How much heavier the trusted word's slice is outlined than every other seam. The
         *  strip marks a chip the size of a word with one pixel; a wedge needs more to read as
         *  deliberately drawn rather than as the same line twice. */
        const val TRUSTED_STROKE_SCALE = 3f

        const val DEFAULT_ROW_PX = 150f

        /** Sized for one preset's own burst count (10) at [CELEBRATE_BURST_MULTIPLIER], scaled
         *  up for a wedge's area (see [com.borderkeys.ime.fx.ParticleSimulation.MAX_EXTENT_FACTOR])
         *  plus the ambient trickle running at the same time -- not for [MAX_WEDGES] each
         *  celebrating at once; only one wedge is ever resolved per gesture. */
        const val FILL_PARTICLE_POOL_CAPACITY = 80

        /** One wedge's own outline at a time -- a long one, so Comet's cap (18) scaled up. */
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

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

        /** Whether ([x], [y]) is close enough to ([centerX], [centerY]) to count as a touch *on*
         *  the ring, not merely somewhere on the host this view happens to span -- see
         *  [onTouchEvent]'s own doc for why that distinction has to be checked at all. Pure and
         *  public for the same reason [wedgeCentreDegrees] is: testable without a `View`. */
        fun isWithinRing(x: Float, y: Float, centerX: Float, centerY: Float, radius: Float): Boolean =
            hypot(x - centerX, y - centerY) <= radius

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
