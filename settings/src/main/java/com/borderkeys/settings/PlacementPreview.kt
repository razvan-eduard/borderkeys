// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.view.Gravity
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.borderkeys.ime.KeyboardHostView
import com.borderkeys.ime.LayoutLoader
import com.borderkeys.theme.DynamicColors
import com.borderkeys.theme.ThemeMode
import com.borderkeys.theme.ThemePaints

/**
 * Where the keyboard actually sits, not how wide the preview column happens to be.
 *
 * [KeyboardPreview] is deliberately always full width -- it exists to show colours and shapes,
 * and drawing it narrow because the keyboard is one-handed made it look broken rather than
 * placed. That is exactly wrong for *this* screen, whose whole subject is size and position: a
 * one-handed or floating keyboard shown at full width previews nothing this screen changes.
 *
 * [KeyboardHostView] does its own placement arithmetic in [KeyboardHostView.setPlacement] --
 * width, which edge, how far off the bottom -- the same class and the same call the input method
 * makes from `BorderKeysService.applyPlacement`. Handing it the real preferences and drawing it
 * inside a fixed-height area standing in for the bottom of a screen is what lets one-handed,
 * floating and a resized dock actually look like what they are, rather than being described in
 * words above a keyboard that ignores all of them.
 *
 * Takes one [KeyboardAppearance] rather than a theme and a set of preferences as two loose
 * parameters -- see [KeyboardAppearance] for why: this preview once composed the keyboard's
 * layout from nothing but the raw asset, ignoring every one of [KeyboardAppearance.preferences]
 * that says which keys should even be there, simply because preferences were something the
 * caller had to remember to also pass and also apply.
 */
@Composable
fun PlacementPreview(
    appearance: KeyboardAppearance,
    modifier: Modifier = Modifier,
    layoutId: String = "qwerty",
    /** Which of [KeyboardPreferences.placementFor]'s two answers to preview -- the tab picked
     *  on screen, not necessarily which way the phone is actually held right now. */
    isLandscape: Boolean = false,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val paints = remember { ThemePaints() }
    val layout = remember(layoutId) { LayoutLoader.load(context.assets, layoutId) }
    val (theme, lightTheme, preferences) = appearance

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        AndroidView(
            factory = { viewContext ->
                // Wrapped for the same reason KeyboardPreview wraps its view: the host can be
                // narrower or offset from either edge, and gravity on its own FrameLayout cell
                // is what lets it be, without Compose needing an opinion about one-handed mode.
                FrameLayout(viewContext).apply {
                    addView(
                        KeyboardHostView(viewContext, paints, strings).apply {
                            isEnabled = false
                            keyboard.isEnabled = false
                            keyboard.swipeEnabled = false
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
                val resolvedTheme = ThemeMode.resolve(theme, lightTheme, preferences, context)
                val effectiveTheme = if (preferences.followSystemColors) {
                    DynamicColors.apply(resolvedTheme, context)
                } else {
                    resolvedTheme
                }
                paints.update(
                    effectiveTheme, context.resources.displayMetrics, placement.heightScale,
                    context,
                )
                // The same three settings the input method composes, in the same order, so a
                // key the keyboard does not have is not a key this preview shows either --
                // KeyboardPreview does this too, and this screen was missing it entirely.
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
                view.keyboard.setLayout(composed)

                // A fourth setting the input method composes -- BorderKeysService.applyQuickActions
                // does the same three steps, gated the same way. Missing here, the bar was left at
                // View's own default visibility, showing a collapsed "more" button that opens onto
                // nothing beside a strip this screen otherwise renders correctly.
                if (preferences.quickActionsEnabled) {
                    val chosen = QuickAction.fromIds(preferences.quickActions)
                        .filter { it != QuickAction.COMPOSE || preferences.composerEnabled }
                    view.quickActions.visibility =
                        if (chosen.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    view.quickActions.actions = chosen
                    view.quickActions.collapsible =
                        preferences.quickActionsMode == KeyboardPreferences.QUICK_ACTIONS_COLLAPSED
                    view.quickActions.sizeLevel = preferences.quickActionsSize
                    view.quickActionsPlacement = preferences.quickActionsPlacement
                } else {
                    view.quickActions.visibility = android.view.View.GONE
                }
                view.fullWidthBackground = effectiveTheme.fullWidthBackground

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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Tall enough for a keyboard resized up to about its own maximum height plus a generous floating
 * offset, without turning the settings screen itself into mostly a picture of a keyboard. A
 * configuration past this simply has its top edge cropped -- the bottom, which is the edge every
 * setting on this screen actually moves, always stays in view.
 */
private const val PREVIEW_HEIGHT_DP = 340
