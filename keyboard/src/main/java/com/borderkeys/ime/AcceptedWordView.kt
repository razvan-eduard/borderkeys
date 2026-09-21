// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.borderkeys.theme.ThemePaints
import kotlin.math.sin

/**
 * The word a swipe settled on, rising from the middle of the keys and fading out.
 *
 * Laid out across the whole host like [RadialSuggestionMenuView], drawn above the keys and
 * never touchable: [onTouchEvent] is not overridden and the view is [android.view.View.GONE]
 * whenever nothing is playing, so a tap during the animation reaches the key underneath.
 *
 * Driven by [postInvalidateOnAnimation] off the frame clock rather than a `ValueAnimator`, the
 * same reason [KeyboardCanvasView]'s press states are: no allocation and no object outliving
 * the view on a surface that is created and destroyed with every field.
 */
class AcceptedWordView(context: Context, private val paints: ThemePaints) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var word: String = ""
    private var startedAt: Long = 0L
    private var centreX: Float = 0f
    private var centreY: Float = 0f

    /**
     * The key area's own rect, set by [KeyboardHostView] from [KeyboardCanvasView]'s bounds.
     *
     * Not this view's own width and height: the keys can be resized and shifted to one side, and
     * a word centred on the window would then sit off the keyboard it came from.
     */
    var keyAreaLeft: Float = 0f
    var keyAreaRight: Float = 0f
    var keyAreaTop: Float = 0f
    var keyAreaBottom: Float = 0f

    /** Starts the animation for [text]. A second call restarts it rather than queueing. */
    fun play(text: String) {
        if (text.isEmpty()) {
            return
        }
        word = text
        startedAt = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        centreX = (keyAreaLeft + keyAreaRight) / 2f
        centreY = (keyAreaTop + keyAreaBottom) / 2f
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    /** Stops whatever is playing without drawing another frame. */
    fun stop() {
        word = ""
        visibility = GONE
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        if (word.isEmpty()) {
            return
        }
        val elapsed = android.view.animation.AnimationUtils.currentAnimationTimeMillis() - startedAt
        val progress = elapsed.toFloat() / DURATION_MILLIS
        if (progress >= 1f) {
            stop()
            return
        }
        // Rises the whole way and fades in then out across it, so the word is brightest halfway
        // up -- the curve the composer's own swipe hint uses.
        val eased = progress * progress * (3f - 2f * progress)
        val alpha = sin(progress * Math.PI.toFloat()).coerceIn(0f, 1f)

        paint.set(paints.label)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = paints.label.textSize * SIZE_MULTIPLIER
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)

        val rise = (keyAreaBottom - keyAreaTop) * RISE_FRACTION * eased
        canvas.drawText(word, centreX, centreY - rise, paint)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val DURATION_MILLIS = 1100f

        /** How far up the word travels, as a fraction of the key area's own height. */
        const val RISE_FRACTION = 0.5f

        /** Bigger than a key's label, so it reads as a statement about the word rather than as
         *  another key. */
        const val SIZE_MULTIPLIER = 2.6f
    }
}
