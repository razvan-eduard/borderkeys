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
 * Where an effect plays: the middle of the keys, above them, never touchable. [onTouchEvent] is
 * not overridden and the view is [android.view.View.GONE] whenever nothing is playing. Driven by
 * [postInvalidateOnAnimation] off the frame clock. [EffectStyle] says where an effect is and
 * [EffectContent] what it shows.
 */
class EffectStage(context: Context) : View(context) {

    /** What an effect is drawn from when it asks for no colour of its own; set by the host. */
    var basePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** How fast an effect runs, 1 the default; set by the host. */
    var speed: Float = 1f

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

    // Asked for while something else is playing: at most MAX_PENDING, the oldest dropped first.
    private val pending = ArrayDeque<Playing>(MAX_PENDING)

    /** The key area's rect, set by the host from the key canvas's bounds. */
    var keyAreaLeft: Float = 0f
    var keyAreaRight: Float = 0f
    var keyAreaTop: Float = 0f
    var keyAreaBottom: Float = 0f

    /**
     * Plays [content] with [style], in [colour] or the theme's label colour when null. A call
     * while something is playing waits its turn.
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

    /** Plays a word, at [TEXT_SIZE_MULTIPLIER] times the label size. */
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
        val progress = elapsed * speed / playing.durationMillis
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

        /** A played word's size, against a key's label. */
        const val TEXT_SIZE_MULTIPLIER = 2.6f

        private const val MAX_PENDING = 2
    }
}
