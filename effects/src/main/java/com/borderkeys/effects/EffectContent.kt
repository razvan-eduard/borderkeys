// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/**
 * What an effect is showing, once [EffectStyle] has decided where it is.
 *
 * The stage moves a thing; this is the thing. Separating the two is what lets one animation
 * serve a word, an emoji and an icon rather than the word it happened to be written for: an
 * autocorrect can float the word it applied, a paste can float the clipboard's own glyph, and
 * both are the same [EffectStage] with the same style and colour behind them.
 */
sealed interface EffectContent {

    /** Draws centred on (`x`, `y`). [paint] already carries the colour and opacity to use. */
    fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, paint: Paint)

    /** Whether there is anything at all to draw -- an empty word plays nothing. */
    fun isEmpty(): Boolean

    /**
     * A word, an emoji, or any other run of characters.
     *
     * [sizeMultiplier] is against the theme's own label size, so the text reads as a statement
     * about the word rather than as another key.
     */
    class Text(private val text: String, private val sizeMultiplier: Float) : EffectContent {

        override fun isEmpty(): Boolean = text.isEmpty()

        override fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, paint: Paint) {
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = paint.textSize * sizeMultiplier * scale
            canvas.drawText(text, x, y, paint)
        }
    }

    /**
     * A drawable, sized against the key area rather than its own intrinsic bounds so an icon
     * and a word of the same effect arrive at the same weight on screen.
     *
     * The drawable is tinted and faded through [paint]'s own colour and alpha rather than
     * through its state, because it is shared with whatever else is drawing it.
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
