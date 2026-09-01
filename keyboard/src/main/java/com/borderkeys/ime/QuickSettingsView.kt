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

/**
 * The settings you want while typing, drawn over the keys.
 *
 * Size and position are the settings nobody wants to leave the keyboard to change. Deciding
 * whether it is too tall, or whether one-handed mode helps, means looking at the keyboard while
 * you adjust it -- and the moment you have to open an application to do that, you are no longer
 * looking at the thing you are adjusting, in the app where it felt wrong.
 *
 * Canvas, like everything else in this module: `:keyboard` cannot see Compose, and the gate that
 * enforces that is the reason the typing surface stays fast. The arithmetic is the same as the
 * keyboard's -- rows and columns laid out into arrays once per size change, touches resolved by
 * comparing against them rather than by walking a view tree.
 *
 * It writes nothing itself. Every control calls back to the service, which writes to the same
 * DataStore the settings application writes to, and the change arrives back through the same
 * flow that already redraws the keyboard. So the panel cannot drift from the settings screen:
 * there is one place the value lives and neither of them holds a second copy.
 */
@SuppressLint("ViewConstructor")
class QuickSettingsView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    /** Which mode chip a row of chips is offering. Mirrors KeyboardPreferences. */
    enum class Placement { DOCKED, LEFT, RIGHT, FLOATING }

    interface Listener {
        fun onHeightScaleChanged(scale: Float)
        fun onWidthScaleChanged(scale: Float)
        fun onPlacementChanged(placement: Placement)
        fun onNumberRowChanged(enabled: Boolean)
        fun onOpenFullSettings()
        fun onCloseQuickSettings()
    }

    var listener: Listener? = null

    private var heightScale = 1f
    private var widthScale = 1f
    private var placement = Placement.DOCKED
    private var numberRow = false

    /**
     * The state to draw, pushed from the service whenever the preferences flow emits.
     *
     * The panel never holds the truth. It is told what the stored values are and it reports
     * taps; if a write fails or is clamped, what comes back is what is drawn.
     */
    fun setState(
        heightScale: Float,
        widthScale: Float,
        placement: Placement,
        numberRow: Boolean,
    ) {
        if (this.heightScale == heightScale && this.widthScale == widthScale &&
            this.placement == placement && this.numberRow == numberRow
        ) {
            return
        }
        this.heightScale = heightScale
        this.widthScale = widthScale
        this.placement = placement
        this.numberRow = numberRow
        invalidate()
    }

    /**
     * The panel's own text paints, sized from the panel rather than from the keys.
     *
     * ThemePaints exists for a keyboard: its label paint is sized so a letter fills a key, which
     * on a settings panel comes out as a headline running off the edge -- which is exactly what
     * the first version of this did. The colours are still the theme's, so the panel matches the
     * keyboard it is covering; only the sizes are its own.
     */
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
    // Laid out once per size change into these fields rather than per frame or per touch. Six
    // rows, evenly spaced, with the sliders and chips positioned inside their row.

    private var rowHeight = 0f
    private var padding = 0f
    private var trackLeft = 0f
    private var trackRight = 0f
    private var chipTop = 0f
    private var chipBottom = 0f
    private var chipWidth = 0f
    private val chipLeft = FloatArray(Placement.entries.size)

    private var heightRowCentre = 0f
    private var widthRowCentre = 0f
    private var toggleTop = 0f
    private var toggleBottom = 0f
    private var footerTop = 0f

    /** Which slider a finger is currently dragging, so a drag keeps its target when it strays. */
    private var draggingHeight = false
    private var draggingWidth = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            return
        }
        padding = w * 0.05f
        rowHeight = h / 6f
        trackLeft = padding + w * 0.22f
        trackRight = w - padding
        heightRowCentre = rowHeight * 1.5f
        widthRowCentre = rowHeight * 2.5f

        chipTop = rowHeight * 3.15f
        chipBottom = rowHeight * 3.85f
        val available = w - padding * 2f
        val gap = w * 0.02f
        chipWidth = (available - gap * (Placement.entries.size - 1)) / Placement.entries.size
        for (i in Placement.entries.indices) {
            chipLeft[i] = padding + (chipWidth + gap) * i
        }

        toggleTop = rowHeight * 4.15f
        toggleBottom = rowHeight * 4.85f
        footerTop = rowHeight * 5f

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
        canvas.drawText("Keyboard", padding, rowHeight * 0.5f + labelBaseline, titlePaint)
        canvas.drawText(CLOSE, w - padding - accentPaint.measureText(CLOSE),
            rowHeight * 0.5f + labelBaseline, accentPaint)

        drawSlider(canvas, "Height", heightRowCentre, fraction(heightScale, MIN_HEIGHT, MAX_HEIGHT),
            enabled = true)
        drawSlider(canvas, "Width", widthRowCentre, fraction(widthScale, MIN_WIDTH, 1f),
            enabled = placement != Placement.DOCKED)

        for (i in Placement.entries.indices) {
            val entry = Placement.entries[i]
            val selected = entry == placement
            val left = chipLeft[i]
            canvas.drawRoundRect(left, chipTop, left + chipWidth, chipBottom, radius, radius,
                if (selected) paints.keyPressedFill else paints.keyFill)
            if (selected) {
                // Selection is an outline, not a text colour. The label always uses the primary
                // colour, which the theme guarantees is readable on a key fill -- that is what
                // the keyboard itself relies on. Dimming the unselected labels instead left
                // three of the four chips unreadable on a dark theme.
                canvas.drawRoundRect(left, chipTop, left + chipWidth, chipBottom, radius, radius,
                    outlinePaint)
            }
            val label = CHIP_LABELS[i]
            canvas.drawText(label, left + (chipWidth - labelPaint.measureText(label)) / 2f,
                (chipTop + chipBottom) / 2f + labelBaseline, labelPaint)
        }

        // One toggle, drawn as a chip that is filled when on: a switch track and thumb would be
        // three more shapes for a control that has two states and one word.
        val toggleLabel = "Number row"
        canvas.drawRoundRect(padding, toggleTop, padding + chipWidth * 2f, toggleBottom,
            radius, radius, if (numberRow) paints.keyPressedFill else paints.keyFill)
        if (numberRow) {
            canvas.drawRoundRect(padding, toggleTop, padding + chipWidth * 2f, toggleBottom,
                radius, radius, outlinePaint)
        }
        canvas.drawText(
            toggleLabel,
            padding + (chipWidth * 2f - labelPaint.measureText(toggleLabel)) / 2f,
            (toggleTop + toggleBottom) / 2f + labelBaseline,
            labelPaint,
        )

        canvas.drawText(MORE, padding, footerTop + rowHeight * 0.5f + labelBaseline, accentPaint)
    }

    private fun drawSlider(canvas: Canvas, label: String, centreY: Float, position: Float,
                           enabled: Boolean) {
        val paint = if (enabled) labelPaint else secondaryPaint
        canvas.drawText(label, padding, centreY + labelBaseline, paint)
        val trackHeight = rowHeight * 0.08f
        canvas.drawRoundRect(trackLeft, centreY - trackHeight / 2f, trackRight,
            centreY + trackHeight / 2f, trackHeight, trackHeight, paints.keyFill)
        val knobX = trackLeft + (trackRight - trackLeft) * position
        if (enabled) {
            canvas.drawRoundRect(trackLeft, centreY - trackHeight / 2f, knobX,
                centreY + trackHeight / 2f, trackHeight, trackHeight, paints.accent)
        }
        canvas.drawCircle(knobX, centreY, rowHeight * 0.22f,
            if (enabled) paints.accent else paints.keyFill)
    }

    private fun fraction(value: Float, min: Float, max: Float): Float =
        ((value - min) / (max - min)).coerceIn(0f, 1f)

    // ---- touch ---------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHeight = false
                draggingWidth = false
                if (y < rowHeight) {
                    if (x > width - padding * 2f - accentPaint.measureText(CLOSE)) {
                        listener?.onCloseQuickSettings()
                    }
                    return true
                }
                if (y < rowHeight * 2f) {
                    draggingHeight = true
                    applyHeight(x)
                    return true
                }
                if (y < rowHeight * 3f) {
                    if (placement != Placement.DOCKED) {
                        draggingWidth = true
                        applyWidth(x)
                    }
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
                if (y >= footerTop) {
                    listener?.onOpenFullSettings()
                    return true
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // A drag that strays out of its row keeps its slider. Re-deciding per move would
                // make a fast horizontal drag jump to whichever control it passed over.
                if (draggingHeight) {
                    applyHeight(x)
                } else if (draggingWidth) {
                    applyWidth(x)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingHeight = false
                draggingWidth = false
                return true
            }
        }
        return true
    }

    private fun applyHeight(x: Float) {
        val position = ((x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        listener?.onHeightScaleChanged(MIN_HEIGHT + position * (MAX_HEIGHT - MIN_HEIGHT))
    }

    private fun applyWidth(x: Float) {
        val position = ((x - trackLeft) / (trackRight - trackLeft)).coerceIn(0f, 1f)
        listener?.onWidthScaleChanged(MIN_WIDTH + position * (1f - MIN_WIDTH))
    }

    private companion object {
        // Mirrors KeyboardPreferences, duplicated rather than imported so that :keyboard's view
        // layer does not depend on :data for four numbers. The service clamps anyway.
        const val MIN_HEIGHT = 0.65f
        const val MAX_HEIGHT = 1.6f
        const val MIN_WIDTH = 0.55f

        val CHIP_LABELS = arrayOf("Dock", "Left", "Right", "Float")
        const val CLOSE = "Close"
        const val MORE = "All settings…"
    }
}
