// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.DynamicLayout
import android.text.Editable
import android.text.Layout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import com.borderkeys.data.theme.ComposerAction
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.theme.ThemePaints
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The draft box: somewhere to write that the application cannot see.
 *
 * Three bands, top to bottom. The text, which scrolls. A thin strip that carries the line of
 * versions once there is more than one. And the control bar, whose middle is in whatever order
 * the user put it and whose two ends are the arrows that walk the versions -- pinned, because a
 * control that moves is one you have to look for.
 *
 * It does not hide the keys. Every other panel in this keyboard replaces them, because every
 * other panel is something you do *instead* of typing; this is something you type into, so the
 * window grows to hold it instead.
 *
 * The text is drawn from an [Editable] this view does not own. The service writes into it
 * through an input connection -- the same primitives it uses on the application's field -- and
 * the connection calls [onBufferChanged] afterwards. That is why every typing rule works in here
 * without being written twice, and why this class holds no text of its own to get out of step.
 *
 * Allocation: this is not the typing hot path in the sense the rest of the keyboard means. It
 * lays text out when the text changes, which is a keystroke, so the layout is a [DynamicLayout]
 * that reflows the changed line rather than rebuilding. `onDraw` allocates nothing.
 */
@SuppressLint("ViewConstructor")
class ComposerView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    interface Listener {
        fun onComposerAction(action: ComposerAction)
        fun onComposerBack()
        fun onComposerForward()
        fun onComposerClose()

        /** A node on the version line was tapped or dragged to. */
        fun onComposerVersionPicked(index: Int)

        /** The text was tapped, at this offset into the buffer. */
        fun onComposerCaretPlaced(offset: Int)

        /** One of the choices in the band was picked, by its position. */
        fun onComposerChoice(index: Int)

        /** The cross at the end of the prompt row: throw the instruction away. */
        fun onComposerPromptDismissed()
    }

    var listener: Listener? = null

    /** The buffer being drawn. Null before the box has been opened for the first time. */
    private var buffer: Editable? = null
    private var layout: DynamicLayout? = null

    /** The version line: how many nodes, and which one is filled. */
    private var versionCount = 0
    private var currentVersion = 0

    /**
     * What the band under the text is showing.
     *
     * One strip doing three jobs, because the box is already as tall as it can afford to be and
     * all three are about the same thing: which version, or what to do to it.
     */
    private var bandMode = BAND_VERSIONS

    private var choices: Array<String?> = emptyArray()
    private var choiceCount = 0
    private var choiceBounds = FloatArray(0)
    private var pressedChoice = NO_BUTTON
    private val choicePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    /** The instruction being written, when the band is showing one. */
    private var prompt: Editable? = null
    private var promptLayout: DynamicLayout? = null
    private val promptDismissBounds = android.graphics.RectF()
    private var pressedPromptDismiss = false
    private val promptMarkPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    /** Which buttons the bar carries, in the user's order, and whether each can be pressed. */
    private var barActions: List<ComposerAction> = emptyList()
    private var barEnabled = BooleanArray(0)

    private var canGoBack = false
    private var canGoForward = false

    /** A status line drawn over the text: what is happening, or what went wrong. */
    private var notice: String = ""

    private val textPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private val hintPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
    private val caretPaint = android.graphics.Paint()
    private val nodePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    private val nodeFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val railPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private val icons = HashMap<ComposerAction, Drawable?>()
    private val backIcon = drawable("bk_composer_back")
    private val forwardIcon = drawable("bk_composer_forward")
    private val closeIcon = drawable("bk_composer_close")
    private val iconBounds = Rect()

    /** Button rectangles, four floats each, filled while drawing and read while touching. */
    private var barBounds = FloatArray(0)
    private val backBounds = android.graphics.RectF()
    private val forwardBounds = android.graphics.RectF()
    private val closeBounds = android.graphics.RectF()

    private var pressedBar = NO_BUTTON
    private var pressedEnd = NO_BUTTON

    // ---- geometry, laid out once per size change ------------------------------------------------

    private var textTop = 0f
    private var textBottom = 0f
    private var railTop = 0f
    private var railBottom = 0f
    private var barTop = 0f
    private var paddingPx = 0f

    private val scroller = OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchY = 0f
    private var dragging = false
    private var draggingRail = false

    private var caretVisible = true
    private val blink = object : Runnable {
        override fun run() {
            caretVisible = !caretVisible
            invalidate()
            postDelayed(this, BLINK_MILLIS)
        }
    }

    private fun drawable(name: String): Drawable? {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        return if (id == 0) null else ContextCompat.getDrawable(context, id)
    }

    /**
     * Hands the view the buffer to draw.
     *
     * The [DynamicLayout] is built once per buffer and then reflows itself: it registers as a
     * watcher on the text and re-lays out only the line that changed, which is what makes a
     * keystroke cost a line rather than a paragraph.
     */
    fun bind(text: Editable) {
        if (buffer === text && layout != null) {
            return
        }
        buffer = text
        layout = null
        requestLayout()
        invalidate()
    }

    /** Called by the buffer's connection after anything changes the text or the caret. */
    fun onBufferChanged() {
        notice = ""
        caretVisible = true
        scrollToCaret()
        invalidate()
    }

    fun setVersions(count: Int, current: Int, canBack: Boolean, canForward: Boolean) {
        if (versionCount == count && currentVersion == current &&
            canGoBack == canBack && canGoForward == canForward
        ) {
            return
        }
        // Crossing one version either way changes the height, because the line appears with the
        // second version and takes its band with it.
        val bandChanged = (versionCount >= 2) != (count >= 2)
        versionCount = count
        currentVersion = current
        canGoBack = canBack
        canGoForward = canForward
        if (bandChanged) {
            requestLayout()
        }
        invalidate()
    }

    /** The buttons the bar carries, and which of them can be pressed right now. */
    fun setBar(actions: List<ComposerAction>, enabled: BooleanArray) {
        barActions = actions
        barEnabled = enabled
        if (barBounds.size != actions.size * 4) {
            barBounds = FloatArray(actions.size * 4)
        }
        for (action in actions) {
            icons.getOrPut(action) { drawable(iconName(action)) }
        }
        invalidate()
    }

    /**
     * Puts a row of choices in the band: which language, which register.
     *
     * The strip above the keys is not used for this. It belongs to the words being typed, and a
     * row that turns into something else while you are mid-word is a row you cannot trust.
     */
    fun showChoices(labels: Array<String?>, count: Int) {
        choices = labels
        choiceCount = count
        if (choiceBounds.size != count * 2) {
            choiceBounds = FloatArray(count * 2)
        }
        pressedChoice = NO_BUTTON
        setBand(BAND_CHOICES)
    }

    fun showVersions() {
        prompt = null
        promptLayout = null
        setBand(BAND_VERSIONS)
    }

    /**
     * Opens the instruction row along the bottom of the box.
     *
     * One line to start with, growing upward into the text as it is written, because an
     * instruction is usually short and occasionally is not.
     */
    fun showPrompt(text: Editable) {
        prompt = text
        promptLayout = null
        setBand(BAND_PROMPT)
        requestLayout()
    }

    val promptShowing: Boolean get() = bandMode == BAND_PROMPT

    private fun setBand(mode: Int) {
        if (bandMode == mode) {
            return
        }
        val before = bandRows()
        bandMode = mode
        if (bandRows() != before) {
            requestLayout()
        }
        invalidate()
    }

    val choosing: Boolean get() = bandMode == BAND_CHOICES

    /** A line over the text: what is running, or why nothing happened. */
    fun showNotice(text: String) {
        notice = text
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blink, BLINK_MILLIS)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // A view that is gone must not keep the choreographer awake for a caret nobody sees.
        removeCallbacks(blink)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(blink)
        if (visibility == VISIBLE) {
            caretVisible = true
            postDelayed(blink, BLINK_MILLIS)
        }
    }

    // ---- measurement -----------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val row = rowHeight()
        applyMetrics(row)
        val contentRows = textRowsFor(width, row)
        val height = paddingPx * 2f + contentRows * row + railHeight() + barHeight()
        setMeasuredDimension(width, height.roundToInt())
    }

    /**
     * How many rows of text to show: enough for what is written, between a floor and a ceiling.
     *
     * The floor is so an empty box looks like somewhere to write. The ceiling is because the
     * window grows by whatever this returns, and a box that grows without limit is a box that
     * pushes the thing you are writing about off the screen.
     */
    private fun textRowsFor(width: Int, row: Float): Float {
        val text = buffer ?: return MIN_TEXT_ROWS
        val available = width - paddingPx * 2f
        if (available <= 0f) {
            return MIN_TEXT_ROWS
        }
        val laid = ensureLayout(text, available.toInt())
        val rows = laid.height / row
        return rows.coerceIn(MIN_TEXT_ROWS, MAX_TEXT_ROWS)
    }

    private fun ensureLayout(text: Editable, width: Int): DynamicLayout {
        val existing = layout
        if (existing != null && existing.width == width) {
            return existing
        }
        val built = DynamicLayout.Builder.obtain(text, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        layout = built
        return built
    }

    private fun rowHeight(): Float =
        if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX

    /**
     * The band takes no room when it has nothing to show.
     *
     * A strip reserved for one version is a strip of nothing, and the box is already a text area
     * and a bar tall before anything has been written in it.
     */
    private fun bandRows(): Float = when {
        bandMode == BAND_CHOICES -> CHOICE_ROWS
        bandMode == BAND_PROMPT -> promptRows()
        versionCount >= 2 -> RAIL_ROWS
        else -> 0f
    }

    /** One row, then as many as the instruction needs, up to a stop. */
    private fun promptRows(): Float {
        val text = prompt ?: return PROMPT_ROWS
        val available = width - paddingPx * 2f - promptMarkWidth() * 2f
        if (available <= 0f) {
            return PROMPT_ROWS
        }
        val laid = ensurePromptLayout(text, available.toInt())
        val rows = laid.height / rowHeight()
        return rows.coerceIn(PROMPT_ROWS, MAX_PROMPT_ROWS)
    }

    private fun ensurePromptLayout(text: Editable, width: Int): DynamicLayout {
        val existing = promptLayout
        if (existing != null && existing.width == width) {
            return existing
        }
        val built = DynamicLayout.Builder.obtain(text, promptMarkPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        promptLayout = built
        return built
    }

    private fun promptMarkWidth(): Float = rowHeight() * PROMPT_MARK_ROWS

    private fun railHeight(): Float = rowHeight() * bandRows()

    private fun barHeight(): Float = rowHeight() * BAR_ROWS

    private fun applyMetrics(row: Float) {
        paddingPx = row * PADDING_ROWS
        textPaint.textSize = paints.label.textSize * TEXT_SCALE
        textPaint.color = paints.label.color
        hintPaint.textSize = paints.labelSecondary.textSize
        hintPaint.color = paints.labelSecondary.color
        caretPaint.color = paints.accent.color
        nodePaint.color = paints.labelSecondary.color
        nodePaint.strokeWidth = row * NODE_STROKE_ROWS
        railPaint.color = paints.labelSecondary.color
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMetrics(rowHeight())
        choicePaint.textSize = paints.labelSecondary.textSize
        promptMarkPaint.textSize = paints.label.textSize * TEXT_SCALE
        textTop = paddingPx
        barTop = h - barHeight()
        railBottom = barTop
        railTop = railBottom - railHeight()
        textBottom = railTop
        layout = null
        clampScroll()
    }

    // ---- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val width = width.toFloat()
        paints.backgroundPainter.draw(canvas, width, height.toFloat())
        drawText(canvas)
        when (bandMode) {
            BAND_CHOICES -> drawChoices(canvas)
            BAND_PROMPT -> drawPrompt(canvas)
            else -> drawRail(canvas)
        }
        drawBar(canvas)
        drawClose(canvas)
    }

    private fun drawText(canvas: Canvas) {
        val text = buffer
        val available = width - paddingPx * 2f
        if (text == null || available <= 0f) {
            return
        }
        if (text.isEmpty() && notice.isEmpty()) {
            val hint = strings[Keys.COMPOSER_EMPTY]
            canvas.drawText(hint, paddingPx, textTop - hintPaint.ascent(), hintPaint)
            return
        }
        val laid = ensureLayout(text, available.toInt())
        canvas.save()
        canvas.clipRect(0f, textTop, width.toFloat(), textBottom)
        canvas.translate(paddingPx, textTop - scrollOffset)
        laid.draw(canvas)
        if (caretVisible && notice.isEmpty()) {
            drawCaret(canvas, laid, text)
        }
        canvas.restore()
        if (notice.isNotEmpty()) {
            canvas.drawText(notice, paddingPx, textBottom - hintPaint.descent(), hintPaint)
        }
    }

    private fun drawCaret(canvas: Canvas, laid: Layout, text: Editable) {
        val offset = android.text.Selection.getSelectionEnd(text).coerceIn(0, text.length)
        val line = laid.getLineForOffset(offset)
        val x = laid.getPrimaryHorizontal(offset)
        val top = laid.getLineTop(line).toFloat()
        val bottom = laid.getLineBottom(line).toFloat()
        canvas.drawRect(x, top, x + caretWidth(), bottom, caretPaint)
    }

    private fun caretWidth(): Float = (resources.displayMetrics.density * CARET_WIDTH_DP)

    /**
     * The line of versions.
     *
     * Empty circles for the versions, a filled dot for the one being read, the ends coloured
     * apart so the original and the newest are findable without counting. Nothing at all until
     * there is more than one version, because a line with one node on it says nothing.
     */
    private fun drawRail(canvas: Canvas) {
        if (versionCount < 2) {
            return
        }
        val y = (railTop + railBottom) / 2f
        val left = paddingPx + nodeRadius()
        val right = width - paddingPx - nodeRadius()
        if (right <= left) {
            return
        }
        canvas.drawRect(left, y - railThickness() / 2f, right, y + railThickness() / 2f, railPaint)
        val radius = nodeRadius()
        for (index in 0 until versionCount) {
            val x = nodeX(index, left, right)
            nodePaint.color = endColour(index)
            canvas.drawCircle(x, y, radius, nodePaint)
            if (index == currentVersion) {
                nodeFill.color = paints.accent.color
                canvas.drawCircle(x, y, radius * NODE_FILL_FRACTION, nodeFill)
            }
        }
    }

    /**
     * The instruction row: a mark, the words, and a cross to throw them away.
     *
     * The mark is there to say what this row is. A blank strip that has appeared under the text
     * could be anything; one that opens with a prompt sign is a place to tell something to do
     * something, which is what it is.
     */
    private fun drawPrompt(canvas: Canvas) {
        val text = prompt ?: return
        promptMarkPaint.color = paints.accent.color
        val mark = promptMarkWidth()
        val baseline = railTop + rowHeight() * PROMPT_BASELINE_ROWS
        canvas.drawText(PROMPT_MARK, paddingPx, baseline, promptMarkPaint)

        val available = width - paddingPx * 2f - mark * 2f
        if (available > 0f) {
            promptMarkPaint.color = paints.label.color
            val laid = ensurePromptLayout(text, available.toInt())
            canvas.save()
            canvas.clipRect(paddingPx + mark, railTop, width - paddingPx - mark, railBottom)
            canvas.translate(paddingPx + mark, railTop + paddingPx / 2f)
            laid.draw(canvas)
            if (caretVisible) {
                val offset = android.text.Selection.getSelectionEnd(text).coerceIn(0, text.length)
                val line = laid.getLineForOffset(offset)
                canvas.drawRect(
                    laid.getPrimaryHorizontal(offset), laid.getLineTop(line).toFloat(),
                    laid.getPrimaryHorizontal(offset) + caretWidth(),
                    laid.getLineBottom(line).toFloat(), caretPaint,
                )
            }
            canvas.restore()
        }

        promptDismissBounds.set(width - paddingPx - mark, railTop, width - paddingPx, railBottom)
        if (pressedPromptDismiss) {
            canvas.drawRect(promptDismissBounds, paints.keyPressedFill)
        }
        drawIcon(
            canvas, closeIcon, promptDismissBounds.centerX(), promptDismissBounds.centerY(),
            true, false,
        )
    }

    /**
     * The choices, as chips across the band.
     *
     * Equal widths rather than measured ones: six language names of different lengths in
     * unequal boxes is a row whose targets move every time it is opened.
     */
    private fun drawChoices(canvas: Canvas) {
        if (choiceCount <= 0) {
            return
        }
        val each = (width - paddingPx * 2f) / choiceCount
        choicePaint.color = paints.label.color
        val baseline = (railTop + railBottom) / 2f -
            (choicePaint.ascent() + choicePaint.descent()) / 2f
        for (index in 0 until choiceCount) {
            val left = paddingPx + each * index
            choiceBounds[index * 2] = left
            choiceBounds[index * 2 + 1] = left + each
            if (index == pressedChoice) {
                canvas.drawRect(left, railTop, left + each, railBottom, paints.keyPressedFill)
            }
            val label = choices.getOrNull(index) ?: continue
            val fitted = fit(label, each - paddingPx)
            canvas.drawText(
                fitted, left + (each - choicePaint.measureText(fitted)) / 2f, baseline, choicePaint,
            )
        }
    }

    /** Trims a label to what its chip can hold, rather than letting it run into the next one. */
    private fun fit(label: String, available: Float): String {
        if (choicePaint.measureText(label) <= available || label.length <= 2) {
            return label
        }
        var end = label.length
        while (end > 1 && choicePaint.measureText(label, 0, end) > available) {
            end -= 1
        }
        return label.substring(0, end)
    }

    /** The first and the last node are the two anyone looks for, so they are not grey. */
    private fun endColour(index: Int): Int =
        if (index == 0 || index == versionCount - 1) paints.accent.color else paints.labelSecondary.color

    private fun nodeX(index: Int, left: Float, right: Float): Float {
        if (versionCount <= 1) {
            return left
        }
        return left + (right - left) * index / (versionCount - 1)
    }

    private fun nodeRadius(): Float = rowHeight() * NODE_RADIUS_ROWS

    private fun railThickness(): Float = rowHeight() * RAIL_THICKNESS_ROWS

    private fun drawBar(canvas: Canvas) {
        val ends = barHeight()
        val top = barTop
        val bottom = height.toFloat()
        drawEnd(canvas, backBounds, 0f, top, ends, bottom, backIcon, canGoBack, pressedEnd == END_BACK)
        drawEnd(
            canvas, forwardBounds, width - ends, top, width.toFloat(), bottom, forwardIcon,
            canGoForward, pressedEnd == END_FORWARD,
        )
        if (barActions.isEmpty()) {
            return
        }
        val left = ends
        val right = width - ends
        val each = (right - left) / barActions.size
        for (index in barActions.indices) {
            val slotLeft = left + each * index
            barBounds[index * 4] = slotLeft
            barBounds[index * 4 + 1] = top
            barBounds[index * 4 + 2] = slotLeft + each
            barBounds[index * 4 + 3] = bottom
            val enabled = barEnabled.getOrElse(index) { true }
            if (index == pressedBar && enabled) {
                canvas.drawRect(slotLeft, top, slotLeft + each, bottom, paints.keyPressedFill)
            }
            drawIcon(
                canvas, icons[barActions[index]], slotLeft + each / 2f, (top + bottom) / 2f,
                enabled, barActions[index] == ComposerAction.INSERT,
            )
        }
    }

    private fun drawEnd(
        canvas: Canvas,
        bounds: android.graphics.RectF,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        icon: Drawable?,
        enabled: Boolean,
        pressed: Boolean,
    ) {
        bounds.set(left, top, right, bottom)
        if (pressed && enabled) {
            canvas.drawRect(bounds, paints.keyPressedFill)
        }
        drawIcon(canvas, icon, (left + right) / 2f, (top + bottom) / 2f, enabled, false)
    }

    private fun drawIcon(
        canvas: Canvas,
        icon: Drawable?,
        centreX: Float,
        centreY: Float,
        enabled: Boolean,
        accent: Boolean,
    ) {
        if (icon == null) {
            return
        }
        val half = (rowHeight() * ICON_ROWS / 2f).roundToInt()
        iconBounds.set(
            (centreX - half).roundToInt(), (centreY - half).roundToInt(),
            (centreX + half).roundToInt(), (centreY + half).roundToInt(),
        )
        icon.bounds = iconBounds
        // Insert is the one affirmative button on the bar, so it is the one that is coloured;
        // a disabled button is drawn dim rather than hidden, or the bar would reflow under a
        // thumb every time a version was added.
        icon.setTint(
            when {
                !enabled -> paints.labelSecondary.color
                accent -> paints.accent.color
                else -> paints.label.color
            },
        )
        icon.alpha = if (enabled) 255 else DISABLED_ALPHA
        icon.draw(canvas)
    }

    private fun drawClose(canvas: Canvas) {
        val size = rowHeight() * CLOSE_ROWS
        val right = width - paddingPx / 2f
        val top = paddingPx / 2f
        closeBounds.set(right - size, top, right, top + size)
        if (pressedEnd == END_CLOSE) {
            canvas.drawRect(closeBounds, paints.keyPressedFill)
        }
        drawIcon(canvas, closeIcon, closeBounds.centerX(), closeBounds.centerY(), true, false)
    }

    // ---- scrolling -------------------------------------------------------------------------------

    private var scrollOffset = 0f

    private fun contentHeight(): Float = layout?.height?.toFloat() ?: 0f

    private fun maxScroll(): Float =
        (contentHeight() - (textBottom - textTop)).coerceAtLeast(0f)

    private fun clampScroll() {
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll())
    }

    /** Keeps the caret on screen as the text grows past the box. */
    private fun scrollToCaret() {
        val text = buffer ?: return
        val laid = layout ?: return
        val offset = android.text.Selection.getSelectionEnd(text).coerceIn(0, text.length)
        val line = laid.getLineForOffset(offset)
        val top = laid.getLineTop(line).toFloat()
        val bottom = laid.getLineBottom(line).toFloat()
        val viewport = textBottom - textTop
        if (bottom - scrollOffset > viewport) {
            scrollOffset = bottom - viewport
        } else if (top < scrollOffset) {
            scrollOffset = top
        }
        clampScroll()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat()
            clampScroll()
            postInvalidateOnAnimation()
        }
    }

    // ---- touch -----------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = event.y
                dragging = false
                draggingRail = false
                pressedBar = NO_BUTTON
                pressedEnd = NO_BUTTON
                if (closeBounds.contains(event.x, event.y)) {
                    pressedEnd = END_CLOSE
                } else if (event.y >= barTop) {
                    onBarDown(event.x)
                } else if (event.y >= railTop && bandMode == BAND_PROMPT) {
                    pressedPromptDismiss = promptDismissBounds.contains(event.x, event.y)
                } else if (event.y >= railTop && bandMode == BAND_CHOICES) {
                    pressedChoice = choiceAt(event.x)
                } else if (event.y >= railTop && versionCount >= 2) {
                    draggingRail = true
                    railTo(event.x)
                } else {
                    velocity?.recycle()
                    velocity = android.view.VelocityTracker.obtain()
                    velocity?.addMovement(event)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingRail) {
                    railTo(event.x)
                    return true
                }
                if (pressedBar != NO_BUTTON || pressedEnd != NO_BUTTON ||
                    pressedChoice != NO_BUTTON
                ) {
                    return true
                }
                velocity?.addMovement(event)
                val delta = lastTouchY - event.y
                if (!dragging && abs(delta) > touchSlop) {
                    dragging = true
                }
                if (dragging) {
                    scrollOffset += delta
                    lastTouchY = event.y
                    clampScroll()
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (draggingRail) {
                    draggingRail = false
                    return true
                }
                if (pressedEnd != NO_BUTTON) {
                    releaseEnd(event.x, event.y)
                    return true
                }
                if (pressedPromptDismiss) {
                    pressedPromptDismiss = false
                    invalidate()
                    if (promptDismissBounds.contains(event.x, event.y)) {
                        listener?.onComposerPromptDismissed()
                    }
                    return true
                }
                if (pressedChoice != NO_BUTTON) {
                    val index = pressedChoice
                    pressedChoice = NO_BUTTON
                    invalidate()
                    if (choiceAt(event.x) == index && event.y >= railTop && event.y <= railBottom) {
                        listener?.onComposerChoice(index)
                    }
                    return true
                }
                if (pressedBar != NO_BUTTON) {
                    releaseBar(event.x, event.y)
                    return true
                }
                if (dragging) {
                    velocity?.let {
                        it.computeCurrentVelocity(1000)
                        scroller.fling(
                            0, scrollOffset.roundToInt(), 0, -it.yVelocity.roundToInt(),
                            0, 0, 0, maxScroll().roundToInt(),
                        )
                        postInvalidateOnAnimation()
                    }
                } else {
                    placeCaret(event.x, event.y)
                }
                velocity?.recycle()
                velocity = null
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressedBar = NO_BUTTON
                pressedEnd = NO_BUTTON
                pressedChoice = NO_BUTTON
                pressedPromptDismiss = false
                dragging = false
                draggingRail = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun onBarDown(x: Float) {
        if (backBounds.left <= x && x <= backBounds.right) {
            pressedEnd = END_BACK
            return
        }
        if (forwardBounds.left <= x && x <= forwardBounds.right) {
            pressedEnd = END_FORWARD
            return
        }
        pressedBar = barAt(x)
    }

    private fun releaseEnd(x: Float, y: Float) {
        val which = pressedEnd
        pressedEnd = NO_BUTTON
        invalidate()
        when (which) {
            END_CLOSE -> if (closeBounds.contains(x, y)) listener?.onComposerClose()
            END_BACK -> if (backBounds.contains(x, y) && canGoBack) listener?.onComposerBack()
            END_FORWARD ->
                if (forwardBounds.contains(x, y) && canGoForward) listener?.onComposerForward()
        }
    }

    private fun releaseBar(x: Float, y: Float) {
        val index = pressedBar
        pressedBar = NO_BUTTON
        invalidate()
        if (index !in barActions.indices || y < barTop || barAt(x) != index) {
            return
        }
        if (barEnabled.getOrElse(index) { true }) {
            listener?.onComposerAction(barActions[index])
        }
    }

    private fun choiceAt(x: Float): Int {
        for (index in 0 until choiceCount) {
            if (x >= choiceBounds[index * 2] && x <= choiceBounds[index * 2 + 1]) {
                return index
            }
        }
        return NO_BUTTON
    }

    private fun barAt(x: Float): Int {
        for (index in barActions.indices) {
            if (x >= barBounds[index * 4] && x <= barBounds[index * 4 + 2]) {
                return index
            }
        }
        return NO_BUTTON
    }

    /** Snaps the dot to the nearest node and reports it, so a drag updates the text as it moves. */
    private fun railTo(x: Float) {
        if (versionCount < 2) {
            return
        }
        val left = paddingPx + nodeRadius()
        val right = width - paddingPx - nodeRadius()
        if (right <= left) {
            return
        }
        val fraction = ((x - left) / (right - left)).coerceIn(0f, 1f)
        val nearest = (fraction * (versionCount - 1)).roundToInt()
        if (nearest != currentVersion) {
            listener?.onComposerVersionPicked(nearest)
        }
    }

    private fun placeCaret(x: Float, y: Float) {
        val text = buffer ?: return
        val laid = layout ?: return
        if (y < textTop || y > textBottom) {
            return
        }
        val line = laid.getLineForVertical((y - textTop + scrollOffset).roundToInt())
        val offset = laid.getOffsetForHorizontal(line, x - paddingPx)
        listener?.onComposerCaretPlaced(offset.coerceIn(0, text.length))
    }

    private fun iconName(action: ComposerAction): String = when (action) {
        ComposerAction.GRAMMAR -> "bk_composer_grammar"
        ComposerAction.TRANSLATE -> "bk_composer_translate"
        ComposerAction.TONE -> "bk_composer_tone"
        ComposerAction.SHORTEN -> "bk_composer_shorten"
        ComposerAction.PROMPT -> "bk_composer_prompt"
        ComposerAction.SAVED_PROMPTS -> "bk_composer_saved"
        ComposerAction.SHOW_ORIGINAL -> "bk_composer_original"
        ComposerAction.INSERT -> "bk_composer_insert"
    }

    private companion object {
        const val NO_BUTTON = -1
        const val END_BACK = -2
        const val END_FORWARD = -3
        const val END_CLOSE = -4

        /** Only reached before the first theme update, when the row height is still zero. */
        const val DEFAULT_ROW_PX = 132f

        /** A box shorter than this does not read as somewhere to write; taller eats the screen. */
        const val MIN_TEXT_ROWS = 1.6f
        const val MAX_TEXT_ROWS = 4f

        const val BAND_VERSIONS = 0
        const val BAND_CHOICES = 1
        const val BAND_PROMPT = 2

        /** What the instruction row opens with, so it reads as a place to give an order. */
        const val PROMPT_MARK = "\u203a"

        const val RAIL_ROWS = 0.55f
        const val CHOICE_ROWS = 0.7f
        const val PROMPT_ROWS = 0.8f
        const val MAX_PROMPT_ROWS = 2.4f
        const val PROMPT_MARK_ROWS = 0.4f
        const val PROMPT_BASELINE_ROWS = 0.55f
        const val BAR_ROWS = 0.9f
        const val PADDING_ROWS = 0.14f
        const val ICON_ROWS = 0.42f
        const val CLOSE_ROWS = 0.5f

        const val NODE_RADIUS_ROWS = 0.09f
        const val NODE_STROKE_ROWS = 0.018f
        const val NODE_FILL_FRACTION = 0.55f
        const val RAIL_THICKNESS_ROWS = 0.012f

        const val TEXT_SCALE = 0.82f
        const val CARET_WIDTH_DP = 2f
        const val DISABLED_ALPHA = 90
        const val BLINK_MILLIS = 500L
    }
}
