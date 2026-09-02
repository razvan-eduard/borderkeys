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

        /** The clipboard chip was tapped. What is pasted is the service's decision, not ours. */
        fun onClipboardPicked()
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
     * Whether the field behind the keyboard is empty.
     *
     * The idle line is an answer to "what is this row for", which is only a question before the
     * first keystroke. Once there is text, an empty strip means the engine had nothing to offer
     * for this particular word, and telling someone mid-sentence to start typing is worse than
     * saying nothing at all.
     */
    var editorEmpty: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (count == 0) {
                    invalidate()
                }
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

    /**
     * A chip offering what is on the clipboard, or null when there is nothing to offer.
     *
     * Occupies the first slot and pushes the suggestions along. First rather than last because
     * it is the one chip whose content the user already knows they want -- they copied it --
     * and because a chip that moves as the number of suggestions changes is a chip nobody can
     * aim at.
     */
    var clipboardChip: String? = null
        set(value) {
            if (field != value) {
                field = value
                layoutChipText()
                measureSlots()
                invalidate()
            }
        }

    /**
     * The chip's text, laid out over two lines.
     *
     * Two because one is not enough: a copied sentence at the strip's text size runs past its
     * slot and over the suggestion beside it, and shrinking it far enough to fit on one line
     * makes it unreadable. Two lines at a slightly smaller size shows about five words, which
     * is enough to recognise what you copied.
     */
    private val chipLines = Array(CHIP_LINES) { CharArray(MAX_WORD_CHARS) }
    private val chipLineLength = IntArray(CHIP_LINES)
    private var chipLineCount = 0
    private var chipTextSize = 0f

    /**
     * The paste mark, drawn before the text.
     *
     * An icon rather than quotation marks. Quotes read as part of what was copied -- and the
     * first thing anyone asked about this chip was why their text had gained a pair.
     */
    private val pasteIcon: android.graphics.drawable.Drawable? =
        androidx.core.content.ContextCompat.getDrawable(context, com.borderkeys.keyboard.R.drawable.bk_action_paste)

    /** One when the clipboard chip is showing, and it always takes the first slot. */
    private val chipOffset: Int
        get() = if (clipboardChip != null) 1 else 0

    /**
     * The slot holding exactly what was typed, or -1 when nothing does.
     *
     * Marked rather than merely present: the whole value of the verbatim word is knowing, at a
     * glance and without reading, which chip leaves your spelling alone. A reader who has to
     * compare it letter by letter against what they wrote has been given nothing.
     */
    var verbatimIndex: Int = -1
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

    /** Reused so the outline around the verbatim chip allocates nothing on the draw path. */
    private val verbatimRect = android.graphics.RectF()

    /** Fires once per press, at which point the press stops being a tap. */
    private val longPressRunnable = Runnable {
        val index = pressedIndex - chipOffset
        val word = if (index >= 0) words[index] else null
        if (word != null && !actionMode) {
            longPressFired = true
            pressedIndex = -1
            invalidate()
            listener?.onSuggestionLongPressed(index, word)
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
        chipTextSize = base * CHIP_TEXT_SCALE
        layoutChipText()
        for (index in 0 until MAX_SUGGESTIONS) {
            val length = charCount[index]
            if (length == 0) {
                slotTextSize[index] = base
                continue
            }
            slotTextSize[index] = fitted(chars[index], length, base, available)
        }
        paints.label.textSize = base
    }

    /**
     * Breaks the chip's text into at most two lines that fit beside the icon.
     *
     * Word by word, falling back to a hard break for a single word longer than the slot -- a
     * copied URL is one word and would otherwise take the whole chip and still not fit. The
     * last line ends in an ellipsis when there is more text than lines.
     */
    private fun layoutChipText() {
        chipLineCount = 0
        val text = clipboardChip ?: return
        val shown = shownCount()
        if (shown <= 0 || width == 0) {
            return
        }
        val slotWidth = width.toFloat() / shown
        val available = slotWidth - iconSizePx() - CHIP_GAP_PX * 2f
        if (available <= 0f) {
            return
        }
        val paint = paints.label
        val previous = paint.textSize
        paint.textSize = if (chipTextSize > 0f) chipTextSize else previous

        var start = 0
        while (chipLineCount < CHIP_LINES && start < text.length) {
            var end = start
            var lastBreak = -1
            while (end < text.length) {
                if (text[end] == ' ') {
                    lastBreak = end
                }
                if (paint.measureText(text, start, end + 1) > available) {
                    break
                }
                end++
            }
            if (end >= text.length) {
                end = text.length
            } else if (lastBreak > start) {
                end = lastBreak
            } else if (end == start) {
                // One character does not fit; take it anyway rather than loop forever.
                end = start + 1
            }
            var line = text.substring(start, end)
            if (chipLineCount == CHIP_LINES - 1 && end < text.length) {
                line = line.dropLast(1) + "\u2026"
            }
            val length = line.length.coerceAtMost(MAX_WORD_CHARS)
            line.toCharArray(chipLines[chipLineCount], 0, 0, length)
            chipLineLength[chipLineCount] = length
            chipLineCount++
            start = if (end < text.length && text.getOrNull(end) == ' ') end + 1 else end
        }
        paint.textSize = previous
    }

    /** The paste mark is square and sized from the strip, like every other icon here. */
    private fun iconSizePx(): Float = height * CHIP_ICON_FRACTION

    /** [base], shrunk until [length] characters fit in [available], with a floor. */
    private fun fitted(text: CharArray, length: Int, base: Float, available: Float): Float {
        if (length == 0) {
            return base
        }
        paints.label.textSize = base
        val measured = paints.label.measureText(text, 0, length)
        return if (measured <= available || measured <= 0f) {
            base
        } else {
            (base * available / measured).coerceAtLeast(base * MIN_TEXT_SCALE)
        }
    }

    /** Switches the strip to the assistant's actions for the current selection. */
    fun setActions(labels: Array<String?>, count: Int) {
        actionMode = true
        setSuggestions(labels, count)
    }

    fun clear() {
        actionMode = false
        verbatimIndex = -1
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
            if (count == 0 && chipOffset == 0) {
                // An empty strip with nothing drawn in it reads as a dead row rather than an
                // idle one, so say what the row is waiting for -- but only while there is
                // nothing to be about. Suppressed in action mode, where an empty strip means
                // the assistant simply offered nothing.
                if (!actionMode && editorEmpty) {
                    drawNotice(canvas, idleNoticeChars, idleNotice.length)
                }
                return
            }

            val shown = shownCount()
            val slotWidth = width.toFloat() / shown
            val baseline = height / 2f + paints.labelBaselineOffsetPx

            if (chipOffset == 1) {
                if (pressedIndex == 0) {
                    canvas.drawRect(0f, 0f, slotWidth, height.toFloat(), paints.keyPressedFill)
                }
                // Drawn in the accent colour rather than the label colour: it is the one chip
                // that inserts something the user did not type, and it should not be possible
                // to tap it by muscle memory while aiming at a word.
                val paint = paints.accentLabel
                val previous = paint.textSize
                val previousAlign = paint.textAlign
                paint.textSize = chipTextSize
                paint.textAlign = android.graphics.Paint.Align.LEFT

                val icon = pasteIcon
                val side = iconSizePx().toInt()
                var textLeft = CHIP_GAP_PX
                if (icon != null) {
                    val top = ((height - side) / 2f).toInt()
                    icon.setBounds(
                        CHIP_GAP_PX.toInt(), top, CHIP_GAP_PX.toInt() + side, top + side,
                    )
                    icon.setTint(paint.color)
                    icon.draw(canvas)
                    textLeft = CHIP_GAP_PX * 2f + side
                }
                // Both lines centred vertically around the middle of the strip, so a chip with
                // one line and a chip with two sit on the same axis as the words beside them.
                val lineHeight = chipTextSize * CHIP_LINE_SPACING
                val first = height / 2f + paints.labelBaselineOffsetPx -
                    lineHeight * (chipLineCount - 1) / 2f
                for (line in 0 until chipLineCount) {
                    canvas.drawText(
                        chipLines[line], 0, chipLineLength[line],
                        textLeft, first + lineHeight * line, paint,
                    )
                }
                paint.textSize = previous
                paint.textAlign = previousAlign
                if (shown > 1) {
                    canvas.drawLine(slotWidth, height * 0.25f, slotWidth, height * 0.75f,
                        paints.keyStroke)
                }
            }

            for (slot in chipOffset until shown) {
                val index = slot - chipOffset
                val length = charCount[index]
                if (length == 0) {
                    continue
                }
                val left = slotWidth * slot
                // Compared against the drawn slot, not the word index: slotAt returns a slot,
                // and with the clipboard chip present the two differ by one -- which lit the
                // chip next to the one under the finger.
                if (slot == pressedIndex) {
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
                if (index == verbatimIndex) {
                    // A traced outline, not a fill and not another colour. It has to be
                    // distinguishable from the chip beside it without competing with the first
                    // suggestion, which is still the engine's answer and still the one space
                    // would take.
                    verbatimRect.set(
                        left + slotWidth * VERBATIM_INSET,
                        height * VERBATIM_INSET,
                        left + slotWidth * (1f - VERBATIM_INSET),
                        height * (1f - VERBATIM_INSET),
                    )
                    val radius = height * VERBATIM_CORNER
                    canvas.drawRoundRect(verbatimRect, radius, radius, paints.keyStroke)
                }
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

                if (slot > chipOffset) {
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
        if (privateMode || (count == 0 && chipOffset == 0)) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = slotAt(event.x)
                longPressFired = false
                invalidate()
                // The chip has no hold behaviour: there is one thing on the clipboard and one
                // thing to do with it.
                if (pressedIndex > chipOffset - 1 && pressedIndex >= 0) {
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
                pressedIndex = -1
                invalidate()
                if (slot == 0 && chipOffset == 1) {
                    listener?.onClipboardPicked()
                    return true
                }
                val index = slot - chipOffset
                val word = if (index >= 0) words[index] else null
                if (index >= 0 && actionMode) {
                    listener?.onActionPicked(index)
                } else if (word != null) {
                    listener?.onSuggestionPicked(index, word)
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
    /**
     * How many slots are drawn.
     *
     * The chip takes one of the slots the user asked for rather than adding one, so turning it
     * on does not silently narrow every target on the row.
     */
    private fun shownCount(): Int {
        val total = count + chipOffset
        return if (total < visibleLimit) total else visibleLimit
    }

    /** How many of the drawn slots hold words rather than the chip. */
    private fun wordSlots(): Int = shownCount() - chipOffset

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

        /** How far the verbatim outline sits inside its slot, as a fraction of the slot. */
        const val VERBATIM_INSET = 0.06f

        /** Its corner radius, as a fraction of the strip's height. */
        const val VERBATIM_CORNER = 0.22f

        /** How many lines the clipboard chip wraps to, and how far apart they sit. */
        const val CHIP_LINES = 2
        const val CHIP_LINE_SPACING = 1.05f

        /** The chip's text is smaller than a suggestion's: it is a preview, not a candidate. */
        const val CHIP_TEXT_SCALE = 0.62f

        /** The paste mark's share of the strip's height, and the gap around it. */
        const val CHIP_ICON_FRACTION = 0.42f
        const val CHIP_GAP_PX = 10f
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
