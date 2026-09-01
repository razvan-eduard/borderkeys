// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import com.borderkeys.theme.ThemePaints
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager

/**
 * The band above the keys: three candidates, or a note that nothing is being learned here.
 *
 * A `View` that draws, not a `RecyclerView` with three rows. A recycler brings a layout manager,
 * an adapter, view holders and a diff pass to lay out three pieces of text whose positions are
 * `width / 3` -- and it does that on the frame after every keystroke.
 *
 * Suggestions arrive as strings from JNI, which is the one place a string is unavoidable. They
 * are copied into a preallocated `CharArray` once, on arrival, so the draw path itself creates
 * nothing.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    interface Listener {
        fun onSuggestionPicked(index: Int, word: String)

        /**
         * A suggestion held down rather than tapped.
         *
         * The gesture for "not this one, ever": it offers to forget the word. Held rather than
         * tapped because tapping is how a suggestion is accepted, and the two must not be one
         * slip apart.
         */
        fun onSuggestionLongPressed(index: Int, word: String)

        /**
         * One of the assistant's actions was tapped.
         *
         * The strip carries these because a text selection and a word in progress cannot both
         * exist: while text is selected there is nothing to suggest, so the row is free, and
         * putting the actions where the user is already looking beats a button they must find.
         */
        fun onActionPicked(index: Int)
    }

    /** True while the strip is showing assistant actions rather than word suggestions. */
    var actionMode: Boolean = false
        private set

    var listener: Listener? = null

    /**
     * Shown instead of suggestions when the editor is a password field or has asked for no
     * personalised learning. Visible, because a user is entitled to know when the keyboard has
     * stopped remembering -- and because silence looks identical to the feature being broken.
     */
    var privateMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Shown while a swipe is still being decoded, and only if that takes long enough to notice.
     * A strip that simply goes blank reads as the gesture having been ignored.
     */
    var decoding: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val words = arrayOfNulls<String>(MAX_SUGGESTIONS)
    private val chars = Array(MAX_SUGGESTIONS) { CharArray(MAX_WORD_CHARS) }
    private val charCount = IntArray(MAX_SUGGESTIONS)

    /**
     * The text size each slot is drawn at, so a long word in a narrow slot shrinks instead of
     * running into its neighbour.
     *
     * Computed when the words change, when the strip is resized and when the number of slots
     * changes -- never per frame. It is the same approach the keys use for "?123" on a key one
     * and a half units wide, and for the same reason: measureText on the draw path is a cost
     * paid sixty times a second for an answer that changes when a suggestion arrives.
     */
    private val slotTextSize = FloatArray(MAX_SUGGESTIONS)
    private var count = 0

    private var pressedIndex = -1

    /** Fires once per press, at which point the press stops being a tap. */
    private val longPressRunnable = Runnable {
        val slot = pressedIndex
        val word = if (slot >= 0) words[slot] else null
        if (word != null && !actionMode) {
            longPressFired = true
            pressedIndex = -1
            invalidate()
            listener?.onSuggestionLongPressed(slot, word)
        }
    }

    /** Set when a hold has already acted, so the lift does not also accept the suggestion. */
    private var longPressFired = false

    /**
     * How many slots the user asked for. The engine is asked for this many, so `count` is
     * normally already within it; the clamp is for the frame between the setting changing and
     * the next request coming back.
     */
    var visibleLimit: Int = 3
        set(value) {
            val clamped = value.coerceIn(1, MAX_SUGGESTIONS)
            if (field != clamped) {
                field = clamped
                measureSlots()
                invalidate()
            }
        }

    /**
     * Replaces what is shown. Called on the UI thread from the prediction result callback.
     *
     * Copies rather than retaining the array it is given: the caller reuses that array for the
     * next request, and holding it would mean the strip and the engine race over the same slots.
     */
    fun setSuggestions(source: Array<String?>, sourceCount: Int) {
        if (!actionMode && source === words) {
            return
        }
        val newCount = sourceCount.coerceIn(0, MAX_SUGGESTIONS)
        var changed = newCount != count
        for (index in 0 until newCount) {
            val word = source[index]
            if (word == null) {
                charCount[index] = 0
                words[index] = null
                continue
            }
            if (words[index] != word) {
                changed = true
            }
            words[index] = word
            val length = word.length.coerceAtMost(MAX_WORD_CHARS)
            word.toCharArray(chars[index], 0, 0, length)
            charCount[index] = length
        }
        for (index in newCount until MAX_SUGGESTIONS) {
            words[index] = null
            charCount[index] = 0
        }
        count = newCount
        if (changed) {
            measureSlots()
            invalidate()
        }
    }

    /**
     * Fixes each slot's text size so its word fits between the dividers.
     *
     * Shrinking rather than ellipsising: a suggestion is chosen by reading it, and "differe…"
     * is not something anyone can choose confidently. There is a floor, below which the word is
     * left to overflow -- at that point the slot is too narrow for any legible text and the
     * honest answer is that the count is too high for this screen, which the preview in the
     * settings screen is there to show before it is chosen.
     */
    private fun measureSlots() {
        val base = paints.label.textSize
        val shown = shownCount()
        if (shown <= 0 || width == 0) {
            for (index in 0 until MAX_SUGGESTIONS) {
                slotTextSize[index] = base
            }
            return
        }
        val available = (width.toFloat() / shown) * SLOT_TEXT_FRACTION
        for (index in 0 until MAX_SUGGESTIONS) {
            val length = charCount[index]
            if (length == 0) {
                slotTextSize[index] = base
                continue
            }
            paints.label.textSize = base
            val measured = paints.label.measureText(chars[index], 0, length)
            slotTextSize[index] = if (measured <= available || measured <= 0f) {
                base
            } else {
                (base * available / measured).coerceAtLeast(base * MIN_TEXT_SCALE)
            }
        }
        paints.label.textSize = base
    }

    /** Switches the strip to the assistant's actions for the current selection. */
    fun setActions(labels: Array<String?>, count: Int) {
        actionMode = true
        setSuggestions(labels, count)
    }

    fun clear() {
        actionMode = false
        if (count != 0) {
            count = 0
            for (index in 0 until MAX_SUGGESTIONS) {
                words[index] = null
                charCount[index] = 0
            }
            invalidate()
        }
    }

    /** The top candidate, or null. Used to commit on a space press. */
    fun topSuggestion(): String? = if (count > 0) words[0] else null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureSlots()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rowHeight = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_HEIGHT_PX
        setMeasuredDimension(width, (rowHeight * HEIGHT_FRACTION).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("SuggestionStripView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)

            if (privateMode) {
                drawNotice(canvas, privateNoticeChars, privateNotice.length)
                return
            }
            if (decoding && count == 0) {
                drawNotice(canvas, decodingNoticeChars, DECODING_NOTICE.length)
                return
            }
            if (count == 0) {
                // An empty strip with nothing drawn in it reads as a dead row rather than an
                // idle one, so say what the row is waiting for. Suppressed in action mode,
                // where an empty strip means the assistant simply offered nothing.
                if (!actionMode) {
                    drawNotice(canvas, idleNoticeChars, idleNotice.length)
                }
                return
            }

            val shown = if (count < visibleLimit) count else visibleLimit
            val slotWidth = width.toFloat() / shown
            val baseline = height / 2f + paints.labelBaselineOffsetPx
            for (index in 0 until shown) {
                val length = charCount[index]
                if (length == 0) {
                    continue
                }
                val left = slotWidth * index
                if (index == pressedIndex) {
                    canvas.drawRect(left, 0f, left + slotWidth, height.toFloat(),
                        paints.keyPressedFill)
                }
                // The first candidate is the engine's best guess, so it gets the full label
                // colour and the rest get the secondary one.
                //
                // It is *not* what the space key commits. Space commits what was typed, letter
                // for letter, and a suggestion is applied only when it is tapped -- see
                // handleCharacter in BorderKeysService, where that is the whole point of the
                // delimiter branch. An earlier version of this comment claimed the opposite,
                // which would have described a keyboard that silently rewrites what you wrote:
                // exactly the behaviour this project exists to avoid.
                val paint = if (index == 0) paints.label else paints.labelSecondary
                val previousSize = paint.textSize
                val fitted = slotTextSize[index]
                if (fitted != previousSize) {
                    paint.textSize = fitted
                }
                canvas.drawText(chars[index], 0, length, left + slotWidth / 2f, baseline, paint)
                if (fitted != previousSize) {
                    paint.textSize = previousSize
                }

                if (index > 0) {
                    canvas.drawLine(left, height * 0.25f, left, height * 0.75f, paints.keyStroke)
                }
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawNotice(canvas: Canvas, chars: CharArray, length: Int) {
        canvas.drawText(
            chars, 0, length,
            width / 2f, height / 2f + paints.secondaryBaselineOffsetPx,
            paints.labelSecondary,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (privateMode || count == 0) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = slotAt(event.x)
                longPressFired = false
                invalidate()
                if (pressedIndex >= 0) {
                    postDelayed(longPressRunnable, LONG_PRESS_MILLIS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val slot = slotAt(event.x)
                if (slot != pressedIndex) {
                    // The finger moved to another slot, so the hold starts again from there.
                    removeCallbacks(longPressRunnable)
                    pressedIndex = slot
                    invalidate()
                    if (slot >= 0) {
                        postDelayed(longPressRunnable, LONG_PRESS_MILLIS)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (longPressFired) {
                    longPressFired = false
                    return true
                }
                val slot = slotAt(event.x)
                val word = if (slot >= 0) words[slot] else null
                pressedIndex = -1
                invalidate()
                if (slot >= 0 && actionMode) {
                    listener?.onActionPicked(slot)
                } else if (word != null) {
                    listener?.onSuggestionPicked(slot, word)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                longPressFired = false
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    /** How many slots are on screen. Hit-testing has to agree with drawing, not with `count`. */
    private fun shownCount(): Int = if (count < visibleLimit) count else visibleLimit

    private fun slotAt(x: Float): Int {
        val shown = shownCount()
        if (shown == 0) {
            return -1
        }
        val slot = (x / (width.toFloat() / shown)).toInt()
        return if (slot in 0 until shown) slot else -1
    }

    // Resolved once, here, rather than on every frame: a lookup returns an existing String but
    // copying it into the CharArray onDraw reads does allocate, and onDraw must not. The view is
    // rebuilt when the language changes, so there is nothing to invalidate.
    private val privateNotice = strings[Keys.STRIP_PRIVATE]
    private val privateNoticeChars =
        CharArray(privateNotice.length).also { privateNotice.toCharArray(it, 0, 0, it.size) }
    private val idleNotice = strings[Keys.STRIP_IDLE]
    private val idleNoticeChars =
        CharArray(idleNotice.length).also { idleNotice.toCharArray(it, 0, 0, it.size) }
    private val decodingNoticeChars =
        CharArray(DECODING_NOTICE.length).also { DECODING_NOTICE.toCharArray(it, 0, 0, it.size) }

    companion object {
        /**
         * The most the strip can ever hold, which is what its buffers are sized for. How many
         * are actually shown is [visibleLimit], a setting; this is the ceiling that lets the
         * setting change without reallocating anything.
         */
        const val MAX_SUGGESTIONS = 8
        private const val MAX_WORD_CHARS = 48
        private const val HEIGHT_FRACTION = 0.78f

        /** The same hold the keys use, so the two feel like one gesture. */
        private const val LONG_PRESS_MILLIS = 380L

        /** How much of a slot a word may occupy before it is shrunk, leaving room for a gap. */
        private const val SLOT_TEXT_FRACTION = 0.80f

        /** Past this the text is too small to read, so the word is allowed to overflow instead. */
        private const val MIN_TEXT_SCALE = 0.62f
        private const val DEFAULT_HEIGHT_PX = 150f

        /**
         * Deliberately not translatable through resources yet: it is drawn from a fixed
         * `CharArray` on a path that must not allocate, and localisation of the keyboard's own
         * chrome arrives with the settings screen.
         */
        private const val DECODING_NOTICE = "…"
    }
}
