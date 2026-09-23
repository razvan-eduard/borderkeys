// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.effects

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.AnimationUtils

/**
 * Where an effect plays: the middle of the keys, above them, never in the way.
 *
 * Laid out across the whole host like the radial menu, drawn above the keys and never
 * touchable: [onTouchEvent] is not overridden and the view is [android.view.View.GONE] whenever
 * nothing is playing, so a tap during an animation reaches the key underneath.
 *
 * Driven by [postInvalidateOnAnimation] off the frame clock rather than a `ValueAnimator`, the
 * same reason the key canvas's press states are: no allocation and no object outliving the
 * view on a surface that is created and destroyed with every field.
 *
 * The stage knows when, [EffectStyle] knows where, and [EffectContent] knows what. Keeping the
 * three apart is what lets a style be chosen in settings and a word, an emoji or an icon play
 * through the same animation -- rather than each pairing being its own view.
 */
class EffectStage(context: Context) : View(context) {

    /**
     * What an effect is drawn from when it asks for no colour of its own.
     *
     * Set by the host from its own theme. Taken as a paint rather than as a theme because this
     * module must not depend on the one that owns themes -- that module depends on this one.
     */
    var basePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private class Playing(
        val content: EffectContent,
        val style: EffectStyle,
        val colour: Int?,
        val durationMillis: Float,
    )

    private var current: Playing? = null
    private var startedAt: Long = 0L
    private var centreX: Float = 0f
    private var centreY: Float = 0f

    // What has been asked for while something else is playing. Two, not unbounded: an effect on
    // a frequent event -- an autocorrect, a learned word -- can be asked for faster than it can
    // be watched, and a queue that keeps every one of them would go on playing long after the
    // typing that caused it. The oldest waiting one is dropped instead, so what plays is always
    // about what just happened.
    private val pending = ArrayDeque<Playing>(MAX_PENDING)

    /**
     * The key area's own rect, set by the host from the key canvas's bounds.
     *
     * Not this view's own width and height: the keys can be resized and shifted to one side, and
     * an effect centred on the window would then sit off the keyboard it came from.
     */
    var keyAreaLeft: Float = 0f
    var keyAreaRight: Float = 0f
    var keyAreaTop: Float = 0f
    var keyAreaBottom: Float = 0f

    /**
     * Plays [content] with [style], in [colour] or the theme's own label colour when null.
     *
     * A call while something is already playing waits its turn rather than cutting in, so two
     * events in quick succession are both seen.
     */
    fun play(
        content: EffectContent,
        style: EffectStyle = EffectStyle.DEFAULT,
        colour: Int? = null,
        durationMillis: Float = DEFAULT_DURATION_MILLIS,
    ) {
        if (content.isEmpty()) {
            return
        }
        val next = Playing(content, style, colour, durationMillis)
        if (current != null) {
            while (pending.size >= MAX_PENDING) {
                pending.removeFirst()
            }
            pending.addLast(next)
            return
        }
        begin(next)
    }

    /** Plays a word, the commonest case, at the size a statement about a word is drawn at. */
    fun playWord(text: String, style: EffectStyle = EffectStyle.DEFAULT, colour: Int? = null) {
        play(EffectContent.Text(text, TEXT_SIZE_MULTIPLIER), style, colour)
    }

    /** Stops whatever is playing, and forgets whatever was waiting behind it. */
    fun stop() {
        current = null
        pending.clear()
        visibility = GONE
    }

    private fun begin(next: Playing) {
        current = next
        startedAt = AnimationUtils.currentAnimationTimeMillis()
        centreX = (keyAreaLeft + keyAreaRight) / 2f
        centreY = (keyAreaTop + keyAreaBottom) / 2f
        visibility = VISIBLE
        postInvalidateOnAnimation()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        val playing = current ?: return
        val elapsed = AnimationUtils.currentAnimationTimeMillis() - startedAt
        val progress = elapsed.toFloat() / playing.durationMillis
        if (progress >= 1f) {
            current = null
            val next = pending.removeFirstOrNull()
            if (next != null) {
                begin(next)
            } else {
                visibility = GONE
            }
            return
        }

        val travel = keyAreaBottom - keyAreaTop
        val transform = playing.style.at(progress, travel)

        paint.set(basePaint)
        playing.colour?.let { paint.color = it }
        paint.alpha = (transform.alpha * 255f).toInt().coerceIn(0, 255)

        playing.content.draw(
            canvas, centreX + transform.dx, centreY + transform.dy, transform.scale, paint,
        )
        postInvalidateOnAnimation()
    }

    companion object {

        /** How long one effect runs for, unless the caller asks for another length. */
        const val DEFAULT_DURATION_MILLIS = 1100f

        /** Bigger than a key's label, so a word reads as a statement about itself rather than
         *  as another key. */
        const val TEXT_SIZE_MULTIPLIER = 2.6f

        private const val MAX_PENDING = 2
    }
}
