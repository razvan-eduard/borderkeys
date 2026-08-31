// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.borderkeys.theme.ThemePaints
import kotlin.math.min

/**
 * The assistant's sheet, drawn over the keyboard.
 *
 * Canvas again, not Compose: this lives in `:keyboard`, which by construction cannot see
 * Compose. It is also the one view here that is allowed to allocate -- laying out paragraphs
 * needs a [StaticLayout], and it is built when the sheet opens, which is an explicit tap, not a
 * frame. Nothing on the typing path touches this class.
 *
 * The shape of it is the policy: original above, proposal below, and three buttons. **Nothing is
 * inserted anywhere until Replace is pressed.** A model's output is a suggestion about the
 * user's own words, and applying it automatically would make the feature something done *to*
 * their text rather than *with* it.
 */
@SuppressLint("ViewConstructor")
class AssistSheetView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    enum class Button { REPLACE, COPY, DISCARD }

    interface Listener {
        fun onAssistButton(button: Button)
    }

    var listener: Listener? = null

    private var title: String = ""
    private var original: String = ""
    private var proposal: String = ""
    private var status: String = ""

    /** Buttons are inert until there is something to act on. */
    private var hasResult = false

    private val textPaint = TextPaint().apply { isAntiAlias = true }
    private val secondaryPaint = TextPaint().apply { isAntiAlias = true }
    private var originalLayout: StaticLayout? = null
    private var proposalLayout: StaticLayout? = null
    private var pressedButton: Button? = null

    private val buttonBounds = FloatArray(Button.entries.size * 4)

    fun showRunning(taskTitle: String, selection: String) {
        title = taskTitle
        original = selection
        proposal = ""
        status = "Working…"
        hasResult = false
        rebuildLayouts()
        invalidate()
    }

    fun showResult(text: String, modelName: String?) {
        proposal = text
        status = modelName?.let { "Produced on this device by $it" } ?: "Produced on this device"
        hasResult = true
        rebuildLayouts()
        invalidate()
    }

    fun showError(message: String) {
        proposal = ""
        status = message
        hasResult = false
        rebuildLayouts()
        invalidate()
    }

    fun currentProposal(): String = proposal

    private fun rebuildLayouts() {
        val width = width - 2 * PADDING_PX
        if (width <= 0) {
            originalLayout = null
            proposalLayout = null
            return
        }
        textPaint.color = paints.theme.textColor
        textPaint.textSize = paints.label.textSize * 0.78f
        secondaryPaint.color = paints.theme.secondaryTextColor
        secondaryPaint.textSize = paints.label.textSize * 0.68f

        originalLayout = build(original, secondaryPaint, width, MAX_ORIGINAL_LINES)
        proposalLayout = if (proposal.isEmpty()) null else build(proposal, textPaint, width,
            MAX_PROPOSAL_LINES)
    }

    private fun build(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowHeight = if (paints.rowHeightPx > 0f) paints.rowHeightPx else 150f
        // Four rows' worth: enough for a short summary without covering more of the screen than
        // the keyboard already did.
        setMeasuredDimension(width, (rowHeight * 4f).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)

        var y = PADDING_PX.toFloat()
        paints.labelSecondary.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText(title, PADDING_PX.toFloat(), y + paints.secondaryBaselineOffsetPx * 2f,
            paints.labelSecondary)
        y += paints.labelSecondary.textSize * 1.8f

        originalLayout?.let { layout ->
            canvas.save()
            canvas.translate(PADDING_PX.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + PADDING_PX
        }

        proposalLayout?.let { layout ->
            canvas.drawLine(PADDING_PX.toFloat(), y, width - PADDING_PX.toFloat(), y,
                paints.keyStroke)
            y += PADDING_PX
            canvas.save()
            canvas.translate(PADDING_PX.toFloat(), y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + PADDING_PX
        }

        if (status.isNotEmpty()) {
            canvas.drawText(status, PADDING_PX.toFloat(), y + paints.secondaryBaselineOffsetPx,
                paints.labelSecondary)
        }
        paints.labelSecondary.textAlign = android.graphics.Paint.Align.CENTER

        drawButtons(canvas)
    }

    private fun drawButtons(canvas: Canvas) {
        val count = Button.entries.size
        val buttonHeight = min(paints.rowHeightPx, height * 0.24f)
        val top = height - buttonHeight - PADDING_PX
        val available = width - 2f * PADDING_PX - (count - 1) * GAP_PX
        val buttonWidth = available / count
        val radius = paints.keyCornerRadiusPx

        for ((index, button) in Button.entries.withIndex()) {
            val left = PADDING_PX + index * (buttonWidth + GAP_PX)
            buttonBounds[index * 4] = left
            buttonBounds[index * 4 + 1] = top
            buttonBounds[index * 4 + 2] = left + buttonWidth
            buttonBounds[index * 4 + 3] = top + buttonHeight

            // Replace is the accented one only when there is something to replace with. A
            // primary-looking button that does nothing is worse than a disabled one.
            val enabled = hasResult || button == Button.DISCARD
            val fill = when {
                button == pressedButton -> paints.keyPressedFill
                button == Button.REPLACE && enabled -> paints.accent
                else -> paints.modifierKeyFill
            }
            canvas.drawRoundRect(left, top, left + buttonWidth, top + buttonHeight, radius,
                radius, fill)

            val label = BUTTON_LABELS[index]
            val paint = if (enabled) paints.label else paints.labelSecondary
            canvas.drawText(
                label, left + buttonWidth / 2f,
                top + buttonHeight / 2f + paints.labelBaselineOffsetPx, paint,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedButton = buttonAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val hit = buttonAt(event.x, event.y)
                if (hit != pressedButton) {
                    pressedButton = hit
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val hit = buttonAt(event.x, event.y)
                pressedButton = null
                invalidate()
                if (hit != null && (hasResult || hit == Button.DISCARD)) {
                    listener?.onAssistButton(hit)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedButton = null
                invalidate()
            }
        }
        return true
    }

    private fun buttonAt(x: Float, y: Float): Button? {
        for ((index, button) in Button.entries.withIndex()) {
            val left = buttonBounds[index * 4]
            val top = buttonBounds[index * 4 + 1]
            val right = buttonBounds[index * 4 + 2]
            val bottom = buttonBounds[index * 4 + 3]
            if (x >= left && x <= right && y >= top && y <= bottom) {
                return button
            }
        }
        return null
    }

    private companion object {
        const val PADDING_PX = 24
        const val GAP_PX = 12f
        const val MAX_ORIGINAL_LINES = 3
        const val MAX_PROPOSAL_LINES = 6
        val BUTTON_LABELS = arrayOf("Replace", "Copy", "Discard")
    }
}
