// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.inline.InlineContentView
import com.borderkeys.theme.ThemePaints
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The one place in this project that hosts framework `View`s, and it has no alternative.
 *
 * A password manager's inline suggestion arrives as an [InlineContentView]: a surface owned by
 * *another process*, handed to us through `SurfaceControlViewHost`. It cannot be drawn onto our
 * `Canvas`, it cannot be read, and it cannot be screenshotted -- which is exactly the property
 * that makes this integration safe. We are told that suggestions exist, we are given something
 * already rendered, and we learn only that the user picked one. The password never passes
 * through this application, and there is no API by which it could.
 *
 * So this is a real `ViewGroup` with real children, sitting where the suggestion strip normally
 * is, for as long as the autofill service has something to offer.
 *
 * Horizontal scrolling is written by hand rather than taken from `HorizontalScrollView`: the
 * whole content is three or four fixed-size children whose positions are a running sum, and a
 * scroll container would bring a nested measure pass and a scroller for that.
 */
@SuppressLint("ViewConstructor")
class InlineSuggestionsHostView(
    context: Context,
    private val paints: ThemePaints,
) : ViewGroup(context) {

    private var scrollOffset = 0f
    private var contentWidth = 0
    private var lastTouchX = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        // Children are surfaces from another process; clipping is what keeps them inside the
        // strip when it is scrolled.
        clipChildren = true
        clipToPadding = true
        setWillNotDraw(false)
    }

    val hasSuggestions: Boolean get() = childCount > 0

    /**
     * Replaces the row.
     *
     * The previous children are released rather than reused: each one is bound to a surface from
     * a specific response, and a response is not valid past the request that produced it.
     */
    fun setSuggestions(views: List<InlineContentView>) {
        removeAllViews()
        scrollOffset = 0f
        for (view in views) {
            addView(view)
        }
        requestLayout()
        invalidate()
    }

    fun clearSuggestions() {
        if (childCount != 0) {
            removeAllViews()
            scrollOffset = 0f
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowHeight = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_HEIGHT_PX
        val height = (rowHeight * HEIGHT_FRACTION).toInt()

        var total = 0
        val childHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.measure(childWidthSpec, childHeightSpec)
            total += child.measuredWidth + GAP_PX
        }
        contentWidth = max(0, total - GAP_PX)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = -scrollOffset.roundToInt()
        val height = b - t
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val childHeight = child.measuredHeight
            val top = (height - childHeight) / 2
            child.layout(x, top, x + child.measuredWidth, top + childHeight)
            x += child.measuredWidth + GAP_PX
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        // A press is left to the child, which is the only thing that may act on it: tapping a
        // suggestion has to reach the other process's view, not us. Only once the finger has
        // travelled far enough to be a scroll do we take the gesture.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.abs(event.x - lastTouchX) > touchSlop) {
                    dragging = true
                    lastTouchX = event.x
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastTouchX - event.x
                lastTouchX = event.x
                val maximum = max(0, contentWidth - width).toFloat()
                val next = (scrollOffset + delta).coerceIn(0f, maximum)
                if (next != scrollOffset) {
                    scrollOffset = next
                    requestLayout()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        const val GAP_PX = 8
        const val HEIGHT_FRACTION = 0.78f
        const val DEFAULT_HEIGHT_PX = 150f
    }
}
