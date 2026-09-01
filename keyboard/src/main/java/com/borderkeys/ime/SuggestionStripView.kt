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
) : View(context) {

    interface Listener {
        fun onSuggestionPicked(index: Int, word: String)

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
    private var count = 0

    private var pressedIndex = -1

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
            invalidate()
        }
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
                drawNotice(canvas, privateNoticeChars, PRIVATE_NOTICE.length)
                return
            }
            if (decoding && count == 0) {
                drawNotice(canvas, decodingNoticeChars, DECODING_NOTICE.length)
                return
            }
            if (count == 0) {
                return
            }

            val slotWidth = width.toFloat() / count
            val baseline = height / 2f + paints.labelBaselineOffsetPx
            for (index in 0 until count) {
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
                canvas.drawText(chars[index], 0, length, left + slotWidth / 2f, baseline, paint)

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
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val slot = slotAt(event.x)
                if (slot != pressedIndex) {
                    pressedIndex = slot
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
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
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    private fun slotAt(x: Float): Int {
        if (count == 0) {
            return -1
        }
        val slot = (x / (width.toFloat() / count)).toInt()
        return if (slot in 0 until count) slot else -1
    }

    private val privateNoticeChars =
        CharArray(PRIVATE_NOTICE.length).also { PRIVATE_NOTICE.toCharArray(it, 0, 0, it.size) }
    private val decodingNoticeChars =
        CharArray(DECODING_NOTICE.length).also { DECODING_NOTICE.toCharArray(it, 0, 0, it.size) }

    companion object {
        const val MAX_SUGGESTIONS = 3
        private const val MAX_WORD_CHARS = 48
        private const val HEIGHT_FRACTION = 0.78f
        private const val DEFAULT_HEIGHT_PX = 150f

        /**
         * Deliberately not translatable through resources yet: it is drawn from a fixed
         * `CharArray` on a path that must not allocate, and localisation of the keyboard's own
         * chrome arrives with the settings screen.
         */
        private const val PRIVATE_NOTICE = "Private field — nothing is learned or saved"
        private const val DECODING_NOTICE = "…"
    }
}
