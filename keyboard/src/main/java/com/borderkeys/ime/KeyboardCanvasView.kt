// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.KeyboardPreferences

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Trace
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeProvider
import android.view.View
import android.view.ViewConfiguration
import com.borderkeys.ime.fx.ParticleSurface
import com.borderkeys.ime.fx.RoundedRectElement
import com.borderkeys.theme.ThemePaints
import kotlin.math.max
import kotlin.math.min
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys

/**
 * The typing surface: one [View] that draws the keys and resolves touches from a layout compiled
 * into arrays.
 *
 * `onDraw` and `onTouchEvent` allocate nothing. Unpressed keys and their labels are recorded into
 * a [RenderNode], re-recorded when the theme, layout or size changes; a pressed key is drawn over
 * it.
 */
@SuppressLint("ViewConstructor")
class KeyboardCanvasView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    /** Whether this view paints the surface behind itself; off under [KeyboardHostView]. */
    var drawsBackground: Boolean = true

    /** What the service is told about. Called on the UI thread, inside a touch event. */
    interface Listener {
        /**
         * The key at [keyIndex] typed [code]; it was chosen at ([x], [y]) in this view's pixels,
         * where it was pressed or a slide entered it, or NaN when no tap chose it.
         */
        fun onKey(code: Int, keyIndex: Int, x: Float, y: Float)
        fun onKeyRepeat(code: Int)
        fun onText(text: CharSequence)
        /** Fired on every press so the service can start a prediction early. */
        fun onKeyDown(code: Int)

        /**
         * A completed swipe, as raw touch samples in view pixels. The arrays are reused on the
         * next gesture; the listener consumes or copies them before returning.
         */
        fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int)

        /**
         * The finger paused mid-swipe long enough to open the ring; the samples so far, which are
         * final. The finger is still down. Same array contract as [onGesture].
         */
        fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int)

        /** The finger moved after [onGesturePaused], still down, to [x], [y] in view pixels. */
        fun onGestureSteered(x: Float, y: Float)

        /** The finger lifted while the ring was open. */
        fun onGestureRingResolved()

        /** The touch stream was cancelled while the ring was open. */
        fun onGestureRingCancelled()

        /**
         * A key held down that has no alternatives to show. Returning true consumes the press, so
         * the lift types nothing.
         */
        fun onKeyLongPress(code: Int, keyIndex: Int): Boolean

        /** The space bar was slid sideways by [steps] characters, positive to the right. */
        fun onCursorNudge(steps: Int)

        /** The space bar was slid up or down by [lines] lines, positive downwards. */
        fun onCursorNudgeLines(lines: Int)

        /** The key at [keyIndex] was flicked in [direction], 0 north and clockwise, where it has a flick. */
        fun onFlick(keyIndex: Int, direction: Int)

        /** A drag along backspace moved [steps] characters, negative leftwards: the selection follows. */
        fun onBackspaceSelect(steps: Int)

        /** A drag along backspace moved [lines] lines, positive downwards: the selection follows. */
        fun onBackspaceSelectLines(lines: Int)

        /** The finger lifted after a drag along backspace: what it selected is deleted. */
        fun onBackspaceSelectionLift()
    }

    var listener: Listener? = null
    var hapticEnabled: Boolean = true

    /** Which [HapticFeedbackConstants] class a press plays -- see [HapticStrength]. */
    var hapticConstant: Int = HapticFeedbackConstants.KEYBOARD_TAP

    /**
     * [com.borderkeys.data.theme.KeyboardPreferences.keyPopup]: the pressed key shown enlarged
     * above the finger, drawn by [KeyboardHostView].
     */
    var keyPopupEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    hidePreview()
                }
            }
        }
    var swipeEnabled: Boolean = true

    /** How far a press travels, in key diagonals, before it is a flick, and the most it may and still be one. */
    var flickMinFraction: Float = KeyboardPreferences.DEFAULT_FLICK_MIN_FRACTION
    var flickMaxFraction: Float = KeyboardPreferences.DEFAULT_FLICK_MAX_FRACTION

    /** Whether a drag along backspace selects for the lift to delete. */
    var backspaceSlideEnabled: Boolean = true

    /** Whether the space bar held still steers the caret, and how fast, in percent. */
    var spaceTrackpointEnabled: Boolean = true
    var trackpointSpeedPercent: Int = KeyboardPreferences.DEFAULT_TRACKPOINT_SPEED

    /** Whether a pause mid-swipe is detected, for the ring. */
    var radialMenuEnabled: Boolean = false

    /** How long a real pause must hold before [Listener.onGesturePaused] fires. */
    var radialPauseDwellMillis: Long = DEFAULT_RADIAL_PAUSE_DWELL_MILLIS

    /** How far a swipe must have travelled, in key widths, before a pause counts; 0 for none. */
    var radialMinPathLetters: Float = DEFAULT_RADIAL_MIN_PATH_LETTERS

    /** Whether a press plays the platform's keypress sound. */
    var soundEnabled: Boolean = false

    /** Whether sliding along the space bar moves the cursor instead of typing a space. */
    var spaceCursorEnabled: Boolean = true

    /**
     * How long a key is held before its long press fires, in milliseconds; capped at
     * [LONG_PRESS_MILLIS] for a repeatable key. See [longPressDelayFor].
     */
    var longPressDelayMillis: Long = LONG_PRESS_MILLIS

    /** Whether a key draws the small corner character showing what its long press would type. */
    var holdHintsEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                backgroundValid = false
                invalidate()
            }
        }

    private fun longPressDelayFor(index: Int): Long =
        if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.REPEATABLE)) {
            minOf(longPressDelayMillis, LONG_PRESS_MILLIS)
        } else {
            longPressDelayMillis
        }

    private var layout: KeyboardLayout = KeyboardLayout.fallbackQwerty()

    /** Where every key is, and which key a touch belongs to. */
    private val geometry = KeyboardGeometry()

    /** The average key width of the compiled layout, in pixels; 0 before the first layout. */
    val keyWidthPx: Float get() = geometry.averageKeyWidth

    /** Per-key text size, fixed when the layout is compiled. */
    private var labelTextSize = FloatArray(0)

    // ---- touch state ---------------------------------------------------------------------

    /** Pointer id to key index. */
    private val pointerKey = IntArray(MAX_POINTERS) { NO_KEY }
    private val pointerDownAt = LongArray(MAX_POINTERS)

    /** Where each pointer pressed its key; NaN once it slid onto another key. */
    private val pointerChosenX = FloatArray(MAX_POINTERS)
    private val pointerChosenY = FloatArray(MAX_POINTERS)
    private var touchSlop = 0

    // ---- gesture capture -----------------------------------------------------------------------

    private fun beginGesture(fromKey: Int) {
        gestureActive = true
        hidePreview()
        // The key the swipe started on is released without typing.
        endPress(fromKey)
        pointerKey[gesturePointer] = fromKey
        cancelPendingCallbacks()
        dismissAlternatives()
        invalidateTrail()
    }

    /** Whether key [index] may begin a flick or a swipe: a letter, or a key with flicks, space aside. */
    private fun mayFlickOrSwipe(index: Int): Boolean {
        val flags = geometry.keyFlags[index]
        return geometry.keyCode[index] != ' '.code &&
            ((swipeEnabled && KeyFlags.has(flags, KeyFlags.LETTER)) || KeyFlags.has(flags, KeyFlags.HAS_FLICKS))
    }

    /** Starts following [pointerId] on key [index] from ([x], [y]) at [eventTime], its points kept. */
    private fun armGesture(pointerId: Int, index: Int, x: Float, y: Float, eventTime: Long) {
        gesturePointer = pointerId
        gestureKey = index
        gestureStartX = x
        gestureStartY = y
        gesturePastTap = false
        gestureLeftKey = false
        gesture.begin(x, y, eventTime)
    }

    private fun disarmGesture() {
        gesturePointer = -1
        gestureKey = NO_KEY
        gesturePastTap = false
        gestureLeftKey = false
        gesture.reset()
    }

    /**
     * [FlickClassifier]'s answer for the pending press ending at ([x], [y]): a tap, a swipe, or
     * a direction.
     */
    private fun classifyPress(x: Float, y: Float, eventTime: Long): Int {
        val index = gestureKey
        if (index == NO_KEY) {
            return FlickClassifier.TAP
        }
        return FlickClassifier.classify(
            gestureStartX, gestureStartY, x, y,
            gesture.pathLength(), eventTime - pointerDownAt[gesturePointer],
            geometry.keyRight[index] - geometry.keyLeft[index],
            geometry.keyBottom[index] - geometry.keyTop[index],
            flickMinFraction, gestureLeftKey || gestureActive,
        )
    }

    /** Whether key [index] has a flick in [direction]. */
    private fun hasFlick(index: Int, direction: Int): Boolean =
        direction in 0 until KeyboardLayout.FLICK_DIRECTIONS &&
            geometry.flickLength[index * KeyboardLayout.FLICK_DIRECTIONS + direction] > 0

    /** Captures every sample the motion event carries, the historical ones included. */
    private fun captureGestureSamples(event: MotionEvent, pointerIndex: Int) {
        eventSamples.bind(event, pointerIndex)
        gesture.capture(eventSamples)
        eventSamples.release()
        invalidateTrail()
        if (radialMenuEnabled) {
            updatePauseDetection()
        }
    }

    /**
     * Re-arms the pause timer on every movement larger than [PAUSE_MOVEMENT_EPSILON_PX], once the
     * swipe is eligible for a preview; [firePause] runs when it expires.
     */
    private fun updatePauseDetection() {
        if (gesture.distanceFromPrevious() <= PAUSE_MOVEMENT_EPSILON_PX) {
            return
        }
        removeCallbacks(pauseRunnable)
        val pathLengthPx = kotlin.math.hypot(
            (gesture.maxX - gesture.minX).toDouble(), (gesture.maxY - gesture.minY).toDouble(),
        ).toFloat()
        // radialMinPathLetters in pixels.
        val keyWidthPx = if (geometry.averageKeyWidth > 0f) {
            geometry.averageKeyWidth
        } else {
            FALLBACK_KEY_WIDTH_PX
        }
        val minPathPx = radialMinPathLetters * keyWidthPx
        if (SwipeRadialController.isEligibleForPreview(
                gesture.count, pathLengthPx, MIN_GESTURE_POINTS, minPathPx,
            )
        ) {
            postDelayed(pauseRunnable, radialPauseDwellMillis)
        }
    }

    private fun firePause() {
        if (!gestureActive) {
            return
        }
        ringOpen = true
        listener?.onGesturePaused(gesture.xs, gesture.ys, gesture.times, gesture.count)
    }

    /**
     * Whether the ring is open for the swipe in progress. While true, movement goes to
     * [Listener.onGestureSteered] instead of the capture.
     */
    private var ringOpen = false

    /** Turns a paused stroke that opened no ring back into a captured swipe. */
    fun resumeGestureCapture() {
        if (!gestureActive) {
            return
        }
        ringOpen = false
    }

    /** The system touch slop, in pixels. */
    val touchSlopPx: Float get() = touchSlop.toFloat()

    private val pauseRunnable = Runnable { firePause() }

    /** The samples of the motion event being handled; one instance, rebound on each move. */
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

    private fun finishGesture(x: Float, y: Float, eventTime: Long) {
        removeCallbacks(pauseRunnable)
        val wasRingOpen = ringOpen
        ringOpen = false
        val count = gesture.count
        val key = gestureKey
        val verdict = classifyPress(x, y, eventTime)
        gestureActive = false
        invalidateTrailFully()
        when {
            wasRingOpen -> listener?.onGestureRingResolved()
            verdict == FlickClassifier.SWIPE && count >= MIN_GESTURE_POINTS ->
                listener?.onGesture(gesture.xs, gesture.ys, gesture.times, count)
            verdict >= 0 && key != NO_KEY && hasFlick(key, verdict) -> listener?.onFlick(key, verdict)
            key != NO_KEY && verdict != FlickClassifier.SWIPE ->
                listener?.onKey(geometry.keyCode[key], key, gestureStartX, gestureStartY)
        }
        disarmGesture()
    }

    private fun abandonGesture() {
        removeCallbacks(pauseRunnable)
        val wasRingOpen = ringOpen
        ringOpen = false
        gestureActive = false
        disarmGesture()
        invalidateTrailFully()
        if (wasRingOpen) {
            listener?.onGestureRingCancelled()
        }
    }

    /**
     * Forgets the stroke under a ring the service closed, with no callback: its lift resolves
     * nothing, decodes nothing and types nothing.
     */
    fun abandonRingStroke() {
        removeCallbacks(pauseRunnable)
        ringOpen = false
        if (!gestureActive) {
            return
        }
        gestureActive = false
        if (gesturePointer in 0 until MAX_POINTERS) {
            pointerKey[gesturePointer] = NO_KEY
        }
        disarmGesture()
        invalidateTrailFully()
    }

    /** Repaints only the rectangle the trail occupies. */
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

    /** Draws the trail as [TRAIL_SEGMENTS] polylines, the oldest faintest. */
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

    /** A fixed pool of press states, one per lit key, advanced by the frame callback. */
    private val pressKey = IntArray(PRESS_POOL) { NO_KEY }
    private val pressProgress = FloatArray(PRESS_POOL)
    private val pressReleasing = BooleanArray(PRESS_POOL)
    private var lastFrameNanos = 0L
    private var animating = false

    /** The keys' particle layers: a fill inside the pressed key and a trace of its outline. */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidateParticleBounds() }

    /** The pressed key's rounded rectangle, for the particles; re-pointed on every press. */
    private val keyElement = RoundedRectElement()
    private val particleBoundsScratch = RectF()
    private val particleBoundsScratch2 = RectF()

    // ---- long press ------------------------------------------------------------------------

    private var alternativesKey = NO_KEY
    private var alternativesSelection = -1
    private var alternativesLeft = 0f
    private var alternativesTop = 0f
    private var alternativesCellWidth = 0f
    private var alternativesHeight = 0f
    private var longPressPointer = -1

    /**
     * The room above this view's top edge, inside its host, that the alternatives popup may draw
     * into. Set by [KeyboardHostView] on every layout pass.
     */
    var hostTopInsetPx: Float = 0f

    private var repeatKey = NO_KEY

    /** The pointer whose held backspace keeps deleting whole words. */
    private var longPressRepeatPointer = -1
    private var longPressRepeatCode = 0
    private var longPressRepeatIndex = NO_KEY

    /** The pointer resting on the space bar, and how far it has taken the caret. */
    private var spacePointer = -1
    private var spaceStartX = 0f
    private var spaceStartY = 0f
    private var spaceMovedBy = 0
    private var spaceMovedLines = 0

    /** The pointer resting on backspace, and how far its drag has taken the selection. */
    private var backspacePointer = -1
    private var backspaceStartX = 0f
    private var backspaceStartY = 0f
    private var backspaceMovedBy = 0
    private var backspaceMovedLines = 0

    /** The space bar held as a joystick: armed on the press, live after [Trackpoint.HOLD_MILLIS]. */
    private var trackpointArmed = false
    private var trackpointActive = false
    private var trackpointCentreX = 0f
    private var trackpointCentreY = 0f
    private var trackpointX = 0f
    private var trackpointY = 0f
    private var trackpointPointerId = -1

    private val trackpointDeadZonePx: Float
        get() = Trackpoint.DEAD_ZONE_DP * resources.displayMetrics.density

    private val trackpointArmRunnable = Runnable { startTrackpoint() }
    private val trackpointTickRunnable = object : Runnable {
        override fun run() {
            if (!trackpointActive) {
                return
            }
            val halfDiagonal = if (geometry.keyCount > 0) {
                kotlin.math.hypot(
                    geometry.keyRight[0] - geometry.keyLeft[0], geometry.keyBottom[0] - geometry.keyTop[0],
                ) / 2f
            } else {
                FALLBACK_KEY_WIDTH_PX
            }
            val tick = Trackpoint.tick(
                trackpointX - trackpointCentreX, trackpointY - trackpointCentreY,
                trackpointDeadZonePx, halfDiagonal, trackpointSpeedPercent,
            )
            if (tick.xSteps != 0) {
                listener?.onCursorNudge(tick.xSteps)
            }
            if (tick.ySteps != 0) {
                listener?.onCursorNudgeLines(tick.ySteps)
            }
            postDelayed(this, tick.delayMillis)
        }
    }

    /** The hold on the space bar elapsed with the finger still: the joystick starts where it rests. */
    private fun startTrackpoint() {
        val pointerId = spacePointer
        if (!trackpointArmed || pointerId < 0) {
            return
        }
        trackpointArmed = false
        trackpointActive = true
        trackpointPointerId = pointerId
        trackpointCentreX = trackpointX
        trackpointCentreY = trackpointY
        val key = pointerKey[pointerId]
        if (key != NO_KEY) {
            endPress(key)
        }
        // The lift types nothing, and the press is no longer a slide.
        pointerKey[pointerId] = NO_KEY
        spacePointer = -1
        hidePreview()
        if (hapticEnabled) {
            performHapticFeedback(hapticConstant)
        }
        postDelayed(trackpointTickRunnable, Trackpoint.MAX_DELAY_MILLIS)
    }

    private fun stopTrackpoint() {
        removeCallbacks(trackpointArmRunnable)
        removeCallbacks(trackpointTickRunnable)
        trackpointArmed = false
        trackpointActive = false
    }

    /**
     * Carries a drag along backspace to ([x], [y]): every character step and, when the finger
     * moves mostly up or down, every line step crossed since the last call goes to the listener
     * as a change of selection. The first step un-presses the key and disarms its hold and
     * repeat. Returns whether the press is a drag.
     */
    private fun slideBackspace(pointerId: Int, x: Float, y: Float): Boolean {
        val dx = x - backspaceStartX
        val dy = y - backspaceStartY
        val keyHeight = if (geometry.keyCount > 0) geometry.keyBottom[0] - geometry.keyTop[0] else DEFAULT_ROW_HEIGHT_PX
        val vertical = kotlin.math.abs(dy) > kotlin.math.abs(dx) * BACKSPACE_VERTICAL_RATIO &&
            kotlin.math.abs(dy) > keyHeight * BACKSPACE_VERTICAL_FRACTION
        val wanted = if (vertical) backspaceMovedBy else (dx / spaceStepPx()).toInt()
        val wantedLines = if (vertical) (dy / spaceLineStepPx()).toInt() else backspaceMovedLines
        if (wanted != backspaceMovedBy || wantedLines != backspaceMovedLines) {
            if (backspaceMovedBy == 0 && backspaceMovedLines == 0) {
                removeCallbacks(longPressRunnable)
                removeCallbacks(repeatRunnable)
                repeatKey = NO_KEY
                stopLongPressRepeat()
                val pressed = pointerKey[pointerId]
                pointerKey[pointerId] = NO_KEY
                if (pressed != NO_KEY) {
                    endPress(pressed)
                }
            }
            if (wanted != backspaceMovedBy) {
                listener?.onBackspaceSelect(wanted - backspaceMovedBy)
                backspaceMovedBy = wanted
            }
            if (wantedLines != backspaceMovedLines) {
                listener?.onBackspaceSelectLines(wantedLines - backspaceMovedLines)
                backspaceMovedLines = wantedLines
            }
        }
        return backspaceMovedBy != 0 || backspaceMovedLines != 0
    }

    /** How far the finger travels for one character: [SPACE_STEP_FRACTION] of a key's width. */
    private fun spaceStepPx(): Float {
        val width = if (geometry.keyCount > 0) {
            geometry.keyRight[0] - geometry.keyLeft[0]
        } else {
            0f
        }
        return if (width > 0f) width * SPACE_STEP_FRACTION else DEFAULT_SPACE_STEP_PX
    }

    /**
     * Carries the slide along the space bar to ([x], [y]): every character step and line step
     * crossed since the last call goes to the listener. Returns whether the press is a slide,
     * which the first step makes it, un-pressing the key and disarming the hold armed for it.
     */
    private fun slideSpaceBar(pointerId: Int, x: Float, y: Float): Boolean {
        val wanted = ((x - spaceStartX) / spaceStepPx()).toInt()
        val wantedLines = ((y - spaceStartY) / spaceLineStepPx()).toInt()
        if (wanted != spaceMovedBy || wantedLines != spaceMovedLines) {
            if (spaceMovedBy == 0 && spaceMovedLines == 0) {
                removeCallbacks(longPressRunnable)
                val pressed = pointerKey[pointerId]
                pointerKey[pointerId] = NO_KEY
                if (pressed != NO_KEY) {
                    endPress(pressed)
                }
            }
            if (wanted != spaceMovedBy) {
                listener?.onCursorNudge(wanted - spaceMovedBy)
                spaceMovedBy = wanted
            }
            if (wantedLines != spaceMovedLines) {
                listener?.onCursorNudgeLines(wantedLines - spaceMovedLines)
                spaceMovedLines = wantedLines
            }
        }
        return spaceMovedBy != 0 || spaceMovedLines != 0
    }

    /** How far the finger travels up or down the space bar for one line: most of a key row. */
    private fun spaceLineStepPx(): Float {
        val height = if (geometry.keyCount > 0) {
            geometry.keyBottom[0] - geometry.keyTop[0]
        } else {
            0f
        }
        return if (height > 0f) height * SPACE_LINE_STEP_FRACTION else DEFAULT_SPACE_LINE_STEP_PX
    }

    // ---- gesture capture ---------------------------------------------------------------------

    /** The virtual view hierarchy a screen reader explores, built from [geometry]. */
    private val accessibility = KeyboardAccessibility(this, geometry, strings).apply {
        listener = object : KeyboardAccessibility.Listener {
            // A key the reader activated goes where a completed tap goes, with no point.
            override fun onAccessibilityKey(code: Int, keyIndex: Int) {
                this@KeyboardCanvasView.listener?.onKey(code, keyIndex, Float.NaN, Float.NaN)
            }

            override fun onAccessibilityFlick(keyIndex: Int, direction: Int) {
                this@KeyboardCanvasView.listener?.onFlick(keyIndex, direction)
            }
        }
    }

    /** The points of the swipe in progress, in preallocated arrays. */
    private val gesture = GestureCapture()

    /** Rebound on every move event; see [EventSamples]. */
    private val eventSamples = EventSamples()

    private var gesturePointer = -1
    private var gestureActive = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    /** The key the pending press began on, while [gesturePointer] may still flick or swipe. */
    private var gestureKey = NO_KEY

    /** Whether the pending press has moved past the tap distance, and whether it has left the key. */
    private var gesturePastTap = false
    private var gestureLeftKey = false


    /** One Path per trail segment, recycled with `rewind()`. */
    private val trailPaths = Array(TRAIL_SEGMENTS) { Path() }

    // ---- reused scratch ----------------------------------------------------------------------

    private val backgroundNode = RenderNode("borderkeys-static")
    private var backgroundValid = false
    private var dirtyLeft = 0
    private var dirtyTop = 0
    private var dirtyRight = 0
    private var dirtyBottom = 0

    /** The scheduled callbacks, one instance each. */
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
    private val longPressRepeatRunnable = object : Runnable {
        override fun run() {
            if (longPressRepeatPointer == -1) {
                return
            }
            listener?.onKeyLongPress(longPressRepeatCode, longPressRepeatIndex)
            postDelayed(this, LONG_PRESS_REPEAT_INTERVAL_MILLIS)
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
            // Drops press states whose key index the new layout does not have.
            for (slot in 0 until PRESS_POOL) {
                if (pressKey[slot] != NO_KEY && pressKey[slot] >= geometry.keyCount) {
                    pressKey[slot] = NO_KEY
                }
            }
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

    /** [KeyboardGeometry.exportAliases]. */
    fun exportAliases(codesOut: IntArray, basesOut: IntArray): Int = geometry.exportAliases(codesOut, basesOut)

    /** The id of the layout on the keys. */
    val layoutId: String get() = layout.id

    /** Where taps land on each letter key, drawn over the keys; null draws nothing. */
    var touchGlows: TouchGlows? = null
        set(value) {
            field = value
            invalidate()
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowCodes = IntArray(MAX_GLOW_KEYS)
    private val glowXs = FloatArray(MAX_GLOW_KEYS)
    private val glowYs = FloatArray(MAX_GLOW_KEYS)

    /** Each letter key's glow from [glows], or the faint default circle for a key without one. */
    private fun drawTouchGlows(canvas: Canvas, glows: TouchGlows) {
        val count = geometry.exportGeometry(glowCodes, glowXs, glowYs)
        val width = geometry.averageKeyWidth
        val height = geometry.averageKeyHeight
        if (count <= 0 || width <= 0f || height <= 0f) {
            return
        }
        for (i in 0 until count) {
            val slot = glows.codes.indexOf(glowCodes[i])
            val strength = if (slot >= 0) glows.strength[slot] else 0f
            if (strength > 0f) {
                drawGlow(
                    canvas,
                    glowXs[i] + glows.meanX[slot] * width,
                    glowYs[i] + glows.meanY[slot] * height,
                    GlowEllipse.of(
                        glows.varianceX[slot] * width * width,
                        glows.varianceY[slot] * height * height,
                        glows.covariance[slot] * width * height,
                        MIN_GLOW_RADIUS_PX,
                    ),
                    GLOW_ALPHA * strength,
                    glows.color,
                )
            } else {
                val spread = DEFAULT_GLOW_SPREAD * DEFAULT_GLOW_SPREAD
                drawGlow(
                    canvas, glowXs[i], glowYs[i],
                    GlowEllipse.of(spread * width * width, spread * height * height, 0f, MIN_GLOW_RADIUS_PX),
                    FAINT_GLOW_ALPHA, glows.color,
                )
            }
        }
    }

    /** A soft glow at ([x], [y]), fading to nothing at [GLOW_SIGMAS] times [ellipse]'s radii. */
    private fun drawGlow(canvas: Canvas, x: Float, y: Float, ellipse: GlowEllipse, alpha: Float, color: Int) {
        val centre = (color and 0x00FFFFFF) or ((alpha * 255f).toInt().coerceIn(0, 255) shl 24)
        glowPaint.shader = RadialGradient(
            0f, 0f, 1f, intArrayOf(centre, color and 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(ellipse.degrees)
        canvas.scale(GLOW_SIGMAS * ellipse.major, GLOW_SIGMAS * ellipse.minor)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    /** Average key size, for the native engine. Zero before the first layout pass. */
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

    /** Routes a screen reader's hover events to the virtual view under them. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /**
     * Compiles the layout into the arrays everything else reads, on a size, layout or theme
     * change. Reallocates only when the key count changed.
     */
    private fun compile(viewWidth: Int, viewHeight: Int) {
        geometry.compile(layout, viewWidth.toFloat(), viewHeight.toFloat(), paints.keyGapPx)
        if (labelTextSize.size != geometry.keyCount) {
            labelTextSize = FloatArray(geometry.keyCount)
        }
        measureLabels()
        recordBackground(viewWidth, viewHeight)
        accessibility.onGeometryChanged()
    }

    /** Fixes each label's text size, shrinking a label wider than its key to fit. */
    private fun measureLabels() {
        val themeSize = paints.label.textSize
        val base = themeSize
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
        paints.label.textSize = themeSize
    }

    /** The key at ([x], [y]), from the compiled geometry. */
    fun findKeyAt(x: Float, y: Float): Int = geometry.findKeyAt(x, y)

    /** The code of the key at [index], or [KeyCodes.NONE] outside the layout. */
    fun keyCodeAt(index: Int): Int =
        if (index in 0 until geometry.keyCount) geometry.keyCode[index] else KeyCodes.NONE

    /** The flick label the layout gives key [index] in [direction], "" for none. */
    fun flickTextAt(index: Int, direction: Int): String =
        if (index in 0 until geometry.keyCount) geometry.flickLabel(index, direction) else ""

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
        if (drawsBackground) {
            paints.backgroundPainter.draw(canvas, viewWidth, viewHeight)
        }
        val radius = paints.keyCornerRadiusPx
        for (index in 0 until geometry.keyCount) {
            val code = geometry.keyCode[index]
            val fill = if ((code == KeyCodes.CONTROL && controlArmed) || (code == KeyCodes.ALT && altArmed) ||
                code == armedAccent
            ) {
                paints.keyPressedFill
            } else if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.MODIFIER) ||
                KeyFlags.has(geometry.keyFlags[index], KeyFlags.SECONDARY_ROW)
            ) {
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
            if (geometry.keyCode[index] == KeyCodes.SHIFT && shiftState == ShiftState.LOCKED) {
                drawShiftLockLed(canvas, index)
            }
        }
    }

    /** Whether shift is off, on for one letter, or locked; the labels are drawn to match. */
    var shiftState: Int = ShiftState.OFF
        set(value) {
            if (field != value) {
                field = value
                // The labels are in the recorded background layer.
                backgroundValid = false
                invalidate()
            }
        }

    /** A reused one-character buffer for upper-casing a label. */
    private val shiftedLabel = CharArray(1)

    /** Control and alt armed for the next key; the armed key is drawn pressed until it comes. */
    private var controlArmed = false
    private var altArmed = false

    fun setArmedModifiers(control: Boolean, alt: Boolean) {
        if (controlArmed == control && altArmed == alt) {
            return
        }
        controlArmed = control
        altArmed = alt
        backgroundValid = false
        invalidate()
    }

    /** The dead key or compose key waiting, drawn pressed until it is spent; [KeyCodes.NONE] for none. */
    private var armedAccent = KeyCodes.NONE

    fun setArmedAccent(code: Int) {
        if (armedAccent == code) {
            return
        }
        armedAccent = code
        backgroundValid = false
        invalidate()
    }

    /** The caps-lock light on the shift key, in a fixed green. */
    private val shiftLockLedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF43A047.toInt()
        style = Paint.Style.FILL
    }

    private fun drawShiftLockLed(canvas: Canvas, index: Int) {
        val width = geometry.keyRight[index] - geometry.keyLeft[index]
        val height = geometry.keyBottom[index] - geometry.keyTop[index]
        val radius = width * LED_RADIUS_FRACTION
        val cx = geometry.keyRight[index] - width * LED_INSET_FRACTION
        val cy = geometry.keyTop[index] + height * LED_INSET_FRACTION
        canvas.drawCircle(cx, cy, radius, shiftLockLedPaint)
    }

    /**
     * Draws the flick labels around the key's edges and corners, north first and clockwise; the
     * north-east corner is left to the hold hint when the key has one.
     */
    private fun drawFlickLabels(canvas: Canvas, index: Int) {
        if (!KeyFlags.has(geometry.keyFlags[index], KeyFlags.HAS_FLICKS)) {
            return
        }
        val left = geometry.keyLeft[index] + paints.hintCellHalfWidthPx
        val right = geometry.keyRight[index] - paints.hintCellHalfWidthPx
        val centreX = geometry.centerX[index]
        val top = geometry.keyTop[index] + paints.hintCellTopInsetPx
        val middle = geometry.centerY[index] + paints.hint.textSize * FLICK_MIDDLE_BASELINE_FRACTION
        val bottom = geometry.keyBottom[index] - paints.hint.textSize * FLICK_BOTTOM_INSET_FRACTION
        val holdHint = holdHintsEnabled && (geometry.altLength[index] > 0 || holdsAMenu(geometry.keyCode[index]))
        for (direction in 0 until KeyboardLayout.FLICK_DIRECTIONS) {
            val slot = index * KeyboardLayout.FLICK_DIRECTIONS + direction
            val length = geometry.flickLength[slot]
            if (length == 0 || (direction == KeyFlick.NORTH_EAST && holdHint)) {
                continue
            }
            val x = when (direction) {
                KeyFlick.NORTH, KeyFlick.SOUTH -> centreX
                KeyFlick.NORTH_EAST, KeyFlick.EAST, KeyFlick.SOUTH_EAST -> right
                else -> left
            }
            val y = when (direction) {
                KeyFlick.NORTH, KeyFlick.NORTH_EAST, KeyFlick.NORTH_WEST -> top
                KeyFlick.EAST, KeyFlick.WEST -> middle
                else -> bottom
            }
            canvas.drawText(geometry.flickChars, geometry.flickOffset[slot], length, x, y, paints.hint)
        }
    }

    private fun drawLabel(canvas: Canvas, index: Int) {
        drawHoldHint(canvas, index)
        drawFlickLabels(canvas, index)
        val length = geometry.labelLength[index]
        if (length == 0) {
            return
        }
        paints.label.textSize = labelTextSize[index]
        // A one-letter label is upper-cased as it is drawn while shift is on.
        val chars = if (shiftState != ShiftState.OFF && length == 1 &&
            Character.isLowerCase(geometry.labelChars[geometry.labelOffset[index]])
        ) {
            shiftedLabel[0] =
                Character.toUpperCase(geometry.labelChars[geometry.labelOffset[index]])
            shiftedLabel
        } else {
            geometry.labelChars
        }
        val offset = if (chars === shiftedLabel) 0 else geometry.labelOffset[index]
        canvas.drawText(
            chars, offset, length,
            geometry.centerX[index], geometry.centerY[index] + paints.labelBaselineOffsetPx,
            paints.label,
        )
    }

    /**
     * Draws in the key's corner what holding it does: its first alternative, or three dots for a
     * key whose hold opens something ([holdsAMenu]).
     */
    private fun drawHoldHint(canvas: Canvas, index: Int) {
        if (!holdHintsEnabled) {
            return
        }
        // The hint's cell sits in the key's top-right corner.
        val hintX = geometry.keyRight[index] - paints.hintCellHalfWidthPx
        val hintY = geometry.keyTop[index] + paints.hintCellTopInsetPx
        if (geometry.altLength[index] > 0) {
            canvas.drawText(
                geometry.altChars, geometry.altOffset[index], 1, hintX, hintY,
                paints.hint,
            )
            return
        }
        if (!holdsAMenu(geometry.keyCode[index])) {
            return
        }
        val radius = paints.hint.textSize * HINT_DOT_RADIUS_FRACTION
        val gap = radius * 3f
        val centreY = hintY - paints.hint.textSize * HINT_DOT_CENTRE_FRACTION
        for (dot in -1..1) {
            canvas.drawCircle(hintX + dot * gap, centreY, radius, paints.hint)
        }
    }

    /** The keys whose hold opens something rather than typing something. */
    private fun holdsAMenu(code: Int): Boolean =
        code == KeyCodes.ENTER || code == KeyCodes.LANGUAGE || code == KeyCodes.SETTINGS ||
            code == KeyCodes.KEYBOARD_PICKER || code == KeyCodes.VOICE || code == ' '.code ||
            DeadKeys.isDead(code)

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("KeyboardCanvasView.onDraw")
        try {
            if (backgroundValid && canvas.isHardwareAccelerated) {
                // Re-records the node when its display list was lost with a previous window.
                if (!backgroundNode.hasDisplayList()) {
                    recordBackground(width, height)
                }
                canvas.drawRenderNode(backgroundNode)
            } else {
                // A software canvas draws the static layer directly.
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

            touchGlows?.let { drawTouchGlows(canvas, it) }

            if (gestureActive) {
                drawGestureTrail(canvas)
            }

            particles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    // ---- long-press alternatives, read by KeyboardHostView -----------------------------------
    //
    // Drawn by KeyboardHostView, which offsets these local coordinates by this view's left/top.

    /** Whether the popup is up at all. */
    val alternativesVisible: Boolean get() = alternativesKey != NO_KEY

    /** How many alternatives the held key has. 0 when [alternativesVisible] is false. */
    val alternativesCount: Int get() = if (alternativesKey == NO_KEY) 0 else geometry.altLength[alternativesKey]

    /** Which of [alternativesCount] the finger is currently over, or -1. */
    val alternativesSelectedIndex: Int get() = alternativesSelection

    val alternativesLeftPx: Float get() = alternativesLeft
    val alternativesTopPx: Float get() = alternativesTop
    val alternativesCellWidthPx: Float get() = alternativesCellWidth
    val alternativesRowHeightPx: Float get() = alternativesHeight

    /** The held key's label size. 0 when [alternativesVisible] is false. */
    val alternativesTextSizePx: Float get() = if (alternativesKey == NO_KEY) 0f else labelTextSize[alternativesKey]

    /** The alternative at [position], already cased for the current shift state. */
    fun alternativeCharAt(position: Int): Char = altCharAt(alternativesKey, position)

    /** An alternative, upper-cased while shift is on. */
    private fun altCharAt(index: Int, position: Int): Char {
        val character = geometry.altChars[geometry.altOffset[index] + position]
        return if (shiftState != ShiftState.OFF && Character.isLowerCase(character)) {
            Character.toUpperCase(character)
        } else {
            character
        }
    }

    // ---- the key preview, read by KeyboardHostView --------------------------------------------
    //
    // The pressed key enlarged above the finger, drawn by KeyboardHostView from these local
    // coordinates.

    private var previewKey = NO_KEY
    private var previewPointer = -1

    /** Whether a key is being previewed. False while the alternatives popup owns the space. */
    val keyPreviewVisible: Boolean get() = previewKey != NO_KEY

    val keyPreviewWidthPx: Float
        get() {
            val index = previewKey
            if (index == NO_KEY) return 0f
            val keyWidth = geometry.keyRight[index] - geometry.keyLeft[index]
            val keyHeight = geometry.keyBottom[index] - geometry.keyTop[index]
            return max(keyWidth * KEY_PREVIEW_WIDTH_SCALE, keyHeight)
        }

    val keyPreviewHeightPx: Float
        get() {
            val index = previewKey
            if (index == NO_KEY) return 0f
            return (geometry.keyBottom[index] - geometry.keyTop[index]) * KEY_PREVIEW_HEIGHT_SCALE
        }

    val keyPreviewLeftPx: Float
        get() {
            val index = previewKey
            if (index == NO_KEY) return 0f
            val width = keyPreviewWidthPx
            return (geometry.centerX[index] - width / 2f).coerceIn(0f, max(0f, this.width - width))
        }

    /** Above the key, or below it where the host has no room above. */
    val keyPreviewTopPx: Float
        get() {
            val index = previewKey
            if (index == NO_KEY) return 0f
            val keyHeight = geometry.keyBottom[index] - geometry.keyTop[index]
            val gap = keyHeight * KEY_PREVIEW_GAP_FRACTION
            val above = geometry.keyTop[index] - gap - keyPreviewHeightPx
            return if (above + hostTopInsetPx >= 0f) above else geometry.keyBottom[index] + gap
        }

    /** The previewed key's label size, enlarged by [KEY_PREVIEW_TEXT_SCALE]. */
    val keyPreviewTextSizePx: Float
        get() = if (previewKey == NO_KEY) 0f else labelTextSize[previewKey] * KEY_PREVIEW_TEXT_SCALE

    /** Fills [out] with the previewed key's label as drawn, and returns its length, or 0. */
    fun keyPreviewLabel(out: CharArray): Int {
        val index = previewKey
        if (index == NO_KEY) return 0
        val length = geometry.labelLength[index].coerceAtMost(out.size)
        val offset = geometry.labelOffset[index]
        for (position in 0 until length) {
            out[position] = geometry.labelChars[offset + position]
        }
        if (shiftState != ShiftState.OFF && length == 1 && Character.isLowerCase(out[0])) {
            out[0] = Character.toUpperCase(out[0])
        }
        return length
    }

    /** Whether a key is previewed: any labelled key but a modifier, repeatable, space or enter. */
    private fun previewable(index: Int): Boolean {
        val flags = geometry.keyFlags[index]
        val code = geometry.keyCode[index]
        return geometry.labelLength[index] > 0 &&
            !KeyFlags.has(flags, KeyFlags.MODIFIER) &&
            !KeyFlags.has(flags, KeyFlags.REPEATABLE) &&
            code != KeyCodes.SPACE && code != KeyCodes.ENTER
    }

    private fun showPreview(index: Int, pointerId: Int) {
        if (!keyPopupEnabled || !previewable(index)) {
            hidePreview()
            return
        }
        previewKey = index
        previewPointer = pointerId
        invalidateAlternatives()
    }

    private fun hidePreview() {
        if (previewKey == NO_KEY) {
            return
        }
        previewKey = NO_KEY
        previewPointer = -1
        invalidateAlternatives()
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
                        if (ringOpen) {
                            listener?.onGestureSteered(
                                event.getX(pointerIndex), event.getY(pointerIndex),
                            )
                        } else {
                            captureGestureSamples(event, pointerIndex)
                        }
                    } else {
                        onPointerMove(pointerId, event.getX(pointerIndex),
                            event.getY(pointerIndex), event, pointerIndex)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                onPointerUp(event.getPointerId(pointerIndex),
                    event.getX(pointerIndex), event.getY(pointerIndex), event.eventTime)
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
        pointerChosenX[pointerId] = x
        pointerChosenY[pointerId] = y
        startPress(index)
        showPreview(index, pointerId)

        // A press that may flick or swipe is followed from here, its points kept.
        if (!gestureActive && gesturePointer == -1 && mayFlickOrSwipe(index)) {
            armGesture(pointerId, index, x, y, eventTime)
        }

        if (hapticEnabled) {
            performHapticFeedback(hapticConstant)
        }
        if (soundEnabled) {
            // Silent when the system's touch sounds are off.
            playSoundEffect(android.view.SoundEffectConstants.CLICK)
        }
        // A press on the space bar records where a caret slide would start.
        val onSpace = geometry.keyCode[index] == ' '.code
        if ((spaceCursorEnabled || spaceTrackpointEnabled) &&
            KeyFlags.has(geometry.keyFlags[index], KeyFlags.REPEATABLE).not() && onSpace
        ) {
            spacePointer = pointerId
            spaceStartX = x
            spaceStartY = y
            spaceMovedBy = 0
            spaceMovedLines = 0
        }
        // A press on backspace records where a selecting drag would start.
        if (backspaceSlideEnabled && geometry.keyCode[index] == KeyCodes.DELETE &&
            !KeyFlags.has(geometry.keyFlags[index], KeyFlags.HAS_FLICKS)
        ) {
            backspacePointer = pointerId
            backspaceStartX = x
            backspaceStartY = y
            backspaceMovedBy = 0
            backspaceMovedLines = 0
        }
        listener?.onKeyDown(geometry.keyCode[index])

        if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.REPEATABLE)) {
            repeatKey = index
            postDelayed(repeatRunnable, REPEAT_DELAY_MILLIS)
        }
        if (onSpace && spaceTrackpointEnabled) {
            // The space bar's hold is the joystick; its listener hold is not armed.
            trackpointArmed = true
            trackpointActive = false
            trackpointX = x
            trackpointY = y
            postDelayed(trackpointArmRunnable, Trackpoint.HOLD_MILLIS)
        } else {
            // Armed for every key; one without alternatives offers the hold to the listener.
            longPressPointer = pointerId
            postDelayed(longPressRunnable, longPressDelayFor(index))
        }
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
        // The joystick follows the finger; a drag along backspace selects.
        if (trackpointActive && pointerId == trackpointPointerId) {
            trackpointX = x
            trackpointY = y
            return
        }
        if (pointerId == backspacePointer) {
            slideBackspace(pointerId, x, y)
            return
        }
        // A press on the space bar is a space or a caret slide, never another key.
        if (pointerId == spacePointer) {
            trackpointX = x
            trackpointY = y
            if (trackpointArmed) {
                val dx = x - spaceStartX
                val dy = y - spaceStartY
                if (dx * dx + dy * dy > trackpointDeadZonePx * trackpointDeadZonePx) {
                    removeCallbacks(trackpointArmRunnable)
                    trackpointArmed = false
                }
            }
            if (spaceCursorEnabled) {
                slideSpaceBar(pointerId, x, y)
            }
            return
        }

        val previous = pointerKey[pointerId]
        if (previous == NO_KEY) {
            return
        }

        // A followed press: its points are kept; past the tap distance the hold is off; once it
        // leaves the key a letter's press becomes a swipe, and a key with flicks waits for the lift.
        if (!gestureActive && pointerId == gesturePointer && gestureKey == previous) {
            eventSamples.bind(event, pointerIndex)
            gesture.capture(eventSamples)
            eventSamples.release()
            val keyWidth = geometry.keyRight[previous] - geometry.keyLeft[previous]
            val keyHeight = geometry.keyBottom[previous] - geometry.keyTop[previous]
            if (!gesturePastTap) {
                // A finger moving past the touch slop is not holding the key.
                val dx = x - gestureStartX
                val dy = y - gestureStartY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    removeCallbacks(longPressRunnable)
                    removeCallbacks(repeatRunnable)
                    repeatKey = NO_KEY
                }
                if (FlickClassifier.pastTap(gestureStartX, gestureStartY, x, y, keyWidth, keyHeight, flickMinFraction)) {
                    gesturePastTap = true
                    hidePreview()
                }
            }
            if (!gestureLeftKey && FlickClassifier.leftKey(gesture.pathLength(), keyWidth, keyHeight, flickMaxFraction)) {
                gestureLeftKey = true
            }
            if (!gestureLeftKey) {
                return
            }
            if (swipeEnabled && KeyFlags.has(geometry.keyFlags[previous], KeyFlags.LETTER)) {
                beginGesture(previous)
                if (radialMenuEnabled) {
                    updatePauseDetection()
                }
                return
            }
            if (KeyFlags.has(geometry.keyFlags[previous], KeyFlags.HAS_FLICKS)) {
                return
            }
        }
        val index = findKeyAt(x, y)
        if (index == previous || index == NO_KEY) {
            return
        }
        // The finger slid onto another key: the previous one is released untyped, and a swipe
        // may start from the new one.
        endPress(previous)
        cancelPendingCallbacks()
        pointerKey[pointerId] = index
        pointerChosenX[pointerId] = Float.NaN
        pointerChosenY[pointerId] = Float.NaN
        startPress(index)
        showPreview(index, pointerId)
        if (pointerId == gesturePointer) {
            disarmGesture()
        }
        if (!gestureActive && gesturePointer == -1 && mayFlickOrSwipe(index)) {
            armGesture(pointerId, index, x, y, event.eventTime)
        }
        run {
            longPressPointer = pointerId
            postDelayed(longPressRunnable, longPressDelayFor(index))
        }
    }

    private fun onPointerUp(pointerId: Int, x: Float, y: Float, eventTime: Long) {
        if (pointerId >= MAX_POINTERS) {
            return
        }
        if (pointerId == previewPointer) {
            hidePreview()
        }
        if (pointerId == longPressRepeatPointer) {
            stopLongPressRepeat()
        }
        if (gestureActive && pointerId == gesturePointer) {
            finishGesture(x, y, eventTime)
            pointerKey[pointerId] = NO_KEY
            return
        }
        // A followed press that stayed near its key: a flick where the key has one, else a tap.
        if (pointerId == gesturePointer && gestureKey != NO_KEY && gestureKey == pointerKey[pointerId] &&
            alternativesKey == NO_KEY
        ) {
            val verdict = classifyPress(x, y, eventTime)
            val key = gestureKey
            disarmGesture()
            if (verdict >= 0 && hasFlick(key, verdict)) {
                pointerKey[pointerId] = NO_KEY
                endPress(key)
                cancelPendingCallbacks()
                listener?.onFlick(key, verdict)
                return
            }
        } else if (pointerId == gesturePointer) {
            disarmGesture()
        }
        if (trackpointActive && pointerId == trackpointPointerId) {
            stopTrackpoint()
            trackpointPointerId = -1
            pointerKey[pointerId] = NO_KEY
            return
        }
        if (pointerId == backspacePointer) {
            val dragged = slideBackspace(pointerId, x, y)
            backspacePointer = -1
            backspaceMovedBy = 0
            backspaceMovedLines = 0
            if (dragged) {
                pointerKey[pointerId] = NO_KEY
                listener?.onBackspaceSelectionLift()
                return
            }
        }
        if (pointerId == spacePointer) {
            if (trackpointArmed) {
                removeCallbacks(trackpointArmRunnable)
                trackpointArmed = false
            }
            // The lift's position is the slide's last step.
            val dragged = spaceCursorEnabled && slideSpaceBar(pointerId, x, y)
            spacePointer = -1
            spaceMovedBy = 0
            spaceMovedLines = 0
            if (dragged) {
                // A slide types no space.
                pointerKey[pointerId] = NO_KEY
                return
            }
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
        listener?.onKey(
            geometry.keyCode[index], index, pointerChosenX[pointerId], pointerChosenY[pointerId],
        )
    }

    private fun cancelAllPointers() {
        hidePreview()
        stopTrackpoint()
        trackpointPointerId = -1
        backspacePointer = -1
        spacePointer = -1
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
        stopLongPressRepeat()
    }

    /** Stops a held backspace's continued word-at-a-time deletion. Idempotent. */
    private fun stopLongPressRepeat() {
        if (longPressRepeatPointer == -1) {
            return
        }
        removeCallbacks(longPressRepeatRunnable)
        longPressRepeatPointer = -1
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
            // With no alternatives, the hold goes to the listener; a consumed hold releases the
            // key untyped and cancels its repeat.
            if (listener?.onKeyLongPress(geometry.keyCode[index], index) == true) {
                endPress(index)
                hidePreview()
                pointerKey[pointerId] = NO_KEY
                cancelPendingCallbacks()
                if (KeyFlags.has(geometry.keyFlags[index], KeyFlags.REPEATABLE)) {
                    // A repeatable key repeats the long-press action until the finger lifts.
                    longPressRepeatPointer = pointerId
                    longPressRepeatCode = geometry.keyCode[index]
                    longPressRepeatIndex = index
                    postDelayed(longPressRepeatRunnable, LONG_PRESS_REPEAT_INTERVAL_MILLIS)
                }
            }
            return
        }
        // The popup replaces the preview.
        hidePreview()
        alternativesKey = index
        alternativesSelection = 0

        val count = geometry.altLength[index]
        alternativesCellWidth = max(geometry.keyRight[index] - geometry.keyLeft[index], MIN_ALTERNATIVE_WIDTH_PX)
        alternativesHeight = geometry.keyBottom[index] - geometry.keyTop[index]
        val desiredLeft = geometry.centerX[index] - alternativesCellWidth * count / 2f
        alternativesLeft = desiredLeft.coerceIn(0f, max(0f, width - alternativesCellWidth * count))
        // Above the key, or below it where the host has no room above.
        alternativesTop = if (geometry.keyTop[index] + hostTopInsetPx - alternativesHeight >= 0f) {
            geometry.keyTop[index] - alternativesHeight
        } else {
            geometry.keyBottom[index]
        }
        invalidateAlternatives()
    }

    /** Invalidates this view and its parent, which draws the popup and the preview. */
    private fun invalidateAlternatives() {
        invalidate()
        (parent as? View)?.invalidate()
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
            invalidateAlternatives()
        }
    }

    private fun commitAlternative(x: Float) {
        val index = alternativesKey
        if (index != NO_KEY) {
            updateAlternativesSelection(x)
            val position = alternativesSelection
            if (position >= 0 && position < geometry.altLength[index]) {
                listener?.onKey(altCharAt(index, position).code, index, Float.NaN, Float.NaN)
            }
        }
        dismissAlternatives()
        cancelPendingCallbacks()
    }

    private fun dismissAlternatives() {
        if (alternativesKey != NO_KEY) {
            alternativesKey = NO_KEY
            alternativesSelection = -1
            invalidateAlternatives()
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
                // A newly lit key starts its particles.
                keyElement.set(
                    geometry.keyLeft[index], geometry.keyTop[index],
                    geometry.keyRight[index], geometry.keyBottom[index],
                    paints.keyCornerRadiusPx,
                )
                particles.press(keyElement)
                scheduleFrame()
                return
            }
        }
        // With the pool full, the key is not lit.
    }

    private fun endPress(index: Int) {
        for (slot in 0 until PRESS_POOL) {
            if (pressKey[slot] == index) {
                pressReleasing[slot] = true
                particles.release()
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

    /** Invalidates one key's rectangle, lift offset included. */
    @Suppress("DEPRECATION")
    private fun invalidateKey(index: Int) {
        val margin = paints.pressedElevationPx + 2f
        dirtyLeft = (geometry.keyLeft[index] - margin).toInt()
        dirtyTop = (geometry.keyTop[index] - margin).toInt()
        dirtyRight = (geometry.keyRight[index] + margin).toInt() + 1
        dirtyBottom = (geometry.keyBottom[index] + margin).toInt() + 1
        invalidate(dirtyLeft, dirtyTop, dirtyRight, dirtyBottom)
    }

    /** Invalidates the bounding box of the live particles on both layers. */
    @Suppress("DEPRECATION")
    private fun invalidateParticleBounds() {
        if (!particles.computeLiveBounds(particleBoundsScratch, particleBoundsScratch2)) {
            return
        }
        invalidate(
            (particleBoundsScratch.left - PARTICLE_INVALIDATE_MARGIN_PX).toInt(),
            (particleBoundsScratch.top - PARTICLE_INVALIDATE_MARGIN_PX).toInt(),
            (particleBoundsScratch.right + PARTICLE_INVALIDATE_MARGIN_PX).toInt() + 1,
            (particleBoundsScratch.bottom + PARTICLE_INVALIDATE_MARGIN_PX).toInt() + 1,
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelPendingCallbacks()
        removeCallbacks(pauseRunnable)
        if (animating) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            animating = false
        }
        particles.cancel()
    }

    companion object {
        const val NO_KEY = KeyboardGeometry.NO_KEY

        private const val MAX_POINTERS = 16

        /** The letter keys a glow is drawn for, at most. */
        private const val MAX_GLOW_KEYS = 64

        /** A glow fades to nothing at this many standard deviations. */
        private const val GLOW_SIGMAS = 2f

        /** A glow's opacity at its centre at full strength, and the default circle's. */
        private const val GLOW_ALPHA = 0.8f
        private const val FAINT_GLOW_ALPHA = 0.22f

        /** The default circle's spread in key units, TouchModel::kReferenceSpread. */
        private const val DEFAULT_GLOW_SPREAD = 0.3f

        /** The least radius a glow is drawn with. */
        private const val MIN_GLOW_RADIUS_PX = 2f
        private const val PRESS_POOL = 10

        /** The most fill particles alive at once. */
        private const val FILL_PARTICLE_POOL_CAPACITY = 40

        /** The most outline particles alive at once. */
        private const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

        /** Added on every side of the live particles' bounds when invalidating. */
        private const val PARTICLE_INVALIDATE_MARGIN_PX = 2f
        private const val LABEL_WIDTH_FRACTION = 0.82f
        private const val DEFAULT_ROW_HEIGHT_PX = 150f
        private const val MIN_ALTERNATIVE_WIDTH_PX = 96f

        /** One key width's fraction of finger travel per character of caret movement. */
        private const val SPACE_STEP_FRACTION = 0.55f

        /** A drag along backspace is vertical once it rises more than this times its run, past this fraction of the key's height. */
        private const val BACKSPACE_VERTICAL_RATIO = 0.4f
        private const val BACKSPACE_VERTICAL_FRACTION = 0.4f
        private const val DEFAULT_SPACE_STEP_PX = 56f
        private const val SPACE_LINE_STEP_FRACTION = 0.9f
        private const val DEFAULT_SPACE_LINE_STEP_PX = 120f

        /** Gestures with fewer samples are neither decoded nor checked for a pause. */
        private const val MIN_GESTURE_POINTS = 6
        private const val TRAIL_SEGMENTS = 4

        /** The pause dwell until preferences arrive; same as the preference's default. */
        private const val DEFAULT_RADIAL_PAUSE_DWELL_MILLIS = 200L

        /** Samples closer than this are one point for pause detection. */
        private const val PAUSE_MOVEMENT_EPSILON_PX = 3f

        /** The minimum path until preferences arrive; same as the preference's default. */
        private const val DEFAULT_RADIAL_MIN_PATH_LETTERS = 1f

        /** The key width [radialMinPathLetters] is measured in before the keyboard is laid out. */
        private const val FALLBACK_KEY_WIDTH_PX = 100f

        /** The long-press threshold, shared with [SuggestionStripView]. */
        internal const val LONG_PRESS_MILLIS = 380L

        /** A hint dot's radius, as a fraction of [ThemePaints.hint]'s text size. */
        private const val HINT_DOT_RADIUS_FRACTION = 0.09f

        /** A flick label's baseline on the key's middle row and above its bottom edge, in hint text sizes. */
        private const val FLICK_MIDDLE_BASELINE_FRACTION = 0.35f
        private const val FLICK_BOTTOM_INSET_FRACTION = 0.3f

        /** The key preview's size, label size and gap, relative to the key it enlarges. */
        private const val KEY_PREVIEW_WIDTH_SCALE = 1.4f
        private const val KEY_PREVIEW_HEIGHT_SCALE = 1.15f
        private const val KEY_PREVIEW_TEXT_SCALE = 1.5f
        private const val KEY_PREVIEW_GAP_FRACTION = 0.12f

        /** How far the three-dot cluster's centre sits above the hint baseline, as a fraction of
         *  [ThemePaints.hint]'s text size. */
        private const val HINT_DOT_CENTRE_FRACTION = 0.35f

        /** The lock light's radius and inset, as fractions of the shift key's width and height. */
        private const val LED_RADIUS_FRACTION = 0.08f
        private const val LED_INSET_FRACTION = 0.2f
        private const val REPEAT_DELAY_MILLIS = 400L
        private const val REPEAT_INTERVAL_MILLIS = 55L

        /** How often a held backspace deletes another whole word, after the first. */
        private const val LONG_PRESS_REPEAT_INTERVAL_MILLIS = 130L

        /** Press and release progress per second. */
        private const val PRESS_RATE = 16f
        private const val RELEASE_RATE = 9f
    }
}
