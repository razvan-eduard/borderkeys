// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Trace
import android.text.TextDirectionHeuristics
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.borderkeys.data.ClipSearch
import com.borderkeys.data.entity.ClipEntry
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.theme.ThemePaints

/**
 * The clipboard history, as a scrolling column of cards inside the keyboard.
 *
 * A panel rather than the suggestion strip, because the strip has room for a few words and the
 * history is a list someone reads: several lines of a copied paragraph, and a picture that can
 * only be recognised by looking at it.
 *
 * Drawn like the rest of the keyboard -- one View, one onDraw, arithmetic hit testing. A
 * RecyclerView would bring a layout manager, an adapter and view recycling into a process whose
 * whole design is that it has none of those.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : View(context) {

    interface Listener {
        /** A card was tapped. Text or image is the service's business, not the panel's. */
        fun onClipPicked(entry: ClipEntry)

        /** The card's pin control was tapped. */
        fun onClipPinToggled(entry: ClipEntry)

        /** The card's delete control was tapped. */
        fun onClipDeleted(entry: ClipEntry)

        /** The card's edit control was tapped. Text only; an image card has none. */
        fun onClipEdited(entry: ClipEntry)

        /** The panel asked to be closed. */
        fun onClipboardPanelClosed()
    }

    var listener: Listener? = null

    /** The history as given, and as drawn: the same entries, the query's matches first. */
    private var all: List<ClipEntry> = emptyList()
    private var entries: List<ClipEntry> = emptyList()

    /** The header's note on the query: how many cards contain it. */
    private var matchNote = ""

    /**
     * The word the panel was opened over. Cards containing it are drawn first and the header
     * says how many there are; blank, the list is the history in its own order.
     */
    var query: String = ""
        set(value) {
            if (field == value) {
                return
            }
            field = value
            applyQuery()
        }

    /**
     * Whether the header, the cards and their actions run from the right edge, the way the
     * alphabetic layout's language reads. A card's text is aligned by its own direction either
     * way.
     */
    var rightToLeft: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The card whose actions a long press opened, or -1. */
    private var actionCard = -1
    private var longPressFired = false
    private val longPressRunnable = Runnable { openActions() }
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val pinLabel = strings[Keys.CLIPBOARD_PIN]
    private val unpinLabel = strings[Keys.CLIPBOARD_UNPIN]
    private val editLabel = strings[Keys.CLIPBOARD_EDIT]
    private val deleteLabel = strings[Keys.CLIPBOARD_DELETE]

    /**
     * Thumbnails, by entry id.
     *
     * Decoded once when the list arrives and held for as long as the list is shown. Bounded by
     * the number of cards, which is bounded by the history size, and each is decoded down to
     * the card's height rather than the image's own -- a screenshot is several megabytes as
     * pixels and a few tens of kilobytes at the size it is drawn.
     */
    private val thumbnails = HashMap<Long, Bitmap?>()

    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastY = 0f
    private var dragging = false
    private var pressedCard = -1

    /** The finger went down on the pinned header -- an up there without a drag is "close". */
    private var pressedHeader = false

    private var cardHeightPx = 0f
    private var headerHeightPx = 0f
    private var paddingPx = 0f
    private var contentHeight = 0

    /** "Clipboard", drawn once a frame in the header; a String because [labelFor] draws that way too. */
    private val title = strings[Keys.SCREEN_CLIPBOARD]

    private val cardRect = RectF()
    private val thumbRect = Rect()
    private val emptyChars = CharArray(96)
    private var emptyLength = 0

    init {
        setWillNotDraw(false)
        isHapticFeedbackEnabled = true
        val empty = strings[Keys.CLIPBOARD_EMPTY]
        emptyLength = empty.length.coerceAtMost(emptyChars.size)
        empty.toCharArray(emptyChars, 0, 0, emptyLength)
    }

    /**
     * Decodes a thumbnail for every image in [list], keyed by entry id -- on whatever thread
     * calls it, which should not be the one that draws: this reads and decodes files, and used
     * to do so inside [setEntries] on the main thread. The caller hands the result to
     * [setEntries] afterwards.
     */
    fun decodeThumbnails(list: List<ClipEntry>): Map<Long, Bitmap?> {
        val decoded = HashMap<Long, Bitmap?>()
        for (entry in list) {
            if (entry.isImage) {
                decoded[entry.id] = decodeThumbnail(entry)
            }
        }
        return decoded
    }

    /** Replaces the list and the thumbnails [decodeThumbnails] produced for it. */
    fun setEntries(list: List<ClipEntry>, decoded: Map<Long, Bitmap?>) {
        all = list
        thumbnails.clear()
        thumbnails.putAll(decoded)
        scroller.forceFinished(true)
        scrollTo(0, 0)
        applyQuery()
    }

    private fun applyQuery() {
        entries = ClipSearch.rank(all, query)
        matchNote = if (query.isEmpty()) {
            ""
        } else {
            strings.getString(Keys.CLIPBOARD_PANEL_MATCHES, ClipSearch.count(all, query), query)
        }
        actionCard = -1
        measureContent()
        invalidate()
    }

    /**
     * Decodes an image small enough to draw, or null when the grant that came with the clip is
     * gone -- which is normal, and why a card without a thumbnail still says what it is.
     */
    private fun decodeThumbnail(entry: ClipEntry): Bitmap? {
        val uri = entry.uri ?: return null
        return runCatching {
            val target = cardHeightPx.toInt().coerceAtLeast(MIN_THUMBNAIL_PX)
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                val buffered = stream.readBytes()
                android.graphics.BitmapFactory.decodeByteArray(
                    buffered, 0, buffered.size, options,
                )
                var sample = 1
                while (options.outHeight / sample > target * 2) {
                    sample *= 2
                }
                android.graphics.BitmapFactory.decodeByteArray(
                    buffered, 0, buffered.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                )
            }
        }.getOrNull()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        measureContent()
    }

    private fun measureContent() {
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else ThemePaints.DEFAULT_ROW_HEIGHT_PX
        cardHeightPx = row * CARD_HEIGHT_ROWS
        headerHeightPx = row * HEADER_HEIGHT_ROWS
        paddingPx = row * PADDING_ROWS
        contentHeight = (headerHeightPx + (cardHeightPx + paddingPx) * entries.size + paddingPx).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("ClipboardPanelView.onDraw")
        try {
            val viewTop = scrollY.toFloat()
            canvas.drawRect(0f, viewTop, width.toFloat(), viewTop + height, paints.background)

            if (entries.isEmpty()) {
                canvas.drawText(
                    emptyChars, 0, emptyLength,
                    width / 2f, viewTop + (headerHeightPx + height) / 2f + paints.secondaryBaselineOffsetPx,
                    paints.labelSecondary,
                )
            } else {
                val step = cardHeightPx + paddingPx
                val cardsTop = headerHeightPx + paddingPx
                // Only the cards the viewport shows are drawn: a list that scrolls must not get
                // slower the further down it goes.
                val first = (((scrollY - cardsTop) / step).toInt()).coerceAtLeast(0)
                val last = (((scrollY + height - cardsTop) / step).toInt() + 1)
                    .coerceAtMost(entries.size - 1)
                for (index in first..last) {
                    drawCard(canvas, index, cardsTop + step * index)
                }
                drawScrollbar(canvas, viewTop)
            }

            // The header last and pinned: drawn at the top of whatever is on screen, not of the
            // content, so "back" is reachable however far the list is scrolled.
            drawHeader(canvas, viewTop)
        } finally {
            Trace.endSection()
        }
    }

    private fun drawHeader(canvas: Canvas, viewTop: Float) {
        canvas.drawRect(0f, viewTop, width.toFloat(), viewTop + headerHeightPx, paints.modifierKeyFill)
        canvas.drawLine(0f, viewTop, width.toFloat(), viewTop, paints.keyStroke)
        canvas.drawLine(
            0f, viewTop + headerHeightPx, width.toFloat(), viewTop + headerHeightPx, paints.keyStroke,
        )
        val midY = viewTop + headerHeightPx / 2f + paints.labelBaselineOffsetPx
        // Placed from the start edge: the left one, or the right when rightToLeft.
        val align = if (rightToLeft) Paint.Align.RIGHT else Paint.Align.LEFT
        val previous = paints.label.textAlign
        paints.label.textAlign = align
        canvas.drawText(if (rightToLeft) BACK_GLYPH_RTL else BACK_GLYPH, fromStart(paddingPx * 2f), midY, paints.label)
        val titleOffset = paddingPx * 2f + headerHeightPx * 0.7f
        canvas.drawText(title, fromStart(titleOffset), midY, paints.label)
        if (matchNote.isNotEmpty()) {
            val noteOffset = titleOffset + paints.label.measureText(title) + paddingPx * 2f
            val previousSecondary = paints.labelSecondary.textAlign
            paints.labelSecondary.textAlign = align
            canvas.save()
            if (rightToLeft) {
                canvas.clipRect(paddingPx, viewTop, width - noteOffset, viewTop + headerHeightPx)
            } else {
                canvas.clipRect(noteOffset, viewTop, width - paddingPx, viewTop + headerHeightPx)
            }
            canvas.drawText(
                matchNote, fromStart(noteOffset),
                viewTop + headerHeightPx / 2f + paints.secondaryBaselineOffsetPx,
                paints.labelSecondary,
            )
            canvas.restore()
            paints.labelSecondary.textAlign = previousSecondary
        }
        paints.label.textAlign = previous
    }

    /** The x [offset] in from the start edge: the left one, or the right when [rightToLeft]. */
    private fun fromStart(offset: Float): Float = if (rightToLeft) width - offset else offset

    private fun drawScrollbar(canvas: Canvas, viewTop: Float) {
        val viewport = height - headerHeightPx
        if (contentHeight <= height || viewport <= 0f) {
            return
        }
        val track = viewport - paddingPx * 2f
        val thumbLength = (track * viewport / contentHeight).coerceAtLeast(paddingPx * 3f)
        val travel = track - thumbLength
        val progress = (scrollY.toFloat() / maxScroll()).coerceIn(0f, 1f)
        val topInView = headerHeightPx + paddingPx + travel * progress
        val barWidth = paddingPx * 0.6f
        val left = if (rightToLeft) paddingPx else width - paddingPx - barWidth
        canvas.drawRoundRect(
            left, viewTop + topInView, left + barWidth, viewTop + topInView + thumbLength,
            barWidth / 2f, barWidth / 2f, paints.labelSecondary,
        )
    }

    private fun drawCard(canvas: Canvas, index: Int, top: Float) {
        val entry = entries[index]
        cardRect.set(paddingPx, top, width - paddingPx, top + cardHeightPx)
        val fill = if (index == pressedCard || index == actionCard) paints.keyPressedFill else paints.keyFill
        canvas.drawRoundRect(cardRect, paints.keyCornerRadiusPx, paints.keyCornerRadiusPx, fill)
        if (index == actionCard) {
            drawActions(canvas, entry, top)
            return
        }
        // The same decision the keys make. A theme with borders off and outlined cards would
        // be one surface disagreeing with itself about whether this keyboard draws edges.
        if (paints.showKeyBorders) {
            canvas.drawRoundRect(
                cardRect, paints.keyCornerRadiusPx, paints.keyCornerRadiusPx, paints.keyStroke,
            )
        }

        val inset = paddingPx
        var textLeft = cardRect.left + inset
        var textRight = cardRect.right - inset
        val thumbnail = thumbnails[entry.id]
        if (thumbnail != null) {
            // At the card's start edge: the left one, or the right when rightToLeft.
            val side = (cardHeightPx - inset * 2f).toInt()
            val thumbLeft = if (rightToLeft) textRight.toInt() - side else textLeft.toInt()
            thumbRect.set(thumbLeft, (top + inset).toInt(), thumbLeft + side, (top + inset).toInt() + side)
            canvas.drawBitmap(thumbnail, null, thumbRect, null)
            if (rightToLeft) {
                textRight = thumbRect.left - inset
            } else {
                textLeft = thumbRect.right + inset
            }
        }

        // Aligned to where the text itself begins, unlike everything else this keyboard draws:
        // the right edge for a text whose first strong letter reads right to left, the left
        // edge otherwise, whichever way the panel runs.
        val label = labelFor(entry)
        val length = label.length.coerceAtMost(MAX_LABEL_CHARS)
        val textRightToLeft = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(label, 0, length)
        val previous = paints.label.textAlign
        paints.label.textAlign = if (textRightToLeft) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.save()
        canvas.clipRect(textLeft, top, textRight, top + cardHeightPx)
        canvas.drawText(
            label, 0, length,
            if (textRightToLeft) textRight else textLeft,
            top + cardHeightPx / 2f + paints.labelBaselineOffsetPx, paints.label,
        )
        canvas.restore()
        paints.label.textAlign = previous

        if (entry.isPinned) {
            // A dot rather than a pin glyph: the panel has no icon set of its own, and the
            // question a reader has is "does this one survive the timer", which a mark answers.
            // At the corner opposite the start edge.
            canvas.drawCircle(
                if (rightToLeft) cardRect.left + inset else cardRect.right - inset,
                top + inset + PIN_RADIUS_FRACTION * cardHeightPx,
                PIN_RADIUS_FRACTION * cardHeightPx, paints.accent,
            )
        }
    }

    /** The card's actions in place of its content: pin or unpin, edit for text, delete. */
    private fun drawActions(canvas: Canvas, entry: ClipEntry, top: Float) {
        val zones = actionCount(entry)
        val zoneWidth = cardRect.width() / zones
        val previous = paints.label.textAlign
        paints.label.textAlign = android.graphics.Paint.Align.CENTER
        val baseline = top + cardHeightPx / 2f + paints.labelBaselineOffsetPx
        for (zone in 0 until zones) {
            val label = actionLabel(entry, zone)
            canvas.drawText(label, cardRect.left + zoneWidth * (slotOf(zone, zones) + 0.5f), baseline, paints.label)
        }
        paints.label.textAlign = previous
    }

    private fun actionCount(entry: ClipEntry): Int = if (entry.isImage) 2 else 3

    /** Where action [zone] of [zones] sits, counted from the left edge: mirrored when [rightToLeft]. */
    private fun slotOf(zone: Int, zones: Int): Int = Mirror.slot(zone, zones, rightToLeft)

    private fun actionLabel(entry: ClipEntry, zone: Int): String = when {
        zone == 0 -> if (entry.isPinned) unpinLabel else pinLabel
        zone == 1 && !entry.isImage -> editLabel
        else -> deleteLabel
    }

    private fun openActions() {
        if (pressedCard < 0 || dragging) {
            return
        }
        actionCard = pressedCard
        pressedCard = -1
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    /** Runs the action under [x] on the open action card, closing the actions either way. */
    private fun pickAction(x: Float) {
        val index = actionCard
        actionCard = -1
        invalidate()
        if (index !in entries.indices) {
            return
        }
        val entry = entries[index]
        val zones = actionCount(entry)
        val slot = ((x - paddingPx) / ((width - paddingPx * 2f) / zones)).toInt().coerceIn(0, zones - 1)
        val zone = slotOf(slot, zones)
        when {
            zone == 0 -> listener?.onClipPinToggled(entry)
            zone == 1 && !entry.isImage -> listener?.onClipEdited(entry)
            else -> listener?.onClipDeleted(entry)
        }
    }

    private fun labelFor(entry: ClipEntry): String = when {
        entry.isImage && thumbnails[entry.id] == null -> strings[Keys.CLIP_IMAGE_UNAVAILABLE]
        entry.isImage -> strings[Keys.CLIP_IMAGE]
        else -> entry.content
    }

    private fun cardAt(y: Float): Int {
        if (entries.isEmpty() || y < headerHeightPx) {
            return -1
        }
        val step = cardHeightPx + paddingPx
        val index = ((y + scrollY - headerHeightPx - paddingPx) / step).toInt()
        return if (index in entries.indices) index else -1
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
                longPressFired = false
                pressedHeader = event.y < headerHeightPx
                pressedCard = if (pressedHeader) -1 else cardAt(event.y)
                if (pressedCard >= 0 && actionCard < 0) {
                    postDelayed(longPressRunnable, longPressTimeout)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastY - event.y
                if (!dragging && kotlin.math.abs(delta) > touchSlop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                    pressedCard = -1
                    pressedHeader = false
                    invalidate()
                }
                if (dragging) {
                    lastY = event.y
                    scrollBy(0, delta.toInt())
                    clampScroll()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (dragging) {
                    tracker.computeCurrentVelocity(1000)
                    scroller.fling(
                        0, scrollY, 0, -tracker.yVelocity.toInt(),
                        0, 0, 0, maxScroll(),
                    )
                    postInvalidateOnAnimation()
                } else if (longPressFired) {
                    // The actions opened under this finger; they wait for the next tap.
                } else if (actionCard >= 0) {
                    // A tap on the open action card picks one of its actions; a tap anywhere
                    // else only closes them.
                    if (pressedCard == actionCard) {
                        pickAction(event.x)
                    } else {
                        actionCard = -1
                    }
                } else if (pressedHeader && event.y < headerHeightPx) {
                    listener?.onClipboardPanelClosed()
                } else {
                    val index = pressedCard
                    if (index >= 0) {
                        listener?.onClipPicked(entries[index])
                    }
                }
                pressedCard = -1
                pressedHeader = false
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                pressedCard = -1
                pressedHeader = false
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
        }
        return false
    }

    private fun maxScroll(): Int = (contentHeight - height).coerceAtLeast(0)

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
        /** The pinned "back" bar, as a fraction of a key row. */
        const val HEADER_HEIGHT_ROWS = 0.66f

        /** A left-pointing arrow, drawn rather than translated: it is a direction, not a word. */
        const val BACK_GLYPH = "←"

        /** The same arrow pointing right, for a header that runs from the right edge. */
        const val BACK_GLYPH_RTL = "→"

        /** A card is this many key rows tall: enough for two lines of text beside a thumbnail. */
        const val CARD_HEIGHT_ROWS = 0.9f

        /** The gap around and between cards, as a fraction of a key row. */
        const val PADDING_ROWS = 0.12f

        /** Never decode below this, however short the panel is when the list arrives. */
        const val MIN_THUMBNAIL_PX = 96

        /** Drawn text is clipped to the card, but a bound keeps a huge clip off the draw path. */
        const val MAX_LABEL_CHARS = 120

        const val PIN_RADIUS_FRACTION = 0.08f
    }
}
