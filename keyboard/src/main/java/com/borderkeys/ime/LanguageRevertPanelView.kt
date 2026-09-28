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
 * The offer `Ask` mode makes, in the suggestion strip's place: the words that may have been
 * corrected under the wrong language, each with its current and its new spelling, one tap to
 * apply. The keys stay live.
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

    /** The text [drawRow] paints for each of [rows], built when the rows are set. */
    private var rowTexts: List<String> = emptyList()
    private var pressedRow = -1
    private var pressedDismiss = false
    private var rowHeightPx = 0f

    /** The panel's particle layers: a burst and a traced outline in the picked row. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidate() }

    /** The picked row, as a particle shape. */
    private val rowElement = RoundedRectElement()

    /** Releases the picked row's particles [OUTLINE_STOP_DELAY_MILLIS] after the tap. */
    private val stopOutlineRunnable = Runnable { particles.release() }

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Replaces the offered list. */
    fun offer(replacements: List<LanguageSwitchCorrector.Replacement>) {
        setRows(replacements.take(MAX_SHOWN))
    }

    /** Removes one row after it was applied, returning how many are left. */
    fun remove(replacement: LanguageSwitchCorrector.Replacement): Int {
        setRows(rows.filterNot { it == replacement })
        return rows.size
    }

    private fun setRows(replacements: List<LanguageSwitchCorrector.Replacement>) {
        rows = replacements
        rowTexts = replacements.map { "${it.previousText} $ARROW ${it.text}" }
        requestLayout()
        invalidate()
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
            // A burst keeps animating after the last row is removed.
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
        val text = rowTexts[index]
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
                    // A picked row gets particles; the dismiss control does not.
                    val top = pressedRow * rowHeightPx
                    val right = width - rowHeightPx
                    val bottom = top + rowHeightPx
                    // The trace stops after a fixed delay.
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
        /** The most rows shown, in commit order. */
        const val MAX_SHOWN = 4

        /** The most fill particles alive at once. */
        const val FILL_PARTICLE_POOL_CAPACITY = 32
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

        /** How long the picked row's outline is traced after the tap. */
        const val OUTLINE_STOP_DELAY_MILLIS = 500L

        const val ARROW = "→"

        /** The dismiss glyph; not translated. */
        const val DISMISS_GLYPH = "×"

        const val DEFAULT_ROW_PX = 132f
    }
}
