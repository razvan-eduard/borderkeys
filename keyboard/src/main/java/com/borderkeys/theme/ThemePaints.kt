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

    /**
     * The surface behind everything, which is more than a colour once a gradient or a pattern
     * is in play. Kept here so the one tile and the one gradient are shared by every view that
     * paints a background rather than built per view.
     */
    val backgroundPainter = KeyboardBackground()
    val keyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyPressedFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val modifierKeyFill: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val keyStroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Marks the suggestion strip's applied word: a traced outline or a filled chip, whichever
     * [KeyboardTheme.appliedHighlightStyle] says. Its own paint rather than [keyStroke] reused --
     * [keyStroke] is shared by several views for their own drawing, mutated in place like
     * everything here, and a style flip meant for this one chip has no business also being a
     * style flip for whichever of them draws next.
     */
    val appliedHighlight: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val label: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val labelSecondary: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val accent: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * Label text in the accent colour, for the one chip on the suggestion strip that inserts
     * something the user did not type. Its own paint rather than [label] recoloured per draw,
     * because a Paint's colour is state and the draw path does not set state it can avoid.
     */
    val accentLabel: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The word exactly as it was typed, on the suggestion strip.
     *
     * Italic, so that the one chip which is not a suggestion does not have to be read to be
     * told apart from the ones that are. Its own paint rather than [label] with a typeface set
     * per draw, for the same reason as [accentLabel]: a typeface is state, and the draw path
     * does not set state it can avoid.
     */
    val labelTyped: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val swipeTrail: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A key's corner hint -- the character a long press would type, or the "more here" dots --
     * drawn at its own size rather than [labelSecondary]'s, which several other views share
     * unchanged. See [hintCellHalfWidthPx] for how it is kept off the key's own edge.
     */
    val hint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var hintCellHalfWidth: Float = 0f
    private var hintCellTopInset: Float = 0f

    /**
     * Half the width of the invisible box a corner hint is centred inside of.
     *
     * The box's own right edge is the key's right edge -- there is no separate gap constant
     * added on top. The box is deliberately bigger than the widest glyph it ever has to hold
     * (every symbol, digit and accent used as a hint, probed in [update]), so its size *is*
     * the gap: a bigger box pushes every hint further from its corner, a smaller one lets them
     * sit closer, and there is exactly one number ([HINT_CELL_MARGIN]) that does it everywhere
     * at once, the way padding on a container does.
     */
    val hintCellHalfWidthPx: Float
        get() = hintCellHalfWidth

    /** Same idea vertically: the box's top edge is the key's own top edge. */
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
        hint.textAlign = Paint.Align.CENTER
        hint.typeface = Typeface.DEFAULT
        accentLabel.textAlign = Paint.Align.CENTER
        accentLabel.typeface = Typeface.DEFAULT
        labelTyped.textAlign = Paint.Align.CENTER
        labelTyped.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)

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

    /**
     * The name of the picture currently decoded, so a theme change that did not touch it does
     * not decode a megabyte of webp again.
     */
    private var loadedImage: String? = null

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
        // Only when the name moved. Decoding happens on the UI thread, which is tolerable once
        // on a theme change and would not be on every one of them.
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

        // The colour itself is stable -- appliedHighlightColorOrDefault() no longer depends on
        // the style -- so what changes here with the style is purely how much of it is drawn:
        // a thin opaque line for the outline (full opacity was never a problem for a 1dp
        // stroke), a quarter-strength tint for the fill (full opacity would be a solid block
        // sitting on top of the word it marks). Neither computation is visible outside this
        // function, so the theme screen's own swatch row always rings the one colour underneath
        // both looks.
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
        // The hint character on a long-press key, at two thirds the size. Fixed ratio rather
        // than a second theme field: it is a typographic relationship, not a preference.
        labelSecondary.textSize = theme.labelTextSizeSp * newScaledDensity * 0.62f
        hint.color = theme.secondaryTextColor
        // A quarter larger again than labelSecondary's own hint-sized text -- it is the one
        // place that size is read at a glance while a finger is already coming down near it,
        // not just glanced at while reading, the way the suggestion strip or the clipboard
        // panel are.
        hint.textSize = labelSecondary.textSize * HINT_TEXT_SCALE
        // Set after label, whose size and colour they borrow.
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

        // The widest single character this ever draws as a hint, measured rather than guessed:
        // a digit, the widest of the number-row symbols, and the widest accented letter any
        // bundled language's overlay can put on a key. HINT_CELL_MARGIN is what turns that
        // into the box -- see hintCellHalfWidthPx's own doc.
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
     * The height [SuggestionStripView][com.borderkeys.ime.SuggestionStripView] and
     * [InlineSuggestionsHostView][com.borderkeys.ime.InlineSuggestionsHostView] each measure
     * themselves to -- one row's own fraction of [rowHeightPx], with its own fallback for the
     * frame before that has ever been set. One function rather than the same three lines typed
     * into both views' `onMeasure`: the two rows take one another's place depending on whether a
     * password manager has anything to offer, so a change to how one is sized belongs to both.
     */
    fun suggestionRowHeightPx(): Int {
        val row = if (rowHeightPx > 0f) rowHeightPx else SUGGESTION_ROW_DEFAULT_PX
        return (row * SUGGESTION_ROW_HEIGHT_FRACTION).toInt()
    }

    companion object {
        /**
         * The row height assumed before the first theme update, when [rowHeightPx] is still
         * zero -- read by every panel that can be measured on that first frame
         * ([com.borderkeys.ime.EmojiPanelView], [com.borderkeys.ime.ClipboardPanelView],
         * [com.borderkeys.ime.KeyboardHostView]'s resize frame) rather than each keeping its own
         * copy of the same placeholder.
         */
        const val DEFAULT_ROW_HEIGHT_PX = 132f

        /**
         * The suggestion row's own fallback and fraction, for [suggestionRowHeightPx]. Its own
         * value rather than [DEFAULT_ROW_HEIGHT_PX]: a suggestion row is a different shape from
         * a full key row, tuned to look right at 78% of 150, not of 132.
         */
        private const val SUGGESTION_ROW_DEFAULT_PX = 150f
        private const val SUGGESTION_ROW_HEIGHT_FRACTION = 0.78f

        /** How much bigger [hint] reads than [labelSecondary]'s own text size. */
        private const val HINT_TEXT_SCALE = 1.25f

        /**
         * How much bigger the hint's invisible box is than the widest glyph it has to hold.
         * 1.0 would be the tightest fit that never clips; this is the one number that sets how
         * far every hint sits from its key's corner -- see [hintCellHalfWidthPx]'s own doc.
         */
        private const val HINT_CELL_MARGIN = 1.3f

        /**
         * Single characters wide enough to matter: a digit, the widest of the number-row
         * symbols, and the widest accented letter any bundled language's overlay puts on a
         * key. Not every hint character -- just the ones likely to set the maximum.
         */
        private val HINT_WIDTH_PROBE = listOf(
            "0", "%", "@", "}", "œ", "æ", "ß", "ñ", "î",
        )
    }
}
