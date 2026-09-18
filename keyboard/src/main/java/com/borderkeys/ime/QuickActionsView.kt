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

    /**
     * Whether each button also says what it is, in small text under its icon -- the same idea
     * as the labels under the draft box's own action bar. The label band is extra thickness on
     * top of [sizeLevel]'s, so the icons stay exactly the size the level chose. A vertical bar
     * has no room under an icon at all and ignores this entirely.
     */
    var showLabels: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Thickness changes with the label band -- the same first-frame reasoning as
                // sizeLevel's setter, which this follows.
                layoutButtons()
                requestLayout()
                invalidate()
            }
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
                // requestLayout() alone does not do this: the bar's own width and height do not
                // change when the button count behind them does, so the framework never calls
                // onSizeChanged again and layoutButtons() -- the only other place button centres
                // are computed -- would otherwise not run again until a button was pressed. Set
                // from applyQuickActions on every keyboard show, after [items] on the same
                // call, so this was also the one write the bar's very first frame depended on.
                layoutButtons()
                requestLayout()
                invalidate()
            }
        }

    /** True while a collapsible bar is open. Always true when the bar is not collapsible. */
    private var expanded = false

    /**
     * How much room each button gets, as one of [SIZE_THICKNESS_FRACTION]'s indices.
     *
     * Thickness grows at each step and the icon's *share* of it stays the same -- [ICON_FRACTION]
     * does not vary by level -- so the icon grows right along with the bar, not against it: an
     * icon and the gap around it both come from the same multiple of the same thickness, which is
     * what keeps them direct proportional to each other rather than trading one for the other.
     */
    var sizeLevel: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, SIZE_THICKNESS_FRACTION.lastIndex)
            if (field != clamped) {
                field = clamped
                // Same reasoning as collapsible's setter: thickness does change here, so
                // requestLayout() would eventually reach onSizeChanged on its own -- but not
                // before a frame draws with the old positions at the new thickness, which is
                // its own visible glitch for the one frame it lasts.
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

    /** [labels], wrapped to what a slot actually fits -- up to two tight lines, ellipsised only
     *  if even that overflows -- recomputed with the geometry on layout, never on a draw. */
    private val labelLayouts = arrayOfNulls<StaticLayout>(MAX_BUTTONS)

    // Left, not centre: StaticLayout does its own per-line centring via ALIGN_CENTER below, and
    // a paint that also centred would offset every line a second time on top of that.
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var labelLayoutWidth = 0
    private var labelTopY = 0f

    /** Where the tallest label on the bar ends -- the near edge of a bottom-attached tab, so
     *  every button reaches the same point whether its own label took one line or two. */
    private var labelBandBottomY = 0f

    /** One slot's width along the bar, the tab's own width. */
    private var slotPx = 0f

    /**
     * Which edge of the bar meets the keyboard: the side every button's tab is flat against,
     * its other three corners rounded, so the buttons read as bookmarks growing out of the
     * keyboard rather than boxes floating in a strip. Set from the bar's placement by the host.
     */
    var attachedEdge: Int = EDGE_BOTTOM
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val tabPath = android.graphics.Path()
    private val tabRadii = FloatArray(8)

    /** The width onMeasure was last given, so the label band can be measured against the slots
     *  the labels will actually wrap in before the view has its final width. */
    private var measuredWidthHint = 0

    /** Button centres, in view coordinates. Recomputed on layout, never per frame. */
    private val centreX = FloatArray(MAX_BUTTONS)
    private val centreY = FloatArray(MAX_BUTTONS)
    private var buttonSizePx = 0

    private var pressedIndex = -1

    /** Both particle layers for the bar -- the same "structurally a key" treatment
     *  [KeyboardCanvasView.particles] gives the keys beside it. Exposed non-private so
     *  [BorderKeysService] can push the user's particle-effect settings directly. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidate() }

    /** The pressed button, as the element the engine reads its shape from: exactly the pressed
     *  highlight square [onDraw] paints under it -- see [pressedBounds]. */
    private val buttonElement = RoundedRectElement()

    /**
     * The one definition of a button's surface -- what a press lights, what the outline traces,
     * and what [pressButton] hands particles: a tab. Most of the slot along the bar, flat
     * against [attachedEdge] where it meets the keyboard, and a quarter of an icon past the
     * icon (or the label, with labels on) on the free side. Its width does not change with
     * labels -- only its height does, to take the label in.
     */
    private fun pressedBounds(index: Int, out: android.graphics.RectF) {
        val half = buttonSizePx / 2f
        val lift = buttonSizePx * TAB_LIFT_FRACTION
        val alongHalf = slotPx * TAB_ALONG_FRACTION / 2f
        val cx = centreX[index]
        val cy = centreY[index]
        when (attachedEdge) {
            EDGE_TOP -> {
                val far = (if (labelsActive()) labelBandBottomY else cy + half) + lift
                out.set(cx - alongHalf, 0f, cx + alongHalf, far.coerceAtMost(height.toFloat()))
            }
            EDGE_LEFT -> out.set(0f, cy - alongHalf, (cx + half + lift).coerceAtMost(width.toFloat()), cy + alongHalf)
            EDGE_RIGHT -> out.set((cx - half - lift).coerceAtLeast(0f), cy - alongHalf, width.toFloat(), cy + alongHalf)
            else -> out.set(cx - alongHalf, (cy - half - lift).coerceAtLeast(0f), cx + alongHalf, height.toFloat())
        }
    }

    /**
     * [rect] as a path with the keys' corner radius on the free corners and none on the two
     * against [attachedEdge], so the tab is flat where it meets the keyboard. The radii array
     * runs top-left, top-right, bottom-right, bottom-left, two floats each.
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

    /** The same catalogue entry every other surface calls this action by. */
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
    }

    /** Labels need a band under the icons; a vertical bar has nowhere to put one. */
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
    }

    /**
     * Whether to paint the surface, or leave it to whatever is behind.
     *
     * False inside the keyboard, where the host paints one surface across the whole of it: this
     * row painting its own would restart a gradient or re-crop a picture at its own edges, and
     * the seam would run across the top of the keyboard.
     */
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
        val base = barBasePx()
        // The label band is appended below the level's own icon band, not carved out of it: the
        // icons stay the size and the centre the level alone gives them, and the bar grows by
        // exactly what the tallest label needs -- so a bar with no labels is shorter.
        val withLabels = if (labelsActive()) base + labelBandPx(base, measuredWidthHint) else base
        return withLabels.toInt().coerceAtLeast(1)
    }

    /** The icon band: the thickness the size level gives a bar with no labels. */
    private fun barBasePx(): Float {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_THICKNESS_PX
        return row * BAR_HEIGHT_FRACTION * SIZE_THICKNESS_FRACTION[sizeLevel]
    }

    /**
     * The band a labelled bar adds below the icons: a gap, the tallest label once every label
     * is wrapped to the slot the bar's [widthPx] gives it, and the same gap again. Leaves
     * [labelLayouts] and [labelLayoutWidth] built for [layoutButtons] to draw from -- wrapped
     * once per geometry, never on a draw. Two lines before an ellipsis: a slot too narrow even
     * for "Copy line" is rarer than one word cut to three dots.
     */
    private fun labelBandPx(base: Float, widthPx: Int): Float {
        val shown = shownCount()
        // Off the icon band, not the label's own: a text size tied to the band it sits in would
        // shrink whenever that band is tightened, unrelated to legibility. Bold, because a
        // caption this small reads better with more ink and is the one text competing with an
        // icon rather than sitting on its own.
        labelPaint.textSize = base * LABEL_TEXT_FRACTION
        labelPaint.typeface = Typeface.create(paints.labelSecondary.typeface, Typeface.BOLD)
        val gapPx = base * ICON_LABEL_GAP_FRACTION
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
        return gapPx + tallest + gapPx
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
        val along = if (vertical) height else width
        val step = along.toFloat() / shown
        slotPx = step
        // The icon band is the size level's own thickness; whatever the bar has beyond it is
        // the label band, appended below. So the icon sits exactly where a label-less bar
        // would put it -- centred in its band -- and only the bar grows for the labels.
        val labelBand = if (labelsActive()) labelBandPx(barBasePx(), width) else 0f
        val iconBand = (thickness - labelBand).coerceAtLeast(1f)
        buttonSizePx = (iconBand * ICON_FRACTION).toInt().coerceAtLeast(1)
        val iconCentre = iconBand / 2f
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
            // One gap below the icon; the layouts were wrapped by labelBandPx to this width.
            labelTopY = iconCentre + buttonSizePx / 2f + barBasePx() * ICON_LABEL_GAP_FRACTION
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
                // Each button traced as a tab, flat against the keyboard, with the keys' own
                // corner radius and hairline -- gated by the theme's own "outline the keys".
                if (paints.showKeyBorders) {
                    pressedBounds(index, pressedBoundsScratch)
                    canvas.drawPath(tabPath(pressedBoundsScratch), paints.keyStroke)
                }
                val collapsedOpener = collapsible && !expanded
                val icon = if (collapsedOpener) moreIcon else icons[index]
                if (icon != null) {
                    icon.setBounds(cx - half, cy - half, cx + half, cy + half)
                    // Tinted to the label colour so the bar belongs to the theme rather than to
                    // whatever colour the drawable was authored in.
                    icon.setTint(paints.label.color)
                    icon.draw(canvas)
                }
                // A dot rather than a second icon: the bar has no room to also spell out "this
                // one is yours", and a mark in the corner answers the only question a glance
                // needs to -- the same reasoning ClipboardPanelView's own pin dot is drawn on.
                // Never on the collapsed opener button, which draws [moreIcon] regardless of
                // what items[index] itself is.
                if (!collapsedOpener && items.getOrNull(index) is QuickActionBarItem.Custom) {
                    canvas.drawCircle(
                        (cx + half).toFloat(), (cy - half).toFloat(),
                        CUSTOM_DOT_RADIUS_FRACTION * buttonSizePx, paints.accent,
                    )
                }
                // Never under the collapsed opener: it draws [moreIcon], not items[0], and a
                // label naming a button it is not would be worse than none. Colour follows
                // labelSecondary per draw, the same way the icons above take label's tint.
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
        // Overriding onTouchEvent skips the base View's own isEnabled check, so nothing else
        // was stopping a tap here from flipping expanded and showing a press -- harmless on its
        // own, since a settings preview wires no listener, but not what "read-only" means for a
        // control sitting beside a keyboard a preview otherwise sets isEnabled = false on too.
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
                    // The opener. Nothing happens beyond opening: a button that both opens the
                    // bar and fires its first action would fire it every time it is opened.
                    expanded = true
                    layoutButtons()
                    requestLayout()
                    invalidate()
                    return true
                }
                val item = items.getOrNull(index)
                if (collapsible) {
                    // Closes as soon as one is chosen, which is what "collapsed" was asked for.
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
        /** The format's own cap; the preferences clamp to the same number. */
        const val MAX_BUTTONS = 10

        /** [MAX_BUTTONS] could each in principle be pressed in quick succession -- sized for one
         *  preset's own burst count (10) plus a little headroom, not for all ten buttons' bursts
         *  landing in the same frame. */
        const val FILL_PARTICLE_POOL_CAPACITY = 24
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 32

        /** The bar is a little shorter than a key row: it is a tool strip, not another row. */
        const val BAR_HEIGHT_FRACTION = 0.82f

        /**
         * [barThicknessPx]'s multiplier at each [sizeLevel], indexed by
         * [KeyboardPreferences.QUICK_ACTIONS_SIZE_DEFAULT] and up. 1 is [BAR_HEIGHT_FRACTION]
         * untouched -- today's bar, unchanged by a setting nobody has picked yet.
         *
         * [ICON_FRACTION] does not have a matching per-level table: it stays one constant share
         * of thickness at every level, which is what makes the icon and the gap around it both
         * grow by exactly this same multiple -- direct proportional to each other, and to the
         * level chosen, rather than one growing at the other's expense on a thickness that held
         * still.
         */
        val SIZE_THICKNESS_FRACTION = floatArrayOf(1.00f, 1.25f, 1.55f, 1.90f)

        /** How much of the bar's thickness an icon takes, leaving a touch margin around it. */
        const val ICON_FRACTION = 0.52f

        /** The label's text size, as a share of the icon band -- the size level's own
         *  thickness, not the label's own band, so the text does not shrink as the band does. */
        const val LABEL_TEXT_FRACTION = 0.19f

        /** How much of a slot's width a label may take before it wraps, and then ellipsises.
         *  Inside [TAB_ALONG_FRACTION] with room to spare, so a full label sits clear of the
         *  tab's own outline. */
        const val LABEL_WIDTH_FRACTION = 0.84f

        /** How much of a slot a button's tab takes along the bar -- what a press lights and
         *  the outline traces -- leaving a gap between neighbours like the keys' own. */
        const val TAB_ALONG_FRACTION = 0.94f

        /** How far past the icon (or the label) the tab reaches on its free side, the corner
         *  that is rounded, as a share of the icon's size. */
        const val TAB_LIFT_FRACTION = 0.25f

        /** Which edge of the bar meets the keyboard -- see [attachedEdge]. */
        const val EDGE_TOP = 0
        const val EDGE_BOTTOM = 1
        const val EDGE_LEFT = 2
        const val EDGE_RIGHT = 3

        /** A label wraps to a second line before it is ellipsised -- a short label cut to
         *  "Copy…" says less than the same word on two lines would. Never a third: past two
         *  lines a label is competing with the icon above it for the same glance. */
        const val LABEL_MAX_LINES = 2

        /** Tighter than a normal line, on purpose -- two lines of a caption, not a paragraph. */
        const val LABEL_LINE_SPACING_MULTIPLIER = 0.9f

        /** The gap between the icon and its label, as a fraction of the bar's whole thickness --
         *  see [layoutButtons]'s own comment for why the icon+label group is centred as a unit
         *  rather than either one pinned to an edge. */
        const val ICON_LABEL_GAP_FRACTION = 0.04f

        /** The custom-action dot's radius, as a fraction of the icon's own size -- see
         *  ClipboardPanelView's PIN_RADIUS_FRACTION, the same idea at the same rough scale. */
        const val CUSTOM_DOT_RADIUS_FRACTION = 0.14f

        const val DEFAULT_THICKNESS_PX = 132f
    }
}
