// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RenderNode
import android.os.Trace
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeProvider
import android.view.View
import android.view.ViewConfiguration
import com.borderkeys.theme.ThemePaints
import kotlin.math.max
import kotlin.math.min

/**
 * The typing surface: one [View] that draws itself and resolves touches arithmetically.
 *
 * No Compose, no XML inflation, no child views, and no object per key. A layout is compiled once
 * into parallel arrays of primitives, and after that the draw path reads floats out of arrays
 * and the touch path indexes into a precomputed grid. The budget it is built around is two
 * milliseconds from a finger going down to a character reaching `InputConnection`, and four for
 * redrawing the region that changed.
 *
 * Two things follow from that and are not negotiable in this file:
 *
 *  * **`onDraw` and `onTouchEvent` allocate nothing.** No `Paint`, no `Rect`, no `String`, no
 *    boxing, no capturing lambda, no iterator. Every buffer is preallocated and reused, labels
 *    are drawn from a shared `CharArray` so no `String` is created, and the runnables for
 *    long-press and auto-repeat are fields rather than lambdas made at press time.
 *  * **The static part is recorded once.** Unpressed keys and their labels go into a
 *    [RenderNode] that is re-recorded only when the theme, layout or size changes. A frame
 *    caused by one key going down replays that display list and draws one key over it, into the
 *    bounding box of that key -- not the whole keyboard.
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvasView(
    context: Context,
    private val paints: ThemePaints,
) : View(context) {

    /** What the service is told about. Called on the UI thread, inside a touch event. */
    interface Listener {
        fun onKey(code: Int, keyIndex: Int)
        fun onKeyRepeat(code: Int)
        fun onText(text: CharSequence)
        /** Fired on every press so the service can start a prediction early. */
        fun onKeyDown(code: Int)

        /**
         * A completed swipe, as raw touch samples in view pixels.
         *
         * The arrays are the view's own capture buffers and are reused on the next gesture, so
         * the listener must consume or copy them before returning.
         */
        fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int)

        /**
         * A key held down that has no alternatives to show.
         *
         * Returning true means the press was consumed: the finger lifting afterwards must not
         * also type the key. That is what makes holding the globe open the settings panel
         * without also switching language on the way out.
         */
        fun onKeyLongPress(code: Int, keyIndex: Int): Boolean
    }

    var listener: Listener? = null
    var hapticEnabled: Boolean = true
    var swipeEnabled: Boolean = true

    private var layout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /**
     * Where every key is, and which key a touch belongs to.
     *
     * Held rather than inlined so that the arithmetic can be tested without a device: a
     * one-pixel gap between two keys is a touch that does nothing, and no screenshot shows it.
     */
    private val geometry = KeyboardGeometry()

    /** Per-key text size, fixed at compile time so the draw path never calls measureText. */
    private var labelTextSize = FloatArray(0)

    // ---- touch state ---------------------------------------------------------------------

    /**
     * pointer id to key index. An `IntArray`, not a `Map`: this is written inside a motion event
     * and a boxed key would allocate on every finger down.
     */
    private val pointerKey = IntArray(MAX_POINTERS) { NO_KEY }
    private val pointerDownAt = LongArray(MAX_POINTERS)
    private var touchSlop = 0

    // ---- gesture capture -----------------------------------------------------------------------

    private fun beginGesture(fromKey: Int) {
        gestureActive = true
        // The key the finger started on is released without committing: the press became a
        // swipe, and a swipe must not also type its first letter.
        endPress(fromKey)
        pointerKey[gesturePointer] = fromKey
        cancelPendingCallbacks()
        dismissAlternatives()
        gesture.begin(gestureStartX, gestureStartY, 0L)
    }

    /**
     * Reads every sample the motion event carries, not just the current one.
     *
     * A touch driver batches: one `ACTION_MOVE` typically holds several samples taken between
     * frames, reachable only through the historical accessors. Ignoring them throws away most
     * of a fast swipe and, with it, exactly the curvature that tells "than" from "thin" -- and
     * it is the single most common mistake in an amateur implementation, because the trail
     * still looks fine and only the accuracy suffers.
     */
    private fun captureGestureSamples(event: MotionEvent, pointerIndex: Int) {
        eventSamples.bind(event, pointerIndex)
        gesture.capture(eventSamples)
        eventSamples.release()
        invalidateTrail()
    }

    /**
     * Reads the samples of the event being handled, without allocating one per event.
     *
     * A single instance is rebound on each `ACTION_MOVE` and cleared afterwards, so the view
     * never holds a `MotionEvent` the framework has already recycled. Being the only
     * implementation loaded in this process, the calls through it stay monomorphic.
     */
    private inner class EventSamples : MotionSamples {
        private var event: MotionEvent? = null
        private var pointerIndex = 0

        fun bind(event: MotionEvent, pointerIndex: Int) {
            this.event = event
            this.pointerIndex = pointerIndex
        }

        fun release() {
            event = null
        }

        override val sampleCount: Int
            get() = (event?.historySize ?: 0) + 1

        override fun xAt(index: Int): Float {
            val e = event ?: return 0f
            return if (index < e.historySize) {
                e.getHistoricalX(pointerIndex, index)
            } else {
                e.getX(pointerIndex)
            }
        }

        override fun yAt(index: Int): Float {
            val e = event ?: return 0f
            return if (index < e.historySize) {
                e.getHistoricalY(pointerIndex, index)
            } else {
                e.getY(pointerIndex)
            }
        }

        override fun timeAt(index: Int): Long {
            val e = event ?: return 0L
            return if (index < e.historySize) {
                e.getHistoricalEventTime(index)
            } else {
                e.eventTime
            }
        }
    }

    private fun finishGesture() {
        val count = gesture.count
        gestureActive = false
        gesturePointer = -1
        invalidateTrailFully()
        if (count >= MIN_GESTURE_POINTS) {
            listener?.onGesture(gesture.xs, gesture.ys, gesture.times, count)
        }
        // Reset by index. The arrays keep their storage for the next swipe.
        gesture.reset()
    }

    private fun abandonGesture() {
        gestureActive = false
        gesturePointer = -1
        gesture.reset()
        invalidateTrailFully()
    }

    /**
     * Repaints only the rectangle the trail occupies.
     *
     * The four-argument `invalidate` is deprecated in favour of repainting the whole view, on
     * the grounds that a hardware-accelerated pipeline redraws everything anyway. That holds for
     * a view whose content is one display list; it does not hold here. The keys are drawn once
     * into a `RenderNode` and replayed, so a full invalidation costs a replay of the whole
     * keyboard plus the trail, and a partial one costs the trail. Keeping the deprecated call is
     * the deliberate choice, and it is measured: it is what holds `onDraw` under 4 ms while a
     * swipe is in flight.
     */
    @Suppress("DEPRECATION")
    private fun invalidateTrail() {
        val margin = paints.swipeTrailWidthPx + 2f
        invalidate(
            (gesture.minX - margin).toInt(), (gesture.minY - margin).toInt(),
            (gesture.maxX + margin).toInt() + 1, (gesture.maxY + margin).toInt() + 1,
        )
    }

    private fun invalidateTrailFully() {
        if (gesture.count > 0) {
            invalidateTrail()
        }
    }

    /**
     * Draws the trail as a few polylines of increasing opacity.
     *
     * The fade is per segment rather than per point because alpha lives on the paint, not on a
     * vertex: three or four `drawPath` calls give the effect that a per-point gradient would
     * need a shader for, and cost nothing measurable.
     */
    private fun drawGestureTrail(canvas: Canvas) {
        if (gesture.count < 2) {
            return
        }
        val perSegment = gesture.count / TRAIL_SEGMENTS + 1
        val baseAlpha = paints.swipeTrail.alpha
        var start = 0
        var segment = 0
        while (start < gesture.count - 1 && segment < TRAIL_SEGMENTS) {
            val end = minOf(gesture.count - 1, start + perSegment)
            val path = trailPaths[segment]
            path.rewind()
            path.moveTo(gesture.xs[start], gesture.ys[start])
            for (i in start + 1..end) {
                path.lineTo(gesture.xs[i], gesture.ys[i])
            }
            // Oldest segment faintest, newest full strength.
            val fraction = (segment + 1).toFloat() / TRAIL_SEGMENTS
            paints.swipeTrail.alpha = (baseAlpha * (0.25f + 0.75f * fraction)).toInt()
                .coerceIn(0, 255)
            canvas.drawPath(path, paints.swipeTrail)
            start = end
            segment++
        }
        paints.swipeTrail.alpha = baseAlpha
    }

    // ---- press animation -----------------------------------------------------------------

    /**
     * A fixed pool of press states, one per key that is currently lit. No `ValueAnimator`, no
     * object per press: a `ValueAnimator` allocates, posts to the animation handler and holds a
     * listener, all to interpolate one float that this already has a frame callback for.
     */
    private val pressKey = IntArray(PRESS_POOL) { NO_KEY }
    private val pressProgress = FloatArray(PRESS_POOL)
    private val pressReleasing = BooleanArray(PRESS_POOL)
    private var lastFrameNanos = 0L
    private var animating = false

    // ---- long press ------------------------------------------------------------------------

    private var alternativesKey = NO_KEY
    private var alternativesSelection = -1
    private var alternativesLeft = 0f
    private var alternativesTop = 0f
    private var alternativesCellWidth = 0f
    private var alternativesHeight = 0f
    private var longPressPointer = -1

    private var repeatKey = NO_KEY

    // ---- gesture capture ---------------------------------------------------------------------

    /**
     * The captured swipe, in three preallocated arrays.
     *
     * Nothing is allocated for the duration of a gesture. A swipe produces hundreds of samples
     * in under a second, and a growing list would allocate and copy several times inside the
     * window where the finger is moving and the trail has to keep up with it.
     */
    /**
     * The points of the swipe in progress, and the bounding box the trail is invalidated
     * against. Lives in its own class so its invariants can be asserted without a `Canvas`.
     */
    /**
     * The virtual view hierarchy a screen reader explores.
     *
     * Built from the same compiled geometry the drawing and the hit-testing use, so a key that
     * is drawn is a key that can be explored and there is no second layout to drift.
     */
    private val accessibility = KeyboardAccessibility(this, geometry).apply {
        listener = KeyboardAccessibility.Listener { code, keyIndex ->
            // Activated by the reader rather than by a finger: there was no press to release,
            // so this goes straight to the same place a completed tap goes. Qualified because
            // `listener` inside `apply` is the accessibility helper's own.
            this@KeyboardCanvasView.listener?.onKey(code, keyIndex)
        }
    }

    private val gesture = GestureCapture()

    /** Rebound on every move event; see [EventSamples]. */
    private val eventSamples = EventSamples()

    private var gesturePointer = -1
    private var gestureActive = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f


    /**
     * One Path per trail segment, recycled with `rewind()`.
     *
     * `rewind()` rather than `reset()`: reset frees the internal buffer and the next gesture
     * allocates it again, which is precisely the allocation this is avoiding. Several paths
     * rather than one because the trail fades with age, and alpha is a property of the paint
     * rather than of a point.
     */
    private val trailPaths = Array(TRAIL_SEGMENTS) { Path() }

    // ---- reused scratch ----------------------------------------------------------------------

    private val backgroundNode = RenderNode("borderkeys-static")
    private var backgroundValid = false
    private var dirtyLeft = 0
    private var dirtyTop = 0
    private var dirtyRight = 0
    private var dirtyBottom = 0

    /**
     * One instance each, created here and never again. Scheduling these with `postDelayed`
     * allocates nothing; a lambda written at the call site would allocate a new object on every
     * key press.
     */
    private val longPressRunnable = Runnable { onLongPressElapsed() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = repeatKey
            if (key == NO_KEY) {
                return
            }
            listener?.onKeyRepeat(geometry.keyCode[key])
            postDelayed(this, REPEAT_INTERVAL_MILLIS)
        }
    }
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        onAnimationFrame(frameTimeNanos)
    }

    init {
        isHapticFeedbackEnabled = true
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    // ---- public surface -----------------------------------------------------------------------

    fun setLayout(newLayout: KeyboardLayout) {
        if (layout === newLayout) {
            return
        }
        layout = newLayout
        if (width > 0 && height > 0) {
            compile(width, height)
        }
        requestLayout()
        invalidate()
    }

    /** Called when the theme changed and the recorded static layer is stale. */
    fun onThemeChanged() {
        if (width > 0 && height > 0) {
            compile(width, height)
        }
        invalidate()
    }

    /** Key centres and codes, in the form the native engine wants for proximity correction. */
    fun exportGeometry(
        codesOut: IntArray,
        centersXOut: FloatArray,
        centersYOut: FloatArray,
    ): Int = geometry.exportGeometry(codesOut, centersXOut, centersYOut)

    /** Average key size, for the same purpose. Zero before the first layout pass. */
    val averageKeyWidth: Float get() = geometry.averageKeyWidth

    val averageKeyHeight: Float get() = geometry.averageKeyHeight

    // ---- measurement and compilation -----------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowHeight = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_HEIGHT_PX
        val height = (layout.totalHeightScale * rowHeight).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            compile(w, h)
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibility.provider

    /**
     * With a screen reader on, a finger dragged over the keyboard produces hover events instead
     * of touches. They are routed to the virtual view under them; anything not consumed falls
     * through to the framework's own handling.
     */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Turns the layout description into the arrays everything else reads.
     *
     * Runs on a size change, a layout change and a theme change -- never per frame and never per
     * touch. Reallocates only when the number of keys actually changed, so rotating the device
     * or resizing the window reuses every array.
     */
    private fun compile(viewWidth: Int, viewHeight: Int) {
        geometry.compile(layout, viewWidth.toFloat(), viewHeight.toFloat(), paints.keyGapPx)
        if (labelTextSize.size != geometry.keyCount) {
            labelTextSize = FloatArray(geometry.keyCount)
        }
        measureLabels()
        recordBackground(viewWidth, viewHeight)
        // Every virtual view just moved, and a reader holding a stale node would speak the
        // wrong key or none at all.
        accessibility.onGeometryChanged()
    }

    /**
     * Fixes each label's text size now, so the draw path never calls `measureText`.
     *
     * Most labels are one character and keep the theme's size. The wide ones -- "?123" on a key
     * one and a half units across -- are shrunk to fit here, once, instead of being measured on
     * every frame or silently overflowing their key.
     */
    private fun measureLabels() {
        val base = paints.label.textSize
        for (index in 0 until geometry.keyCount) {
            val length = geometry.labelLength[index]
            if (length == 0) {
                labelTextSize[index] = base
                continue
            }
            paints.label.textSize = base
            val measured = paints.label.measureText(geometry.labelChars, geometry.labelOffset[index], length)
            val available = (geometry.keyRight[index] - geometry.keyLeft[index]) * LABEL_WIDTH_FRACTION
            labelTextSize[index] = if (measured > available && measured > 0f) {
                base * (available / measured)
            } else {
                base
            }
        }
        paints.label.textSize = base
    }

    /** O(1). Delegated to the compiled geometry, where it can be tested. */
    fun findKeyAt(x: Float, y: Float): Int = geometry.findKeyAt(x, y)

    // ---- drawing -------------------------------------------------------------------------------

    private fun recordBackground(viewWidth: Int, viewHeight: Int) {
        backgroundNode.setPosition(0, 0, viewWidth, viewHeight)
        val canvas = backgroundNode.beginRecording()
        try {
            drawStatic(canvas, viewWidth.toFloat(), viewHeight.toFloat())
        } finally {
            backgroundNode.endRecording()
        }
        backgroundValid = true
    }

    private fun drawStatic(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, paints.background)
        val radius = paints.keyCornerRadiusPx
        for (index in 0 until geometry.keyCount) {
            val fill = if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.MODIFIER)) {
                paints.modifierKeyFill
            } else {
                paints.keyFill
            }
            canvas.drawRoundRect(
                geometry.keyLeft[index], geometry.keyTop[index], geometry.keyRight[index], geometry.keyBottom[index],
                radius, radius, fill,
            )
            if (paints.showKeyBorders) {
                canvas.drawRoundRect(
                    geometry.keyLeft[index], geometry.keyTop[index], geometry.keyRight[index], geometry.keyBottom[index],
                    radius, radius, paints.keyStroke,
                )
            }
            drawLabel(canvas, index)
        }
    }

    private fun drawLabel(canvas: Canvas, index: Int) {
        val length = geometry.labelLength[index]
        if (length == 0) {
            return
        }
        paints.label.textSize = labelTextSize[index]
        canvas.drawText(
            geometry.labelChars, geometry.labelOffset[index], length,
            geometry.centerX[index], geometry.centerY[index] + paints.labelBaselineOffsetPx,
            paints.label,
        )
        // The long-press hint, in the corner. Drawn from the same shared buffer.
        if (geometry.altLength[index] > 0) {
            canvas.drawText(
                geometry.altChars, geometry.altOffset[index], 1,
                geometry.keyRight[index] - (geometry.keyRight[index] - geometry.keyLeft[index]) * 0.22f,
                geometry.keyTop[index] + paints.secondaryBaselineOffsetPx * 2.2f,
                paints.labelSecondary,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("KeyboardCanvasView.onDraw")
        try {
            if (backgroundValid && canvas.isHardwareAccelerated) {
                // A RenderNode's display list belongs to the hardware renderer of the window the
                // view is attached to, and that renderer is destroyed when the view leaves the
                // window. An input view outlives its window -- the framework keeps it and shows
                // it again in the next editor -- so a node recorded during one appearance can
                // come back empty on the next, and `drawRenderNode` on an empty node draws
                // nothing at all. That is invisible in testing until the keys vanish and only
                // the suggestion strip is left, which is exactly how it was found.
                //
                // The check is one boolean read per frame and re-records only after a real loss.
                if (!backgroundNode.hasDisplayList()) {
                    recordBackground(width, height)
                }
                canvas.drawRenderNode(backgroundNode)
            } else {
                // Software canvas: a screenshot, a magnifier, or the theme preview being drawn
                // into a bitmap. Correct, just not the fast path.
                drawStatic(canvas, width.toFloat(), height.toFloat())
            }

            val radius = paints.keyCornerRadiusPx
            for (slot in 0 until PRESS_POOL) {
                val index = pressKey[slot]
                if (index == NO_KEY) {
                    continue
                }
                val progress = pressProgress[slot]
                if (progress <= 0f) {
                    continue
                }
                val lift = paints.pressedElevationPx * progress
                paints.keyPressedFill.alpha = (255 * progress).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(
                    geometry.keyLeft[index] + lift, geometry.keyTop[index] + lift,
                    geometry.keyRight[index] - lift, geometry.keyBottom[index] - lift,
                    radius, radius, paints.keyPressedFill,
                )
                drawLabel(canvas, index)
            }
            paints.keyPressedFill.alpha = 255

            if (gestureActive) {
                drawGestureTrail(canvas)
            }

            if (alternativesKey != NO_KEY) {
                drawAlternatives(canvas)
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawAlternatives(canvas: Canvas) {
        val index = alternativesKey
        val count = geometry.altLength[index]
        if (count == 0) {
            return
        }
        val radius = paints.keyCornerRadiusPx
        canvas.drawRoundRect(
            alternativesLeft, alternativesTop,
            alternativesLeft + alternativesCellWidth * count, alternativesTop + alternativesHeight,
            radius, radius, paints.modifierKeyFill,
        )
        val base = paints.label.textSize
        paints.label.textSize = labelTextSize[index]
        for (position in 0 until count) {
            val left = alternativesLeft + alternativesCellWidth * position
            if (position == alternativesSelection) {
                canvas.drawRoundRect(
                    left, alternativesTop, left + alternativesCellWidth,
                    alternativesTop + alternativesHeight, radius, radius, paints.accent,
                )
            }
            canvas.drawText(
                geometry.altChars, geometry.altOffset[index] + position, 1,
                left + alternativesCellWidth / 2f,
                alternativesTop + alternativesHeight / 2f + paints.labelBaselineOffsetPx,
                paints.label,
            )
        }
        paints.label.textSize = base
    }

    // ---- touch ----------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerIndex = event.actionIndex
                onPointerDown(event.getPointerId(pointerIndex),
                    event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (pointerIndex in 0 until event.pointerCount) {
                    val pointerId = event.getPointerId(pointerIndex)
                    if (gestureActive && pointerId == gesturePointer) {
                        captureGestureSamples(event, pointerIndex)
                    } else {
                        onPointerMove(pointerId, event.getX(pointerIndex),
                            event.getY(pointerIndex), event, pointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                onPointerUp(event.getPointerId(pointerIndex),
                    event.getX(pointerIndex), event.getY(pointerIndex))
            }
            MotionEvent.ACTION_CANCEL -> cancelAllPointers()
        }
        return true
    }

    private fun onPointerDown(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        if (pointerId >= MAX_POINTERS) {
            return
        }
        val index = findKeyAt(x, y)
        if (index == NO_KEY) {
            return
        }
        pointerKey[pointerId] = index
        pointerDownAt[pointerId] = eventTime
        startPress(index)

        // Every press on a letter is a gesture that has not started yet. Recording the origin
        // here costs two floats and means the slop test below needs no extra state.
        if (swipeEnabled && !gestureActive && KeyFlags.has(geometry.keyFlags[index], KeyFlags.LETTER)) {
            gesturePointer = pointerId
            gestureStartX = x
            gestureStartY = y
        }

        if (hapticEnabled) {
            // Needs no VIBRATE permission, which is why the manifest has none.
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        listener?.onKeyDown(geometry.keyCode[index])

        if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.REPEATABLE)) {
            repeatKey = index
            postDelayed(repeatRunnable, REPEAT_DELAY_MILLIS)
        }
        // Armed for every key, not only for keys with alternatives. A key with none offers the
        // hold to the service instead, which is how holding the globe opens the settings panel.
        // The cost is one postDelayed and one removeCallbacks per press, both of which the
        // repeatable keys above were already paying, and neither allocates.
        longPressPointer = pointerId
        postDelayed(longPressRunnable, LONG_PRESS_MILLIS)
    }

    private fun onPointerMove(
        pointerId: Int,
        x: Float,
        y: Float,
        event: MotionEvent,
        pointerIndex: Int,
    ) {
        if (pointerId >= MAX_POINTERS) {
            return
        }
        if (alternativesKey != NO_KEY && pointerId == longPressPointer) {
            updateAlternativesSelection(x)
            return
        }
        val previous = pointerKey[pointerId]
        if (previous == NO_KEY) {
            return
        }

        // A swipe begins when the finger has travelled past the touch slop without lifting.
        // Distance is the only arbiter: a timer would either start a gesture out of a slow tap
        // or refuse one from a fast flick, and the user's intent is in the movement.
        if (swipeEnabled && !gestureActive && pointerId == gesturePointer &&
            KeyFlags.has(geometry.keyFlags[previous], KeyFlags.LETTER)
        ) {
            val dx = x - gestureStartX
            val dy = y - gestureStartY
            if (dx * dx + dy * dy > touchSlop * touchSlop) {
                beginGesture(previous)
                captureGestureSamples(event, pointerIndex)
                return
            }
        }
        val index = findKeyAt(x, y)
        if (index == previous || index == NO_KEY) {
            return
        }
        // The finger slid onto another key before lifting. The previous key is released without
        // being committed, which is what lets someone correct a landing without lifting.
        endPress(previous)
        cancelPendingCallbacks()
        pointerKey[pointerId] = index
        startPress(index)
        run {
            longPressPointer = pointerId
            postDelayed(longPressRunnable, LONG_PRESS_MILLIS)
        }
    }

    private fun onPointerUp(pointerId: Int, x: Float, y: Float) {
        if (pointerId >= MAX_POINTERS) {
            return
        }
        if (gestureActive && pointerId == gesturePointer) {
            finishGesture()
            pointerKey[pointerId] = NO_KEY
            return
        }
        val index = pointerKey[pointerId]
        pointerKey[pointerId] = NO_KEY
        if (index == NO_KEY) {
            return
        }
        endPress(index)

        if (alternativesKey != NO_KEY && pointerId == longPressPointer) {
            commitAlternative(x)
            return
        }
        cancelPendingCallbacks()
        listener?.onKey(geometry.keyCode[index], index)
    }

    private fun cancelAllPointers() {
        if (gestureActive) {
            abandonGesture()
        }
        for (pointerId in 0 until MAX_POINTERS) {
            val index = pointerKey[pointerId]
            if (index != NO_KEY) {
                endPress(index)
                pointerKey[pointerId] = NO_KEY
            }
        }
        cancelPendingCallbacks()
        dismissAlternatives()
    }

    private fun cancelPendingCallbacks() {
        removeCallbacks(longPressRunnable)
        removeCallbacks(repeatRunnable)
        repeatKey = NO_KEY
        longPressPointer = -1
    }

    // ---- long-press alternatives ------------------------------------------------------------------

    private fun onLongPressElapsed() {
        val pointerId = longPressPointer
        if (pointerId < 0 || pointerId >= MAX_POINTERS) {
            return
        }
        val index = pointerKey[pointerId]
        if (index == NO_KEY) {
            return
        }
        if (geometry.altLength[index] == 0) {
            // No alternatives to show, so the hold is offered to the service instead. If it
            // takes it, the press is released here so that lifting the finger does not also
            // type the key that was held.
            if (listener?.onKeyLongPress(geometry.keyCode[index], index) == true) {
                endPress(index)
                pointerKey[pointerId] = NO_KEY
                longPressPointer = -1
            }
            return
        }
        alternativesKey = index
        alternativesSelection = 0

        val count = geometry.altLength[index]
        alternativesCellWidth = max(geometry.keyRight[index] - geometry.keyLeft[index], MIN_ALTERNATIVE_WIDTH_PX)
        alternativesHeight = geometry.keyBottom[index] - geometry.keyTop[index]
        val desiredLeft = geometry.centerX[index] - alternativesCellWidth * count / 2f
        alternativesLeft = desiredLeft.coerceIn(0f, max(0f, width - alternativesCellWidth * count))
        // Above the key normally; below it for the top row, where above is off screen. Drawn
        // inside this view rather than in a PopupWindow: a second window costs a surface, a
        // layout pass and a frame of latency for something that lives for half a second.
        alternativesTop = if (geometry.keyTop[index] - alternativesHeight >= 0f) {
            geometry.keyTop[index] - alternativesHeight
        } else {
            geometry.keyBottom[index]
        }
        invalidate()
    }

    private fun updateAlternativesSelection(x: Float) {
        val index = alternativesKey
        if (index == NO_KEY) {
            return
        }
        val count = geometry.altLength[index]
        val position = ((x - alternativesLeft) / alternativesCellWidth).toInt()
        val clamped = position.coerceIn(0, count - 1)
        if (clamped != alternativesSelection) {
            alternativesSelection = clamped
            invalidate()
        }
    }

    private fun commitAlternative(x: Float) {
        val index = alternativesKey
        if (index != NO_KEY) {
            updateAlternativesSelection(x)
            val position = alternativesSelection
            if (position >= 0 && position < geometry.altLength[index]) {
                listener?.onKey(geometry.altChars[geometry.altOffset[index] + position].code, index)
            }
        }
        dismissAlternatives()
        cancelPendingCallbacks()
    }

    private fun dismissAlternatives() {
        if (alternativesKey != NO_KEY) {
            alternativesKey = NO_KEY
            alternativesSelection = -1
            invalidate()
        }
    }

    // ---- press animation -----------------------------------------------------------------------------

    private fun startPress(index: Int) {
        for (slot in 0 until PRESS_POOL) {
            if (pressKey[slot] == index) {
                pressReleasing[slot] = false
                scheduleFrame()
                return
            }
        }
        for (slot in 0 until PRESS_POOL) {
            if (pressKey[slot] == NO_KEY) {
                pressKey[slot] = index
                pressProgress[slot] = 0f
                pressReleasing[slot] = false
                invalidateKey(index)
                scheduleFrame()
                return
            }
        }
        // Pool full: eleven fingers, or a stuck slot. Dropping the highlight is the right
        // failure -- the key still commits, it just does not light up.
    }

    private fun endPress(index: Int) {
        for (slot in 0 until PRESS_POOL) {
            if (pressKey[slot] == index) {
                pressReleasing[slot] = true
                scheduleFrame()
                return
            }
        }
    }

    private fun scheduleFrame() {
        if (!animating) {
            animating = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun onAnimationFrame(frameTimeNanos: Long) {
        val deltaSeconds = if (lastFrameNanos == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
        }
        lastFrameNanos = frameTimeNanos

        var stillAnimating = false
        for (slot in 0 until PRESS_POOL) {
            val index = pressKey[slot]
            if (index == NO_KEY) {
                continue
            }
            val target = if (pressReleasing[slot]) 0f else 1f
            val rate = if (pressReleasing[slot]) RELEASE_RATE else PRESS_RATE
            val progress = pressProgress[slot]
            val next = if (target > progress) {
                min(target, progress + rate * deltaSeconds)
            } else {
                max(target, progress - rate * deltaSeconds)
            }
            if (next != progress) {
                pressProgress[slot] = next
                invalidateKey(index)
            }
            if (pressReleasing[slot] && next <= 0f) {
                pressKey[slot] = NO_KEY
            } else {
                stillAnimating = true
            }
        }

        if (stillAnimating) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            animating = false
        }
    }

    /**
     * Invalidates one key's rectangle, not the view.
     *
     * The dirty rectangle is deprecated, and it is worth being precise about why it is still
     * here. Under hardware rendering the framework ignores it: the view's display list is
     * re-recorded whole either way, so this buys nothing on a modern device and is kept for the
     * software path and for the intent it records.
     *
     * What actually pays for a cheap frame is the [RenderNode] above. Re-recording this view
     * costs one `drawRenderNode` plus the handful of pressed keys -- not forty rounded
     * rectangles and forty labels -- and that is true whether or not the dirty rectangle is
     * honoured. The lift offset is included in the box so the software path leaves nothing
     * behind.
     */
    @Suppress("DEPRECATION")
    private fun invalidateKey(index: Int) {
        val margin = paints.pressedElevationPx + 2f
        dirtyLeft = (geometry.keyLeft[index] - margin).toInt()
        dirtyTop = (geometry.keyTop[index] - margin).toInt()
        dirtyRight = (geometry.keyRight[index] + margin).toInt() + 1
        dirtyBottom = (geometry.keyBottom[index] + margin).toInt() + 1
        invalidate(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingCallbacks()
        if (animating) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
        }
    }

    companion object {
        const val NO_KEY = -1

        private const val MAX_POINTERS = 16
        private const val PRESS_POOL = 10
        private const val LABEL_WIDTH_FRACTION = 0.82f
        private const val DEFAULT_ROW_HEIGHT_PX = 150f
        private const val MIN_ALTERNATIVE_WIDTH_PX = 96f

        /**
         * Samples one swipe may hold before the buffer is decimated. Five hundred and twelve
         * covers a long word at a high report rate; beyond that the path is oversampled
         * relative to the sixty-four points the decoder reduces it to anyway.
         */
        private const val MIN_GESTURE_POINTS = 6
        private const val TRAIL_SEGMENTS = 4

        private const val LONG_PRESS_MILLIS = 380L
        private const val REPEAT_DELAY_MILLIS = 400L
        private const val REPEAT_INTERVAL_MILLIS = 55L

        /** Progress per second. A press reaches full in about 60 ms, a release fades in 110 ms. */
        private const val PRESS_RATE = 16f
        private const val RELEASE_RATE = 9f
    }
}
