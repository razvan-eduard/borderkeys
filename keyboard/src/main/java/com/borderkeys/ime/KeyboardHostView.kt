// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.annotation.SuppressLint
import android.content.Context
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowInsets
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.effects.EffectStage
import com.borderkeys.theme.ThemePaints
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.i18n.Keys

/**
 * The root of the input view: its children stacked vertically and laid out in code. Only one of
 * the two suggestion rows, ours or a password manager's inline one, is visible at a time.
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

    /** Size and position settings, shown in the keys' place. */
    val quickSettings = QuickSettingsView(context, paints, strings)
    val quickActions = QuickActionsView(context, paints, strings)
    val clipboardPanel = ClipboardPanelView(context, paints, strings)
    val emojiPanel = EmojiPanelView(context, paints)

    /** The answer to "why this word?", shown in the keys' place. */
    val explainPanel = ExplainPanelView(context, paints, strings)

    /** Shown in the suggestion strip's place. */
    val languageRevertPanel = LanguageRevertPanelView(context, paints)

    /** The ring of words of a paused or lifted swipe, over [keyboard]; added after it. */
    val radialSuggestionMenu = RadialSuggestionMenuView(context, paints)

    /** The word a swipe settled on, rising and fading over the keys; added after the ring. */
    val effects = EffectStage(context).apply { basePaint = paints.label }

    /** Whether [setRadialMenuVisible] blurs [keyboard] behind the ring, on API 31+. */
    var radialBlurBackground: Boolean = true

    /** Reports a drag on one of the resize handles, in the units the settings store. */
    var onResizeDrag: ((height: Float, width: Float, offset: Float) -> Unit)? = null

    /** Called when a resize drag ends. */
    var onResizeFinished: (() -> Unit)? = null

    /** Called when the user leaves the resize overlay. */
    var onResizeExit: (() -> Unit)? = null

    /** Whether the keyboard is showing its resize handles. */
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

    /** Which edge the quick-action bar sits against, as a PLACEMENT_ constant. */
    var quickActionsPlacement: Int = 0
        set(value) {
            if (field != value) {
                field = value
                quickActions.vertical = value == PLACEMENT_LEFT || value == PLACEMENT_RIGHT
                // The bar's tabs are flat on the side that meets the keyboard.
                quickActions.attachedEdge = when (value) {
                    PLACEMENT_BELOW_KEYS -> QuickActionsView.EDGE_TOP
                    PLACEMENT_LEFT -> QuickActionsView.EDGE_RIGHT
                    PLACEMENT_RIGHT -> QuickActionsView.EDGE_LEFT
                    else -> QuickActionsView.EDGE_BOTTOM
                }
                requestLayout()
            }
        }

    /** The navigation bar's height along the bottom edge, set by [applyNavigationInset]. */
    private var navigationBarInset = 0

    /** Whether a three-button navigation bar moves to a side edge in landscape on this device. */
    private val navigationBarCanMove: Boolean by lazy {
        val id = resources.getIdentifier("config_navBarCanMove", "bool", "android")
        id > 0 && resources.getBoolean(id)
    }

    /** Whether the system uses gesture navigation: `navigation_mode` 2. */
    private fun isGestureNavigation(): Boolean =
        android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun applyNavigationInset(insets: WindowInsets) {
        // None for a settings preview (disabled) or a three-button bar moved to the side; the
        // system's bar height when a visible bar reports zero.
        val bottom = if (!isEnabled) {
            0
        } else if (navigationBarCanMove && isLandscape() && !isGestureNavigation()) {
            0
        } else {
            val type = WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout()
            val navigationBars = insets.getInsets(WindowInsets.Type.navigationBars())
            var resolved = insets.getInsets(type).bottom
            if (resolved == 0 && navigationBars.left == 0 && navigationBars.right == 0 &&
                insets.isVisible(WindowInsets.Type.navigationBars())
            ) {
                resolved = systemNavigationBarHeightPx()
            }
            resolved
        }
        if (bottom != navigationBarInset) {
            navigationBarInset = bottom
            requestLayout()
        }
    }

    /** The navigation bar height from the system's resources. */
    private fun systemNavigationBarHeightPx(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { applyNavigationInset(it) }
    }

    // ---- size and position -------------------------------------------------------------------

    /** Fraction of the available width the keys occupy. Below 1 only away from docked. */
    private var widthScale = 1f
    /** MODE_ constants from KeyboardPreferences. */
    private var positionMode = 0
    private var bottomOffsetPx = 0
    private var horizontalOffsetPx = 0

    /** Mirrors the theme's opacity: the whole keyboard drawn this much solid. */
    var opacity: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                alpha = value
            }
        }

    /** The theme's full-width background flag. */
    var fullWidthBackground: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /**
     * Whether this view extends, transparent, to the top of the screen while a ring is open; a
     * tap there goes to the ring. The keyboard's reported top stays at [keyboardAreaTop].
     */
    var reserveScreenAbove: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** The photo rising from a strip chip ([playPhotoLamp]), or null. */
    private var photoLamp: PhotoLampRun? = null

    /** A lamp in progress: the image, the chip it left in the strip's pixels, and its start. */
    private class PhotoLampRun(val bitmap: android.graphics.Bitmap, val chip: android.graphics.RectF, val startedAt: Long)

    /** Whether a photo is rising; the window keeps the room above the keys meanwhile. */
    val photoLampRunning: Boolean get() = photoLamp != null

    /** Told when [photoLampRunning] changes, to grow or shrink the window. */
    var onPhotoLampChanged: (() -> Unit)? = null

    private val photoLampVertices = FloatArray(PhotoLamp.VERTEX_FLOATS)
    private val photoLampPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val photoLampFrom = android.graphics.RectF()
    private val photoLampArea = android.graphics.RectF()
    private val photoLampTarget = android.graphics.RectF()
    private val stripOrigin = IntArray(2)
    private val hostOrigin = IntArray(2)

    /** Starts [bitmap] rising out of [chip], the chip's bounds in the strip's pixels. */
    fun playPhotoLamp(bitmap: android.graphics.Bitmap, chip: android.graphics.RectF) {
        val wasRunning = photoLamp != null
        photoLamp = PhotoLampRun(bitmap, android.graphics.RectF(chip), android.os.SystemClock.uptimeMillis())
        if (!wasRunning) {
            onPhotoLampChanged?.invoke()
        }
        postInvalidateOnAnimation()
    }

    /** Draws the rising photo over everything, until its time is up. */
    private fun drawPhotoLamp(canvas: android.graphics.Canvas) {
        val run = photoLamp ?: return
        val progress = (android.os.SystemClock.uptimeMillis() - run.startedAt).toFloat() / PhotoLamp.DURATION_MILLIS
        if (progress >= 1f) {
            photoLamp = null
            onPhotoLampChanged?.invoke()
            return
        }
        if (keyboardAreaTop > 0) {
            suggestionStrip.getLocationInWindow(stripOrigin)
            getLocationInWindow(hostOrigin)
            photoLampFrom.set(run.chip)
            photoLampFrom.offset((stripOrigin[0] - hostOrigin[0]).toFloat(), (stripOrigin[1] - hostOrigin[1]).toFloat())
            photoLampArea.set(0f, 0f, width.toFloat(), photoLampFrom.top)
            PhotoLamp.fit(run.bitmap.width, run.bitmap.height, photoLampArea, density() * PHOTO_LAMP_MARGIN_DP, photoLampTarget)
            PhotoLamp.mesh(progress, photoLampFrom, photoLampTarget, photoLampVertices)
            photoLampPaint.alpha = PhotoLamp.alpha(progress)
            canvas.drawBitmapMesh(
                run.bitmap, PhotoLamp.COLUMNS, PhotoLamp.ROWS, photoLampVertices, 0, null, 0, photoLampPaint,
            )
        }
        postInvalidateOnAnimation()
    }

    /** How far down the window the keyboard starts: the room above it when [reserveScreenAbove],
     *  else zero. */
    var keyboardAreaTop: Int = 0
        private set

    /** [keyboardAreaTop] as of the last layout. */
    private var laidOutKeyboardAreaTop = 0

    /** Mirrors [com.borderkeys.data.theme.KeyboardTheme.navigationBarBackground]. */
    var navigationBarBackground: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** The current height scale, where a resize drag starts from. */
    var heightScaleForDrag: Float = 1f

    private val handleFrame = android.graphics.RectF()
    private val resetHit = android.graphics.RectF()
    private val doneHit = android.graphics.RectF()
    private val labelMetrics = android.graphics.Paint.FontMetrics()

    /** The resize overlay's paints. */
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

    /** Where the arrow is, in this view's coordinates, or empty; computed in layout. */
    private val arrowBounds = android.graphics.Rect()
    private val arrowPath = android.graphics.Path()
    private var arrowPressed = false

    /**
     * Turns a drag on a handle into the three numbers the settings hold, reported on every move
     * and finished when the finger lifts.
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
                dragStartX = event.rawX
                dragStartY = event.rawY
                dragStartWidth = widthScale
                dragStartHeight = heightScaleForDrag
                // The height the keys would have at scale 1.
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

                    // Dragging away from the keys widens it: a centred keyboard at both edges,
                    // a one-handed one at the dragged edge only.
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
        // Every mode honours the width; contentLeft centres a narrowed dock.
        val effectiveWidth = widthScale.coerceIn(0.4f, 1f)
        if (this.positionMode == mode && this.widthScale == effectiveWidth &&
            this.bottomOffsetPx == bottomOffsetPx && this.horizontalOffsetPx == horizontalOffsetPx
        ) {
            return
        }
        this.positionMode = mode
        this.widthScale = effectiveWidth
        // A docked keyboard sits flush with the bottom edge.
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
        else -> (totalWidth - contentWidth) / 2
    }

    init {
        // The group draws the gutter arrow.
        setWillNotDraw(false)
        isClickable = false
        // One background for the whole window, painted here.
        keyboard.drawsBackground = false
        suggestionStrip.drawsBackground = false
        quickActions.drawsBackground = false
        addView(suggestionStrip)
        addView(inlineSuggestions)
        addView(languageRevertPanel)
        languageRevertPanel.visibility = GONE
        addView(keyboard)
        addView(quickSettings)
        addView(clipboardPanel)
        clipboardPanel.visibility = GONE
        addView(emojiPanel)
        emojiPanel.visibility = GONE
        addView(explainPanel)
        explainPanel.visibility = GONE
        // The bar draws over the panels, the ring and the effects over everything.
        addView(quickActions)
        addView(radialSuggestionMenu)
        radialSuggestionMenu.visibility = GONE
        addView(effects)
        effects.visibility = GONE
        inlineSuggestions.visibility = GONE
        quickSettings.visibility = GONE

        setOnApplyWindowInsetsListener { _, insets ->
            applyNavigationInset(insets)
            // The children get no insets.
            WindowInsets.CONSUMED
        }
    }

    /** Switches between our suggestions and the autofill service's. */
    fun showInlineSuggestions(show: Boolean) {
        val wantsInline = show && inlineSuggestions.hasSuggestions
        val inlineVisibility = if (wantsInline) VISIBLE else GONE
        val stripVisibility = if (wantsInline) GONE else stripRestored()
        if (inlineSuggestions.visibility != inlineVisibility ||
            suggestionStrip.visibility != stripVisibility
        ) {
            inlineSuggestions.visibility = inlineVisibility
            suggestionStrip.visibility = stripVisibility
            requestLayout()
        }
    }

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
     * Whether the suggestion row is shown at all (`KeyboardPreferences.showSuggestionStrip`);
     * while off, no panel restores it.
     */
    var suggestionStripEnabled: Boolean = true
        set(value) {
            if (field == value) {
                return
            }
            field = value
            val overlaid = clipboardPanelVisible || explainPanelVisible || languageRevertPanelVisible ||
                radialMenuVisible || inlineSuggestions.visibility == VISIBLE
            if (!overlaid) {
                suggestionStrip.visibility = stripRestored()
                requestLayout()
            }
        }

    /** What the strip goes back to when whatever covered it leaves. */
    private fun stripRestored(): Int = if (suggestionStripEnabled) VISIBLE else GONE

    val emojiPanelVisible: Boolean get() = emojiPanel.visibility == VISIBLE

    /** Shows or hides the emoji grid, standing the keys down while it is up. */
    fun setEmojiPanelVisible(visible: Boolean) {
        if (emojiPanelVisible == visible) {
            return
        }
        if (visible) {
            emojiPanel.load(context)
        } else {
            emojiPanel.query = ""
        }
        emojiPanel.visibility = if (visible) VISIBLE else GONE
        keyboard.visibility = if (visible) GONE else VISIBLE
        requestLayout()
    }

    /** Shows or hides the clipboard history, in the keys' place, the strip with them. */
    fun setClipboardPanelVisible(visible: Boolean) {
        if (clipboardPanelVisible == visible) {
            return
        }
        clipboardPanel.visibility = if (visible) VISIBLE else GONE
        keyboard.visibility = if (visible) GONE else VISIBLE
        suggestionStrip.visibility = if (visible) GONE else stripRestored()
        if (visible) {
            inlineSuggestions.visibility = GONE
        }
        requestLayout()
    }

    val explainPanelVisible: Boolean get() = explainPanel.visibility == VISIBLE

    /** Shows or hides the "why this word?" panel, in the keys' place, the strip with them. */
    fun setExplainPanelVisible(visible: Boolean) {
        if (explainPanelVisible == visible) {
            return
        }
        explainPanel.visibility = if (visible) VISIBLE else GONE
        keyboard.visibility = if (visible) GONE else VISIBLE
        suggestionStrip.visibility = if (visible) GONE else stripRestored()
        if (visible) {
            inlineSuggestions.visibility = GONE
        }
        requestLayout()
    }

    val languageRevertPanelVisible: Boolean get() = languageRevertPanel.visibility == VISIBLE

    /** Shows or hides the language-switch revert offer in the strip's place; the keys stay up. */
    fun setLanguageRevertPanelVisible(visible: Boolean) {
        if (languageRevertPanelVisible == visible) {
            return
        }
        languageRevertPanel.visibility = if (visible) VISIBLE else GONE
        suggestionStrip.visibility = if (visible) GONE else stripRestored()
        if (visible) {
            inlineSuggestions.visibility = GONE
        }
        requestLayout()
    }

    val radialMenuVisible: Boolean get() = radialSuggestionMenu.visibility == VISIBLE

    /**
     * Shows or hides the radial menu over [keyboard]; the keys stay up and the strip is hidden
     * while it is open.
     */
    fun setRadialMenuVisible(visible: Boolean) {
        // A ring waiting for its burst to finish counts as hidden.
        val wasShown = radialMenuVisible && !radialSuggestionMenu.pendingHide
        if (visible) {
            radialSuggestionMenu.pendingHide = false
        }
        if (wasShown == visible) {
            return
        }
        if (visible) {
            radialSuggestionMenu.bringToFront()
            inlineSuggestions.visibility = GONE
        }
        // With a burst still animating, the ring hides itself once the burst ends.
        if (!visible && radialSuggestionMenu.hasLiveParticles()) {
            radialSuggestionMenu.pendingHide = true
        } else {
            radialSuggestionMenu.visibility = if (visible) VISIBLE else GONE
        }
        // The strip keeps its height while the ring is up.
        suggestionStrip.visibility = when {
            !visible -> stripRestored()
            suggestionStripEnabled -> INVISIBLE
            else -> GONE
        }
        // Blurs the keys behind the ring, on API 31+.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            keyboard.setRenderEffect(
                if (visible && radialBlurBackground) {
                    android.graphics.RenderEffect.createBlurEffect(
                        RADIAL_BLUR_RADIUS_PX, RADIAL_BLUR_RADIUS_PX,
                        android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    null
                },
            )
        }
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

        // Measured first: a side bar takes width from the keys, a top or bottom bar height.
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
        if (languageRevertPanel.visibility != GONE) {
            languageRevertPanel.measure(exactBody, unbounded)
            height += languageRevertPanel.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.measure(exactBody, unbounded)
            height += keyboard.measuredHeight
        }
        if (quickSettings.visibility != GONE) {
            // Each panel takes the height the keys would have had.
            quickSettings.measure(exactBody, MeasureSpec.makeMeasureSpec(
                keyboardHeightForPanel(bodyWidth), MeasureSpec.EXACTLY,
            ))
            height += quickSettings.measuredHeight
        }
        if (clipboardPanel.visibility != GONE) {
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
        if (explainPanel.visibility != GONE) {
            explainPanel.measure(exactBody, MeasureSpec.makeMeasureSpec(
                keyboardHeightForPanel(bodyWidth), MeasureSpec.EXACTLY,
            ))
            height += explainPanel.measuredHeight
        }
        if (quickActions.visibility != GONE) {
            if (sideBar) {
                // A side bar is as tall as the body.
                quickActions.measure(
                    MeasureSpec.makeMeasureSpec(barThickness, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            } else {
                height += barThickness
            }
        }

        val keyboardHeight = height + navigationBarInset + bottomOffsetPx
        // With reserveScreenAbove, the room above the keys is whatever the window has left.
        keyboardAreaTop = if (reserveScreenAbove) {
            val available = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> resources.displayMetrics.heightPixels
                else -> MeasureSpec.getSize(heightMeasureSpec)
            }
            (available - keyboardHeight).coerceAtLeast(0)
        } else {
            0
        }
        val totalHeight = keyboardHeight + keyboardAreaTop
        // The ring and the effects are measured to the whole view, hidden or not.
        radialSuggestionMenu.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY),
        )
        effects.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY),
        )

        // The window is always full width; the keys are narrower and offset inside it.
        setMeasuredDimension(width, totalHeight)
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
        // Everything starts below the room reserved above the keys, when there is any.
        var y = keyboardAreaTop
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
        if (languageRevertPanel.visibility != GONE) {
            languageRevertPanel.layout(bodyLeft, y, bodyRight, y + languageRevertPanel.measuredHeight)
            y += languageRevertPanel.measuredHeight
        }
        if (keyboard.visibility != GONE) {
            keyboard.layout(bodyLeft, y, bodyRight, y + keyboard.measuredHeight)
            // The room above the keys, for the alternatives popup's placement.
            keyboard.hostTopInsetPx = keyboard.top.toFloat()
            y += keyboard.measuredHeight
        }
        // The ring and the effects cover the whole view, hidden or not.
        radialSuggestionMenu.topInset = keyboardAreaTop.toFloat()
        radialSuggestionMenu.layout(0, 0, width, b - t)
        effects.keyAreaLeft = keyboard.left.toFloat()
        effects.keyAreaRight = keyboard.right.toFloat()
        effects.keyAreaTop = keyboard.top.toFloat()
        effects.keyAreaBottom = keyboard.bottom.toFloat()
        effects.layout(0, 0, width, b - t)
        // An open ring moves with the keys when the room above them changes.
        if (keyboardAreaTop != laidOutKeyboardAreaTop) {
            radialSuggestionMenu.shiftBy((keyboardAreaTop - laidOutKeyboardAreaTop).toFloat())
            laidOutKeyboardAreaTop = keyboardAreaTop
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
        if (explainPanel.visibility != GONE) {
            explainPanel.layout(bodyLeft, y, bodyRight, y + explainPanel.measuredHeight)
            y += explainPanel.measuredHeight
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

    /** Puts an arrow in the wider gutter, when it is at least [MIN_ARROW_GUTTER_DP] wide. */
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
        // Points into the gutter it sits in, the way the keyboard would move.
        val pointsRight = arrowBounds.centerX() > width / 2
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

    /**
     * Paints the surface the keys sit on, full width or under the keys only, from
     * [keyboardAreaTop] to the keyboard's bottom, short of the navigation bar when
     * [navigationBarBackground] is off.
     */
    private fun drawBackground(canvas: android.graphics.Canvas) {
        val skippedInset = if (navigationBarBackground) 0 else navigationBarInset
        val top = keyboardAreaTop.toFloat()
        val bottom = (height - bottomOffsetPx - skippedInset).toFloat()
        if (bottom <= top) {
            return
        }
        if (fullWidthBackground) {
            paints.backgroundPainter.draw(canvas, 0f, top, width.toFloat(), bottom)
            drawOutline(canvas, 0f, width.toFloat(), bottom)
            return
        }
        val contentWidth = (width * widthScale).toInt().coerceAtLeast(1)
        val left = contentLeft(width, contentWidth).toFloat()
        paints.backgroundPainter.draw(canvas, left, top, left + contentWidth, bottom)
        drawOutline(canvas, left, left + contentWidth, bottom)
    }

    /**
     * A hairline framing the whole keyboard when key outlines are on: the top always, the bottom
     * and the sides where the keyboard stops short of the screen's edge.
     */
    private fun drawOutline(canvas: android.graphics.Canvas, left: Float, right: Float, bottom: Float) {
        if (!paints.showKeyBorders) {
            return
        }
        val half = paints.keyStroke.strokeWidth / 2f
        canvas.drawLine(left, half, right, half, paints.keyStroke)
        if (bottomOffsetPx > 0) {
            canvas.drawLine(left, bottom - half, right, bottom - half, paints.keyStroke)
        }
        if (left > 0f) {
            canvas.drawLine(left + half, 0f, left + half, bottom, paints.keyStroke)
        }
        if (right < width) {
            canvas.drawLine(right - half, 0f, right - half, bottom, paints.keyStroke)
        }
        // A line on the boundary between the quick-action bar and the rest.
        if (quickActions.visibility != GONE) {
            when (quickActionsPlacement) {
                PLACEMENT_ABOVE_STRIP -> {
                    val y = quickActions.bottom.toFloat()
                    canvas.drawLine(left, y, right, y, paints.keyStroke)
                }
                PLACEMENT_BELOW_KEYS -> {
                    val y = quickActions.top.toFloat()
                    canvas.drawLine(left, y, right, y, paints.keyStroke)
                }
                PLACEMENT_LEFT -> {
                    val x = quickActions.right.toFloat()
                    canvas.drawLine(x, 0f, x, bottom, paints.keyStroke)
                }
                PLACEMENT_RIGHT -> {
                    val x = quickActions.left.toFloat()
                    canvas.drawLine(x, 0f, x, bottom, paints.keyStroke)
                }
            }
        }
    }

    /** Forces every child to re-measure after the shared metrics changed. */
    fun relayoutForNewMetrics() {
        for (i in 0 until childCount) {
            getChildAt(i).forceLayout()
        }
        requestLayout()
    }

    /** Re-reads the overlay's colours from the theme. */
    fun onThemeChanged() {
        resizeFrame.color = paints.accent.color
        resizeFrame.strokeWidth =
            (paints.rowHeightPx.takeIf { it > 0f } ?: ThemePaints.DEFAULT_ROW_HEIGHT_PX) * RESIZE_FRAME_ROWS
        resizeScrim.color = paints.background.color
        resizeScrim.alpha = RESIZE_SCRIM_ALPHA
        pillFill.color = paints.background.color
        pillFill.alpha = 255
        resizeLabel.color = paints.accent.color
        if (resizing) {
            invalidate()
        }
    }

    /** A reused buffer for one alternative's label. */
    private val alternativeLabel = CharArray(1)

    /** Draws the alternatives popup or key preview, and the resize overlay, over the children. */
    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        if (keyboard.alternativesVisible) {
            drawAlternativesPopup(canvas)
        } else if (keyboard.keyPreviewVisible) {
            drawKeyPreview(canvas)
        }
        drawPhotoLamp(canvas)
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
        // A wash over the keys.
        canvas.drawRect(left, top, right, bottom, resizeScrim)
        handleFrame.set(left, top, right, bottom)
        canvas.drawRect(handleFrame, resizeFrame)

        val radius = handleRadiusPx()
        // Top for height, sides for width.
        drawHandle(canvas, (left + right) / 2f, top, radius, draggingHandle == HANDLE_TOP)
        drawHandle(canvas, left, (top + bottom) / 2f, radius, draggingHandle == HANDLE_LEFT)
        drawHandle(canvas, right, (top + bottom) / 2f, radius, draggingHandle == HANDLE_RIGHT)

        // Reset and Done, on pills inside the frame.
        resizeLabel.textSize = density() * LABEL_TEXT_DP
        resizeLabel.getFontMetrics(labelMetrics)
        val labelTop = top + radius * 1.4f
        drawPill(canvas, strings[Keys.RESIZE_RESET], left + radius * 1.4f, labelTop, resetHit)
        val done = strings[Keys.RESIZE_DONE]
        drawPill(canvas, done, right - radius * 1.4f - pillWidth(done), labelTop, doneHit)

        // The hint, along the bottom edge.
        val hint = strings[Keys.RESIZE_HINT]
        drawPill(canvas, hint, (left + right - pillWidth(hint)) / 2f,
            bottom - radius * 1.4f - pillHeight(), null)
    }

    /** A held key's alternatives popup, in [keyboard]'s coordinates offset by its position. */
    private fun drawAlternativesPopup(canvas: android.graphics.Canvas) {
        val count = keyboard.alternativesCount
        if (count == 0) {
            return
        }
        val offsetX = keyboard.left.toFloat()
        val offsetY = keyboard.top.toFloat()
        val left = keyboard.alternativesLeftPx + offsetX
        val top = keyboard.alternativesTopPx + offsetY
        val cellWidth = keyboard.alternativesCellWidthPx
        val rowHeight = keyboard.alternativesRowHeightPx
        val radius = paints.keyCornerRadiusPx
        val alpha = keyboard.alternativesAlpha
        val layer = if (alpha < 1f) {
            canvas.saveLayerAlpha(left, top, left + cellWidth * count, top + rowHeight, (alpha * 255f).toInt())
        } else {
            -1
        }
        canvas.drawRoundRect(
            left, top, left + cellWidth * count, top + rowHeight, radius, radius,
            if (keyboard.alternativesModifierStyled) paints.modifierKeyFill else paints.keyFill,
        )
        val selected = keyboard.alternativesSelectedIndex
        if (selected in 0 until count) {
            val cellLeft = left + cellWidth * selected
            canvas.drawRoundRect(
                cellLeft, top, cellLeft + cellWidth, top + rowHeight, radius, radius,
                paints.accent,
            )
        }
        // With key outlines on, the popup is outlined with a line between cells, under the labels.
        if (paints.showKeyBorders) {
            canvas.drawRoundRect(
                left, top, left + cellWidth * count, top + rowHeight, radius, radius,
                paints.keyStroke,
            )
            for (position in 1 until count) {
                val x = left + cellWidth * position
                canvas.drawLine(x, top, x, top + rowHeight, paints.keyStroke)
            }
        }
        val base = paints.label.textSize
        paints.label.textSize = keyboard.alternativesTextSizePx
        for (position in 0 until count) {
            val cellLeft = left + cellWidth * position
            alternativeLabel[0] = keyboard.alternativeCharAt(position)
            canvas.drawText(
                alternativeLabel, 0, 1,
                cellLeft + cellWidth / 2f, top + rowHeight / 2f + paints.labelBaselineOffsetPx,
                paints.label,
            )
        }
        paints.label.textSize = base
        if (layer >= 0) {
            canvas.restoreToCount(layer)
        }
    }

    /** A reused buffer for the key preview's label. */
    private val previewLabel = CharArray(8)

    /** The pressed key enlarged above the finger, with the key's fill and outline. */
    private fun drawKeyPreview(canvas: android.graphics.Canvas) {
        val length = keyboard.keyPreviewLabel(previewLabel)
        if (length == 0) {
            return
        }
        val left = keyboard.keyPreviewLeftPx + keyboard.left
        val top = keyboard.keyPreviewTopPx + keyboard.top
        val right = left + keyboard.keyPreviewWidthPx
        val bottom = top + keyboard.keyPreviewHeightPx
        val radius = paints.keyCornerRadiusPx
        canvas.drawRoundRect(
            left, top, right, bottom, radius, radius,
            if (keyboard.keyPreviewModifierStyled) paints.modifierKeyFill else paints.keyFill,
        )
        if (paints.showKeyBorders) {
            canvas.drawRoundRect(left, top, right, bottom, radius, radius, paints.keyStroke)
        }
        val base = paints.label.textSize
        paints.label.textSize = keyboard.keyPreviewTextSizePx
        // Centred on the box from the enlarged font's metrics.
        val metrics = paints.label.fontMetrics
        val baseline = (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(previewLabel, 0, length, (left + right) / 2f, baseline, paints.label)
        paints.label.textSize = base
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

    /** The top of the frame: the top of the keys, below the strip. */
    private fun keyboardTopForResize(): Int = keyboard.top

    private fun density(): Float = resources.displayMetrics.density

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

    /** Keeps every touch away from the children while resizing. */
    override fun onInterceptTouchEvent(event: android.view.MotionEvent): Boolean = resizing

    @SuppressLint("ClickableViewAccessibility")
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

    /** What the keys would measure, for a panel shown in their place. */
    private fun keyboardHeightForPanel(widthSpec: Int): Int {
        val unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        keyboard.measure(widthSpec, unbounded)
        return keyboard.measuredHeight
    }

    private companion object {
        /** How strongly the keyboard blurs behind the radial ring, in pixels. */
        const val RADIAL_BLUR_RADIUS_PX = 18f

        const val MODE_DOCKED = KeyboardPreferences.MODE_DOCKED

        const val PLACEMENT_ABOVE_STRIP = KeyboardPreferences.QUICK_ACTIONS_ABOVE_STRIP
        const val PLACEMENT_BELOW_KEYS = KeyboardPreferences.QUICK_ACTIONS_BELOW_KEYS
        const val PLACEMENT_LEFT = KeyboardPreferences.QUICK_ACTIONS_LEFT
        const val PLACEMENT_RIGHT = KeyboardPreferences.QUICK_ACTIONS_RIGHT

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

        /** The space kept around the risen photo, in dp. */
        const val PHOTO_LAMP_MARGIN_DP = 16f

        /** How much of the keys the resize wash covers, out of 255. */
        const val RESIZE_SCRIM_ALPHA = 96

        /** The frame's stroke, as a fraction of a key row. */
        const val RESIZE_FRAME_ROWS = 0.022f

        const val MODE_ONE_HANDED_LEFT = KeyboardPreferences.MODE_ONE_HANDED_LEFT
        const val MODE_ONE_HANDED_RIGHT = KeyboardPreferences.MODE_ONE_HANDED_RIGHT
        const val MODE_FLOATING = KeyboardPreferences.MODE_FLOATING

        /** The narrowest gutter that gets an arrow, in dp. */
        const val MIN_ARROW_GUTTER_DP = 28f
        const val MAX_ARROW_SIZE_DP = 56f
    }
}
