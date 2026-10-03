// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.borderkeys.theme.ThemePaints
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys

/**
 * Size and position settings, drawn over the keys. The panel writes nothing: its controls call
 * back to the service, and it draws the state it is given.
 */
@SuppressLint("ViewConstructor")
class QuickSettingsView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    /** The chip labels, resolved once. */
    private val chipLabels = Array(CHIP_LABEL_KEYS.size) { strings[CHIP_LABEL_KEYS[it]] }

    /** Which mode chip a row of chips is offering. Mirrors KeyboardPreferences. */
    enum class Placement { DOCKED, LEFT, RIGHT, FLOATING }

    interface Listener {
        /** Resize was tapped: the handles go on the keyboard. */
        fun onStartResize()
        fun onPlacementChanged(placement: Placement)
        fun onNumberRowChanged(enabled: Boolean)
        fun onOpenFullSettings()
        fun onCloseQuickSettings()
    }

    var listener: Listener? = null

    private var placement = Placement.DOCKED
    private var numberRow = false

    /** Whether the footer that opens the settings application is shown. */
    private var fullSettings = true

    /** The state to draw, pushed from the service whenever the preferences flow emits. */
    fun setState(placement: Placement, numberRow: Boolean, fullSettings: Boolean = true) {
        if (this.placement == placement && this.numberRow == numberRow &&
            this.fullSettings == fullSettings
        ) {
            return
        }
        this.placement = placement
        this.numberRow = numberRow
        this.fullSettings = fullSettings
        invalidate()
    }

    /** The panel's text paints: the theme's colours, sized from the panel. */
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val metrics = Paint.FontMetrics()

    /** Distance from a row's centre to the text baseline, for the current label size. */
    private var labelBaseline = 0f

    /** Colours are re-read whenever the theme changes; sizes whenever the panel is measured. */
    fun onThemeChanged() {
        titlePaint.color = paints.label.color
        labelPaint.color = paints.label.color
        secondaryPaint.color = paints.labelSecondary.color
        accentPaint.color = paints.accent.color
        outlinePaint.color = paints.accent.color
        invalidate()
    }

    // ---- geometry ------------------------------------------------------------------------------
    //
    // Evenly spaced rows, laid out once per size change.

    private var rowHeight = 0f
    private var padding = 0f
    private var resizeTop = 0f
    private var resizeBottom = 0f
    private var chipTop = 0f
    private var chipBottom = 0f
    private var chipWidth = 0f
    private val chipLeft = FloatArray(Placement.entries.size)

    private var toggleTop = 0f
    private var toggleBottom = 0f
    private var footerTop = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            return
        }
        padding = w * 0.05f
        rowHeight = h / 5f
        resizeTop = rowHeight * 1.15f
        resizeBottom = rowHeight * 1.85f

        chipTop = rowHeight * 2.15f
        chipBottom = rowHeight * 2.85f
        val available = w - padding * 2f
        val gap = w * 0.02f
        chipWidth = (available - gap * (Placement.entries.size - 1)) / Placement.entries.size
        for (i in Placement.entries.indices) {
            chipLeft[i] = padding + (chipWidth + gap) * i
        }

        toggleTop = rowHeight * 3.15f
        toggleBottom = rowHeight * 3.85f
        footerTop = rowHeight * 4f

        titlePaint.textSize = rowHeight * 0.36f
        labelPaint.textSize = rowHeight * 0.30f
        secondaryPaint.textSize = rowHeight * 0.28f
        accentPaint.textSize = rowHeight * 0.28f
        outlinePaint.strokeWidth = rowHeight * 0.05f
        onThemeChanged()
        labelPaint.getFontMetrics(metrics)
        labelBaseline = -(metrics.ascent + metrics.descent) / 2f
    }

    // ---- drawing -------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, paints.background)

        val radius = paints.keyCornerRadiusPx
        canvas.drawText(strings[Keys.PANEL_KEYBOARD], padding, rowHeight * 0.5f + labelBaseline, titlePaint)
        canvas.drawText(strings[Keys.PANEL_CLOSE], w - padding - accentPaint.measureText(strings[Keys.PANEL_CLOSE]),
            rowHeight * 0.5f + labelBaseline, accentPaint)

        // Resize closes the panel and puts handles on the keyboard.
        val resizeLabel = strings[Keys.PANEL_RESIZE]
        canvas.drawRoundRect(padding, resizeTop, w - padding, resizeBottom, radius, radius,
            paints.keyFill)
        canvas.drawRoundRect(padding, resizeTop, w - padding, resizeBottom, radius, radius,
            outlinePaint)
        canvas.drawText(resizeLabel, (w - labelPaint.measureText(resizeLabel)) / 2f,
            (resizeTop + resizeBottom) / 2f + labelBaseline, labelPaint)

        for (i in Placement.entries.indices) {
            val entry = Placement.entries[i]
            val selected = entry == placement
            val left = chipLeft[i]
            canvas.drawRoundRect(left, chipTop, left + chipWidth, chipBottom, radius, radius,
                if (selected) paints.keyPressedFill else paints.keyFill)
            if (selected) {
                // The selected chip gets the accent outline; every label keeps the primary colour.
                canvas.drawRoundRect(left, chipTop, left + chipWidth, chipBottom, radius, radius,
                    outlinePaint)
            } else if (paints.showKeyBorders) {
                // The other chips get the keys' hairline.
                canvas.drawRoundRect(left, chipTop, left + chipWidth, chipBottom, radius, radius,
                    paints.keyStroke)
            }
            val label = chipLabels[i]
            canvas.drawText(label, left + (chipWidth - labelPaint.measureText(label)) / 2f,
                (chipTop + chipBottom) / 2f + labelBaseline, labelPaint)
        }

        // The number-row toggle: a chip, filled when on.
        val toggleLabel = strings[Keys.PANEL_NUMBER_ROW]
        canvas.drawRoundRect(padding, toggleTop, padding + chipWidth * 2f, toggleBottom,
            radius, radius, if (numberRow) paints.keyPressedFill else paints.keyFill)
        if (numberRow) {
            canvas.drawRoundRect(padding, toggleTop, padding + chipWidth * 2f, toggleBottom,
                radius, radius, outlinePaint)
        } else if (paints.showKeyBorders) {
            canvas.drawRoundRect(padding, toggleTop, padding + chipWidth * 2f, toggleBottom,
                radius, radius, paints.keyStroke)
        }
        canvas.drawText(
            toggleLabel,
            padding + (chipWidth * 2f - labelPaint.measureText(toggleLabel)) / 2f,
            (toggleTop + toggleBottom) / 2f + labelBaseline,
            labelPaint,
        )

        if (fullSettings) {
            canvas.drawText(strings[Keys.PANEL_ALL_SETTINGS], padding, footerTop + rowHeight * 0.5f + labelBaseline, accentPaint)
        }
    }

    // ---- touch ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y < rowHeight) {
                    if (x > width - padding * 2f - accentPaint.measureText(strings[Keys.PANEL_CLOSE])) {
                        listener?.onCloseQuickSettings()
                    }
                    return true
                }
                if (y in resizeTop..resizeBottom && x >= padding && x <= width - padding) {
                    listener?.onStartResize()
                    return true
                }
                if (y in chipTop..chipBottom) {
                    for (i in Placement.entries.indices) {
                        if (x >= chipLeft[i] && x <= chipLeft[i] + chipWidth) {
                            listener?.onPlacementChanged(Placement.entries[i])
                            return true
                        }
                    }
                    return true
                }
                if (y in toggleTop..toggleBottom && x <= padding + chipWidth * 2f) {
                    listener?.onNumberRowChanged(!numberRow)
                    return true
                }
                if (y >= footerTop && fullSettings) {
                    listener?.onOpenFullSettings()
                    return true
                }
                return true
            }

        }
        return true
    }

    private companion object {
        /** The chip labels' catalogue keys. */
        val CHIP_LABEL_KEYS = arrayOf(
            Keys.PANEL_DOCK, Keys.PANEL_LEFT, Keys.PANEL_RIGHT, Keys.PANEL_FLOAT,
        )
    }
}
