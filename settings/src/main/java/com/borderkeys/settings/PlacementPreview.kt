// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBar
import com.borderkeys.data.theme.QuickActionBarItem
import com.borderkeys.ime.KeyboardHostView
import com.borderkeys.ime.KeyboardLayout
import com.borderkeys.ime.KeyboardCanvasView
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.ime.LayoutLoader
import com.borderkeys.ime.TouchGlows
import com.borderkeys.theme.ThemeMode
import com.borderkeys.theme.ThemePaints

/**
 * The keyboard where it actually sits: width, edge and offsets come from the real placement,
 * through [KeyboardHostView.setPlacement] as in the input method. The height is always
 * [PREVIEW_HEIGHT_FRACTION] of a standard keyboard's; nothing here sets a height, so the [Box]
 * wraps to what [KeyboardHostView] measures.
 */
@Composable
fun PlacementPreview(
    appearance: KeyboardAppearance,
    modifier: Modifier = Modifier,
    layoutId: String = "qwerty",
    /** Which of [KeyboardPreferences.placementFor]'s two answers to preview. */
    isLandscape: Boolean = false,
    /** Where taps land on each letter key, drawn over the keys; null for none. */
    touchGlows: TouchGlows? = null,
    /** Set, the keys take taps and report the tapped key's code; the preview is otherwise inert. */
    onKeyPicked: ((Int) -> Unit)? = null,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val paints = remember { ThemePaints() }
    val layout = remember(layoutId) { LayoutLoader.load(context.assets, layoutId) }
    val (theme, lightTheme, preferences) = appearance

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            factory = { viewContext ->
                // Wrapped so the host can be narrower or offset from either edge.
                FrameLayout(viewContext).apply {
                    addView(
                        KeyboardHostView(viewContext, paints, strings).apply {
                            isEnabled = onKeyPicked != null
                            keyboard.isEnabled = onKeyPicked != null
                            keyboard.swipeEnabled = false
                            keyboard.keyPopupEnabled = false
                            if (onKeyPicked != null) {
                                keyboard.listener = KeyPickingListener(onKeyPicked)
                            }
                            quickActions.isEnabled = false
                        },
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM,
                        ),
                    )
                }
            },
            update = { frame ->
                val view = frame.getChildAt(0) as KeyboardHostView
                val placement = preferences.placementFor(isLandscape)
                val effectiveTheme = ThemeMode.effective(theme, lightTheme, preferences, context)
                val standardHeightScale = KeyboardPreferences().placementFor(isLandscape).heightScale
                paints.update(
                    effectiveTheme, context.resources.displayMetrics,
                    standardHeightScale * PREVIEW_HEIGHT_FRACTION, context,
                )
                // The same three settings the input method composes, in the same order.
                var composed = layout
                if (!preferences.emojiKey) {
                    composed = composed.withoutEmojiKey()
                }
                if (!preferences.languageKey) {
                    composed = composed.withoutLanguageKey()
                }
                if (preferences.numberRow) {
                    composed = composed.withNumberRow()
                }
                if (preferences.keyFlicks.isNotEmpty()) {
                    composed = composed.withFlickLabels(flickLabelsByKey(preferences.keyFlicks))
                }
                view.keyboard.setLayout(composed)
                view.keyboard.touchGlows = touchGlows

                // The quick action bar, as BorderKeysService.applyQuickActions sets it.
                if (preferences.quickActionsEnabled) {
                    val chosen = QuickActionBar.resolve(preferences.quickActions, preferences.customQuickActions)
                        .filterNot {
                            it is QuickActionBarItem.Builtin && it.action == QuickAction.COMPOSE &&
                                !preferences.composerEnabled
                        }
                    view.quickActions.visibility =
                        if (chosen.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    view.quickActions.items = chosen
                    view.quickActions.collapsible =
                        preferences.quickActionsMode == KeyboardPreferences.QUICK_ACTIONS_COLLAPSED
                    view.quickActions.sizeLevel = preferences.quickActionsSize
                    view.quickActions.showLabels = preferences.quickActionsLabels
                    view.quickActionsPlacement = preferences.quickActionsPlacement
                } else {
                    view.quickActions.visibility = android.view.View.GONE
                }
                view.fullWidthBackground = effectiveTheme.fullWidthBackground
                view.navigationBarBackground = effectiveTheme.navigationBarBackground

                val density = context.resources.displayMetrics.density
                view.setPlacement(
                    placement.positionMode,
                    placement.widthScale,
                    (placement.bottomOffsetDp * density).toInt(),
                    (placement.horizontalOffsetDp * density).toInt(),
                )
                view.onThemeChanged()
                view.requestLayout()
            },
            // Width only: the Box wraps to the height KeyboardHostView measures.
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The preview's height, as a fraction of a standard keyboard's. */
private const val PREVIEW_HEIGHT_FRACTION = 0.75f

/** The user's flick labels by key code, eight per key north first and clockwise, as the input method lays them. */
internal fun flickLabelsByKey(flicks: List<KeyFlick>): Map<Int, Array<String>> {
    val byKey = HashMap<Int, Array<String>>()
    for (flick in flicks) {
        val labels = byKey.getOrPut(flick.keyCode) { Array(KeyboardLayout.FLICK_DIRECTIONS) { "" } }
        labels[flick.direction] = KeyFlick.shownLabel(flick).ifEmpty { "\u2022" }
    }
    return byKey
}

/** A keyboard listener that reports taps and does nothing else. */
private class KeyPickingListener(private val onKeyPicked: (Int) -> Unit) : KeyboardCanvasView.Listener {
    override fun onKey(code: Int, keyIndex: Int, x: Float, y: Float) = onKeyPicked(code)
    override fun onKeyRepeat(code: Int) = Unit
    override fun onText(text: CharSequence) = Unit
    override fun onKeyDown(code: Int) = Unit
    override fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) = Unit
    override fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) = Unit
    override fun onGestureSteered(x: Float, y: Float) = Unit
    override fun onGestureRingResolved() = Unit
    override fun onGestureRingCancelled() = Unit
    override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean = false
    override fun onCursorNudge(steps: Int) = Unit
    override fun onCursorNudgeLines(lines: Int) = Unit
    override fun onFlick(keyIndex: Int, direction: Int) = Unit
}
