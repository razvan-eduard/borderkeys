// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.keyboard.R
import com.borderkeys.theme.ThemePaints

/**
 * A row of buttons for the things that are otherwise several gestures.
 *
 * Drawn rather than composed, like everything else in the keyboard process: icons are vector
 * drawables loaded once and drawn into bounds computed on layout, so the draw path sets no
 * state and allocates nothing.
 *
 * Two shapes. Open, it is the whole row. Collapsed, it is one button that opens the row and
 * closes it again as soon as an action is chosen -- which is the point of collapsing it, since
 * the alternative is a row that costs height on every screen for a button pressed twice a day.
 */
@SuppressLint("ViewConstructor")
class QuickActionsView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: com.borderkeys.i18n.LanguageManager,
) : View(context) {

    fun interface Listener {
        fun onQuickAction(action: QuickAction)
    }

    var listener: Listener? = null

    /** What the bar offers, in order. Replacing it re-resolves the icons and re-lays them out. */
    var actions: List<QuickAction> = emptyList()
        set(value) {
            field = value.take(MAX_BUTTONS)
            resolveIcons()
            layoutButtons()
            invalidate()
        }

    /**
     * Whether the bar shows as one button until it is opened.
     *
     * Held separately from [expanded] so that closing the bar after an action returns it to the
     * shape the user chose rather than to whatever it was showing a moment ago.
     */
    var collapsible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                expanded = false
                requestLayout()
                invalidate()
            }
        }

    /** True while a collapsible bar is open. Always true when the bar is not collapsible. */
    private var expanded = false

    /** Whether the buttons run left to right or top to bottom. */
    var vertical: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
                invalidate()
            }
        }

    private val icons = arrayOfNulls<Drawable>(MAX_BUTTONS)
    private val moreIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.bk_action_more)

    /** Button centres, in view coordinates. Recomputed on layout, never per frame. */
    private val centreX = FloatArray(MAX_BUTTONS)
    private val centreY = FloatArray(MAX_BUTTONS)
    private var buttonSizePx = 0

    private var pressedIndex = -1

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** How many buttons are drawn right now: all of them, or the single opener. */
    private fun shownCount(): Int =
        if (collapsible && !expanded) 1 else actions.size

    private fun resolveIcons() {
        for (index in icons.indices) {
            icons[index] = if (index < actions.size) {
                ContextCompat.getDrawable(context, iconFor(actions[index]))
            } else {
                null
            }
        }
    }

    private fun iconFor(action: QuickAction): Int = when (action) {
        QuickAction.COPY_PREVIOUS_WORD -> R.drawable.bk_action_copy_previous_word
        QuickAction.COPY_LINE -> R.drawable.bk_action_copy_line
        QuickAction.COPY_ALL -> R.drawable.bk_action_copy_all
        QuickAction.PASTE -> R.drawable.bk_action_paste
        QuickAction.CLIPBOARD_HISTORY -> R.drawable.bk_action_clipboard_history
        QuickAction.SELECT_ALL -> R.drawable.bk_action_select_all
        QuickAction.CUT -> R.drawable.bk_action_cut
        QuickAction.SELECT_WORD -> R.drawable.bk_action_select_word
        QuickAction.DELETE_WORD -> R.drawable.bk_action_delete_word
        QuickAction.CURSOR_START -> R.drawable.bk_action_cursor_start
        QuickAction.CURSOR_END -> R.drawable.bk_action_cursor_end
        QuickAction.NEWLINE -> R.drawable.bk_action_newline
        QuickAction.SWITCH_LAYOUT -> R.drawable.bk_action_switch_layout
        QuickAction.SETTINGS -> R.drawable.bk_action_settings
        QuickAction.UNDO -> R.drawable.bk_action_undo
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val thickness = barThicknessPx()
        if (vertical) {
            setMeasuredDimension(thickness, MeasureSpec.getSize(heightMeasureSpec))
        } else {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thickness)
        }
    }

    private fun barThicknessPx(): Int {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_THICKNESS_PX
        return (row * BAR_HEIGHT_FRACTION).toInt().coerceAtLeast(1)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutButtons()
    }

    /**
     * Spreads the buttons evenly along the bar and fixes the icon size from the short side.
     *
     * Icons are square and sized from the thickness rather than from the spacing, so a bar with
     * two buttons and a bar with eight draw the same size icon -- a button that grows because
     * it has fewer neighbours is a button that moves when the bar is edited.
     */
    private fun layoutButtons() {
        val shown = shownCount()
        if (shown <= 0 || width == 0 || height == 0) {
            return
        }
        val thickness = if (vertical) width else height
        buttonSizePx = (thickness * ICON_FRACTION).toInt().coerceAtLeast(1)
        val along = if (vertical) height else width
        val step = along.toFloat() / shown
        for (index in 0 until shown) {
            val centre = step * index + step / 2f
            if (vertical) {
                centreX[index] = width / 2f
                centreY[index] = centre
            } else {
                centreX[index] = centre
                centreY[index] = height / 2f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("QuickActionsView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
            val shown = shownCount()
            val half = buttonSizePx / 2
            for (index in 0 until shown) {
                val cx = centreX[index].toInt()
                val cy = centreY[index].toInt()
                if (index == pressedIndex) {
                    val padding = half + (half / 2)
                    canvas.drawRect(
                        (cx - padding).toFloat(), (cy - padding).toFloat(),
                        (cx + padding).toFloat(), (cy + padding).toFloat(),
                        paints.keyPressedFill,
                    )
                }
                val icon = if (collapsible && !expanded) moreIcon else icons[index]
                if (icon != null) {
                    icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                    // Tinted to the label colour so the bar belongs to the theme rather than to
                    // whatever colour the drawable was authored in.
                    icon.setTint(paints.label.color)
                    icon.draw(canvas)
                }
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun buttonAt(x: Float, y: Float): Int {
        val shown = shownCount()
        if (shown <= 0) {
            return -1
        }
        val along = if (vertical) y else x
        val extent = if (vertical) height else width
        if (extent <= 0) {
            return -1
        }
        val index = (along / (extent.toFloat() / shown)).toInt()
        return if (index in 0 until shown) index else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = buttonAt(event.x, event.y)
                invalidate()
                return pressedIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val index = buttonAt(event.x, event.y)
                if (index != pressedIndex) {
                    pressedIndex = index
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = buttonAt(event.x, event.y)
                pressedIndex = -1
                if (index < 0) {
                    invalidate()
                    return true
                }
                if (collapsible && !expanded) {
                    // The opener. Nothing happens beyond opening: a button that both opens the
                    // bar and fires its first action would fire it every time it is opened.
                    expanded = true
                    layoutButtons()
                    requestLayout()
                    invalidate()
                    return true
                }
                val action = actions.getOrNull(index)
                if (collapsible) {
                    // Closes as soon as one is chosen, which is what "collapsed" was asked for.
                    expanded = false
                    requestLayout()
                }
                invalidate()
                if (action != null) {
                    listener?.onQuickAction(action)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return false
    }

    /** Closes a collapsible bar, for when the keyboard is dismissed with it standing open. */
    fun collapse() {
        if (collapsible && expanded) {
            expanded = false
            requestLayout()
            invalidate()
        }
    }

    private companion object {
        /** The format's own cap; the preferences clamp to the same number. */
        const val MAX_BUTTONS = 10

        /** The bar is a little shorter than a key row: it is a tool strip, not another row. */
        const val BAR_HEIGHT_FRACTION = 0.82f

        /** How much of the bar's thickness an icon takes, leaving a touch margin around it. */
        const val ICON_FRACTION = 0.52f

        const val DEFAULT_THICKNESS_PX = 132f
    }
}
