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
        val exactWidth = MeasureSpec.makeMeasureSpec(
            width, MeasureSpec.EXACTLY,
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

        setMeasuredDimension(width, height + navigationBarInset)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var y = 0
        if (suggestionStrip.visibility != GONE) {
            suggestionStrip.layout(0, y, width, y + suggestionStrip.measuredHeight)
            y += suggestionStrip.measuredHeight
        }
        if (inlineSuggestions.visibility != GONE) {
            inlineSuggestions.layout(0, y, width, y + inlineSuggestions.measuredHeight)
            y += inlineSuggestions.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.layout(0, y, width, y + keyboard.measuredHeight)
            y += keyboard.measuredHeight
        }
        if (assistSheet.visibility != GONE) {
            assistSheet.layout(0, y, width, y + assistSheet.measuredHeight)
        }
    }
}
