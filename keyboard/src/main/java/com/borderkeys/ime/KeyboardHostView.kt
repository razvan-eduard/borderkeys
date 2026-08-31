// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import com.borderkeys.theme.ThemePaints

/**
 * The root of the input view: three children stacked vertically, laid out by arithmetic.
 *
 * Built in code, never inflated. `LayoutInflater` parses XML, reflects to construct each view and
 * walks an attribute table, and it does that every time the input view is created -- which is
 * every time the keyboard is shown in a new editor. Three children whose positions are two
 * additions do not need any of that.
 *
 * No `ConstraintLayout` and no `LinearLayout` either, for the same reason: a measure pass that
 * resolves constraints, for a stack.
 *
 * Only one of the two suggestion rows is visible at a time. When a password manager has inline
 * suggestions to offer, its row takes the place of ours -- and in a password field ours is
 * empty anyway, which is the point.
 */
@SuppressLint("ViewConstructor")
class KeyboardHostView(
    context: Context,
    paints: ThemePaints,
) : ViewGroup(context) {

    val suggestionStrip = SuggestionStripView(context, paints)
    val inlineSuggestions = InlineSuggestionsHostView(context, paints)
    val keyboard = KeyboardCanvasView(context, paints)

    /**
     * Covers the keys while the assistant's answer is on screen.
     *
     * Present in both flavors because the view is in `:keyboard`; it is only ever shown when
     * `:assist` exists to fill it, which in the free build is never.
     */
    val assistSheet = AssistSheetView(context, paints)

    /**
     * Space the system's own IME navigation bar occupies along the bottom edge.
     *
     * Android 15 enforces edge-to-edge for the input method window, so the framework draws its
     * switcher and hide-keyboard controls over whatever we put there unless we move out of the
     * way. Without this the bottom row -- symbols, comma, space, full stop, enter -- sits
     * underneath them, and the keys are both unreadable and untappable.
     */
    private var navigationBarInset = 0

    // ---- size and position -------------------------------------------------------------------
    //
    // A keyboard is the one part of the screen a thumb has to reach a hundred times a minute,
    // and the right size for it depends on the hand holding the phone rather than on the phone.
    // These four values are what let it be moved instead of endured, and they arrive from the
    // preferences flow, so a change applies to the keyboard that is already on screen.

    /** Fraction of the available width the keys occupy. Below 1 only away from docked. */
    private var widthScale = 1f
    /** MODE_ constants from KeyboardPreferences. */
    private var positionMode = 0
    private var bottomOffsetPx = 0
    private var horizontalOffsetPx = 0

    fun setPlacement(mode: Int, widthScale: Float, bottomOffsetPx: Int, horizontalOffsetPx: Int) {
        val docked = mode == MODE_DOCKED
        val effectiveWidth = if (docked) 1f else widthScale.coerceIn(0.4f, 1f)
        if (this.positionMode == mode && this.widthScale == effectiveWidth &&
            this.bottomOffsetPx == bottomOffsetPx && this.horizontalOffsetPx == horizontalOffsetPx
        ) {
            return
        }
        this.positionMode = mode
        this.widthScale = effectiveWidth
        // Docked means flush with the bottom edge; a gap under a docked keyboard is just a gap.
        this.bottomOffsetPx = if (docked) 0 else bottomOffsetPx
        this.horizontalOffsetPx = if (mode == MODE_FLOATING) horizontalOffsetPx else 0
        requestLayout()
    }

    /** Where the keys start horizontally, given the mode. */
    private fun contentLeft(totalWidth: Int, contentWidth: Int): Int = when (positionMode) {
        MODE_ONE_HANDED_LEFT -> 0
        MODE_ONE_HANDED_RIGHT -> totalWidth - contentWidth
        MODE_FLOATING -> ((totalWidth - contentWidth) / 2 + horizontalOffsetPx)
            .coerceIn(0, totalWidth - contentWidth)
        else -> 0
    }

    init {
        // Painted by the children; the group itself has nothing to draw.
        setWillNotDraw(true)
        isClickable = false
        addView(suggestionStrip)
        addView(inlineSuggestions)
        addView(keyboard)
        addView(assistSheet)
        inlineSuggestions.visibility = GONE
        assistSheet.visibility = GONE

        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = insets.getInsets(
                WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout(),
            ).bottom
            if (bottom != navigationBarInset) {
                navigationBarInset = bottom
                requestLayout()
            }
            // Consumed rather than passed on: the children are ours, they fill what is left, and
            // none of them has any use for an inset.
            WindowInsets.CONSUMED
        }
    }

    /**
     * Switches between our suggestions and the autofill service's.
     *
     * Called from `onInlineSuggestionsResponse`, which can arrive at any moment while an editor
     * is focused.
     */
    fun showInlineSuggestions(show: Boolean) {
        val wantsInline = show && inlineSuggestions.hasSuggestions
        val inlineVisibility = if (wantsInline) VISIBLE else GONE
        val stripVisibility = if (wantsInline) GONE else VISIBLE
        if (inlineSuggestions.visibility != inlineVisibility ||
            suggestionStrip.visibility != stripVisibility
        ) {
            inlineSuggestions.visibility = inlineVisibility
            suggestionStrip.visibility = stripVisibility
            requestLayout()
        }
    }

    /** Puts the assistant's sheet over the keys, or takes it away. */
    fun showAssistSheet(show: Boolean) {
        val visibility = if (show) VISIBLE else GONE
        if (assistSheet.visibility != visibility) {
            assistSheet.visibility = visibility
            keyboard.visibility = if (show) GONE else VISIBLE
            requestLayout()
        }
    }

    val assistSheetVisible: Boolean get() = assistSheet.visibility == VISIBLE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width * widthScale).toInt().coerceAtLeast(1)
        val exactWidth = MeasureSpec.makeMeasureSpec(
            contentWidth, MeasureSpec.EXACTLY,
        )
        val unbounded = MeasureSpec.makeMeasureSpec(
            0, MeasureSpec.UNSPECIFIED,
        )

        var height = 0
        if (suggestionStrip.visibility != GONE) {
            suggestionStrip.measure(exactWidth, unbounded)
            height += suggestionStrip.measuredHeight
        }
        if (inlineSuggestions.visibility != GONE) {
            inlineSuggestions.measure(exactWidth, unbounded)
            height += inlineSuggestions.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.measure(exactWidth, unbounded)
            height += keyboard.measuredHeight
        }
        if (assistSheet.visibility != GONE) {
            assistSheet.measure(exactWidth, unbounded)
            height += assistSheet.measuredHeight
        }

        // The window is always full width; the keys are narrower and offset inside it. That
        // keeps the touchable region and the insets the system computes correct in every mode.
        setMeasuredDimension(width, height + navigationBarInset + bottomOffsetPx)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val contentWidth = (width * widthScale).toInt().coerceAtLeast(1)
        val left = contentLeft(width, contentWidth)
        val right = left + contentWidth
        var y = 0
        if (suggestionStrip.visibility != GONE) {
            suggestionStrip.layout(left, y, right, y + suggestionStrip.measuredHeight)
            y += suggestionStrip.measuredHeight
        }
        if (inlineSuggestions.visibility != GONE) {
            inlineSuggestions.layout(left, y, right, y + inlineSuggestions.measuredHeight)
            y += inlineSuggestions.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.layout(left, y, right, y + keyboard.measuredHeight)
            y += keyboard.measuredHeight
        }
        if (assistSheet.visibility != GONE) {
            assistSheet.layout(left, y, right, y + assistSheet.measuredHeight)
        }
    }

    private companion object {
        // Mirrors KeyboardPreferences. Duplicated rather than imported so that :keyboard's view
        // layer does not depend on :data for four integers.
        const val MODE_DOCKED = 0
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2
        const val MODE_FLOATING = 3
    }
}
