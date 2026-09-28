// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/** What an effect is showing, once [EffectStyle] has decided where it is. */
sealed interface EffectContent {

    /** Draws centred on (`x`, `y`). [paint] already carries the colour and opacity to use. */
    fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, paint: Paint)

    /** Whether there is anything at all to draw -- an empty word plays nothing. */
    fun isEmpty(): Boolean

    /** A word, an emoji, or any other run of characters, [sizeMultiplier] times the label size. */
    class Text(private val text: String, private val sizeMultiplier: Float) : EffectContent {

        override fun isEmpty(): Boolean = text.isEmpty()

        override fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, paint: Paint) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = paint.textSize * sizeMultiplier * scale
            canvas.drawText(text, x, y, paint)
        }
    }

    /**
     * A drawable, drawn [side] pixels square rather than at its intrinsic size, in the paint's
     * colour and alpha.
     */
    class Icon(private val drawable: Drawable, private val side: Float) : EffectContent {

        override fun isEmpty(): Boolean = side <= 0f

        override fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, paint: Paint) {
            val half = side * scale / 2f
            drawable.setBounds(
                (x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt(),
            )
            drawable.alpha = paint.alpha
            drawable.setTint(paint.color)
            drawable.draw(canvas)
        }
    }
}
