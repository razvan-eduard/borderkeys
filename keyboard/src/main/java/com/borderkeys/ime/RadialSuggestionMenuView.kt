// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import com.borderkeys.theme.ThemePaints
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A ring of words around the finger, for a paused or just-lifted swipe.
 *
 * One view serves both phases a [SwipeRadialController] moves through, because both need the
 * same wedge geometry around the same anchor point and differ only in touch handling and visual
 * weight -- see [interactive]'s own doc. Sized to overlay the keyboard's own rect exactly (see
 * `KeyboardHostView`), added as that view's last child so it draws over everything else.
 *
 * No new drawable assets: wedges are flat-filled arcs in this theme's own paints, the same
 * "canvas draws itself" style [KeyboardCanvasView]/[LanguageRevertPanelView] already use.
 */
@SuppressLint("ViewConstructor")
class RadialSuggestionMenuView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    interface Listener {
        /** A wedge was tapped: apply that candidate. Only ever called while [interactive]. */
        fun onRadialPicked(index: Int, word: String)

        /** The menu was tapped somewhere that was not a wedge -- empty space inside the ring, or
         *  the dead zone around the anchor itself. Only ever called while [interactive]; a
         *  preview never receives touches at all, see [interactive]'s own doc. */
        fun onRadialDismissed()
    }

    var listener: Listener? = null

    private var words: List<String> = emptyList()
    private var anchorX = 0f
    private var anchorY = 0f

    /**
     * Which phase is showing.
     *
     * `false` (preview): drawn at low opacity, no scrim, and [onTouchEvent] always returns
     * `false` -- not because the swiping finger could otherwise trigger a pick (Android already
     * locked that pointer to [KeyboardCanvasView] at its original `ACTION_DOWN`, before this view
     * was ever shown, so those events never reach here regardless of z-order), but so a
     * hypothetical second pointer -- a shift-hold on the other hand -- falls through to the keys
     * underneath rather than being swallowed by an inert preview.
     *
     * `true` (real menu, after lift): drawn opaquely over a scrim, and consumes every touch
     * inside its bounds the way `ClipboardPanelView` does over the key area.
     */
    var interactive: Boolean = false
        private set

    private var pressedWedge = -1
    private val wedgeBounds = RectF()

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Replaces what is shown -- always the whole list, since one pause or one lift is one menu.
     *  [words] beyond [MAX_WEDGES] are dropped; the setting that bounds
     *  `radialSuggestionCount` already keeps this from happening in practice. */
    fun show(anchorX: Float, anchorY: Float, words: List<String>, interactive: Boolean) {
        this.anchorX = anchorX
        this.anchorY = anchorY
        this.words = words.take(MAX_WEDGES)
        this.interactive = interactive
        pressedWedge = -1
        invalidate()
    }

    /** Clears the menu. Idempotent -- every dismiss path in `BorderKeysService` calls this
     *  unconditionally, whether or not anything was actually showing. */
    fun hide() {
        if (words.isEmpty() && !interactive) {
            return
        }
        words = emptyList()
        interactive = false
        pressedWedge = -1
        invalidate()
    }

    private fun outerRadius(): Float =
        (if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX) * OUTER_RADIUS_ROWS

    private fun innerRadius(): Float = outerRadius() * INNER_RADIUS_FRACTION

    /**
     * Which wedge, if any, a point belongs to.
     *
     * -1 covers three cases at once: too close to the anchor (the dead zone around the finger
     * itself, so lifting in place cannot accidentally pick the nearest wedge), past the outer
     * ring, or there being nothing to pick from. All three read the same to a caller: not a
     * wedge, so [onRadialDismissed].
     */
    private fun wedgeAt(x: Float, y: Float): Int {
        if (words.isEmpty()) {
            return -1
        }
        val dx = x - anchorX
        val dy = y - anchorY
        val distance = hypot(dx, dy)
        if (distance < innerRadius() || distance > outerRadius()) {
            return -1
        }
        // Rotated so angle 0 is straight up rather than atan2's own "straight right", then
        // normalised into [0, 2*PI) so dividing by one wedge's own angular width gives an index
        // directly -- screen y grows downward, which is what already makes this clockwise from
        // the top without an extra sign flip.
        var angle = atan2(dy, dx) + PI.toFloat() / 2f
        if (angle < 0f) {
            angle += (PI * 2).toFloat()
        }
        val wedgeWidth = (PI * 2).toFloat() / words.size
        return (angle / wedgeWidth).toInt().coerceIn(0, words.size - 1)
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("RadialSuggestionMenuView.onDraw")
        try {
            if (words.isEmpty()) {
                return
            }
            if (interactive) {
                drawScrim(canvas)
            }
            drawWedges(canvas)
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
        val inner = innerRadius()
        wedgeBounds.set(anchorX - outer, anchorY - outer, anchorX + outer, anchorY + outer)
        val sweep = 360f / words.size
        val fillBaseAlpha = paints.keyFill.alpha
        val modifierBaseAlpha = paints.modifierKeyFill.alpha
        val strokeBaseAlpha = paints.keyStroke.alpha
        val labelBaseAlpha = paints.label.alpha
        if (!interactive) {
            paints.keyFill.alpha = (fillBaseAlpha * PREVIEW_OPACITY).toInt().coerceIn(0, 255)
            paints.modifierKeyFill.alpha =
                (modifierBaseAlpha * PREVIEW_OPACITY).toInt().coerceIn(0, 255)
            paints.keyStroke.alpha = (strokeBaseAlpha * PREVIEW_OPACITY).toInt().coerceIn(0, 255)
            paints.label.alpha = (labelBaseAlpha * PREVIEW_OPACITY).toInt().coerceIn(0, 255)
        }
        try {
            val midRadius = (inner + outer) / 2f
            val previousAlign = paints.label.textAlign
            paints.label.textAlign = Paint.Align.CENTER
            for (index in words.indices) {
                // -90 to start the first wedge at the top, matching wedgeAt's own rotation.
                val startAngle = -90f + sweep * index
                val fill = if (index == pressedWedge) paints.accent else paints.keyFill
                canvas.drawArc(wedgeBounds, startAngle, sweep, true, fill)
                canvas.drawArc(wedgeBounds, startAngle, sweep, true, paints.keyStroke)
                val midAngleRad = Math.toRadians((startAngle + sweep / 2f).toDouble())
                val textX = anchorX + (midRadius * kotlin.math.cos(midAngleRad)).toFloat()
                val textY = anchorY + (midRadius * kotlin.math.sin(midAngleRad)).toFloat() +
                    paints.labelBaselineOffsetPx
                canvas.drawText(words[index], textX, textY, paints.label)
            }
            paints.label.textAlign = previousAlign
        } finally {
            paints.keyFill.alpha = fillBaseAlpha
            paints.modifierKeyFill.alpha = modifierBaseAlpha
            paints.keyStroke.alpha = strokeBaseAlpha
            paints.label.alpha = labelBaseAlpha
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) {
            // See this property's own doc: the swiping finger's events never reach here
            // regardless, this only matters for a hypothetical second pointer.
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedWedge = wedgeAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val wedge = wedgeAt(event.x, event.y)
                if (wedge != pressedWedge) {
                    pressedWedge = wedge
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wedge = pressedWedge
                pressedWedge = -1
                invalidate()
                if (wedge >= 0 && wedge < words.size) {
                    listener?.onRadialPicked(wedge, words[wedge])
                } else {
                    listener?.onRadialDismissed()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedWedge = -1
                invalidate()
                return true
            }
        }
        return false
    }

    private companion object {
        /** More wedges than this and each one is narrower than a fingertip on any phone this
         *  runs on -- matches [com.borderkeys.data.theme.KeyboardPreferences.MAX_RADIAL_SUGGESTIONS],
         *  kept here too only as this view's own defensive ceiling. */
        const val MAX_WEDGES = 6

        const val DEFAULT_ROW_PX = 150f

        /** The ring's outer edge, in row heights -- big enough that six wedges are each wide
         *  enough to tap, small enough that the ring fits inside a keyboard's own height. */
        const val OUTER_RADIUS_ROWS = 1.7f

        /** The dead zone around the anchor, as a fraction of the outer radius -- lifting right
         *  where the finger already is must not read as picking the nearest wedge by accident. */
        const val INNER_RADIUS_FRACTION = 0.35f

        /** How dark the scrim behind the real menu is, out of 255 -- matches the resize
         *  overlay's own wash in `KeyboardHostView`. */
        const val SCRIM_ALPHA = 200

        /** How faint the preview phase draws, as a fraction of full opacity -- a hint, not a
         *  thing to read carefully while still swiping. */
        const val PREVIEW_OPACITY = 0.55f
    }
}
