// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Trace
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.borderkeys.theme.ThemePaints

/**
 * The emoji picker: a scrolling grid with a row of category tabs, from the list
 * tools/build_emoji.py compiles, without skin-tone variants, drawn in the system font.
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    fun interface Listener {
        fun onEmojiPicked(emoji: String)
    }

    var listener: Listener? = null

    private var categories: List<String> = emptyList()
    private var byCategory: Map<String, List<String>> = emptyMap()
    private var index: List<EmojiSearch.Entry> = emptyList()
    private var matches: List<String> = emptyList()

    /** What the grid is showing: the chosen category, the recents, or the query's matches. */
    private var current: List<String> = emptyList()
    private var selectedTab = 0

    /**
     * The word the grid is searched by: its matches replace the recents, under a magnifier tab,
     * until a tab is picked. Set after [load].
     */
    var query: String = ""
        set(value) {
            field = value
            matches = EmojiSearch.matches(value, index, MAX_MATCHES, keywords)
            selectTab(if (matches.isEmpty()) 0 else SEARCH_TAB)
        }

    /**
     * The keywords each emoji is also found by, in the languages switched on: loaded by the
     * service beside the dictionaries (see [EmojiKeywords]) and searched after the names.
     */
    var keywords: Map<String, List<String>> = emptyMap()

    /** The last emoji used, most recent first; the service persists them. */
    var recents: List<String> = emptyList()
        set(value) {
            field = value.take(MAX_RECENTS)
            if (selectedTab == 0) {
                current = field
                measureContent()
                invalidate()
            }
        }

    /**
     * Whether the grid and the tabs run from the right edge, the way the alphabetic layout's
     * language reads: the first emoji at the top right, the recents tab rightmost.
     */
    var rightToLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastY = 0f
    private var dragging = false
    private var pressedCell = -1

    private var cellPx = 0f
    private var columns = 1
    private var tabHeightPx = 0f
    private var contentHeight = 0

    private val glyph = CharArray(16)

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
    }

    /** Reads the compiled list, once per view. */
    fun load(context: Context) {
        if (categories.isNotEmpty()) {
            return
        }
        runCatching {
            // One line per category: its name, a tab, then the emoji separated by spaces.
            val names = ArrayList<String>()
            val lists = HashMap<String, List<String>>()
            context.assets.open(ASSET).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) {
                        continue
                    }
                    val name = line.substring(0, tab)
                    names += name
                    lists[name] = line.substring(tab + 1).split(' ').filter { it.isNotEmpty() }
                }
            }
            categories = names
            byCategory = lists
        }
        runCatching {
            context.assets.open(NAMES_ASSET).bufferedReader().useLines { lines ->
                index = EmojiSearch.parse(lines)
            }
        }
        selectTab(0)
    }

    /** Tab zero is the recents, [SEARCH_TAB] the query's matches; the rest are the categories
     *  in the order Unicode lists them. */
    private fun selectTab(index: Int) {
        selectedTab = index.coerceIn(SEARCH_TAB, categories.size)
        current = when (selectedTab) {
            SEARCH_TAB -> matches
            0 -> recents
            else -> byCategory[categories[selectedTab - 1]].orEmpty()
        }
        scroller.forceFinished(true)
        scrollTo(0, 0)
        measureContent()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec),
        )
        measureContent(MeasureSpec.getSize(widthMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureContent(w)
    }

    /** Lays the grid out for [widthPx]: the columns that fit, and the height the rows need. */
    private fun measureContent(widthPx: Int = width) {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else ThemePaints.DEFAULT_ROW_HEIGHT_PX
        tabHeightPx = row * TAB_HEIGHT_ROWS
        cellPx = row * CELL_ROWS
        columns = if (widthPx > 0) (widthPx / cellPx).toInt().coerceAtLeast(1) else 1
        val rows = if (current.isEmpty()) 0 else (current.size + columns - 1) / columns
        contentHeight = (rows * cellPx).toInt()
    }

    /** The [android.view.HapticFeedbackConstants] class an emoji plays, the same as the keys'. */
    var hapticConstant: Int = android.view.HapticFeedbackConstants.KEYBOARD_TAP
    var hapticEnabled: Boolean = true

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("EmojiPanelView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
            drawTabs(canvas)

            if (current.isEmpty()) {
                return
            }
            val top = tabHeightPx
            val first = ((scrollY / cellPx).toInt() * columns).coerceAtLeast(0)
            val last = (first + (((height - top) / cellPx).toInt() + 2) * columns - 1)
                .coerceAtMost(current.size - 1)
            val previousSize = paints.label.textSize
            paints.label.textSize = cellPx * GLYPH_FRACTION
            for (index in first..last) {
                val left = columnLeft(index % columns)
                val row = index / columns
                val cx = left + cellPx / 2f
                val cy = top + row * cellPx - scrollY + cellPx / 2f
                if (index == pressedCell) {
                    canvas.drawRect(
                        left, cy - cellPx / 2f,
                        left + cellPx, cy + cellPx / 2f,
                        paints.keyPressedFill,
                    )
                }
                val text = current[index]
                val length = text.length.coerceAtMost(glyph.size)
                text.toCharArray(glyph, 0, 0, length)
                canvas.drawText(
                    glyph, 0, length, cx, cy + paints.label.textSize * GLYPH_BASELINE,
                    paints.label,
                )
            }
            paints.label.textSize = previousSize
        } finally {
            Trace.endSection()
        }
    }

    private fun drawTabs(canvas: Canvas) {
        if (categories.isEmpty()) {
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), tabHeightPx, paints.background)
        canvas.drawLine(0f, tabHeightPx, width.toFloat(), tabHeightPx, paints.keyStroke)
        val count = categories.size + 1
        val step = width.toFloat() / count
        val previousSize = paints.label.textSize
        paints.label.textSize = tabHeightPx * TAB_GLYPH_FRACTION
        for (index in 0 until count) {
            val left = step * slotOf(index, count)
            val cx = left + step / 2f
            if (index == selectedTab || (index == 0 && selectedTab == SEARCH_TAB)) {
                canvas.drawLine(
                    left + step * 0.2f, tabHeightPx - 2f,
                    left + step * 0.8f, tabHeightPx - 2f, paints.accent,
                )
            }
            // Each tab shows an emoji.
            val text = tabGlyph(index)
            val length = text.length.coerceAtMost(glyph.size)
            text.toCharArray(glyph, 0, 0, length)
            canvas.drawText(
                glyph, 0, length, cx, tabHeightPx / 2f + paints.label.textSize * GLYPH_BASELINE,
                if (index == selectedTab) paints.label else paints.labelSecondary,
            )
        }
        paints.label.textSize = previousSize
    }

    /** The first emoji of a category stands for it; recents get a clock, and a search the
     *  magnifier in the clock's place. */
    private fun tabGlyph(index: Int): String {
        if (index == 0) {
            return if (selectedTab == SEARCH_TAB) "🔍" else "🕒"
        }
        return byCategory[categories[index - 1]]?.firstOrNull() ?: "•"
    }

    /** The left edge of grid column [column], counted from the right when [rightToLeft]. */
    private fun columnLeft(column: Int): Float = Mirror.cellLeft(column, cellPx, width.toFloat(), rightToLeft)

    /** Where tab [index] of [count] sits from the left edge, mirrored when [rightToLeft]. */
    private fun slotOf(index: Int, count: Int): Int = Mirror.slot(index, count, rightToLeft)

    private fun cellAt(x: Float, y: Float): Int {
        if (y < tabHeightPx || current.isEmpty()) {
            return -1
        }
        val column = Mirror.cellAt(x, cellPx, width.toFloat(), rightToLeft)
        if (column !in 0 until columns) {
            return -1
        }
        val row = ((y - tabHeightPx + scrollY) / cellPx).toInt()
        val index = row * columns + column
        return if (index in current.indices) index else -1
    }

    private fun tabAt(x: Float): Int {
        val count = categories.size + 1
        if (count == 0) {
            return -1
        }
        val slot = (x / (width.toFloat() / count)).toInt().coerceIn(0, count - 1)
        return slotOf(slot, count)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastY = event.y
                dragging = false
                pressedCell = cellAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (lastY >= tabHeightPx) {
                    val delta = lastY - event.y
                    if (!dragging && kotlin.math.abs(delta) > touchSlop) {
                        dragging = true
                        pressedCell = -1
                        invalidate()
                    }
                    if (dragging) {
                        lastY = event.y
                        scrollBy(0, delta.toInt())
                        clampScroll()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    tracker.computeCurrentVelocity(1000)
                    scroller.fling(0, scrollY, 0, -tracker.yVelocity.toInt(), 0, 0, 0, maxScroll())
                    postInvalidateOnAnimation()
                } else if (event.y < tabHeightPx) {
                    selectTab(tabAt(event.x))
                } else {
                    val index = cellAt(event.x, event.y)
                    if (index >= 0) {
                        if (hapticEnabled) {
                            performHapticFeedback(hapticConstant)
                        }
                        listener?.onEmojiPicked(current[index])
                    }
                }
                pressedCell = -1
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedCell = -1
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun maxScroll(): Int =
        (contentHeight - (height - tabHeightPx).toInt()).coerceAtLeast(0)

    private fun clampScroll() {
        if (scrollY < 0) {
            scrollTo(0, 0)
        } else if (scrollY > maxScroll()) {
            scrollTo(0, maxScroll())
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    private companion object {
        const val ASSET = "emoji/emoji.txt"
        const val NAMES_ASSET = "emoji/emoji_names.txt"

        /** The grid's view of a search: the tab before the recents, never drawn as a tab. */
        const val SEARCH_TAB = -1

        /** The most matches a word shows. */
        const val MAX_MATCHES = 30

        /** How many recents are kept. */
        const val MAX_RECENTS = 24

        const val TAB_HEIGHT_ROWS = 0.62f
        const val CELL_ROWS = 0.78f

        /** The glyph's share of its cell, and where its baseline sits in it. */
        const val GLYPH_FRACTION = 0.62f
        const val GLYPH_BASELINE = 0.36f
        const val TAB_GLYPH_FRACTION = 0.52f
    }
}
