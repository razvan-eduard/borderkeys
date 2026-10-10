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
import android.view.ViewConfiguration
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
            refreshAllowed()
        }

    /**
     * Whether each item may run in the field now; one that may not is drawn dimmed and takes no
     * tap. Set from the field's policy; [refreshAllowed] re-reads it.
     */
    var allowed: (QuickActionBarItem) -> Boolean = { true }
        set(value) {
            field = value
            refreshAllowed()
        }

    /** Whether each shown button is allowed, by index. */
    private var allowedNow = BooleanArray(0)

    /** Re-reads [allowed] for every item and redraws. */
    fun refreshAllowed() {
        allowedNow = BooleanArray(items.size) { allowed(items[it]) }
        invalidate()
    }

    /** Whether the button at [index] may run now; the collapsed opener always may. */
    private fun allowedAt(index: Int): Boolean =
        (collapsible && !expanded) || allowedNow.getOrElse(index) { true }

    /** Whether the Enabled/Disabled button reads Disabled; its icon and label follow at once. */
    var featuresOff: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resolveIcons()
                resolveLabels()
                layoutButtons()
                invalidate()
            }
        }

    /** Whether a label too long for its slot scrolls past rather than being cut. */
    var labelsScroll: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resolveLabels()
                layoutButtons()
                invalidate()
            }
        }

    /** How fast the scrolling labels run, 1 the default. */
    var animationSpeed: Float = 1f

    /** Whether animations play at all: an [com.borderkeys.data.theme.EffectsSettings] mode. */
    var animationMode: Int = com.borderkeys.data.theme.EffectsSettings.MODE_SYSTEM
        set(value) {
            if (field != value) {
                field = value
                resolveLabels()
                layoutButtons()
                invalidate()
            }
        }

    /** How much of each label hangs past its slot, in pixels; 0 for one that fits or is cut. */
    private val labelOverflow = FloatArray(MAX_BUTTONS)

    /** When the labels' scroll cycle began, so they all move together. */
    private var marqueeStartedAt = 0L

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

    /** How far the buttons are scrolled along the bar, when they do not all fit. */
    private var scrollPx = 0f

    /** The buttons' total length along the bar. */
    private var contentPx = 0f

    private val scroller = android.widget.OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private var downAlong = 0f
    private var downScroll = 0f

    /** Whether the finger is scrolling the bar rather than pressing a button. */
    private var scrolling = false

    /** The furthest the bar scrolls: the content past its length, or nothing. */
    private fun maxScroll(): Float = max(0f, contentPx - (if (vertical) height else width))

    /**
     * A button's slot along a bar of [along] with [shown] buttons: an even share while they all
     * fit, else a fixed width from the Size setting, through the icon, the bar scrolling past its
     * end.
     */
    private fun slotFor(shown: Int, along: Float): Float {
        val fixed = barIconPx() * FIXED_SLOT_ICONS
        return if (shown * fixed <= along) along / shown else fixed
    }

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

    /** Button [index]'s centre on the view, the scroll applied. */
    internal fun buttonCentre(index: Int): android.graphics.PointF =
        if (vertical) {
            android.graphics.PointF(centreX[index], centreY[index] - scrollPx)
        } else {
            android.graphics.PointF(centreX[index] - scrollPx, centreY[index])
        }
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
    /** The keys' corner radius, no more than half of [rect]'s shorter side. */
    private fun tabCornerRadius(rect: android.graphics.RectF): Float =
        paints.keyCornerRadiusPx.coerceAtMost(minOf(rect.width(), rect.height()) / 2f)

    private fun tabPath(rect: android.graphics.RectF): android.graphics.Path {
        val radius = tabCornerRadius(rect)
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

    /** Whether the letters on the keys read right to left; an icon drawn to mirror then does. */
    var rightToLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                resolveIcons()
                invalidate()
            }
        }

    private fun resolveIcons() {
        val direction = if (rightToLeft) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
        for (index in icons.indices) {
            icons[index] = if (index < items.size) {
                ContextCompat.getDrawable(context, iconFor(items[index]))?.also {
                    androidx.core.graphics.drawable.DrawableCompat.setLayoutDirection(it, direction)
                }
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
        is QuickActionBarItem.Builtin -> when {
            item.action != QuickAction.FEATURES_SWITCH -> strings[labelKey(item.action)]
            featuresOff -> strings[Keys.ACTION_DISABLED]
            else -> strings[Keys.ACTION_ENABLED]
        }
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
        QuickAction.WORD_LEFT -> Keys.ACTION_WORD_LEFT
        QuickAction.WORD_RIGHT -> Keys.ACTION_WORD_RIGHT
        QuickAction.SELECT_WORD_LEFT -> Keys.ACTION_SELECT_WORD_LEFT
        QuickAction.SELECT_WORD_RIGHT -> Keys.ACTION_SELECT_WORD_RIGHT
        QuickAction.SELECT_TO_LINE_START -> Keys.ACTION_SELECT_TO_LINE_START
        QuickAction.SELECT_TO_LINE_END -> Keys.ACTION_SELECT_TO_LINE_END
        QuickAction.DELETE_WORD_FORWARD -> Keys.ACTION_DELETE_WORD_FORWARD
        QuickAction.ESCAPE -> Keys.ACTION_ESCAPE
        QuickAction.TAB -> Keys.ACTION_TAB
        QuickAction.FEATURES_SWITCH -> Keys.ACTION_FEATURES_SWITCH
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
        QuickAction.WORD_LEFT -> R.drawable.bk_action_word_left
        QuickAction.WORD_RIGHT -> R.drawable.bk_action_word_right
        QuickAction.SELECT_WORD_LEFT -> R.drawable.bk_action_select_word_left
        QuickAction.SELECT_WORD_RIGHT -> R.drawable.bk_action_select_word_right
        QuickAction.SELECT_TO_LINE_START -> R.drawable.bk_action_select_to_line_start
        QuickAction.SELECT_TO_LINE_END -> R.drawable.bk_action_select_to_line_end
        QuickAction.DELETE_WORD_FORWARD -> R.drawable.bk_action_delete_word_forward
        QuickAction.ESCAPE -> R.drawable.bk_action_escape
        QuickAction.TAB -> R.drawable.bk_action_tab
        QuickAction.FEATURES_SWITCH ->
            if (featuresOff) R.drawable.bk_action_features_off else R.drawable.bk_action_features_on
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
     * The label line's height, each label on one line at its nominal size in its slot of a bar
     * [widthPx] long; a label longer than the slot is cut with an ellipsis. Builds [labelLayouts]
     * and [labelLayoutWidth].
     */
    private fun labelBandPx(widthPx: Int): Float {
        val shown = shownCount()
        // Bold, sized from the nominal thickness.
        labelPaint.textSize = barBasePx() * LABEL_TEXT_FRACTION
        labelPaint.typeface = Typeface.create(paints.labelSecondary.typeface, Typeface.BOLD)
        if (shown > 0 && widthPx > 0) {
            val step = slotFor(shown, widthPx.toFloat())
            labelLayoutWidth = (step * LABEL_WIDTH_FRACTION).toInt().coerceAtLeast(1)
            val scrolls = labelsScroll && AnimationGate.plays(animationMode)
            var anyScrolls = false
            for (index in 0 until shown) {
                labelOverflow[index] = 0f
                labelLayouts[index] = labels[index]?.let { text ->
                    val textWidth = labelPaint.measureText(text)
                    if (scrolls && textWidth > labelLayoutWidth) {
                        // Laid out whole, to be scrolled past its slot.
                        labelOverflow[index] = textWidth - labelLayoutWidth
                        anyScrolls = true
                        StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, kotlin.math.ceil(textWidth).toInt())
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setMaxLines(1)
                            .setIncludePad(false)
                            .build()
                    } else {
                        StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, labelLayoutWidth)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setMaxLines(1)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .setIncludePad(false)
                            .build()
                    }
                }
            }
            if (anyScrolls) {
                marqueeStartedAt = android.os.SystemClock.uptimeMillis()
            }
        }
        return labelPaint.fontMetrics.let { it.descent - it.ascent }
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
        val step = slotFor(shown, along.toFloat())
        slotPx = step
        contentPx = step * shown
        scrollPx = scrollPx.coerceIn(0f, maxScroll())
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
            // The buttons are laid out along the content and drawn shifted by the scroll.
            canvas.save()
            if (vertical) canvas.translate(0f, -scrollPx) else canvas.translate(-scrollPx, 0f)
            val shown = shownCount()
            val half = buttonSizePx / 2
            var anyLabelScrolling = false
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
                val dim = if (allowedAt(index)) 255 else DISALLOWED_ALPHA
                if (icon != null) {
                    icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                    icon.setTint(paints.label.color)
                    icon.alpha = dim
                    icon.draw(canvas)
                }
                // A dot marks a custom action, except on the collapsed opener: at the centre of
                // the tab's rounded corner, the same distance from both edges on the diagonal.
                if (!collapsedOpener && items.getOrNull(index) is QuickActionBarItem.Custom) {
                    pressedBounds(index, pressedBoundsScratch)
                    val corner = tabCornerRadius(pressedBoundsScratch)
                    val dotX = if (attachedEdge == EDGE_RIGHT) pressedBoundsScratch.left + corner else pressedBoundsScratch.right - corner
                    val dotY = if (attachedEdge == EDGE_TOP) pressedBoundsScratch.bottom - corner else pressedBoundsScratch.top + corner
                    canvas.drawCircle(dotX, dotY, CUSTOM_DOT_RADIUS_FRACTION * buttonSizePx, paints.accent)
                }
                // No label under the collapsed opener.
                if (labelsActive() && !collapsedOpener) {
                    val labelLayout = labelLayouts[index]
                    if (labelLayout != null) {
                        labelPaint.color = paints.labelSecondary.color
                        labelPaint.alpha = android.graphics.Color.alpha(paints.labelSecondary.color) * dim / 255
                        val left = centreX[index] - labelLayoutWidth / 2f
                        canvas.save()
                        val overflow = labelOverflow[index]
                        if (overflow > 0f) {
                            // Scrolled past its slot: held, slid, held, and over again.
                            val offset = LabelMarquee.offsetPx(
                                android.os.SystemClock.uptimeMillis() - marqueeStartedAt, overflow,
                                resources.displayMetrics.density * LabelMarquee.PACE_DP_PER_SECOND, animationSpeed,
                            )
                            canvas.clipRect(left, labelTopY, left + labelLayoutWidth, labelTopY + labelLayout.height)
                            canvas.translate(left - offset, labelTopY)
                            anyLabelScrolling = true
                        } else {
                            canvas.translate(left, labelTopY)
                        }
                        labelLayout.draw(canvas)
                        canvas.restore()
                    }
                }
            }
            particles.draw(canvas, paints.particlePaint)
            canvas.restore()
            if (anyLabelScrolling) {
                postInvalidateOnAnimation()
            }
        } finally {
            Trace.endSection()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollPx = scroller.currX.toFloat().coerceIn(0f, maxScroll())
            postInvalidateOnAnimation()
        }
    }

    private fun buttonAt(x: Float, y: Float): Int {
        val shown = shownCount()
        if (shown <= 0) {
            return -1
        }
        val along = (if (vertical) y else x) + scrollPx
        if (slotPx <= 0f || along < 0f) {
            return -1
        }
        val index = (along / slotPx).toInt()
        return if (index in 0 until shown) index else -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A disabled bar takes no touches.
        if (!isEnabled) {
            return false
        }
        val along = if (vertical) event.y else event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A finger stops a fling where it is.
                scroller.forceFinished(true)
                scrolling = false
                downAlong = along
                downScroll = scrollPx
                velocity?.recycle()
                velocity = android.view.VelocityTracker.obtain().also { it.addMovement(event) }
                pressedIndex = buttonAt(event.x, event.y).takeIf { it < 0 || allowedAt(it) } ?: -1
                if (pressedIndex >= 0) {
                    pressButton(pressedIndex)
                }
                invalidate()
                return pressedIndex >= 0 || maxScroll() > 0f
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (maxScroll() > 0f) {
                    if (!scrolling && kotlin.math.abs(along - downAlong) > ViewConfiguration.get(context).scaledTouchSlop) {
                        // Past the slop the finger is scrolling the bar, not pressing a button.
                        scrolling = true
                        pressedIndex = -1
                        particles.release()
                    }
                    if (scrolling) {
                        scrollPx = (downScroll - (along - downAlong)).coerceIn(0f, maxScroll())
                        invalidate()
                        return true
                    }
                }
                val index = buttonAt(event.x, event.y).takeIf { it < 0 || allowedAt(it) } ?: -1
                if (index != pressedIndex) {
                    pressedIndex = index
                    // The highlight follows the finger onto another button; so do the particles.
                    if (index >= 0) pressButton(index) else particles.release()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (scrolling) {
                    // The bar coasts on with the finger's speed and stops at its ends.
                    scrolling = false
                    velocity?.let { tracker ->
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        val speed = if (vertical) tracker.yVelocity else tracker.xVelocity
                        scroller.fling(scrollPx.toInt(), 0, (-speed).toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        tracker.recycle()
                    }
                    velocity = null
                    postInvalidateOnAnimation()
                    return true
                }
                velocity?.recycle()
                velocity = null
                val index = buttonAt(event.x, event.y).takeIf { it < 0 || allowedAt(it) } ?: -1
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
                scrolling = false
                velocity?.recycle()
                velocity = null
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

        /** A button's fixed slot, in icons, once the buttons no longer fit and the bar scrolls. */
        const val FIXED_SLOT_ICONS = 2.2f

        /** The icon and label alpha of a button the field does not allow. */
        const val DISALLOWED_ALPHA = 80

        /** The gap between the icon and its label, as a share of the icon's size. */
        const val ICON_LABEL_GAP_FRACTION = 0.12f

        /** The margin around the bar's content, as a share of the icon's size. */
        const val EDGE_MARGIN_FRACTION = 0.28f

        /** The custom-action dot's radius, as a fraction of the icon's size. */
        const val CUSTOM_DOT_RADIUS_FRACTION = 0.14f

        const val DEFAULT_THICKNESS_PX = 132f
    }
}
