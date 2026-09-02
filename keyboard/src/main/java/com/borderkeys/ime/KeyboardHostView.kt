// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import com.borderkeys.theme.ThemePaints
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys

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
    private val paints: ThemePaints,
    private val strings: LanguageManager,
) : ViewGroup(context) {

    val suggestionStrip = SuggestionStripView(context, paints, strings)
    val inlineSuggestions = InlineSuggestionsHostView(context, paints)
    val keyboard = KeyboardCanvasView(context, paints, strings)

    /**
     * Covers the keys while the assistant's answer is on screen.
     *
     * Present in both flavors because the view is in `:keyboard`; it is only ever shown when
     * `:assist` exists to fill it, which in the free build is never.
     */
    val assistSheet = AssistSheetView(context, paints, strings)

    /**
     * Size and position, reachable without leaving the keyboard. Covers the keys the same way
     * the assistant's sheet does, because it is the same trade: the panel needs the space, and
     * the keys are not useful while it is open.
     */
    val quickSettings = QuickSettingsView(context, paints, strings)
    val quickActions = QuickActionsView(context, paints, strings)
    val clipboardPanel = ClipboardPanelView(context, paints, strings)

    /**
     * Which edge the quick-action bar sits against. Mirrors KeyboardPreferences; kept as an Int
     * so this module does not depend on :data for four constants.
     */
    var quickActionsPlacement: Int = 0
        set(value) {
            if (field != value) {
                field = value
                quickActions.vertical = value == PLACEMENT_LEFT || value == PLACEMENT_RIGHT
                requestLayout()
            }
        }

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

    /** Whether the empty space beside the keys offers a way to move it across. */
    var edgeArrows: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Called when the arrow in the gutter is tapped. */
    var onMoveToOtherSide: (() -> Unit)? = null

    /**
     * Where the arrow is, in this view's coordinates, or empty when there is none.
     *
     * Computed in layout rather than per touch: the gutter only moves when the placement does,
     * and a touch that has to recompute geometry is a touch that has to think.
     */
    private val arrowBounds = android.graphics.Rect()
    private val arrowPath = android.graphics.Path()
    private var arrowPressed = false

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
        // The children paint themselves; the group paints only the arrow in the gutter, and only
        // when the keys have been narrowed enough to leave one.
        setWillNotDraw(false)
        isClickable = false
        addView(suggestionStrip)
        addView(inlineSuggestions)
        addView(keyboard)
        addView(assistSheet)
        addView(quickSettings)
        // Last, so it draws over the others where a side bar overlaps a rounded corner. It is
        // measured and laid out by this class like the rest; being a child is what makes that
        // reach it at all -- the first version measured it and never added it, so it took up
        // height in the window and drew nothing in it.
        addView(clipboardPanel)
        clipboardPanel.visibility = GONE
        addView(quickActions)
        inlineSuggestions.visibility = GONE
        assistSheet.visibility = GONE
        quickSettings.visibility = GONE

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

    /** Opens or closes the quick panel, hiding the keys underneath it while it is open. */
    fun showQuickSettings(show: Boolean) {
        val visibility = if (show) VISIBLE else GONE
        if (quickSettings.visibility != visibility) {
            quickSettings.visibility = visibility
            keyboard.visibility = if (show) GONE else VISIBLE
            requestLayout()
        }
    }

    val quickSettingsVisible: Boolean get() = quickSettings.visibility == VISIBLE

    val clipboardPanelVisible: Boolean get() = clipboardPanel.visibility == VISIBLE

    /**
     * Shows or hides the clipboard history, standing the keys down while it is up.
     *
     * The keys go rather than being covered: a panel drawn over live keys is a panel a stray
     * touch types through, and the window keeps its height either way because the panel is
     * measured to exactly the height the keys had.
     */
    fun setClipboardPanelVisible(visible: Boolean) {
        if (clipboardPanelVisible == visible) {
            return
        }
        clipboardPanel.visibility = if (visible) VISIBLE else GONE
        keyboard.visibility = if (visible) GONE else VISIBLE
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val contentWidth = (width * widthScale).toInt().coerceAtLeast(1)
        val exactWidth = MeasureSpec.makeMeasureSpec(
            contentWidth, MeasureSpec.EXACTLY,
        )
        val unbounded = MeasureSpec.makeMeasureSpec(
            0, MeasureSpec.UNSPECIFIED,
        )

        // A bar down the side takes width from the keys; one above or below takes height. Both
        // are measured before anything else so the keys are laid out in what is left, rather
        // than being pushed off the bottom of a window that was already sized.
        val sideBar = quickActions.visibility != GONE &&
            (quickActionsPlacement == PLACEMENT_LEFT || quickActionsPlacement == PLACEMENT_RIGHT)
        var barThickness = 0
        if (quickActions.visibility != GONE) {
            quickActions.measure(
                if (sideBar) unbounded else exactWidth,
                if (sideBar) MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED) else unbounded,
            )
            barThickness = if (sideBar) quickActions.measuredWidth else quickActions.measuredHeight
        }
        val bodyWidth = (contentWidth - if (sideBar) barThickness else 0).coerceAtLeast(1)
        val exactBody = MeasureSpec.makeMeasureSpec(bodyWidth, MeasureSpec.EXACTLY)

        var height = 0
        if (suggestionStrip.visibility != GONE) {
            suggestionStrip.measure(exactBody, unbounded)
            height += suggestionStrip.measuredHeight
        }
        if (inlineSuggestions.visibility != GONE) {
            inlineSuggestions.measure(exactBody, unbounded)
            height += inlineSuggestions.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.measure(exactBody, unbounded)
            height += keyboard.measuredHeight
        }
        if (assistSheet.visibility != GONE) {
            assistSheet.measure(exactBody, unbounded)
            height += assistSheet.measuredHeight
        }
        if (quickSettings.visibility != GONE) {
            // The panel takes exactly the height the keys would have had, so opening it does not
            // move the editor's text or resize the window under the user's finger.
            quickSettings.measure(exactBody, MeasureSpec.makeMeasureSpec(
                keyboardHeightForPanel(bodyWidth), MeasureSpec.EXACTLY,
            ))
            height += quickSettings.measuredHeight
        }
        if (clipboardPanel.visibility != GONE) {
            // Same rule, same reason: the window keeps the height it had, so opening the
            // history does not shove the conversation up the screen and back down again.
            clipboardPanel.measure(exactBody, MeasureSpec.makeMeasureSpec(
                keyboardHeightForPanel(bodyWidth), MeasureSpec.EXACTLY,
            ))
            height += clipboardPanel.measuredHeight
        }
        if (quickActions.visibility != GONE) {
            if (sideBar) {
                // Re-measured now that the body's height is known, because a side bar is as tall
                // as what it sits beside.
                quickActions.measure(
                    MeasureSpec.makeMeasureSpec(barThickness, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            } else {
                height += barThickness
            }
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
        val sideBar = quickActions.visibility != GONE &&
            (quickActionsPlacement == PLACEMENT_LEFT || quickActionsPlacement == PLACEMENT_RIGHT)
        val barThickness = when {
            quickActions.visibility == GONE -> 0
            sideBar -> quickActions.measuredWidth
            else -> quickActions.measuredHeight
        }
        val bodyLeft = if (sideBar && quickActionsPlacement == PLACEMENT_LEFT) {
            left + barThickness
        } else {
            left
        }
        val bodyRight = if (sideBar && quickActionsPlacement == PLACEMENT_RIGHT) {
            right - barThickness
        } else {
            right
        }
        var y = 0
        if (quickActions.visibility != GONE && quickActionsPlacement == PLACEMENT_ABOVE_STRIP) {
            quickActions.layout(left, y, right, y + barThickness)
            y += barThickness
        }
        if (suggestionStrip.visibility != GONE) {
            suggestionStrip.layout(bodyLeft, y, bodyRight, y + suggestionStrip.measuredHeight)
            y += suggestionStrip.measuredHeight
        }
        if (inlineSuggestions.visibility != GONE) {
            inlineSuggestions.layout(bodyLeft, y, bodyRight, y + inlineSuggestions.measuredHeight)
            y += inlineSuggestions.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.layout(bodyLeft, y, bodyRight, y + keyboard.measuredHeight)
            y += keyboard.measuredHeight
        }
        if (assistSheet.visibility != GONE) {
            assistSheet.layout(bodyLeft, y, bodyRight, y + assistSheet.measuredHeight)
            y += assistSheet.measuredHeight
        }
        if (quickSettings.visibility != GONE) {
            quickSettings.layout(bodyLeft, y, bodyRight, y + quickSettings.measuredHeight)
            y += quickSettings.measuredHeight
        }
        if (clipboardPanel.visibility != GONE) {
            clipboardPanel.layout(bodyLeft, y, bodyRight, y + clipboardPanel.measuredHeight)
            y += clipboardPanel.measuredHeight
        }
        if (quickActions.visibility != GONE) {
            when (quickActionsPlacement) {
                PLACEMENT_BELOW_KEYS -> quickActions.layout(left, y, right, y + barThickness)
                PLACEMENT_LEFT -> quickActions.layout(left, 0, left + barThickness, y)
                PLACEMENT_RIGHT -> quickActions.layout(right - barThickness, 0, right, y)
                else -> Unit
            }
        }
        layoutArrow(width, left, right, keyboard.top, keyboard.bottom)
    }

    /**
     * Puts an arrow in the wider of the two gutters, pointing at the emptier side.
     *
     * Only where there is room for a target a thumb can hit: below that the arrow is either
     * invisible or a mis-tap waiting to happen next to the outermost key, and the panel behind
     * the globe still moves the keyboard.
     */
    private fun layoutArrow(width: Int, contentLeft: Int, contentRight: Int, top: Int,
                            bottom: Int) {
        arrowBounds.setEmpty()
        if (!edgeArrows || positionMode == MODE_DOCKED || quickSettings.visibility != GONE) {
            return
        }
        val leftGutter = contentLeft
        val rightGutter = width - contentRight
        val gutter = maxOf(leftGutter, rightGutter)
        val minimum = (resources.displayMetrics.density * MIN_ARROW_GUTTER_DP).toInt()
        if (gutter < minimum || bottom <= top) {
            return
        }
        val centreY = (top + bottom) / 2
        val half = minOf(gutter, (resources.displayMetrics.density * MAX_ARROW_SIZE_DP).toInt()) / 2
        val centreX = if (rightGutter >= leftGutter) width - rightGutter / 2 else leftGutter / 2
        arrowBounds.set(centreX - half, centreY - half, centreX + half, centreY + half)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (arrowBounds.isEmpty) {
            return
        }
        // Pointing at the gutter it sits in, which is the direction the keyboard would travel.
        val pointsRight = arrowBounds.centerX() > width / 2
        // The label paints are fills, so the arrow is a filled triangle rather than a stroke.
        val paint = if (arrowPressed) paints.label else paints.labelSecondary
        val inset = arrowBounds.width() * 0.22f
        val tipX = if (pointsRight) arrowBounds.right - inset else arrowBounds.left + inset
        val baseX = if (pointsRight) arrowBounds.left + inset else arrowBounds.right - inset
        arrowPath.reset()
        arrowPath.moveTo(baseX, arrowBounds.top + inset)
        arrowPath.lineTo(tipX, arrowBounds.exactCenterY())
        arrowPath.lineTo(baseX, arrowBounds.bottom - inset)
        canvas.drawPath(arrowPath, paint)
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (arrowBounds.isEmpty) {
            return false
        }
        val inside = arrowBounds.contains(event.x.toInt(), event.y.toInt())
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (!inside) {
                    return false
                }
                arrowPressed = true
                invalidate()
                return true
            }

            android.view.MotionEvent.ACTION_UP -> {
                val wasPressed = arrowPressed
                arrowPressed = false
                invalidate()
                if (wasPressed && inside) {
                    onMoveToOtherSide?.invoke()
                }
                return wasPressed
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                arrowPressed = false
                invalidate()
                return true
            }
        }
        return arrowPressed
    }

    /**
     * What the keys would have measured, so the panel can take their place exactly.
     *
     * Measured rather than remembered: the keyboard is GONE while the panel is open, and a view
     * that is GONE reports a measured height of zero.
     */
    private fun keyboardHeightForPanel(widthSpec: Int): Int {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        keyboard.measure(widthSpec, unbounded)
        return keyboard.measuredHeight
    }

    private companion object {
        // Mirrors KeyboardPreferences. Duplicated rather than imported so that :keyboard's view
        // layer does not depend on :data for four integers.
        const val MODE_DOCKED = 0

        // Mirrors KeyboardPreferences.QUICK_ACTIONS_*. Duplicated rather than depended on, for
        // the same reason MODE_DOCKED is: four integers are not worth a module edge.
        const val PLACEMENT_ABOVE_STRIP = 0
        const val PLACEMENT_BELOW_KEYS = 1
        const val PLACEMENT_LEFT = 2
        const val PLACEMENT_RIGHT = 3
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2
        const val MODE_FLOATING = 3

        /** Below this there is not enough empty space for a target a thumb can hit. */
        const val MIN_ARROW_GUTTER_DP = 28f
        const val MAX_ARROW_SIZE_DP = 56f
    }
}
