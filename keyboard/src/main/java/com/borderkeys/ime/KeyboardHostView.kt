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

    /**
     * A place to write that the application cannot see, above everything else.
     *
     * The only child here that does *not* replace the keys. Every panel below is something you
     * do instead of typing, so it takes the keys' place and the window keeps its height; this is
     * something you type into, so the window grows to hold it and the keys stay where they are.
     */
    val composer = ComposerView(context, paints, strings)

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
    val emojiPanel = EmojiPanelView(context, paints)

    /**
     * Which edge the quick-action bar sits against. Mirrors KeyboardPreferences; kept as an Int
     * so this module does not depend on :data for four constants.
     */
    /**
     * Reports a drag on one of the resize handles, in the units the settings store.
     *
     * The view knows where the finger is; what a scale means is the service's business, and it
     * is the one that can write it down.
     */
    var onResizeDrag: ((height: Float, width: Float, offset: Float) -> Unit)? = null

    /** Called when a resize drag ends, so the result can be written once rather than per frame. */
    var onResizeFinished: (() -> Unit)? = null

    /** Called when the user is done resizing, from the overlay's own way out. */
    var onResizeExit: (() -> Unit)? = null

    /**
     * Whether the keyboard is showing its resize handles.
     *
     * A mode rather than handles that are always there: a handle on the edge of a keyboard is
     * a handle a thumb reaching for the outermost key finds by accident, every time.
     */
    var resizing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                draggingHandle = HANDLE_NONE
                resetHit.setEmpty()
                doneHit.setEmpty()
                onThemeChanged()
                invalidate()
            }
        }

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

    /** Mirrors the theme's own flag; pushed in with the theme rather than read per frame. */
    var fullWidthBackground: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The height scale, kept so a drag can start from where the keyboard already is. */
    var heightScaleForDrag: Float = 1f

    private val handleFrame = android.graphics.RectF()
    private val resetHit = android.graphics.RectF()
    private val doneHit = android.graphics.RectF()
    private val labelMetrics = android.graphics.Paint.FontMetrics()

    /**
     * The overlay's own paints.
     *
     * Not the theme's key stroke: that one is hairline-thin and, on a theme with borders turned
     * off, has no width at all -- which would draw the resize frame as nothing.
     */
    private val resizeFrame = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
    }
    private val resizeScrim = android.graphics.Paint()
    private val pillFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val resizeLabel = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var draggingHandle = HANDLE_NONE
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragStartWidth = 1f
    private var dragStartHeight = 1f
    private var dragBaseHeightPx = 1f

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

    /**
     * Turns a drag on a handle into the three numbers the settings hold.
     *
     * Reported continuously so the keyboard resizes under the finger, and written once when the
     * finger lifts -- a preferences write per frame would be sixty database writes a second for
     * a value only the last of which matters.
     */
    private fun handleResizeTouch(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (doneHit.contains(event.x, event.y)) {
                    onResizeExit?.invoke()
                    return true
                }
                if (resetHit.contains(event.x, event.y)) {
                    onResizeDrag?.invoke(1f, 1f, 0f)
                    onResizeFinished?.invoke()
                    return true
                }
                draggingHandle = handleAt(event.x, event.y)
                // Screen coordinates, not this view's.
                //
                // The view is the thing being resized: growing the keyboard by a hundred
                // pixels moves this view's top edge a hundred pixels up the screen, so a
                // finger that has not moved reads a hundred pixels further down in local
                // coordinates. Measuring the drag that way makes every frame cancel the last
                // one, and the keyboard bounces between two sizes instead of following the
                // finger. rawX and rawY are fixed to the screen and do not move with it.
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragStartWidth = widthScale
                dragStartHeight = heightScaleForDrag
                // The height the keys would have at scale 1, captured once. Dividing the
                // finger's travel by it makes the drag linear: the same distance is the same
                // change in scale, whether the keyboard is currently short or tall.
                dragBaseHeightPx = ((keyboard.bottom - keyboardTopForResize()) /
                    dragStartHeight.coerceAtLeast(0.01f)).coerceAtLeast(1f)
                invalidate()
                return true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (draggingHandle == HANDLE_NONE) {
                    return true
                }
                when (draggingHandle) {
                    // Up is taller: the keyboard grows from its bottom edge, which stays put.
                    HANDLE_TOP -> onResizeDrag?.invoke(
                        dragStartHeight + (dragStartY - event.rawY) / dragBaseHeightPx,
                        widthScale,
                        horizontalOffsetPx.toFloat(),
                    )

                    // Dragging away from the keys widens it, whichever side the handle is on.
                    // How fast depends on what the other edge is doing: a centred keyboard
                    // grows at both ends at once, so the edge under the finger only accounts
                    // for half the width, while a one-handed keyboard is pinned to its side
                    // and the edge under the finger accounts for all of it. Using one factor
                    // for both made the one-handed keyboard leap out from under the finger.
                    HANDLE_LEFT, HANDLE_RIGHT -> {
                        val direction = if (draggingHandle == HANDLE_LEFT) -1f else 1f
                        val edges = if (isOneHanded()) 1f else 2f
                        val delta = direction * (event.rawX - dragStartX) * edges /
                            width.coerceAtLeast(1)
                        onResizeDrag?.invoke(heightScaleForDrag, dragStartWidth + delta,
                            horizontalOffsetPx.toFloat())
                    }
                }
                return true
            }

            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                val wasDragging = draggingHandle != HANDLE_NONE
                draggingHandle = HANDLE_NONE
                invalidate()
                if (wasDragging) {
                    onResizeFinished?.invoke()
                }
                return true
            }
        }
        return true
    }

    fun setPlacement(mode: Int, widthScale: Float, bottomOffsetPx: Int, horizontalOffsetPx: Int) {
        val docked = mode == MODE_DOCKED
        // The dock honours the width too: the resize handles are on the keyboard in every mode,
        // and a side handle that does nothing in the mode most people are in is a broken
        // handle. Docked and narrow means centred, which contentLeft does.
        val effectiveWidth = widthScale.coerceIn(0.4f, 1f)
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

    /** True when one edge of the keys is pinned to the edge of the screen. */
    private fun isOneHanded(): Boolean =
        positionMode == MODE_ONE_HANDED_LEFT || positionMode == MODE_ONE_HANDED_RIGHT

    /** Where the keys start horizontally, given the mode. */
    private fun contentLeft(totalWidth: Int, contentWidth: Int): Int = when (positionMode) {
        MODE_ONE_HANDED_LEFT -> 0
        MODE_ONE_HANDED_RIGHT -> totalWidth - contentWidth
        MODE_FLOATING -> ((totalWidth - contentWidth) / 2 + horizontalOffsetPx)
            .coerceIn(0, totalWidth - contentWidth)
        // Centred, not flush left: a docked keyboard narrowed by the resize handles should sit
        // in the middle of the screen. At full width the two are the same expression.
        else -> (totalWidth - contentWidth) / 2
    }

    init {
        // The children paint themselves; the group paints only the arrow in the gutter, and only
        // when the keys have been narrowed enough to leave one.
        setWillNotDraw(false)
        isClickable = false
        // One background for the whole window, painted here. A pattern drawn separately by each
        // child would restart its tile at that child's corner, which shows as a seam along
        // every edge where two children meet.
        keyboard.drawsBackground = false
        suggestionStrip.drawsBackground = false
        composer.visibility = GONE
        addView(composer)
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
        addView(emojiPanel)
        emojiPanel.visibility = GONE
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
    val emojiPanelVisible: Boolean get() = emojiPanel.visibility == VISIBLE

    /** Shows or hides the emoji grid, standing the keys down while it is up. */
    fun setEmojiPanelVisible(visible: Boolean) {
        if (emojiPanelVisible == visible) {
            return
        }
        if (visible) {
            emojiPanel.load(context)
        }
        emojiPanel.visibility = if (visible) VISIBLE else GONE
        keyboard.visibility = if (visible) GONE else VISIBLE
        requestLayout()
    }

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
        // Measured unbounded, like the strip and the keys and unlike the panels: it chooses its
        // own height from what has been written, and the window grows by whatever that is.
        if (composer.visibility != GONE) {
            composer.measure(exactBody, unbounded)
            height += composer.measuredHeight
        }
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
        if (emojiPanel.visibility != GONE) {
            emojiPanel.measure(exactBody, MeasureSpec.makeMeasureSpec(
                keyboardHeightForPanel(bodyWidth), MeasureSpec.EXACTLY,
            ))
            height += emojiPanel.measuredHeight
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
        if (composer.visibility != GONE) {
            composer.layout(bodyLeft, y, bodyRight, y + composer.measuredHeight)
            y += composer.measuredHeight
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
        if (emojiPanel.visibility != GONE) {
            emojiPanel.layout(bodyLeft, y, bodyRight, y + emojiPanel.measuredHeight)
            y += emojiPanel.measuredHeight
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
        drawBackground(canvas)
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
    /**
     * Paints the surface the keys sit on.
     *
     * Full width by default, including the space beside a one-handed or floating keyboard: that
     * space used to be a hole showing the application underneath, and a background that stops
     * at the keys leaves the pattern nowhere to show. Down to the keyboard's own bottom rather
     * than the view's, so the gap a floating keyboard is lifted by stays a gap.
     */
    private fun drawBackground(canvas: android.graphics.Canvas) {
        val bottom = (height - bottomOffsetPx).toFloat()
        if (bottom <= 0f) {
            return
        }
        if (fullWidthBackground) {
            paints.backgroundPainter.draw(canvas, 0f, 0f, width.toFloat(), bottom)
            return
        }
        val contentWidth = (width * widthScale).toInt().coerceAtLeast(1)
        val left = contentLeft(width, contentWidth).toFloat()
        paints.backgroundPainter.draw(canvas, left, 0f, left + contentWidth, bottom)
    }

    /**
     * Shows or hides the draft box.
     *
     * Note what is missing: the line every other panel here has, turning the keys off. Its
     * absence is the design -- a box you cannot type into would be a box for nothing.
     */
    fun setComposerVisible(visible: Boolean) {
        val wanted = if (visible) VISIBLE else GONE
        if (composer.visibility == wanted) {
            return
        }
        composer.visibility = wanted
        requestLayout()
    }

    val composerVisible: Boolean get() = composer.visibility == VISIBLE

    /**
     * Re-measures every child after the shared metrics changed.
     *
     * `View.measure` skips a child whose measure spec has not changed, and the specs do not
     * change when the row height does -- the children read that from the paints they share
     * rather than from the spec. Without forcing each one, a taller row height only makes the
     * key labels bigger inside a keyboard that keeps the height it had.
     */
    fun relayoutForNewMetrics() {
        for (i in 0 until childCount) {
            getChildAt(i).forceLayout()
        }
        requestLayout()
    }

    /** Re-reads the overlay's colours from the theme. Cheap, and only on a theme change. */
    fun onThemeChanged() {
        resizeFrame.color = paints.accent.color
        resizeFrame.strokeWidth =
            (paints.rowHeightPx.takeIf { it > 0f } ?: DEFAULT_ROW_PX) * RESIZE_FRAME_ROWS
        resizeScrim.color = paints.background.color
        resizeScrim.alpha = RESIZE_SCRIM_ALPHA
        // Opaque, unlike the wash: the pill is what makes its label readable over the keys.
        pillFill.color = paints.background.color
        pillFill.alpha = 255
        resizeLabel.color = paints.accent.color
        if (resizing) {
            invalidate()
        }
    }

    /**
     * Draws the resize overlay on top of the children.
     *
     * `dispatchDraw` rather than `onDraw`: a ViewGroup paints itself first and its children
     * after, so the frame drawn in `onDraw` would end up underneath the keys it is framing.
     */
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (!resizing) {
            return
        }
        val left = contentLeft(width, (width * widthScale).toInt().coerceAtLeast(1)).toFloat()
        val right = left + (width * widthScale)
        val top = keyboardTopForResize().toFloat()
        val bottom = keyboard.bottom.toFloat()
        if (bottom <= top) {
            return
        }
        // A wash over the keys, so the frame reads as a frame rather than as a stray rectangle
        // drawn across a keyboard that still looks live.
        canvas.drawRect(left, top, right, bottom, resizeScrim)
        handleFrame.set(left, top, right, bottom)
        canvas.drawRect(handleFrame, resizeFrame)

        val radius = handleRadiusPx()
        // Top for height, sides for width. No bottom handle: the bottom edge is where the
        // keyboard meets the screen, and dragging it would fight the offset setting rather
        // than the size.
        drawHandle(canvas, (left + right) / 2f, top, radius, draggingHandle == HANDLE_TOP)
        drawHandle(canvas, left, (top + bottom) / 2f, radius, draggingHandle == HANDLE_LEFT)
        drawHandle(canvas, right, (top + bottom) / 2f, radius, draggingHandle == HANDLE_RIGHT)

        // Two words and no way to leave would be a trap, so the way out is drawn where the
        // finger already is: inside the frame it is resizing. Both sit on a filled pill, or
        // they would be blue text on top of key labels and legible in neither theme.
        resizeLabel.textSize = density() * LABEL_TEXT_DP
        resizeLabel.getFontMetrics(labelMetrics)
        val labelTop = top + radius * 1.4f
        drawPill(canvas, strings[Keys.RESIZE_RESET], left + radius * 1.4f, labelTop, resetHit)
        val done = strings[Keys.RESIZE_DONE]
        drawPill(canvas, done, right - radius * 1.4f - pillWidth(done), labelTop, doneHit)

        // What to do, once, along the bottom edge where nothing else is drawn.
        val hint = strings[Keys.RESIZE_HINT]
        drawPill(canvas, hint, (left + right - pillWidth(hint)) / 2f,
            bottom - radius * 1.4f - pillHeight(), null)
    }

    private fun pillWidth(text: String): Float =
        resizeLabel.measureText(text) + density() * PILL_PADDING_DP * 2f

    private fun pillHeight(): Float =
        labelMetrics.descent - labelMetrics.ascent + density() * PILL_PADDING_DP * 2f

    /** A label on a filled, outlined pill, with its own touch rectangle where it has one. */
    private fun drawPill(
        canvas: android.graphics.Canvas,
        text: String,
        left: Float,
        top: Float,
        hit: android.graphics.RectF?,
    ) {
        val width = pillWidth(text)
        val height = pillHeight()
        val radius = height / 2f
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, pillFill)
        canvas.drawRoundRect(left, top, left + width, top + height, radius, radius, resizeFrame)
        canvas.drawText(text, left + density() * PILL_PADDING_DP,
            top + density() * PILL_PADDING_DP - labelMetrics.ascent, resizeLabel)
        hit?.set(left, top, left + width, top + height)
    }

    private fun drawHandle(
        canvas: android.graphics.Canvas,
        x: Float,
        y: Float,
        radius: Float,
        pressed: Boolean,
    ) {
        canvas.drawCircle(x, y, radius, if (pressed) paints.accent else paints.keyFill)
        canvas.drawCircle(x, y, radius, resizeFrame)
    }

    /**
     * The top of the frame.
     *
     * The keys, not the strip above them: height scales the key rows, so framing the strip too
     * would show an edge that the top handle cannot move.
     */
    private fun keyboardTopForResize(): Int = keyboard.top

    private fun density(): Float = resources.displayMetrics.density

    /**
     * Big enough to hit without looking, which is the whole point of dragging one.
     *
     * In dp rather than as a fraction of a key row: the row height is the thing being dragged,
     * so tying the handle to it would make the handle hardest to grab exactly when the keyboard
     * is at its smallest and the user most wants it back.
     */
    private fun handleRadiusPx(): Float = density() * HANDLE_RADIUS_DP

    /** Which handle a touch is on, or HANDLE_NONE. */
    private fun handleAt(x: Float, y: Float): Int {
        val left = contentLeft(width, (width * widthScale).toInt().coerceAtLeast(1)).toFloat()
        val right = left + (width * widthScale)
        val top = keyboardTopForResize().toFloat()
        val middle = (top + keyboard.bottom) / 2f
        val reach = density() * HANDLE_TOUCH_DP
        if (kotlin.math.hypot(x - (left + right) / 2f, y - top) <= reach) {
            return HANDLE_TOP
        }
        if (kotlin.math.hypot(x - left, y - middle) <= reach) {
            return HANDLE_LEFT
        }
        if (kotlin.math.hypot(x - right, y - middle) <= reach) {
            return HANDLE_RIGHT
        }
        return HANDLE_NONE
    }

    /**
     * Keeps every touch away from the children while resizing.
     *
     * Without this the keys would take the down event and type a letter, and the drag would
     * never reach this class at all.
     */
    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean = resizing

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (resizing) {
            return handleResizeTouch(event)
        }
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

        const val HANDLE_NONE = -1
        const val HANDLE_TOP = 0
        const val HANDLE_LEFT = 1
        const val HANDLE_RIGHT = 2

        /** A handle's radius, and how far from its centre a touch still counts, both in dp. */
        const val HANDLE_RADIUS_DP = 13f
        const val HANDLE_TOUCH_DP = 34f

        /** The overlay's label size and the padding inside the pill behind it, in dp. */
        const val LABEL_TEXT_DP = 14f
        const val PILL_PADDING_DP = 8f

        /** Only reached before the first theme update, when the row height is still zero. */
        const val DEFAULT_ROW_PX = 132f

        /** How much of the keys the resize wash covers, out of 255. */
        const val RESIZE_SCRIM_ALPHA = 96

        /** The frame's stroke, as a fraction of a key row. */
        const val RESIZE_FRAME_ROWS = 0.022f
        const val MODE_ONE_HANDED_LEFT = 1
        const val MODE_ONE_HANDED_RIGHT = 2
        const val MODE_FLOATING = 3

        /** Below this there is not enough empty space for a target a thumb can hit. */
        const val MIN_ARROW_GUTTER_DP = 28f
        const val MAX_ARROW_SIZE_DP = 56f
    }
}
