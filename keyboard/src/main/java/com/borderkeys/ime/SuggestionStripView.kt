// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.Trace
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.fx.ParticleSurface
import com.borderkeys.ime.fx.RoundedRectElement
import com.borderkeys.theme.ThemePaints
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.predict.Candidate

/**
 * The band above the keys: the candidates and the clipboard chip, or a note that nothing is being
 * learned here. Words are copied into preallocated buffers on arrival; drawing allocates nothing.
 */
@SuppressLint("ViewConstructor")
class SuggestionStripView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    /** Whether this view paints the surface behind itself; off under [KeyboardHostView]. */
    var drawsBackground: Boolean = true

    /** Mirrors [com.borderkeys.data.theme.KeyboardPreferences.hapticFeedback]. */
    var hapticEnabled: Boolean = true

    /** The [HapticFeedbackConstants] class a chip plays, the same as the keys'. */
    var hapticConstant: Int = HapticFeedbackConstants.KEYBOARD_TAP

    init {
        isHapticFeedbackEnabled = true
    }

    /** Plays the keys' tap for a chip. */
    private fun tapHaptic() {
        if (hapticEnabled) {
            performHapticFeedback(hapticConstant)
        }
    }

    interface Listener {
        fun onSuggestionPicked(index: Int, word: String)

        /** The private row's Show or Hide was tapped. */
        fun onPrivateRevealToggled()

        /** A suggestion held down rather than tapped. */
        fun onSuggestionLongPressed(index: Int, word: String)

        /** One of the action chips of [actionMode] was tapped. */
        fun onActionPicked(index: Int)

        /** The clipboard chip was tapped. */
        fun onClipboardPicked()

        /** The screenshot chip was tapped. */
        fun onScreenshotPicked()

        /** The screenshot chip held down rather than tapped. */
        fun onScreenshotLongPressed()
    }

    /** True while the strip is showing action chips rather than word suggestions. */
    var actionMode: Boolean = false
        private set

    var listener: Listener? = null

    /** Whether the private row replaces the suggestions: a password field, or no learning asked. */
    var privateMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Whether the private row shows the field's text instead of the notice. */
    var privateReveal: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The field's text, drawn while [privateReveal] is on; copied into a fixed buffer. */
    var privateText: CharSequence? = null
        set(value) {
            field = value
            privateTextLength = 0
            if (value != null) {
                val length = value.length.coerceAtMost(privateTextChars.size)
                for (i in 0 until length) {
                    privateTextChars[i] = value[i]
                }
                privateTextLength = length
            }
            invalidate()
        }

    private val privateTextChars = CharArray(PRIVATE_TEXT_CHARS)
    private var privateTextLength = 0
    private var privateToggleLeft = 0f
    private val revealPaint = android.graphics.Paint(paints.label).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        textAlign = android.graphics.Paint.Align.LEFT
    }

    /** Whether the field behind the keyboard is empty; the idle line shows only then. */
    var editorEmpty: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (count == 0) {
                    invalidate()
                }
            }
        }

    /** A short message shown in place of the row, or null. */
    var notice: String? = null
        set(value) {
            if (field != value) {
                field = value
                noticeChars = value?.toCharArray() ?: CharArray(0)
                invalidate()
            }
        }
    private var noticeChars = CharArray(0)

    /** Shows the decoding notice while a swipe is still being decoded. */
    var decoding: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** A chip offering what is on the clipboard, in the first slots, or null. */
    var clipboardChip: String? = null
        set(value) {
            if (field != value) {
                field = value
                arrangeChips()
            }
        }

    /** A chip offering the newest screenshot, in the first slots, or null. */
    var screenshotChip: String? = null
        set(value) {
            if (field != value) {
                field = value
                arrangeChips()
            }
        }

    /** Whether the screenshot chip comes before the clipboard chip when both show. */
    var screenshotFirst: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                arrangeChips()
            }
        }

    /** A small preview drawn in front of the clipboard chip's icon, or null. */
    var clipboardThumbnail: android.graphics.Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                arrangeChips()
            }
        }

    /** A small preview drawn in front of the screenshot chip's icon, or null. */
    var screenshotThumbnail: android.graphics.Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                arrangeChips()
            }
        }

    /** The chips showing, in slot order, whether each is the screenshot chip, and its preview. */
    private val chipTexts = arrayOfNulls<String>(MAX_CHIPS)
    private val chipIsScreenshot = BooleanArray(MAX_CHIPS)
    private val chipThumbnails = arrayOfNulls<android.graphics.Bitmap>(MAX_CHIPS)
    private var chipCount = 0

    /** The preview's source and destination rectangles, reused. */
    private val thumbnailSource = android.graphics.Rect()
    private val thumbnailTarget = android.graphics.RectF()
    private val thumbnailPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    /** Each chip's text, laid out over [CHIP_LINES] lines. */
    private val chipLines = Array(MAX_CHIPS) { Array(CHIP_LINES) { CharArray(MAX_WORD_CHARS) } }
    private val chipLineLength = Array(MAX_CHIPS) { IntArray(CHIP_LINES) }
    private val chipLineCount = IntArray(MAX_CHIPS)
    private var chipTextSize = 0f

    /** The paste mark, drawn before a chip's text. */
    private val pasteIcon: android.graphics.drawable.Drawable? =
        androidx.core.content.ContextCompat.getDrawable(context, com.borderkeys.keyboard.R.drawable.bk_action_paste)

    /** How many chips are showing; they always take the first slots. */
    private val chipOffset: Int
        get() = chipCount

    /** Orders the chips showing, the screenshot first when [screenshotFirst], and lays them out. */
    private fun arrangeChips() {
        chipCount = 0
        val screenshot = screenshotChip
        val clipboard = clipboardChip
        if (screenshot != null && screenshotFirst) {
            chipTexts[chipCount] = screenshot
            chipThumbnails[chipCount] = screenshotThumbnail
            chipIsScreenshot[chipCount++] = true
        }
        if (clipboard != null) {
            chipTexts[chipCount] = clipboard
            chipThumbnails[chipCount] = clipboardThumbnail
            chipIsScreenshot[chipCount++] = false
        }
        if (screenshot != null && !screenshotFirst) {
            chipTexts[chipCount] = screenshot
            chipThumbnails[chipCount] = screenshotThumbnail
            chipIsScreenshot[chipCount++] = true
        }
        for (chip in chipCount until MAX_CHIPS) {
            chipTexts[chip] = null
            chipThumbnails[chip] = null
        }
        layoutChipText()
        measureSlots()
        invalidate()
    }

    /** The slot holding exactly what was typed, drawn in italic, or -1. */
    var typedIndex: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The slot holding the correction a delimiter would apply, outlined, or -1. */
    var appliedIndex: Int = -1
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val words = arrayOfNulls<String>(MAX_SUGGESTIONS)
    private val chars = Array(MAX_SUGGESTIONS) { CharArray(MAX_WORD_CHARS) }
    private val charCount = IntArray(MAX_SUGGESTIONS)

    /** Each slot's text size, fitted when the words, the size or the slot count change. */
    private val slotTextSize = FloatArray(MAX_SUGGESTIONS)
    private var count = 0

    private var pressedIndex = -1

    /** The applied chip's outline rectangle, reused. */
    private val appliedRect = android.graphics.RectF()

    /**
     * The strip's particle layers: held around the applied chip and the decoding notice, and a
     * burst in a tapped chip.
     */
    val particles = ParticleSurface(FILL_PARTICLE_POOL_CAPACITY, OUTLINE_PARTICLE_POOL_CAPACITY) { invalidate() }

    /** The particles' shapes: the applied chip, the decoding notice's pill and a tapped slot. */
    private val appliedChip = RoundedRectElement()
    private val decodingNotice = RoundedRectElement()
    private val tappedSlot = RoundedRectElement()

    /**
     * Fires once per press, at which point the press stops being a tap: on a word, or on the
     * screenshot chip.
     */
    private val longPressRunnable = Runnable {
        val slot = pressedIndex
        if (slot < 0 || actionMode) {
            return@Runnable
        }
        if (slot < chipOffset) {
            if (chipIsScreenshot[slot]) {
                holdFired()
                listener?.onScreenshotLongPressed()
            }
            return@Runnable
        }
        val index = slot - chipOffset
        val word = words[index] ?: return@Runnable
        holdFired()
        listener?.onSuggestionLongPressed(index, word)
    }

    /** A hold acted: the lift accepts nothing. */
    private fun holdFired() {
        longPressFired = true
        pressedIndex = -1
        invalidate()
        tapHaptic()
    }

    /** Set when a hold has acted; the lift then accepts nothing. */
    private var longPressFired = false

    /** How many slots the user asked for, 1 to [MAX_SUGGESTIONS]. */
    var visibleLimit: Int = 3
        set(value) {
            val clamped = value.coerceIn(1, MAX_SUGGESTIONS)
            if (field != clamped) {
                field = clamped
                measureSlots()
                invalidate()
            }
        }

    /** Replaces what is shown, copying the words. Called on the UI thread. */
    fun setSuggestions(source: List<Candidate>) {
        // A new row leaves action mode and clears the typed and applied marks.
        val wasActionMode = actionMode
        actionMode = false
        typedIndex = -1
        appliedIndex = -1
        val newCount = source.size.coerceIn(0, MAX_SUGGESTIONS)
        var changed = wasActionMode || newCount != count
        for (index in 0 until newCount) {
            val word = source[index].text
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

    /** Fixes each slot's text size so its word fits between the dividers, down to a floor. */
    private fun measureSlots() {
        val label = paints.labelTextSizePx.takeIf { it > 0f } ?: paints.label.textSize
        val base = label * SLOT_TEXT_SCALE
        val shown = shownCount()
        if (shown <= 0 || width == 0) {
            for (index in 0 until MAX_SUGGESTIONS) {
                slotTextSize[index] = base
            }
            return
        }
        val available = (width.toFloat() / shown) * SLOT_TEXT_FRACTION
        chipTextSize = label * CHIP_TEXT_SCALE
        layoutChipText()
        for (index in 0 until MAX_SUGGESTIONS) {
            val length = charCount[index]
            if (length == 0) {
                slotTextSize[index] = base
                continue
            }
            slotTextSize[index] = fitted(chars[index], length, base, available)
        }
        paints.label.textSize = label
    }

    /**
     * Writes into [out] the bounds of the screenshot chip, or of the clipboard chip, in this
     * view's pixels; false when that chip is not showing.
     */
    fun chipBounds(screenshot: Boolean, out: android.graphics.RectF): Boolean {
        val shown = shownCount()
        if (shown <= 0) {
            return false
        }
        val slotWidth = width.toFloat() / shown
        for (chip in 0 until chipCount) {
            if (chipIsScreenshot[chip] == screenshot) {
                val left = slotLeft(chip, slotWidth)
                out.set(left, 0f, left + slotWidth, height.toFloat())
                return true
            }
        }
        return false
    }

    /** Lays out every chip's text; see [layoutChipText]. */
    private fun layoutChipText() {
        for (chip in 0 until MAX_CHIPS) {
            chipLineCount[chip] = 0
        }
        for (chip in 0 until chipCount) {
            layoutChipText(chip)
        }
    }

    /**
     * Breaks chip [chip]'s text into at most [CHIP_LINES] lines beside the icon, word by word, with
     * a hard break for a word longer than a line and an ellipsis when text is left over.
     */
    private fun layoutChipText(chip: Int) {
        val text = chipTexts[chip] ?: return
        val shown = shownCount()
        if (shown <= 0 || width == 0) {
            return
        }
        val slotWidth = width.toFloat() / shown
        val available = slotWidth - iconSizePx() - CHIP_GAP_PX * 2f - thumbnailWidth(chip)
        if (available <= 0f) {
            return
        }
        val paint = paints.label
        val previous = paint.textSize
        paint.textSize = if (chipTextSize > 0f) chipTextSize else previous
        val lines = chipLines[chip]
        val lineLength = chipLineLength[chip]
        var lineCount = 0

        var start = 0
        while (lineCount < CHIP_LINES && start < text.length) {
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
                // A character that does not fit on its own is taken anyway.
                end = start + 1
            }
            var line = text.substring(start, end)
            if (lineCount == CHIP_LINES - 1 && end < text.length) {
                line = line.dropLast(1) + "\u2026"
            }
            val length = line.length.coerceAtMost(MAX_WORD_CHARS)
            line.toCharArray(lines[lineCount], 0, 0, length)
            lineLength[lineCount] = length
            lineCount++
            start = if (end < text.length && text.getOrNull(end) == ' ') end + 1 else end
        }
        chipLineCount[chip] = lineCount
        paint.textSize = previous
    }

    /** The room chip [chip]'s preview takes before its icon, gap included; zero without one. */
    private fun thumbnailWidth(chip: Int): Float =
        if (chipThumbnails[chip] != null) height * CHIP_THUMBNAIL_FRACTION + CHIP_GAP_PX else 0f

    /** Draws [bitmap] centre-cropped into a square of the strip's height, from [left]. */
    private fun drawThumbnail(canvas: Canvas, bitmap: android.graphics.Bitmap, left: Float) {
        val side = height * CHIP_THUMBNAIL_FRACTION
        val top = (height - side) / 2f
        val crop = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - crop) / 2
        val y = (bitmap.height - crop) / 2
        thumbnailSource.set(x, y, x + crop, y + crop)
        thumbnailTarget.set(left, top, left + side, top + side)
        canvas.drawBitmap(bitmap, thumbnailSource, thumbnailTarget, thumbnailPaint)
    }

    /** The paste mark's side, from the strip's height. */
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

    /** Shows action chips instead of words: a held suggestion's Forget, Cancel and Why. */
    fun setActions(labels: List<Candidate>) {
        setSuggestions(labels)
        actionMode = true
        invalidate()
    }

    /** How many slots a row of words can fill: the visible limit less the clipboard chip's. */
    val wordSlotLimit: Int
        get() = (visibleLimit - chipOffset).coerceAtLeast(0)

    fun clear() {
        actionMode = false
        typedIndex = -1
        appliedIndex = -1
        if (count != 0) {
            count = 0
            for (index in 0 until MAX_SUGGESTIONS) {
                words[index] = null
                charCount[index] = 0
            }
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measureSlots()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, paints.suggestionRowHeightPx())
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("SuggestionStripView.onDraw")
        try {
            if (drawsBackground) {
                paints.backgroundPainter.draw(canvas, width.toFloat(), height.toFloat())
            }

            if (privateMode) {
                // No particles on the private row.
                particles.release()
                drawPrivateRow(canvas)
                return
            }
            if (noticeChars.isNotEmpty()) {
                particles.release()
                drawNotice(canvas, noticeChars, noticeChars.size)
                return
            }
            if (decoding && count == 0) {
                syncDecodingAmbient()
                drawNotice(canvas, decodingNoticeChars, DECODING_NOTICE.length)
                particles.draw(canvas, paints.particlePaint)
                return
            }
            if (count == 0 && chipOffset == 0) {
                // The idle line, while the editor is empty and not in action mode.
                particles.release()
                if (!actionMode && editorEmpty) {
                    drawNotice(canvas, idleNoticeChars, idleNotice.length)
                }
                // A burst from the accepted suggestion finishes on the empty row.
                particles.draw(canvas, paints.particlePaint)
                return
            }

            val shown = shownCount()
            val slotWidth = width.toFloat() / shown
            var appliedRectDrawnThisFrame = false

            for (chip in 0 until chipOffset) {
                val chipLeft = slotLeft(chip, slotWidth)
                if (pressedIndex == chip) {
                    canvas.drawRect(chipLeft, 0f, chipLeft + slotWidth, height.toFloat(), paints.keyPressedFill)
                }
                // The chip is drawn in the accent colour.
                val paint = paints.accentLabel
                val previous = paint.textSize
                val previousAlign = paint.textAlign
                paint.textSize = chipTextSize
                paint.textAlign = android.graphics.Paint.Align.LEFT

                // The preview, when there is one, comes first, inside the strip's height.
                val previewWidth = thumbnailWidth(chip)
                chipThumbnails[chip]?.let { drawThumbnail(canvas, it, chipLeft + CHIP_GAP_PX) }
                val icon = pasteIcon
                val side = iconSizePx().toInt()
                var textLeft = chipLeft + CHIP_GAP_PX + previewWidth
                if (icon != null) {
                    val top = ((height - side) / 2f).toInt()
                    val iconLeft = (chipLeft + CHIP_GAP_PX + previewWidth).toInt()
                    icon.setBounds(iconLeft, top, iconLeft + side, top + side)
                    icon.setTint(paint.color)
                    icon.draw(canvas)
                    textLeft = chipLeft + CHIP_GAP_PX * 2f + side + previewWidth
                }
                // The lines are centred vertically on the strip.
                val lineHeight = chipTextSize * CHIP_LINE_SPACING
                val lineCount = chipLineCount[chip]
                val first = height / 2f + paints.labelBaselineOffsetPx -
                    lineHeight * (lineCount - 1) / 2f
                for (line in 0 until lineCount) {
                    canvas.drawText(
                        chipLines[chip][line], 0, chipLineLength[chip][line],
                        textLeft, first + lineHeight * line, paint,
                    )
                }
                paint.textSize = previous
                paint.textAlign = previousAlign
                if (shown > chip + 1) {
                    val edge = if (rightToLeft) chipLeft else chipLeft + slotWidth
                    canvas.drawLine(edge, height * 0.25f, edge, height * 0.75f, paints.keyStroke)
                }
            }

            for (slot in chipOffset until shown) {
                val index = slot - chipOffset
                val length = charCount[index]
                if (length == 0) {
                    continue
                }
                val left = slotLeft(slot, slotWidth)
                // pressedIndex is a slot, not a word index.
                if (slot == pressedIndex) {
                    canvas.drawRect(left, 0f, left + slotWidth, height.toFloat(),
                        paints.keyPressedFill)
                }
                // The correction a delimiter would apply gets the theme's outline or fill.
                if (index == appliedIndex) {
                    appliedRect.set(
                        left + slotWidth * APPLIED_INSET,
                        height * APPLIED_INSET,
                        left + slotWidth * (1f - APPLIED_INSET),
                        height * (1f - APPLIED_INSET),
                    )
                    val radius = height * APPLIED_CORNER
                    canvas.drawRoundRect(appliedRect, radius, radius, paints.appliedHighlight)
                    // Particles are held around the applied chip.
                    appliedChip.set(appliedRect.left, appliedRect.top, appliedRect.right, appliedRect.bottom, radius)
                    particles.hold(appliedChip)
                    appliedRectDrawnThisFrame = true
                }
                // Italic for what was typed, the full label colour for the correction, the
                // secondary colour for the rest.
                val paint = when (index) {
                    typedIndex -> paints.labelTyped
                    appliedIndex -> paints.label
                    else -> paints.labelSecondary
                }
                val previousSize = paint.textSize
                val fitted = slotTextSize[index]
                if (fitted != previousSize) {
                    paint.textSize = fitted
                }
                // Centred on the strip at the size this word is drawn at.
                val wordBaseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
                // A word wider than its slot is clipped to it, drawn from its reading start.
                if (paint.measureText(chars[index], 0, length) > slotWidth) {
                    val previousAlign = paint.textAlign
                    canvas.save()
                    canvas.clipRect(left, 0f, left + slotWidth, height.toFloat())
                    paint.textAlign = if (rightToLeft) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
                    val start = if (rightToLeft) left + slotWidth else left
                    canvas.drawText(chars[index], 0, length, start, wordBaseline, paint)
                    paint.textAlign = previousAlign
                    canvas.restore()
                } else {
                    canvas.drawText(chars[index], 0, length, left + slotWidth / 2f, wordBaseline, paint)
                }
                if (fitted != previousSize) {
                    paint.textSize = previousSize
                }

                if (slot > chipOffset) {
                    val divider = if (rightToLeft) left + slotWidth else left
                    canvas.drawLine(divider, height * 0.25f, divider, height * 0.75f, paints.keyStroke)
                }
            }
            if (!appliedRectDrawnThisFrame) {
                particles.release()
            }
            particles.draw(canvas, paints.particlePaint)
        } finally {
            Trace.endSection()
        }
    }

    /**
     * The private field's row: the notice and a Show, or the field's text and a Hide; text wider
     * than the room shows its end.
     */
    private fun drawPrivateRow(canvas: Canvas) {
        val toggleChars = if (privateReveal) privateHideChars else privateShowChars
        val toggleLength = if (privateReveal) privateHideLabel.length else privateShowLabel.length
        val togglePaint = paints.accentLabel
        val previousAlign = togglePaint.textAlign
        togglePaint.textAlign = android.graphics.Paint.Align.CENTER
        val padding = height * PRIVATE_PADDING_FRACTION
        val toggleWidth = togglePaint.measureText(toggleChars, 0, toggleLength) + 2f * padding
        privateToggleLeft = width - toggleWidth
        val baseline = height / 2f + paints.secondaryBaselineOffsetPx
        canvas.drawText(
            toggleChars, 0, toggleLength, privateToggleLeft + toggleWidth / 2f, baseline,
            togglePaint,
        )
        togglePaint.textAlign = previousAlign
        if (!privateReveal || privateTextLength == 0) {
            canvas.drawText(
                privateNoticeChars, 0, privateNotice.length, privateToggleLeft / 2f, baseline,
                paints.labelSecondary,
            )
            return
        }
        val textWidth = revealPaint.measureText(privateTextChars, 0, privateTextLength)
        val room = privateToggleLeft - 2f * padding
        val x = if (textWidth <= room) padding else privateToggleLeft - padding - textWidth
        canvas.save()
        canvas.clipRect(0f, 0f, privateToggleLeft, height.toFloat())
        canvas.drawText(privateTextChars, 0, privateTextLength, x, baseline, revealPaint)
        canvas.restore()
    }

    private fun drawNotice(canvas: Canvas, chars: CharArray, length: Int) {
        canvas.drawText(
            chars, 0, length,
            width / 2f, height / 2f + paints.secondaryBaselineOffsetPx,
            paints.labelSecondary,
        )
    }

    /** Holds particles in an undrawn pill around the decoding notice's glyph. */
    private fun syncDecodingAmbient() {
        val centerX = width / 2f
        val halfWidth = width * DECODING_GLOW_WIDTH_FRACTION / 2f
        val top = height * 0.25f
        val bottom = height * 0.75f
        decodingNotice.set(centerX - halfWidth, top, centerX + halfWidth, bottom, (bottom - top) / 2f)
        particles.hold(decodingNotice)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (privateMode) {
            // The row holds one control, the Show or Hide at its right edge.
            if (event.actionMasked == MotionEvent.ACTION_UP && event.x >= privateToggleLeft) {
                tapHaptic()
                listener?.onPrivateRevealToggled()
            }
            return event.actionMasked == MotionEvent.ACTION_DOWN ||
                event.actionMasked == MotionEvent.ACTION_UP
        }
        if (count == 0 && chipOffset == 0) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = slotAt(event.x)
                longPressFired = false
                invalidate()
                if (pressedIndex >= 0) {
                    postDelayed(longPressRunnable, KeyboardCanvasView.LONG_PRESS_MILLIS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val slot = slotAt(event.x)
                if (slot != pressedIndex) {
                    // The hold restarts on the new slot.
                    removeCallbacks(longPressRunnable)
                    pressedIndex = slot
                    invalidate()
                    if (slot >= 0) {
                        postDelayed(longPressRunnable, KeyboardCanvasView.LONG_PRESS_MILLIS)
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
                if (slot >= 0) {
                    // Every tapped chip gets a burst.
                    val slotWidth = width.toFloat() / shownCount()
                    val left = slotWidth * slot
                    tappedSlot.set(left, 0f, left + slotWidth, height.toFloat())
                    particles.press(tappedSlot)
                }
                if (slot in 0 until chipOffset) {
                    tapHaptic()
                    if (chipIsScreenshot[slot]) {
                        listener?.onScreenshotPicked()
                    } else {
                        listener?.onClipboardPicked()
                    }
                    return true
                }
                val index = slot - chipOffset
                val word = if (index >= 0) words[index] else null
                if (index >= 0 && actionMode) {
                    tapHaptic()
                    listener?.onActionPicked(index)
                } else if (word != null) {
                    tapHaptic()
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

    /** How many slots are drawn, the clipboard chip's included. */
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
        val fromStart = if (rightToLeft) width - x else x
        val slot = (fromStart / (width.toFloat() / shown)).toInt()
        return if (slot in 0 until shown) slot else -1
    }

    /**
     * Whether the slots run from the right, the way the language on the keys is read. The first
     * slot then sits at the right edge and the words are clipped from their own start.
     */
    var rightToLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The left edge of [slot], with slot zero at the reading start. */
    private fun slotLeft(slot: Int, slotWidth: Float): Float =
        if (rightToLeft) width - slotWidth * (slot + 1) else slotWidth * slot

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        particles.cancel()
    }

    // The notices as character arrays, resolved once per view.
    private val privateNotice = strings[Keys.STRIP_PRIVATE]
    private val privateNoticeChars =
        CharArray(privateNotice.length).also { privateNotice.toCharArray(it, 0, 0, it.size) }
    private val privateShowLabel = strings[Keys.STRIP_SHOW_TYPED]
    private val privateShowChars =
        CharArray(privateShowLabel.length).also { privateShowLabel.toCharArray(it, 0, 0, it.size) }
    private val privateHideLabel = strings[Keys.STRIP_HIDE_TYPED]
    private val privateHideChars =
        CharArray(privateHideLabel.length).also { privateHideLabel.toCharArray(it, 0, 0, it.size) }
    private val idleNotice = strings[Keys.STRIP_IDLE]
    private val idleNoticeChars =
        CharArray(idleNotice.length).also { idleNotice.toCharArray(it, 0, 0, it.size) }
    private val decodingNoticeChars =
        CharArray(DECODING_NOTICE.length).also { DECODING_NOTICE.toCharArray(it, 0, 0, it.size) }

    companion object {
        /** The most of a private field's text the row keeps to draw. */
        const val PRIVATE_TEXT_CHARS = 256

        /** The Show or Hide label's side padding, as a share of the row's height. */
        const val PRIVATE_PADDING_FRACTION = 0.35f

        /** The most the strip can hold, which its buffers are sized for. */
        const val MAX_SUGGESTIONS = KeyboardPreferences.MAX_SUGGESTIONS

        /** The most fill particles alive at once. */
        const val FILL_PARTICLE_POOL_CAPACITY = 48

        /** The most outline particles alive at once. */
        const val OUTLINE_PARTICLE_POOL_CAPACITY = 56

        /** The decoding notice's glow width, as a fraction of the strip's width. */
        const val DECODING_GLOW_WIDTH_FRACTION = 0.3f

        /** How far the applied-word outline sits inside its slot, as a fraction of the slot. */
        const val APPLIED_INSET = 0.06f

        /** Its corner radius, as a fraction of the strip's height. */
        const val APPLIED_CORNER = 0.22f

        /** How many lines the clipboard chip wraps to, and how far apart they sit. */
        const val CHIP_LINES = 2

        // The chips the strip shows at most: the clipboard's and the newest screenshot's.
        const val MAX_CHIPS = 2
        const val CHIP_LINE_SPACING = 1.05f

        /** The chip's text size, relative to the theme's key label size. */
        const val CHIP_TEXT_SCALE = 0.62f

        /** The paste mark's share of the strip's height, and the gap around it. */
        const val CHIP_ICON_FRACTION = 0.42f

        // A chip's preview's side, as a share of the strip's height.
        const val CHIP_THUMBNAIL_FRACTION = 0.72f
        const val CHIP_GAP_PX = 10f
        private const val MAX_WORD_CHARS = 48

        /** How much of a slot a word may occupy before it is shrunk. */
        private const val SLOT_TEXT_FRACTION = 0.80f

        /** A word on the strip, relative to the theme's key label size. */
        private const val SLOT_TEXT_SCALE = 1.2f

        /** The smallest a word is shrunk to, relative to its slot's base size. */
        private const val MIN_TEXT_SCALE = 0.62f

        /** The decoding notice: an ellipsis, not translated. */
        private const val DECODING_NOTICE = "…"
    }
}
