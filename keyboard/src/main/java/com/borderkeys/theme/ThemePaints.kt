// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.theme

import android.graphics.Paint
import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import com.borderkeys.data.theme.KeyboardTheme

/**
 * The theme compiled into paints and sizes for the draw path, recompiled in place when the theme
 * or the density changes. UI thread only.
 */
class ThemePaints {

    val background: Paint = Paint()

    /** The surface behind everything, shared by every view that paints a background. */
    val backgroundPainter = KeyboardBackground()
    val keyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyPressedFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val modifierKeyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyStroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Marks the suggestion strip's applied word: a traced outline or a filled chip, as
     * [KeyboardTheme.appliedHighlightStyle] says.
     */
    val appliedHighlight: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val label: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val labelSecondary: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accent: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Label text in the accent colour. */
    val accentLabel: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The word exactly as it was typed on the suggestion strip, in italic. */
    val labelTyped: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val swipeTrail: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** The particles' paint, shared by every view and recoloured per particle. */
    val particlePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** A key's corner hint: the character a long press would type, or the dots. */
    val hint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var hintCellHalfWidth: Float = 0f
    private var hintCellTopInset: Float = 0f

    /**
     * Half the width of the box a corner hint is centred in, whose right edge is the key's: the
     * widest hint glyph times [HINT_CELL_MARGIN].
     */
    val hintCellHalfWidthPx: Float
        get() = hintCellHalfWidth

    /** The hint's baseline below the key's top edge. */
    val hintCellTopInsetPx: Float
        get() = hintCellTopInset

    var keyCornerRadiusPx: Float = 0f
        private set
    var keyGapPx: Float = 0f
        private set
    var rowHeightPx: Float = 0f
        private set
    var swipeTrailWidthPx: Float = 0f
        private set

    /** How far a pressed key lifts, in pixels, drawn as an offset. */
    var pressedElevationPx: Float = 0f
        private set

    var showKeyBorders: Boolean = false
        private set

    /** The theme these paints were compiled from. */
    var theme: KeyboardTheme = KeyboardTheme()
        private set

    private var density: Float = 0f
    private var scaledDensity: Float = 0f

    private val fontMetrics = Paint.FontMetrics()
    private var labelBaselineOffset: Float = 0f
    private var secondaryBaselineOffset: Float = 0f

    /** Vertical offset from a key's centre to the label baseline. */
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

        label.textAlign = Paint.Align.CENTER
        label.typeface = Typeface.DEFAULT
        labelSecondary.textAlign = Paint.Align.CENTER
        labelSecondary.typeface = Typeface.DEFAULT
        hint.textAlign = Paint.Align.CENTER
        hint.typeface = Typeface.DEFAULT
        accentLabel.textAlign = Paint.Align.CENTER
        accentLabel.typeface = Typeface.DEFAULT
        labelTyped.textAlign = Paint.Align.CENTER
        labelTyped.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)

        swipeTrail.style = Paint.Style.STROKE
        swipeTrail.strokeCap = Paint.Cap.ROUND
        swipeTrail.strokeJoin = Paint.Join.ROUND

        particlePaint.style = Paint.Style.FILL
    }

    /** Multiplier on the row height, from the size settings. */
    var heightScale: Float = 1f
        private set

    /** The name of the picture currently decoded. */
    private var loadedImage: String? = null

    /** Recompiles if the theme, the density or the height scale changed; returns whether it did. */
    fun update(
        theme: KeyboardTheme,
        metrics: DisplayMetrics,
        heightScale: Float = 1f,
        context: android.content.Context? = null,
    ): Boolean {
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
        backgroundPainter.update(theme, metrics.density)
        // Decoded on the UI thread, only when the picture changed.
        if (context != null && theme.backgroundImage != loadedImage) {
            loadedImage = theme.backgroundImage
            backgroundPainter.setImage(
                com.borderkeys.data.theme.BackgroundImages.load(context, theme.backgroundImage),
            )
        }
        keyFill.color = theme.keyColor
        keyPressedFill.color = theme.keyPressedColor
        modifierKeyFill.color = theme.modifierKeyColor
        accent.color = theme.accentColor
        accentLabel.color = theme.accentColor

        keyStroke.color = theme.secondaryTextColor
        keyStroke.strokeWidth = 1f * density

        // One colour: an opaque thin line for the outline, a quarter-strength tint for the fill.
        val highlightBase = theme.appliedHighlightColorOrDefault()
        val highlightFilled = theme.appliedHighlightStyle == KeyboardTheme.APPLIED_HIGHLIGHT_BACKGROUND
        appliedHighlight.style = if (highlightFilled) Paint.Style.FILL else Paint.Style.STROKE
        appliedHighlight.color = if (highlightFilled) {
            (highlightBase and 0x00FFFFFF) or (0x40 shl 24)
        } else {
            highlightBase or 0xFF000000.toInt()
        }
        appliedHighlight.strokeWidth = 1f * density

        label.color = theme.textColor
        label.textSize = theme.labelTextSizeSp * newScaledDensity
        labelSecondary.color = theme.secondaryTextColor
        labelSecondary.textSize = theme.labelTextSizeSp * newScaledDensity * 0.62f
        hint.color = theme.secondaryTextColor
        hint.textSize = theme.accentTextSizeSp * newScaledDensity
        // After label, whose size they copy.
        accentLabel.textSize = label.textSize
        labelTyped.color = theme.textColor
        labelTyped.textSize = label.textSize

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

        // The widest of HINT_WIDTH_PROBE, measured.
        var widestHintGlyph = 0f
        for (probe in HINT_WIDTH_PROBE) {
            widestHintGlyph = maxOf(widestHintGlyph, hint.measureText(probe, 0, 1))
        }
        hintCellHalfWidth = maxOf(widestHintGlyph, hint.textSize) / 2f * HINT_CELL_MARGIN
        hint.getFontMetrics(fontMetrics)
        hintCellTopInset = -fontMetrics.ascent * HINT_CELL_MARGIN

        return true
    }

    /**
     * The height of the suggestion strip and of the inline suggestions row: a fraction of
     * [rowHeightPx], or of a fallback before that is set.
     */
    fun suggestionRowHeightPx(): Int {
        val row = if (rowHeightPx > 0f) rowHeightPx else SUGGESTION_ROW_DEFAULT_PX
        return (row * SUGGESTION_ROW_HEIGHT_FRACTION).toInt()
    }

    companion object {
        /** The row height assumed while [rowHeightPx] is still zero. */
        const val DEFAULT_ROW_HEIGHT_PX = 132f

        /** The suggestion row's fallback and fraction, for [suggestionRowHeightPx]. */
        private const val SUGGESTION_ROW_DEFAULT_PX = 150f
        private const val SUGGESTION_ROW_HEIGHT_FRACTION = 0.78f

        /** How much bigger the hint's box is than the widest hint glyph. */
        private const val HINT_CELL_MARGIN = 1.1f

        /** The widest hint characters: a digit, number-row symbols and accented letters. */
        private val HINT_WIDTH_PROBE = listOf(
            "0", "%", "@", "}", "œ", "æ", "ß", "ñ", "î",
        )
    }
}
