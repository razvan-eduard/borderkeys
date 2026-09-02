// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import com.borderkeys.data.theme.KeyboardTheme

/**
 * The theme, compiled into objects the draw path can use without touching anything else.
 *
 * `onDraw` has four milliseconds for the invalidated region and is not allowed to allocate. That
 * rules out constructing a `Paint`, reading a `?attr/`, converting dp to pixels, or measuring
 * text while drawing -- every one of those is either an allocation or a lookup, and at sixty
 * frames a second with a finger moving across the keyboard they are the frame budget.
 *
 * So all of it happens here instead, exactly twice: when the theme changes and when the display
 * density changes. Everything is mutated in place, so even recompiling allocates nothing after
 * the first construction. The draw path then does nothing but `canvas.drawRoundRect(..., keyFill)`.
 *
 * Not thread safe, and does not need to be: it is written and read on the UI thread only.
 */
class ThemePaints {

    val background: Paint = Paint()
    val keyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyPressedFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val modifierKeyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyStroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val label: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val labelSecondary: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accent: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Label text in the accent colour, for the one chip on the suggestion strip that inserts
     * something the user did not type. Its own paint rather than [label] recoloured per draw,
     * because a Paint's colour is state and the draw path does not set state it can avoid.
     */
    val accentLabel: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val swipeTrail: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var keyCornerRadiusPx: Float = 0f
        private set
    var keyGapPx: Float = 0f
        private set
    var rowHeightPx: Float = 0f
        private set
    var swipeTrailWidthPx: Float = 0f
        private set

    /**
     * How far a pressed key lifts, in pixels.
     *
     * Used as a geometric offset, not as a blur. `Paint.setShadowLayer` on a shape forces the
     * canvas onto a software layer, which costs far more than the effect is worth on something
     * redrawn on every touch -- so the view draws the pressed key inset by this instead.
     */
    var pressedElevationPx: Float = 0f
        private set

    var showKeyBorders: Boolean = false
        private set

    /** The theme these paints were compiled from. */
    var theme: KeyboardTheme = KeyboardTheme()
        private set

    private var density: Float = 0f
    private var scaledDensity: Float = 0f

    // Reused: Paint.getFontMetrics allocates a FontMetrics unless it is handed one, and this is
    // the only place that asks for them.
    private val fontMetrics = Paint.FontMetrics()
    private var labelBaselineOffset: Float = 0f
    private var secondaryBaselineOffset: Float = 0f

    /**
     * Vertical offset from a key's centre to the text baseline.
     *
     * Precomputed for the same reason as everything else here: the draw path centres a label by
     * adding this to the key's centre y, instead of measuring the font on every key on every
     * frame.
     */
    val labelBaselineOffsetPx: Float
        get() = labelBaselineOffset

    val secondaryBaselineOffsetPx: Float
        get() = secondaryBaselineOffset

    init {
        background.style = Paint.Style.FILL
        keyFill.style = Paint.Style.FILL
        keyPressedFill.style = Paint.Style.FILL
        modifierKeyFill.style = Paint.Style.FILL
        accent.style = Paint.Style.FILL

        keyStroke.style = Paint.Style.STROKE

        // Centred once here rather than offset per draw call: drawText with a centred paint takes
        // the key's centre x directly, so the view does not compute a left edge per label.
        label.textAlign = Paint.Align.CENTER
        label.typeface = Typeface.DEFAULT
        labelSecondary.textAlign = Paint.Align.CENTER
        labelSecondary.typeface = Typeface.DEFAULT
        accentLabel.textAlign = Paint.Align.CENTER
        accentLabel.typeface = Typeface.DEFAULT

        swipeTrail.style = Paint.Style.STROKE
        swipeTrail.strokeCap = Paint.Cap.ROUND
        swipeTrail.strokeJoin = Paint.Join.ROUND
    }

    /**
     * Recompiles if anything relevant changed. Returns true when it did, so the caller knows
     * whether the static background layer has to be redrawn.
     *
     * Cheap to call on every theme emission: an unchanged theme at an unchanged density does
     * nothing at all.
     */
    /**
     * Multiplier on the row height, from the size settings.
     *
     * Kept here rather than applied by the view because everything that depends on row height --
     * the keyboard's measured height, the suggestion strip, the assistant sheet -- reads it from
     * this one place, and scaling it in three of them would let them disagree.
     */
    var heightScale: Float = 1f
        private set

    fun update(theme: KeyboardTheme, metrics: DisplayMetrics, heightScale: Float = 1f): Boolean {
        val newScaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, metrics)
        if (this.theme == theme && density == metrics.density &&
            scaledDensity == newScaledDensity && this.heightScale == heightScale
        ) {
            return false
        }
        this.heightScale = heightScale
        this.theme = theme
        density = metrics.density
        scaledDensity = newScaledDensity

        background.color = theme.backgroundColor
        keyFill.color = theme.keyColor
        keyPressedFill.color = theme.keyPressedColor
        modifierKeyFill.color = theme.modifierKeyColor
        accent.color = theme.accentColor
        accentLabel.color = theme.accentColor

        keyStroke.color = theme.secondaryTextColor
        keyStroke.strokeWidth = 1f * density

        label.color = theme.textColor
        label.textSize = theme.labelTextSizeSp * newScaledDensity
        labelSecondary.color = theme.secondaryTextColor
        // The hint character on a long-press key, at two thirds the size. Fixed ratio rather
        // than a second theme field: it is a typographic relationship, not a preference.
        labelSecondary.textSize = theme.labelTextSizeSp * newScaledDensity * 0.62f
        // Set after label, whose size it borrows.
        accentLabel.textSize = label.textSize

        swipeTrail.color = theme.swipeTrailColor
        swipeTrail.strokeWidth = theme.swipeTrailWidthDp * density

        keyCornerRadiusPx = theme.keyCornerRadiusDp * density
        keyGapPx = theme.keyGapDp * density
        rowHeightPx = theme.rowHeightDp * density * heightScale
        swipeTrailWidthPx = theme.swipeTrailWidthDp * density
        pressedElevationPx = theme.pressedElevation * density
        showKeyBorders = theme.showKeyBorders

        label.getFontMetrics(fontMetrics)
        labelBaselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f
        labelSecondary.getFontMetrics(fontMetrics)
        secondaryBaselineOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f

        return true
    }
}
