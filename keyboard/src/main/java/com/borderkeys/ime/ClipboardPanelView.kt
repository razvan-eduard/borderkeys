// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Trace
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
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

        /** The panel asked to be closed. */
        fun onClipboardPanelClosed()
    }

    var listener: Listener? = null

    private var entries: List<ClipEntry> = emptyList()

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

    private var cardHeightPx = 0f
    private var paddingPx = 0f
    private var contentHeight = 0

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

    /** Replaces the list, decoding thumbnails for whatever images are in it. */
    fun setEntries(list: List<ClipEntry>) {
        entries = list
        thumbnails.keys.retainAll(list.map { it.id }.toSet())
        for (entry in list) {
            if (entry.isImage && !thumbnails.containsKey(entry.id)) {
                thumbnails[entry.id] = decodeThumbnail(entry)
            }
        }
        scroller.forceFinished(true)
        scrollTo(0, 0)
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
        val row = if (paints.rowHeightPx > 0f) paints.rowHeightPx else DEFAULT_ROW_PX
        cardHeightPx = row * CARD_HEIGHT_ROWS
        paddingPx = row * PADDING_ROWS
        contentHeight = ((cardHeightPx + paddingPx) * entries.size + paddingPx).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        Trace.beginSection("ClipboardPanelView.onDraw")
        try {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.background)
            if (entries.isEmpty()) {
                canvas.drawText(
                    emptyChars, 0, emptyLength,
                    width / 2f, height / 2f + paints.secondaryBaselineOffsetPx,
                    paints.labelSecondary,
                )
                return
            }
            val step = cardHeightPx + paddingPx
            // Only the cards the viewport actually shows are drawn. With a bounded history this
            // is a small saving; it is here because a list that scrolls must not get slower the
            // further down it goes.
            val first = ((scrollY - paddingPx) / step).toInt().coerceAtLeast(0)
            val last = (((scrollY + height) / step).toInt() + 1).coerceAtMost(entries.size - 1)
            for (index in first..last) {
                drawCard(canvas, index, paddingPx + step * index)
            }
        } finally {
            Trace.endSection()
        }
    }

    private fun drawCard(canvas: Canvas, index: Int, top: Float) {
        val entry = entries[index]
        cardRect.set(paddingPx, top, width - paddingPx, top + cardHeightPx)
        val fill = if (index == pressedCard) paints.keyPressedFill else paints.keyFill
        canvas.drawRoundRect(cardRect, paints.keyCornerRadiusPx, paints.keyCornerRadiusPx, fill)
        canvas.drawRoundRect(
            cardRect, paints.keyCornerRadiusPx, paints.keyCornerRadiusPx, paints.keyStroke,
        )

        val inset = paddingPx
        var textLeft = cardRect.left + inset
        val thumbnail = thumbnails[entry.id]
        if (thumbnail != null) {
            val side = (cardHeightPx - inset * 2f).toInt()
            thumbRect.set(
                (cardRect.left + inset).toInt(), (top + inset).toInt(),
                (cardRect.left + inset).toInt() + side, (top + inset).toInt() + side,
            )
            canvas.drawBitmap(thumbnail, null, thumbRect, null)
            textLeft = thumbRect.right + inset
        }

        // Left aligned, unlike everything else this keyboard draws: a card is read from its
        // start, and a centred line of copied text is a line nobody can scan down.
        val previous = paints.label.textAlign
        paints.label.textAlign = android.graphics.Paint.Align.LEFT
        val label = labelFor(entry)
        val available = cardRect.right - inset - textLeft
        canvas.save()
        canvas.clipRect(textLeft, top, cardRect.right - inset, top + cardHeightPx)
        canvas.drawText(
            label, 0, label.length.coerceAtMost(MAX_LABEL_CHARS),
            textLeft, top + cardHeightPx / 2f + paints.labelBaselineOffsetPx, paints.label,
        )
        canvas.restore()
        paints.label.textAlign = previous

        if (entry.isPinned) {
            // A dot rather than a pin glyph: the panel has no icon set of its own, and the
            // question a reader has is "does this one survive the timer", which a mark answers.
            canvas.drawCircle(
                cardRect.right - inset, top + inset + PIN_RADIUS_FRACTION * cardHeightPx,
                PIN_RADIUS_FRACTION * cardHeightPx, paints.accent,
            )
        }
        if (available <= 0f) {
            return
        }
    }

    private fun labelFor(entry: ClipEntry): String = when {
        entry.isImage && thumbnails[entry.id] == null -> strings[Keys.CLIP_IMAGE_UNAVAILABLE]
        entry.isImage -> strings[Keys.CLIP_IMAGE]
        else -> entry.content
    }

    private fun cardAt(y: Float): Int {
        if (entries.isEmpty()) {
            return -1
        }
        val step = cardHeightPx + paddingPx
        val index = ((y + scrollY - paddingPx) / step).toInt()
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
                pressedCard = cardAt(event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = lastY - event.y
                if (!dragging && kotlin.math.abs(delta) > touchSlop) {
                    dragging = true
                    pressedCard = -1
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
                if (dragging) {
                    tracker.computeCurrentVelocity(1000)
                    scroller.fling(
                        0, scrollY, 0, -tracker.yVelocity.toInt(),
                        0, 0, 0, maxScroll(),
                    )
                    postInvalidateOnAnimation()
                } else {
                    val index = pressedCard
                    if (index >= 0) {
                        listener?.onClipPicked(entries[index])
                    }
                }
                pressedCard = -1
                dragging = false
                velocity?.recycle()
                velocity = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedCard = -1
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
        /** A card is this many key rows tall: enough for two lines of text beside a thumbnail. */
        const val CARD_HEIGHT_ROWS = 0.9f

        /** The gap around and between cards, as a fraction of a key row. */
        const val PADDING_ROWS = 0.12f

        const val DEFAULT_ROW_PX = 132f

        /** Never decode below this, however short the panel is when the list arrives. */
        const val MIN_THUMBNAIL_PX = 96

        /** Drawn text is clipped to the card, but a bound keeps a huge clip off the draw path. */
        const val MAX_LABEL_CHARS = 120

        const val PIN_RADIUS_FRACTION = 0.08f
    }
}
