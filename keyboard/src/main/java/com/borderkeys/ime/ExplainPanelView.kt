// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Trace
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.theme.ThemePaints

/**
 * The answer to "why this word?": a title naming the word and what was typed, then one line
 * per term of the engine's own account of its score, and a Close control.
 *
 * Takes the keys' place the way the clipboard and emoji panels do, because it is a few lines
 * somebody reads rather than an offer beside live keys. Drawn like the rest of the keyboard:
 * one View, one onDraw, arithmetic hit testing, nothing allocated on the draw path.
 */
@SuppressLint("ViewConstructor")
class ExplainPanelView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    fun interface Listener {
        /** The Close control was tapped. */
        fun onExplainDismissed()
    }

    var listener: Listener? = null

    private var title = ""
    private var lines: List<String> = emptyList()
    private var pressedClose = false
    private var rowHeightPx = 0f

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Replaces what the panel shows. */
    fun show(title: String, lines: List<String>) {
        this.title = title
        this.lines = lines
        pressedClose = false
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        rowHeightPx = if (paints.rowHeightPx > 0f) paints.rowHeightPx * ROW_FRACTION else DEFAULT_ROW_PX
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("ExplainPanelView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
            val margin = rowHeightPx * MARGIN_ROWS
            val closeWidth = closeWidthPx()
            drawLine(canvas, title, 0f, width - closeWidth - margin, paints.label, margin)
            drawClose(canvas, closeWidth)
            var top = rowHeightPx
            for (line in lines) {
                if (top + rowHeightPx > height) {
                    break
                }
                canvas.drawLine(0f, top, width.toFloat(), top, paints.keyStroke)
                drawLine(canvas, line, top, width - margin, paints.labelSecondary, margin)
                top += rowHeightPx
            }
        } finally {
            Trace.endSection()
        }
    }

    /** One row of text at [top], left-aligned at [left], cut with an ellipsis to [right]. */
    private fun drawLine(canvas: Canvas, text: String, top: Float, right: Float, paint: Paint, left: Float) {
        val previous = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        val midY = top + rowHeightPx / 2f + paints.labelBaselineOffsetPx
        val room = (right - left).coerceAtLeast(0f)
        val fits = paint.breakText(text, true, room, null)
        if (fits >= text.length) {
            canvas.drawText(text, left, midY, paint)
        } else {
            val ellipsisWidth = paint.measureText(ELLIPSIS)
            val kept = paint.breakText(text, true, (room - ellipsisWidth).coerceAtLeast(0f), null)
            canvas.drawText(text.substring(0, kept) + ELLIPSIS, left, midY, paint)
        }
        paint.textAlign = previous
    }

    private fun drawClose(canvas: Canvas, closeWidth: Float) {
        val left = width - closeWidth
        if (pressedClose) {
            canvas.drawRect(left, 0f, width.toFloat(), rowHeightPx, paints.keyPressedFill)
        }
        val previous = paints.accentLabel.textAlign
        paints.accentLabel.textAlign = Paint.Align.CENTER
        canvas.drawText(
            strings[Keys.PANEL_CLOSE],
            left + closeWidth / 2f,
            rowHeightPx / 2f + paints.labelBaselineOffsetPx,
            paints.accentLabel,
        )
        paints.accentLabel.textAlign = previous
    }

    private fun closeWidthPx(): Float =
        paints.accentLabel.measureText(strings[Keys.PANEL_CLOSE]) + rowHeightPx * MARGIN_ROWS * 2f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedClose = event.y < rowHeightPx && event.x >= width - closeWidthPx()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pressedClose) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    listener?.onExplainDismissed()
                }
                pressedClose = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedClose = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val ROW_FRACTION = 0.55f
        const val DEFAULT_ROW_PX = 96f
        const val MARGIN_ROWS = 0.3f
        const val ELLIPSIS = "…"
    }
}
