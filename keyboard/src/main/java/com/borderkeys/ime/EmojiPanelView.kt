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
 * The emoji picker: a scrolling grid with a row of category tabs.
 *
 * The list comes from Unicode's own emoji-test.txt, compiled to an asset by
 * tools/build_emoji.py, in the order that file recommends for keyboard palettes. Nothing is
 * hand-sorted here, and skin-tone variants are left out -- they multiply the grid by six for a
 * choice a strip of tabs has no room to offer.
 *
 * Drawn like the rest of the keyboard: one View, one onDraw, arithmetic hit testing, and only
 * the rows the viewport shows. The glyphs come from the system font, so the keyboard ships no
 * emoji images and looks like the phone it is running on.
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

    /** What the grid is showing: the chosen category, or the recents when that tab is picked. */
    private var current: List<String> = emptyList()
    private var selectedTab = 0

    /**
     * The last emoji used, most recent first.
     *
     * Held here and handed to the service to persist. A picker that forgets what you just used
     * is a picker you scroll through again every time.
     */
    var recents: List<String> = emptyList()
        set(value) {
            field = value.take(MAX_RECENTS)
            if (selectedTab == 0) {
                current = field
                measureContent()
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

    /** Reads the compiled list. Once per view, off the typing path. */
    fun load(context: Context) {
        if (categories.isNotEmpty()) {
            return
        }
        runCatching {
            // One line per category: name, tab, emoji separated by spaces. No parser, because
            // no emoji contains a space or a tab and a JSON library has no business being in
            // this process for a list of lists of strings.
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
        selectTab(0)
    }

    /** Tab zero is the recents; the rest are the categories in the order Unicode lists them. */
    private fun selectTab(index: Int) {
        selectedTab = index.coerceIn(0, categories.size)
        current = if (selectedTab == 0) {
            recents
        } else {
            byCategory[categories[selectedTab - 1]].orEmpty()
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
        measureContent()
    }

    private fun measureContent() {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX
        tabHeightPx = row * TAB_HEIGHT_ROWS
        cellPx = row * CELL_ROWS
        columns = if (width > 0) (width / cellPx).toInt().coerceAtLeast(1) else 1
        val rows = if (current.isEmpty()) 0 else (current.size + columns - 1) / columns
        contentHeight = (rows * cellPx).toInt()
    }

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
                val column = index % columns
                val row = index / columns
                val cx = column * cellPx + cellPx / 2f
                val cy = top + row * cellPx - scrollY + cellPx / 2f
                if (index == pressedCell) {
                    canvas.drawRect(
                        column * cellPx, cy - cellPx / 2f,
                        column * cellPx + cellPx, cy + cellPx / 2f,
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
            val cx = step * index + step / 2f
            if (index == selectedTab) {
                canvas.drawLine(
                    step * index + step * 0.2f, tabHeightPx - 2f,
                    step * (index + 1) - step * 0.2f, tabHeightPx - 2f, paints.accent,
                )
            }
            // A representative emoji per tab rather than an icon set of its own: the tabs are
            // then in the same font as their contents and need no drawable per category.
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

    /** The first emoji of a category stands for it; recents get a clock. */
    private fun tabGlyph(index: Int): String {
        if (index == 0) {
            return "🕒"
        }
        return byCategory[categories[index - 1]]?.firstOrNull() ?: "•"
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (y < tabHeightPx || current.isEmpty()) {
            return -1
        }
        val column = (x / cellPx).toInt()
        if (column >= columns) {
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
        return (x / (width.toFloat() / count)).toInt().coerceIn(0, count - 1)
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
                        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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

        /** How many recents are kept. A row and a half on most phones. */
        const val MAX_RECENTS = 24

        const val TAB_HEIGHT_ROWS = 0.62f
        const val CELL_ROWS = 0.78f
        const val DEFAULT_ROW_PX = 132f

        /** The glyph's share of its cell, and where its baseline sits in it. */
        const val GLYPH_FRACTION = 0.62f
        const val GLYPH_BASELINE = 0.36f
        const val TAB_GLYPH_FRACTION = 0.52f
    }
}
