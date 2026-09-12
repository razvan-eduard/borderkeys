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
            if (rows.isEmpty()) {
                return
            }
            val dismissWidth = rowHeightPx
            for (index in rows.indices) {
                drawRow(canvas, index, index * rowHeightPx, dismissWidth)
            }
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

    private companion object {
        /** More than this and the offer is no longer "a couple of words," it is a list -- shown
         *  once, in commit order, rather than scrolled. */
        const val MAX_SHOWN = 4

        const val ARROW = "→"

        /** A plain glyph rather than translated text, the same call [ClipboardPanelView]'s own
         *  BACK_GLYPH already makes: dismissing this is not a word in any one language. */
        const val DISMISS_GLYPH = "×"

        const val DEFAULT_ROW_PX = 132f
    }
}
