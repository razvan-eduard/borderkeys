// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.borderkeys.data.theme.QuickTile
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.keyboard.R
import com.borderkeys.theme.ThemePaints
import kotlin.math.max
import kotlin.math.min

/**
 * The quick panel over the keys: a grid of tiles, one setting or action each, drawn in the
 * quick actions bar's look, an icon over a label. Held, a tile puts the grid in arrange mode,
 * where tiles drag into a new order and carry a remove badge; the + tile adds one of the tiles
 * not yet on the panel. The panel writes nothing: its taps call back to the service, and it
 * draws the state it is given.
 */
@SuppressLint("ViewConstructor")
class QuickSettingsView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    /** One tile as drawn: which, and whether it is on, or the current position. */
    class TileState(val tile: QuickTile, val on: Boolean)

    interface Listener {
        /** A tile was tapped: an action runs, a switch flips, a position is chosen. */
        fun onTileTapped(tile: QuickTile)

        /** The tiles were arranged, added to or removed from; [tiles] is the new list, in order. */
        fun onTilesArranged(tiles: List<QuickTile>)

        fun onOpenFullSettings()
        fun onCloseQuickSettings()
    }

    var listener: Listener? = null

    private enum class Mode { VIEW, ARRANGE, PICK }

    private var mode = Mode.VIEW

    /** The tiles on the panel, in order, as last given or as arranged since. */
    private var tiles: List<TileState> = emptyList()

    /** The tiles not on the panel, offered by the + tile. */
    private var available: List<QuickTile> = emptyList()

    /** Whether the footer that opens the settings application is shown. */
    private var fullSettings = true

    /** Whether the Close and All settings labels sit at the right, the keyboard's side. */
    private var alignRight = true

    /** Whether a label too long for its tile scrolls past rather than being cut. */
    var labelsScroll: Boolean = false
        set(value) {
            if (field != value) {
                field = value
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
                invalidate()
            }
        }

    /** When the labels' scroll cycle began; reset whenever the tiles change. */
    private var marqueeStartedAt = 0L

    /**
     * The state to draw, pushed from the service whenever the preferences flow emits. While a
     * tile is being dragged the given order is set aside, since it is the one being changed.
     */
    fun setState(tiles: List<TileState>, available: List<QuickTile>, fullSettings: Boolean, alignRight: Boolean) {
        this.tiles = if (dragging >= 0) {
            this.tiles.map { shown -> tiles.firstOrNull { it.tile == shown.tile } ?: shown }
        } else {
            tiles
        }
        this.available = available
        this.fullSettings = fullSettings
        this.alignRight = alignRight
        marqueeStartedAt = android.os.SystemClock.uptimeMillis()
        resolveIcons()
        fitLabels()
        invalidate()
    }

    /** When the panel last opened, for [OPEN_GUARD_MILLIS]. */
    private var openedAt = 0L

    /** Whether the touch under way began inside the guard and is ignored to its end. */
    private var guarded = false

    /** The panel has just opened; touches in the next [OPEN_GUARD_MILLIS] are ignored. */
    fun opened() {
        openedAt = android.os.SystemClock.uptimeMillis()
    }

    /** Leaves arrange or pick mode; called when the panel closes. */
    fun reset() {
        mode = Mode.VIEW
        dragging = -1
        pressed = -1
        removeCallbacks(longPress)
        invalidate()
    }

    // ---- paints ----------------------------------------------------------------------------

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val metrics = Paint.FontMetrics()

    /** Colours are re-read whenever the theme changes; sizes whenever the panel is measured. */
    fun onThemeChanged() {
        titlePaint.color = paints.label.color
        labelPaint.color = paints.label.color
        secondaryPaint.color = paints.labelSecondary.color
        accentPaint.color = paints.accent.color
        outlinePaint.color = paints.accent.color
        dashedPaint.color = paints.labelSecondary.color
        badgePaint.color = paints.accent.color
        badgeMarkPaint.color = paints.background.color
        invalidate()
    }

    // ---- geometry ----------------------------------------------------------------------------

    private var padding = 0f
    private var headerHeight = 0f
    private var footerTop = 0f
    private var gridTop = 0f
    private var columns = COLUMNS
    private var rows = 1
    private var cellWidth = 0f
    private var cellHeight = 0f
    private var gap = 0f
    private var iconPx = 0
    private var nominalLabelPx = 0f
    private var badgeRadius = 0f
    private val cell = RectF()

    /** How many tiles the grid holds. */
    private val capacity: Int get() = columns * rows

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            return
        }
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else h / 5f
        padding = w * 0.04f
        gap = w * 0.02f
        headerHeight = row * 0.8f
        val footerHeight = row * 0.8f
        footerTop = h - footerHeight
        gridTop = headerHeight
        val gridHeight = footerTop - gridTop
        cellWidth = (w - padding * 2f - gap * (columns - 1)) / columns
        // As many rows as fit at the smallest cell, each then as tall as the room allows.
        val smallest = cellWidth * MIN_CELL_ASPECT
        rows = max(1, ((gridHeight + gap) / (smallest + gap)).toInt())
        cellHeight = min(cellWidth * CELL_ASPECT, (gridHeight - gap * (rows - 1)) / rows)
        // The grid sits at the top of its area; what is left over stays above the footer.
        iconPx = (cellHeight * ICON_FRACTION).toInt().coerceAtLeast(1)
        nominalLabelPx = cellHeight * LABEL_FRACTION
        badgeRadius = cellHeight * BADGE_FRACTION

        titlePaint.textSize = headerHeight * 0.42f
        secondaryPaint.textSize = headerHeight * 0.34f
        accentPaint.textSize = headerHeight * 0.36f
        outlinePaint.strokeWidth = row * 0.05f
        dashedPaint.strokeWidth = row * 0.035f
        dashedPaint.pathEffect = DashPathEffect(floatArrayOf(row * 0.12f, row * 0.08f), 0f)
        badgeMarkPaint.strokeWidth = badgeRadius * 0.3f
        onThemeChanged()
        fitLabels()
    }

    /** The cell at [index] of the grid, into [out]. */
    private fun cellAt(index: Int, out: RectF) {
        val column = index % columns
        val row = index / columns
        val left = padding + column * (cellWidth + gap)
        val top = gridTop + row * (cellHeight + gap)
        out.set(left, top, left + cellWidth, top + cellHeight)
    }

    /** How many tiles the grid holds at its current size. */
    internal val tileCapacity: Int get() = capacity

    /** Cell [index]'s centre. */
    internal fun cellCentre(index: Int): android.graphics.PointF {
        cellAt(index, cell)
        return android.graphics.PointF(cell.centerX(), cell.centerY())
    }

    /** The cell under ([x], [y]), or -1. */
    private fun cellUnder(x: Float, y: Float): Int {
        if (y < gridTop || x < padding) {
            return -1
        }
        val column = ((x - padding) / (cellWidth + gap)).toInt()
        val row = ((y - gridTop) / (cellHeight + gap)).toInt()
        if (column !in 0 until columns || row !in 0 until rows) {
            return -1
        }
        return row * columns + column
    }

    // ---- what the grid shows ----------------------------------------------------------------

    /** The tiles in the grid now: the panel's, or in pick mode the ones that can be added. */
    private fun shownTiles(): List<TileState> = when (mode) {
        Mode.PICK -> available.take(capacity).map { TileState(it, false) }
        else -> tiles.take(capacity)
    }

    /** Whether the + tile is shown, after the last tile: when a tile can still be added. */
    private fun plusShown(): Boolean =
        mode != Mode.PICK && available.isNotEmpty() && tiles.size < capacity

    private val icons = HashMap<Int, Drawable?>()

    private fun resolveIcons() {
        icons.clear()
        for (tile in QuickTile.entries) {
            val on = tiles.firstOrNull { it.tile == tile }?.on == true
            icons[tile.id] = ContextCompat.getDrawable(context, iconFor(tile, on))
        }
    }

    private fun iconFor(tile: QuickTile, on: Boolean): Int = when (tile) {
        QuickTile.RESIZE -> R.drawable.bk_tile_resize
        QuickTile.DOCK -> R.drawable.bk_tile_dock
        QuickTile.LEFT -> R.drawable.bk_tile_left
        QuickTile.RIGHT -> R.drawable.bk_tile_right
        QuickTile.FLOAT -> R.drawable.bk_tile_float
        QuickTile.NUMBER_ROW -> R.drawable.bk_tile_number_row
        QuickTile.SUGGESTION_STRIP -> R.drawable.bk_tile_strip
        QuickTile.SWIPE -> R.drawable.bk_tile_swipe
        QuickTile.AUTOCORRECT -> R.drawable.bk_tile_autocorrect
        QuickTile.LEARNING -> R.drawable.bk_tile_learning
        QuickTile.CLIPBOARD_OFFER -> R.drawable.bk_action_clipboard_history
        QuickTile.KEY_SOUND -> R.drawable.bk_tile_sound
        QuickTile.VIBRATION -> R.drawable.bk_tile_vibration
        QuickTile.MODIFIER_ROW -> R.drawable.bk_tile_modifier_row
        QuickTile.EMOJI_KEY -> R.drawable.bk_tile_emoji
        QuickTile.GLOBE_KEY -> R.drawable.bk_action_switch_layout
        QuickTile.KEY_POPUP -> R.drawable.bk_tile_popup
        QuickTile.RADIAL_MENU -> R.drawable.bk_tile_ring
        QuickTile.QUICK_ACTIONS -> R.drawable.bk_tile_bar
        QuickTile.NUMERIC_KEYPAD -> R.drawable.bk_tile_numpad
        QuickTile.FEATURES -> if (on) R.drawable.bk_action_features_on else R.drawable.bk_action_features_off
    }

    private fun labelFor(state: TileState): String = when (state.tile) {
        QuickTile.RESIZE -> strings[Keys.PANEL_RESIZE]
        QuickTile.DOCK -> strings[Keys.PANEL_DOCK]
        QuickTile.LEFT -> strings[Keys.PANEL_LEFT]
        QuickTile.RIGHT -> strings[Keys.PANEL_RIGHT]
        QuickTile.FLOAT -> strings[Keys.PANEL_FLOAT]
        QuickTile.NUMBER_ROW -> strings[Keys.PANEL_NUMBER_ROW]
        QuickTile.SUGGESTION_STRIP -> strings[Keys.PANEL_TILE_STRIP]
        QuickTile.SWIPE -> strings[Keys.PANEL_TILE_SWIPE]
        QuickTile.AUTOCORRECT -> strings[Keys.PANEL_TILE_AUTOCORRECT]
        QuickTile.LEARNING -> strings[Keys.PANEL_TILE_LEARNING]
        QuickTile.CLIPBOARD_OFFER -> strings[Keys.PANEL_TILE_CLIPBOARD]
        QuickTile.KEY_SOUND -> strings[Keys.PANEL_TILE_SOUND]
        QuickTile.VIBRATION -> strings[Keys.PANEL_TILE_VIBRATION]
        QuickTile.MODIFIER_ROW -> strings[Keys.PANEL_TILE_MODIFIER_ROW]
        QuickTile.EMOJI_KEY -> strings[Keys.PANEL_TILE_EMOJI_KEY]
        QuickTile.GLOBE_KEY -> strings[Keys.PANEL_TILE_GLOBE_KEY]
        QuickTile.KEY_POPUP -> strings[Keys.PANEL_TILE_KEY_POPUP]
        QuickTile.RADIAL_MENU -> strings[Keys.PANEL_TILE_RING]
        QuickTile.QUICK_ACTIONS -> strings[Keys.PANEL_TILE_QUICK_ACTIONS]
        QuickTile.NUMERIC_KEYPAD -> strings[Keys.PANEL_TILE_NUMBER_PAD]
        QuickTile.FEATURES -> strings[if (state.on) Keys.ACTION_ENABLED else Keys.ACTION_DISABLED]
    }

    /**
     * Sizes the labels: one line each, the same size for every tile, shrinking until the widest
     * fits its cell, down to [LABEL_MIN_SCALE] of the nominal; past that a label is cut.
     */
    private fun fitLabels() {
        if (nominalLabelPx <= 0f) {
            return
        }
        labelPaint.textSize = nominalLabelPx
        labelPaint.typeface = android.graphics.Typeface.create(paints.labelSecondary.typeface, android.graphics.Typeface.BOLD)
        val room = cellWidth * LABEL_WIDTH_FRACTION
        var widest = 0f
        val all = shownTiles().map(::labelFor) + listOf(strings[Keys.PANEL_ADD])
        for (label in all) {
            widest = max(widest, labelPaint.measureText(label))
        }
        if (widest > room && room > 0f) {
            labelPaint.textSize = max(nominalLabelPx * room / widest, nominalLabelPx * LABEL_MIN_SCALE)
        }
    }

    /** [label] cut with an ellipsis to [room], when it does not fit. */
    private fun fitted(label: String, room: Float): String {
        if (labelPaint.measureText(label) <= room) {
            return label
        }
        var end = label.length
        while (end > 1 && labelPaint.measureText(label, 0, end) + labelPaint.measureText("…") > room) {
            end--
        }
        return label.substring(0, end) + "…"
    }

    // ---- drawing -----------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, paints.background)
        drawHeader(canvas, w)
        val shown = shownTiles()
        for (index in shown.indices) {
            if (index == dragging) {
                continue
            }
            cellAt(index, cell)
            drawTile(canvas, shown[index], cell, pressed == index)
        }
        if (plusShown() && shown.size < capacity) {
            cellAt(shown.size, cell)
            drawPlus(canvas, cell, pressed == shown.size)
        }
        if (dragging in shown.indices) {
            // The held tile rides under the finger, over the others.
            cellAt(dragging, cell)
            cell.offset(dragX - cell.centerX(), dragY - cell.centerY())
            drawTile(canvas, shown[dragging], cell, pressed = true)
        }
        drawFooter(canvas, w, h)
    }

    private fun drawHeader(canvas: Canvas, w: Float) {
        val title = when (mode) {
            Mode.PICK -> strings[Keys.PANEL_ADD_TILE]
            else -> strings[Keys.PANEL_KEYBOARD]
        }
        val action = when (mode) {
            Mode.VIEW -> strings[Keys.PANEL_CLOSE]
            Mode.ARRANGE -> strings[Keys.PANEL_DONE]
            Mode.PICK -> strings[Keys.PANEL_BACK]
        }
        titlePaint.getFontMetrics(metrics)
        val baseline = headerHeight / 2f - (metrics.ascent + metrics.descent) / 2f
        val titleX = if (alignRight) padding else w - padding - titlePaint.measureText(title)
        canvas.drawText(title, titleX, baseline, titlePaint)
        val actionX = if (alignRight) w - padding - accentPaint.measureText(action) else padding
        canvas.drawText(action, actionX, baseline, accentPaint)
    }

    private fun drawFooter(canvas: Canvas, w: Float, h: Float) {
        accentPaint.getFontMetrics(metrics)
        val baseline = footerTop + (h - footerTop) / 2f - (metrics.ascent + metrics.descent) / 2f
        if (fullSettings && mode != Mode.PICK) {
            val label = strings[Keys.PANEL_ALL_SETTINGS]
            val x = if (alignRight) w - padding - accentPaint.measureText(label) else padding
            canvas.drawText(label, x, baseline, accentPaint)
        }
        if (mode == Mode.VIEW) {
            val hint = strings[Keys.PANEL_HOLD_TO_ARRANGE]
            val x = if (alignRight) padding else w - padding - secondaryPaint.measureText(hint)
            canvas.drawText(hint, x, baseline, secondaryPaint)
        }
    }

    private fun drawTile(canvas: Canvas, state: TileState, bounds: RectF, pressed: Boolean) {
        val radius = paints.keyCornerRadiusPx
        val on = state.on && mode != Mode.PICK
        canvas.drawRoundRect(bounds, radius, radius, if (on || pressed) paints.keyPressedFill else paints.keyFill)
        if (on) {
            canvas.drawRoundRect(bounds, radius, radius, outlinePaint)
        } else if (paints.showKeyBorders) {
            canvas.drawRoundRect(bounds, radius, radius, paints.keyStroke)
        }
        val room = cellWidth * LABEL_WIDTH_FRACTION
        val whole = labelFor(state)
        val wholeWidth = labelPaint.measureText(whole)
        val scrolls = labelsScroll && wholeWidth > room && AnimationGate.plays(animationMode)
        val label = if (scrolls) whole else fitted(whole, room)
        labelPaint.getFontMetrics(metrics)
        val labelHeight = metrics.descent - metrics.ascent
        val iconGap = cellHeight * ICON_LABEL_GAP_FRACTION
        val stack = iconPx + iconGap + labelHeight
        val iconTop = bounds.top + (bounds.height() - stack) / 2f
        val icon = icons[state.tile.id]
        if (icon != null) {
            val left = (bounds.centerX() - iconPx / 2f).toInt()
            icon.setBounds(left, iconTop.toInt(), left + iconPx, iconTop.toInt() + iconPx)
            icon.setTint(paints.label.color)
            icon.draw(canvas)
        }
        val labelBaseline = iconTop + iconPx + iconGap - metrics.ascent
        if (scrolls) {
            // Scrolled past its tile: held, slid, held, and over again.
            val offset = LabelMarquee.offsetPx(
                android.os.SystemClock.uptimeMillis() - marqueeStartedAt, wholeWidth - room,
                resources.displayMetrics.density * LabelMarquee.PACE_DP_PER_SECOND, animationSpeed,
            )
            val left = bounds.centerX() - room / 2f
            canvas.save()
            canvas.clipRect(left, labelBaseline + metrics.ascent, left + room, labelBaseline + metrics.descent)
            canvas.drawText(label, left - offset, labelBaseline, labelPaint)
            canvas.restore()
            postInvalidateOnAnimation()
        } else {
            canvas.drawText(label, bounds.centerX() - labelPaint.measureText(label) / 2f, labelBaseline, labelPaint)
        }
        if (mode == Mode.ARRANGE) {
            drawBadge(canvas, bounds)
        }
    }

    /** The remove badge at a tile's top corner on the side away from the aligned edge's labels. */
    private fun drawBadge(canvas: Canvas, bounds: RectF) {
        val cx = bounds.right - badgeRadius * 0.6f
        val cy = bounds.top + badgeRadius * 0.6f
        canvas.drawCircle(cx, cy, badgeRadius, badgePaint)
        val arm = badgeRadius * 0.45f
        canvas.drawLine(cx - arm, cy - arm, cx + arm, cy + arm, badgeMarkPaint)
        canvas.drawLine(cx - arm, cy + arm, cx + arm, cy - arm, badgeMarkPaint)
    }

    private fun drawPlus(canvas: Canvas, bounds: RectF, pressed: Boolean) {
        val radius = paints.keyCornerRadiusPx
        if (pressed) {
            canvas.drawRoundRect(bounds, radius, radius, paints.keyPressedFill)
        }
        val inset = dashedPaint.strokeWidth
        canvas.drawRoundRect(
            bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset,
            radius, radius, dashedPaint,
        )
        val label = strings[Keys.PANEL_ADD]
        labelPaint.getFontMetrics(metrics)
        val labelHeight = metrics.descent - metrics.ascent
        val iconGap = cellHeight * ICON_LABEL_GAP_FRACTION
        val stack = iconPx + iconGap + labelHeight
        val top = bounds.top + (bounds.height() - stack) / 2f
        val cx = bounds.centerX()
        val cy = top + iconPx / 2f
        val arm = iconPx * 0.32f
        val previous = secondaryPaint.strokeWidth
        secondaryPaint.strokeWidth = outlinePaint.strokeWidth
        secondaryPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(cx - arm, cy, cx + arm, cy, secondaryPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, secondaryPaint)
        secondaryPaint.strokeWidth = previous
        val labelBaseline = top + iconPx + iconGap - metrics.ascent
        val previousColor = labelPaint.color
        labelPaint.color = secondaryPaint.color
        canvas.drawText(label, cx - labelPaint.measureText(label) / 2f, labelBaseline, labelPaint)
        labelPaint.color = previousColor
    }

    // ---- touch -------------------------------------------------------------------------------

    /** The cell pressed, for its highlight; -1 for none. */
    private var pressed = -1

    /** The cell being dragged in arrange mode; -1 for none. */
    private var dragging = -1
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f

    /** A press held long enough on a tile: arrange mode, the tile lifted. */
    private val longPress = Runnable {
        val index = pressed
        if (index in tiles.indices && mode != Mode.PICK) {
            mode = Mode.ARRANGE
            dragging = index
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        // A touch that starts right after the panel opens is a typing finger: it does nothing.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            guarded = android.os.SystemClock.uptimeMillis() - openedAt < OPEN_GUARD_MILLIS
        }
        if (guarded) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                guarded = false
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                dragX = x
                dragY = y
                pressed = -1
                if (y < headerHeight || y >= footerTop) {
                    return true
                }
                val index = cellUnder(x, y)
                val shown = shownTiles()
                if (index in shown.indices) {
                    pressed = index
                    if (mode == Mode.ARRANGE) {
                        // In arrange mode a tile lifts at once; its badge removes it on the lift.
                        if (onBadge(index, x, y)) {
                            pressed = -1
                            remove(index)
                            return true
                        }
                        dragging = index
                    } else if (mode == Mode.VIEW) {
                        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                } else if (index == shown.size && plusShown()) {
                    pressed = index
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                dragX = x
                dragY = y
                if (dragging >= 0) {
                    val over = cellUnder(x, y)
                    if (over in tiles.indices && over != dragging) {
                        val order = tiles.toMutableList()
                        order.add(over, order.removeAt(dragging))
                        tiles = order
                        dragging = over
                        pressed = over
                    }
                    invalidate()
                } else if (pressed >= 0) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.abs(x - downX) > slop || kotlin.math.abs(y - downY) > slop) {
                        removeCallbacks(longPress)
                        pressed = -1
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging >= 0) {
                    dragging = -1
                    pressed = -1
                    listener?.onTilesArranged(tiles.map { it.tile })
                    invalidate()
                    return true
                }
                val index = pressed
                pressed = -1
                invalidate()
                if (y < headerHeight) {
                    onHeaderTapped(x)
                } else if (y >= footerTop) {
                    onFooterTapped(x)
                } else if (index >= 0 && cellUnder(x, y) == index) {
                    onCellTapped(index)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging >= 0) {
                    dragging = -1
                    listener?.onTilesArranged(tiles.map { it.tile })
                }
                pressed = -1
                invalidate()
                return true
            }
        }
        return true
    }

    /** Whether ([x], [y]) is on the remove badge of the tile at [index]. */
    private fun onBadge(index: Int, x: Float, y: Float): Boolean {
        cellAt(index, cell)
        val cx = cell.right - badgeRadius * 0.6f
        val cy = cell.top + badgeRadius * 0.6f
        val reach = badgeRadius * 1.6f
        return x >= cx - reach && x <= cx + reach && y >= cy - reach && y <= cy + reach
    }

    private fun remove(index: Int) {
        val order = tiles.toMutableList()
        order.removeAt(index)
        tiles = order
        listener?.onTilesArranged(order.map { it.tile })
        invalidate()
    }

    private fun onHeaderTapped(x: Float) {
        val action = when (mode) {
            Mode.VIEW -> strings[Keys.PANEL_CLOSE]
            Mode.ARRANGE -> strings[Keys.PANEL_DONE]
            Mode.PICK -> strings[Keys.PANEL_BACK]
        }
        val reach = accentPaint.measureText(action) + padding * 2f
        val hit = if (alignRight) x >= width - reach else x <= reach
        if (!hit) {
            return
        }
        when (mode) {
            Mode.VIEW -> listener?.onCloseQuickSettings()
            Mode.ARRANGE -> {
                mode = Mode.VIEW
                invalidate()
            }
            Mode.PICK -> {
                mode = Mode.ARRANGE
                fitLabels()
                invalidate()
            }
        }
    }

    private fun onFooterTapped(x: Float) {
        if (!fullSettings || mode == Mode.PICK) {
            return
        }
        val label = strings[Keys.PANEL_ALL_SETTINGS]
        val reach = accentPaint.measureText(label) + padding * 2f
        val hit = if (alignRight) x >= width - reach else x <= reach
        if (hit) {
            listener?.onOpenFullSettings()
        }
    }

    private fun onCellTapped(index: Int) {
        val shown = shownTiles()
        when (mode) {
            Mode.VIEW -> if (index in shown.indices) {
                listener?.onTileTapped(shown[index].tile)
            } else if (index == shown.size && plusShown()) {
                mode = Mode.PICK
                fitLabels()
                invalidate()
            }
            Mode.ARRANGE -> if (index == shown.size && plusShown()) {
                mode = Mode.PICK
                fitLabels()
                invalidate()
            }
            Mode.PICK -> if (index in shown.indices) {
                val added = tiles + TileState(shown[index].tile, false)
                tiles = added
                mode = Mode.ARRANGE
                listener?.onTilesArranged(added.map { it.tile })
                fitLabels()
                invalidate()
            }
        }
    }

    private companion object {
        const val COLUMNS = 4

        /** A cell's height as a share of its width, at most. */
        const val CELL_ASPECT = 0.9f

        /** A cell's height as a share of its width, at least; it sets how many rows fit. */
        const val MIN_CELL_ASPECT = 0.55f

        /** How long after the panel opens a touch is ignored, so a finger still typing hits no tile. */
        const val OPEN_GUARD_MILLIS = 500L

        /** The icon's size as a share of the cell's height. */
        const val ICON_FRACTION = 0.4f

        /** The label's nominal text size as a share of the cell's height. */
        const val LABEL_FRACTION = 0.16f

        /** The room a label may take of the cell's width. */
        const val LABEL_WIDTH_FRACTION = 0.86f

        /** The smallest a label's text goes, as a share of its nominal size, before it is cut. */
        const val LABEL_MIN_SCALE = 0.7f

        /** The gap between the icon and the label as a share of the cell's height. */
        const val ICON_LABEL_GAP_FRACTION = 0.06f

        /** The remove badge's radius as a share of the cell's height. */
        const val BADGE_FRACTION = 0.11f
    }
}
