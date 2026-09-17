// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import com.borderkeys.ime.fx.ParticleSurface
import com.borderkeys.ime.fx.RoundedRectElement
import com.borderkeys.theme.ThemePaints

/**
 * The offer `Ask` mode makes: a short list of words that may have been autocorrected under the
 * wrong language, each with what it currently reads and what the newly-dominant language would
 * spell it as, one tap to apply.
 *
 * Takes the suggestion strip's own slot rather than the clipboard/emoji panels' -- those replace
 * the keys because they are a screen someone reads; this is a handful of short rows beside keys
 * that stay live and typeable, closer kin to the strip it displaces than to a panel. An offer,
 * not a modal: ignoring it (typing on, or the explicit dismiss) costs nothing, and the words it
 * named stay exactly as they were unless one is actually tapped.
 */
@SuppressLint("ViewConstructor")
class LanguageRevertPanelView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    interface Listener {
        /** One row was tapped: apply that one replacement. */
        fun onLanguageRevertPicked(replacement: LanguageSwitchCorrector.Replacement)

        /** The dismiss control was tapped, with nothing picked. */
        fun onLanguageRevertDismissed()
    }

    var listener: Listener? = null

    private var rows: List<LanguageSwitchCorrector.Replacement> = emptyList()
    private var pressedRow = -1
    private var pressedDismiss = false
    private var rowHeightPx = 0f

    /** Both particle layers for the panel: the row actually picked is pressed (a burst inside
     *  it, its outline traced) -- exposed non-private so [BorderKeysService] can push the user's
     *  particle-effect settings directly, the same way [KeyboardCanvasView.particles] already
     *  is. See the `ACTION_UP` handler below for why the trace needs a timed release here. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidate() }

    /** The picked row, as the element the engine reads its shape from -- the same rect
     *  [drawRow] paints. */
    private val rowElement = RoundedRectElement()

    /** Releases the picked row ~500ms after the tap -- there is no hover/hold state on this
     *  view to hook a natural start/stop pair to, unlike every other particle-owning view in
     *  this package, since a row is removed right after being tapped. */
    private val stopOutlineRunnable = Runnable { particles.release() }

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Replaces the offered list -- always the whole thing, since one flip is checked once. */
    fun offer(replacements: List<LanguageSwitchCorrector.Replacement>) {
        rows = replacements.take(MAX_SHOWN)
        requestLayout()
        invalidate()
    }

    /** Removes one row after it was applied, returning how many are left. */
    fun remove(replacement: LanguageSwitchCorrector.Replacement): Int {
        rows = rows.filterNot { it == replacement }
        requestLayout()
        invalidate()
        return rows.size
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX
        rowHeightPx = row
        val height = (row * (rows.size.coerceAtLeast(1))).toInt()
            .coerceAtMost(MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(row.toInt()))
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("LanguageRevertPanelView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
            // Not an early return on an empty list any more: picking the last row empties it on
            // this same tap, and a burst just spawned on it must still get to draw itself out
            // over however many frames are left, same reasoning as
            // RadialSuggestionMenuView.onDraw's own restructuring.
            if (rows.isNotEmpty()) {
                val dismissWidth = rowHeightPx
                for (index in rows.indices) {
                    drawRow(canvas, index, index * rowHeightPx, dismissWidth)
                }
            }
            particles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawRow(canvas: Canvas, index: Int, top: Float, dismissWidth: Float) {
        val replacement = rows[index]
        val bottom = top + rowHeightPx
        if (index == pressedRow) {
            canvas.drawRect(0f, top, width - dismissWidth, bottom, paints.keyPressedFill)
        }
        if (index > 0) {
            canvas.drawLine(0f, top, width.toFloat(), top, paints.keyStroke)
        }
        val midY = top + rowHeightPx / 2f + paints.labelBaselineOffsetPx
        val previous = paints.label.textAlign
        paints.label.textAlign = Paint.Align.LEFT
        val text = "${replacement.previousText} $ARROW ${replacement.text}"
        canvas.save()
        canvas.clipRect(0f, top, width - dismissWidth, bottom)
        canvas.drawText(text, paints.rowHeightPx * 0.3f, midY, paints.label)
        canvas.restore()
        paints.label.textAlign = previous

        if (index == 0) {
            val dismissFill = if (pressedDismiss) paints.keyPressedFill else null
            if (dismissFill != null) {
                canvas.drawRect(width - dismissWidth, 0f, width.toFloat(), rowHeightPx, dismissFill)
            }
            canvas.drawText(
                DISMISS_GLYPH, width - dismissWidth / 2f,
                rowHeightPx / 2f + paints.labelBaselineOffsetPx, paints.labelSecondary,
            )
        }
    }

    private fun rowAt(y: Float): Int {
        if (rowHeightPx <= 0f) {
            return -1
        }
        val index = (y / rowHeightPx).toInt()
        return if (index in rows.indices) index else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dismissWidth = rowHeightPx
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val onDismiss = event.y < rowHeightPx && event.x >= width - dismissWidth
                pressedDismiss = onDismiss
                pressedRow = if (onDismiss) -1 else rowAt(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedDismiss) {
                    listener?.onLanguageRevertDismissed()
                } else if (pressedRow >= 0) {
                    // Only the row actually picked -- never the dismiss control, which discards
                    // rather than accepts.
                    val top = pressedRow * rowHeightPx
                    val right = width - rowHeightPx
                    val bottom = top + rowHeightPx
                    // No hover/hold state exists to hook a natural stop to (the row is gone the
                    // instant this listener call returns) -- so the trace gets a fixed, timed
                    // window instead, long enough to actually be seen.
                    removeCallbacks(stopOutlineRunnable)
                    rowElement.set(0f, top, right, bottom)
                    particles.press(rowElement)
                    postDelayed(stopOutlineRunnable, OUTLINE_STOP_DELAY_MILLIS)
                    listener?.onLanguageRevertPicked(rows[pressedRow])
                }
                pressedRow = -1
                pressedDismiss = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedRow = -1
                pressedDismiss = false
                invalidate()
                return true
            }
        }
        return false
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(stopOutlineRunnable)
        particles.cancel()
    }

    private companion object {
        /** More than this and the offer is no longer "a couple of words," it is a list -- shown
         *  once, in commit order, rather than scrolled. */
        const val MAX_SHOWN = 4

        /** A row is a wide element -- sized for one preset's own burst count (10) scaled up for
         *  its area (see [com.borderkeys.ime.fx.ParticleSimulation.MAX_EXTENT_FACTOR]), not for
         *  all [MAX_SHOWN] rows at once. */
        const val FILL_PARTICLE_POOL_CAPACITY = 32
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

        /** How long the picked row stays pressed after the tap -- see [stopOutlineRunnable]'s
         *  own doc for why this view needs a timer at all. */
        const val OUTLINE_STOP_DELAY_MILLIS = 500L

        const val ARROW = "→"

        /** A plain glyph rather than translated text, the same call [ClipboardPanelView]'s own
         *  BACK_GLYPH already makes: dismissing this is not a word in any one language. */
        const val DISMISS_GLYPH = "×"

        const val DEFAULT_ROW_PX = 132f
    }
}
