// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.max
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.borderkeys.data.theme.CustomIcon
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBarItem
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.fx.ParticleSurface
import com.borderkeys.ime.fx.RoundedRectElement
import com.borderkeys.keyboard.R
import com.borderkeys.theme.ThemePaints

/**
 * A row of buttons for the things that are otherwise several gestures, with icons loaded once and
 * placed on layout. Collapsible, it shows one button that opens the row and closes it again once
 * an action is chosen.
 */
@SuppressLint("ViewConstructor")
class QuickActionsView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: com.borderkeys.i18n.LanguageManager,
) : View(context) {

    fun interface Listener {
        fun onQuickAction(item: QuickActionBarItem)
    }

    var listener: Listener? = null

    /** What the bar offers, in order. Replacing it re-resolves the icons and re-lays them out. */
    var items: List<QuickActionBarItem> = emptyList()
        set(value) {
            field = value.take(MAX_BUTTONS)
            resolveIcons()
            resolveLabels()
            layoutButtons()
            invalidate()
        }

    /** Whether each button shows its name under its icon; a vertical bar ignores this. */
    var showLabels: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                layoutButtons()
                requestLayout()
                invalidate()
            }
        }

    /** Whether the bar shows as one button until it is opened. */
    var collapsible: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                expanded = false
                layoutButtons()
                requestLayout()
                invalidate()
            }
        }

    /** True while a collapsible bar is open. Always true when the bar is not collapsible. */
    private var expanded = false

    /** How much room each button gets, as an index into [SIZE_THICKNESS_FRACTION]. */
    var sizeLevel: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, SIZE_THICKNESS_FRACTION.lastIndex)
            if (field != clamped) {
                field = clamped
                layoutButtons()
                requestLayout()
                invalidate()
            }
        }

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

    /** What each button is called, resolved with [items]; custom actions use their own name. */
    private val labels = arrayOfNulls<String>(MAX_BUTTONS)

    /** [labels], wrapped to their slots on layout. */
    private val labelLayouts = arrayOfNulls<StaticLayout>(MAX_BUTTONS)

    // Left-aligned; the layouts centre each line.
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var labelLayoutWidth = 0
    private var labelTopY = 0f

    /** Where the tallest label on the bar ends. */
    private var labelBandBottomY = 0f

    /** One slot's width along the bar. */
    private var slotPx = 0f

    /** Which edge of the bar meets the keyboard; each button's tab is flat against it. */
    var attachedEdge: Int = EDGE_BOTTOM
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val tabPath = android.graphics.Path()
    private val tabRadii = FloatArray(8)

    /** The width onMeasure was last given, for measuring the label band before layout. */
    private var measuredWidthHint = 0

    /** Button centres, in view coordinates, computed on layout. */
    private val centreX = FloatArray(MAX_BUTTONS)
    private val centreY = FloatArray(MAX_BUTTONS)
    private var buttonSizePx = 0

    private var pressedIndex = -1

    /** The bar's particle layers: a burst in a pressed button. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidate() }

    /** The pressed button's tab, as a particle shape. */
    private val buttonElement = RoundedRectElement()

    /**
     * A button's tab, what a press lights and the outline traces: most of its slot along the bar,
     * flat against [attachedEdge], a small gap in from the free edge.
     */
    private fun pressedBounds(index: Int, out: android.graphics.RectF) {
        val gap = buttonSizePx * TAB_FREE_GAP_FRACTION
        val alongHalf = slotPx * TAB_ALONG_FRACTION / 2f
        val cx = centreX[index]
        val cy = centreY[index]
        when (attachedEdge) {
            EDGE_TOP -> out.set(cx - alongHalf, 0f, cx + alongHalf, height - gap)
            EDGE_LEFT -> out.set(0f, cy - alongHalf, width - gap, cy + alongHalf)
            EDGE_RIGHT -> out.set(gap, cy - alongHalf, width.toFloat(), cy + alongHalf)
            else -> out.set(cx - alongHalf, gap, cx + alongHalf, height.toFloat())
        }
    }

    /**
     * [rect] as a path with the keys' corner radius on the free corners and none on the two
     * against [attachedEdge]. The radii run top-left, top-right, bottom-right, bottom-left, two
     * floats each.
     */
    private fun tabPath(rect: android.graphics.RectF): android.graphics.Path {
        val radius = paints.keyCornerRadiusPx.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)
        java.util.Arrays.fill(tabRadii, radius)
        val square = when (attachedEdge) {
            EDGE_TOP -> intArrayOf(0, 1, 2, 3)
            EDGE_LEFT -> intArrayOf(0, 1, 6, 7)
            EDGE_RIGHT -> intArrayOf(2, 3, 4, 5)
            else -> intArrayOf(4, 5, 6, 7)
        }
        for (slot in square) {
            tabRadii[slot] = 0f
        }
        tabPath.reset()
        tabPath.addRoundRect(rect, tabRadii, android.graphics.Path.Direction.CW)
        return tabPath
    }

    private val pressedBoundsScratch = android.graphics.RectF()

    private fun pressButton(index: Int) {
        pressedBounds(index, pressedBoundsScratch)
        buttonElement.set(
            pressedBoundsScratch.left, pressedBoundsScratch.top, pressedBoundsScratch.right, pressedBoundsScratch.bottom,
        )
        particles.press(buttonElement)
    }

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** How many buttons are drawn right now: all of them, or the single opener. */
    private fun shownCount(): Int =
        if (collapsible && !expanded) 1 else items.size

    private fun resolveIcons() {
        for (index in icons.indices) {
            icons[index] = if (index < items.size) {
                ContextCompat.getDrawable(context, iconFor(items[index]))
            } else {
                null
            }
        }
    }

    private fun iconFor(item: QuickActionBarItem): Int = when (item) {
        is QuickActionBarItem.Builtin -> iconFor(item.action)
        is QuickActionBarItem.Custom -> iconFor(CustomIcon.fromId(item.action.icon))
    }

    private fun iconFor(icon: CustomIcon): Int = when (icon) {
        CustomIcon.WAND -> R.drawable.bk_icon_wand
        CustomIcon.CHAT -> R.drawable.bk_icon_chat
        CustomIcon.STAR -> R.drawable.bk_icon_star
        CustomIcon.TAG -> R.drawable.bk_icon_tag
        CustomIcon.QUOTE -> R.drawable.bk_icon_quote
        CustomIcon.PENCIL -> R.drawable.bk_icon_pencil
        CustomIcon.BOOK -> R.drawable.bk_icon_book
        CustomIcon.GLOBE -> R.drawable.bk_icon_globe
        CustomIcon.LIGHTBULB -> R.drawable.bk_icon_lightbulb
        CustomIcon.FLAG -> R.drawable.bk_icon_flag
        CustomIcon.REFRESH -> R.drawable.bk_icon_refresh
        CustomIcon.CHECK -> R.drawable.bk_icon_check
        CustomIcon.MEGAPHONE -> R.drawable.bk_icon_megaphone
        CustomIcon.HEART -> R.drawable.bk_icon_heart
        CustomIcon.COMPASS -> R.drawable.bk_icon_compass
        CustomIcon.BOOKMARK -> R.drawable.bk_icon_bookmark
    }

    private fun resolveLabels() {
        for (index in labels.indices) {
            labels[index] = if (index < items.size) labelFor(items[index]) else null
        }
    }

    private fun labelFor(item: QuickActionBarItem): String = when (item) {
        is QuickActionBarItem.Builtin -> strings[labelKey(item.action)]
        is QuickActionBarItem.Custom -> item.action.name
    }

    /** The catalogue key naming [action]. */
    private fun labelKey(action: QuickAction): String = when (action) {
        QuickAction.COPY_PREVIOUS_WORD -> Keys.ACTION_COPY_PREVIOUS_WORD
        QuickAction.COPY_LINE -> Keys.ACTION_COPY_LINE
        QuickAction.COPY_ALL -> Keys.ACTION_COPY_ALL
        QuickAction.PASTE -> Keys.ACTION_PASTE
        QuickAction.CLIPBOARD_HISTORY -> Keys.ACTION_CLIPBOARD_HISTORY
        QuickAction.SELECT_ALL -> Keys.ACTION_SELECT_ALL
        QuickAction.CUT -> Keys.ACTION_CUT
        QuickAction.SELECT_WORD -> Keys.ACTION_SELECT_WORD
        QuickAction.DELETE_WORD -> Keys.ACTION_DELETE_WORD
        QuickAction.CURSOR_START -> Keys.ACTION_CURSOR_START
        QuickAction.CURSOR_END -> Keys.ACTION_CURSOR_END
        QuickAction.NEWLINE -> Keys.ACTION_NEWLINE
        QuickAction.SWITCH_LAYOUT -> Keys.ACTION_SWITCH_LAYOUT
        QuickAction.SETTINGS -> Keys.ACTION_SETTINGS
        QuickAction.UNDO -> Keys.ACTION_UNDO
        QuickAction.COMPOSE -> Keys.ACTION_COMPOSE
        QuickAction.REDO -> Keys.ACTION_REDO
        QuickAction.CAPITAL -> Keys.ACTION_CAPITAL
        QuickAction.NORMALISE -> Keys.ACTION_NORMALISE
        QuickAction.CURSOR_LEFT -> Keys.ACTION_CURSOR_LEFT
        QuickAction.CURSOR_RIGHT -> Keys.ACTION_CURSOR_RIGHT
        QuickAction.TIMESTAMP -> Keys.ACTION_TIMESTAMP
        QuickAction.PRIVATE_COPY -> Keys.ACTION_PRIVATE_COPY
        QuickAction.PICK_KEYBOARD -> Keys.ACTION_PICK_KEYBOARD
        QuickAction.VOICE_INPUT -> Keys.ACTION_VOICE_INPUT
    }

    /** Whether labels are drawn: on, on a horizontal bar. */
    private fun labelsActive(): Boolean = showLabels && !vertical

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
        QuickAction.COMPOSE -> R.drawable.bk_action_compose
        QuickAction.REDO -> R.drawable.bk_action_redo
        QuickAction.CAPITAL -> R.drawable.bk_action_capital
        QuickAction.NORMALISE -> R.drawable.bk_action_normalise
        QuickAction.CURSOR_LEFT -> R.drawable.bk_action_cursor_left
        QuickAction.CURSOR_RIGHT -> R.drawable.bk_action_cursor_right
        QuickAction.TIMESTAMP -> R.drawable.bk_action_timestamp
        QuickAction.PRIVATE_COPY -> R.drawable.bk_action_copy_private
        QuickAction.PICK_KEYBOARD -> R.drawable.bk_action_pick_keyboard
        QuickAction.VOICE_INPUT -> R.drawable.bk_action_voice_input
    }

    override fun getAccessibilityClassName(): CharSequence = QuickActionsView::class.java.name

    /** Whether this view paints the surface behind itself; off under [KeyboardHostView]. */
    var drawsBackground: Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measuredWidthHint = MeasureSpec.getSize(widthMeasureSpec)
        val thickness = barThicknessPx()
        if (vertical) {
            setMeasuredDimension(thickness, MeasureSpec.getSize(heightMeasureSpec))
        } else {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thickness)
        }
    }

    private fun barThicknessPx(): Int {
        // A margin, the icon, with labels a gap and the tallest label, then the margin again.
        val icon = barIconPx()
        val margin = icon * EDGE_MARGIN_FRACTION
        val h = if (labelsActive()) {
            margin + icon + icon * ICON_LABEL_GAP_FRACTION + labelBandPx(measuredWidthHint) + margin
        } else {
            icon + 2f * margin
        }
        return h.toInt().coerceAtLeast(1)
    }

    /** The nominal thickness of the size level, before margins; the icon is a share of it. */
    private fun barBasePx(): Float {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_THICKNESS_PX
        return row * BAR_HEIGHT_FRACTION * SIZE_THICKNESS_FRACTION[sizeLevel]
    }

    /** The icon's size, from the level. */
    private fun barIconPx(): Float = barBasePx() * ICON_FRACTION

    /**
     * The tallest label's height, with each label wrapped to its slot of [widthPx] in at most
     * [LABEL_MAX_LINES] lines; builds [labelLayouts] and [labelLayoutWidth].
     */
    private fun labelBandPx(widthPx: Int): Float {
        val shown = shownCount()
        // Bold, sized from the nominal thickness.
        labelPaint.textSize = barBasePx() * LABEL_TEXT_FRACTION
        labelPaint.typeface = Typeface.create(paints.labelSecondary.typeface, Typeface.BOLD)
        val oneLine = labelPaint.fontMetrics.let { it.descent - it.ascent }
        var tallest = oneLine
        if (shown > 0 && widthPx > 0) {
            val step = widthPx.toFloat() / shown
            labelLayoutWidth = (step * LABEL_WIDTH_FRACTION).toInt().coerceAtLeast(1)
            for (index in 0 until shown) {
                labelLayouts[index] = labels[index]?.let { text ->
                    StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, labelLayoutWidth)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setMaxLines(LABEL_MAX_LINES)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .setLineSpacing(0f, LABEL_LINE_SPACING_MULTIPLIER)
                        .build()
                }
                tallest = max(tallest, labelLayouts[index]?.height?.toFloat() ?: oneLine)
            }
        }
        return tallest
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutButtons()
    }

    /** Spreads the buttons evenly along the bar; the icon size comes from the size level. */
    private fun layoutButtons() {
        val shown = shownCount()
        if (shown <= 0 || width == 0 || height == 0) {
            return
        }
        val along = if (vertical) height else width
        val step = along.toFloat() / shown
        slotPx = step
        // The icon sits a margin from the free edge; the labels are re-wrapped for the real width.
        val icon = barIconPx()
        buttonSizePx = icon.toInt().coerceAtLeast(1)
        val margin = icon * EDGE_MARGIN_FRACTION
        val gap = icon * ICON_LABEL_GAP_FRACTION
        val iconCentre = margin + icon / 2f
        if (labelsActive()) {
            labelBandPx(width)
        }
        labelBandBottomY = 0f
        for (index in 0 until shown) {
            val centre = step * index + step / 2f
            if (vertical) {
                centreX[index] = width / 2f
                centreY[index] = centre
            } else {
                centreX[index] = centre
                centreY[index] = iconCentre
            }
        }
        if (labelsActive()) {
            labelTopY = iconCentre + buttonSizePx / 2f + gap
            for (index in 0 until shown) {
                val layoutBottom = labelTopY + (labelLayouts[index]?.height ?: 0)
                if (layoutBottom > labelBandBottomY) {
                    labelBandBottomY = layoutBottom
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("QuickActionsView.onDraw")
        try {
            if (drawsBackground) {
                paints.backgroundPainter.draw(canvas, width.toFloat(), height.toFloat())
            }
            val shown = shownCount()
            val half = buttonSizePx / 2
            for (index in 0 until shown) {
                val cx = centreX[index].toInt()
                val cy = centreY[index].toInt()
                if (index == pressedIndex) {
                    pressedBounds(index, pressedBoundsScratch)
                    canvas.drawPath(tabPath(pressedBoundsScratch), paints.keyPressedFill)
                }
                // With key outlines on, each button's tab is outlined.
                if (paints.showKeyBorders) {
                    pressedBounds(index, pressedBoundsScratch)
                    canvas.drawPath(tabPath(pressedBoundsScratch), paints.keyStroke)
                }
                val collapsedOpener = collapsible && !expanded
                val icon = if (collapsedOpener) moreIcon else icons[index]
                if (icon != null) {
                    icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                    icon.setTint(paints.label.color)
                    icon.draw(canvas)
                }
                // A dot marks a custom action, except on the collapsed opener.
                if (!collapsedOpener && items.getOrNull(index) is QuickActionBarItem.Custom) {
                    canvas.drawCircle(
                        (cx + half).toFloat(), (cy - half).toFloat(),
                        CUSTOM_DOT_RADIUS_FRACTION * buttonSizePx, paints.accent,
                    )
                }
                // No label under the collapsed opener.
                if (labelsActive() && !collapsedOpener) {
                    val labelLayout = labelLayouts[index]
                    if (labelLayout != null) {
                        labelPaint.color = paints.labelSecondary.color
                        canvas.save()
                        canvas.translate(centreX[index] - labelLayoutWidth / 2f, labelTopY)
                        labelLayout.draw(canvas)
                        canvas.restore()
                    }
                }
            }
            particles.draw(canvas, paints.particlePaint)
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
        // A disabled bar takes no touches.
        if (!isEnabled) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = buttonAt(event.x, event.y)
                if (pressedIndex >= 0) {
                    pressButton(pressedIndex)
                }
                invalidate()
                return pressedIndex >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                val index = buttonAt(event.x, event.y)
                if (index != pressedIndex) {
                    pressedIndex = index
                    // The highlight follows the finger onto another button; so do the particles.
                    if (index >= 0) pressButton(index) else particles.release()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = buttonAt(event.x, event.y)
                pressedIndex = -1
                particles.release()
                if (index < 0) {
                    invalidate()
                    return true
                }
                if (collapsible && !expanded) {
                    // The opener only opens the bar.
                    expanded = true
                    layoutButtons()
                    requestLayout()
                    invalidate()
                    return true
                }
                val item = items.getOrNull(index)
                if (collapsible) {
                    // A collapsible bar closes once an action is chosen.
                    expanded = false
                    requestLayout()
                }
                invalidate()
                if (item != null) {
                    listener?.onQuickAction(item)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                particles.release()
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
            layoutButtons()
            requestLayout()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        particles.cancel()
    }

    companion object {
        /** The most buttons; the preferences clamp to the same number. */
        const val MAX_BUTTONS = 10

        /** The most fill and outline particles alive at once. */
        const val FILL_PARTICLE_POOL_CAPACITY = 24
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 32

        /** The bar's base thickness, as a fraction of a key row. */
        const val BAR_HEIGHT_FRACTION = 0.82f

        /** [barBasePx]'s multiplier at each [sizeLevel]. */
        val SIZE_THICKNESS_FRACTION = floatArrayOf(1.00f, 1.25f, 1.55f, 1.90f)

        /** How much of the nominal thickness an icon takes. */
        const val ICON_FRACTION = 0.52f

        /** The label's text size, as a share of the nominal thickness. */
        const val LABEL_TEXT_FRACTION = 0.19f

        /** How much of a slot's width a label may take before it wraps. */
        const val LABEL_WIDTH_FRACTION = 0.84f

        /** How much of a slot a button's tab takes along the bar. */
        const val TAB_ALONG_FRACTION = 0.94f

        /** The gap between the tab and the bar's free edge, as a share of the icon's size. */
        const val TAB_FREE_GAP_FRACTION = 0.14f

        /** Which edge of the bar meets the keyboard; see [attachedEdge]. */
        const val EDGE_TOP = 0
        const val EDGE_BOTTOM = 1
        const val EDGE_LEFT = 2
        const val EDGE_RIGHT = 3

        /** The most lines a label wraps to before it is ellipsised. */
        const val LABEL_MAX_LINES = 2

        /** The label's line spacing multiplier. */
        const val LABEL_LINE_SPACING_MULTIPLIER = 0.9f

        /** The gap between the icon and its label, as a share of the icon's size. */
        const val ICON_LABEL_GAP_FRACTION = 0.12f

        /** The margin around the bar's content, as a share of the icon's size. */
        const val EDGE_MARGIN_FRACTION = 0.28f

        /** The custom-action dot's radius, as a fraction of the icon's size. */
        const val CUSTOM_DOT_RADIUS_FRACTION = 0.14f

        const val DEFAULT_THICKNESS_PX = 132f
    }
}
