// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.ime.KeyboardCanvasView
import com.borderkeys.ime.KeyboardLayout
import com.borderkeys.ime.LayoutLoader
import com.borderkeys.theme.ThemePaints

/**
 * The real keyboard, embedded in the settings screen.
 *
 * Not a mock-up and not a drawing of one: this is [KeyboardCanvasView], the same class the input
 * method shows, fed from the same theme object and the same size settings. There is one
 * rendering path in this application and two places that display it, so a preview cannot drift
 * from the thing it previews -- which is the failure mode of every hand-built theme preview.
 *
 * It is inert. Touches are ignored, because a keyboard inside a settings screen that typed into
 * something would be a puzzle rather than a preview.
 */
@Composable
fun KeyboardPreview(
    theme: KeyboardTheme,
    preferences: KeyboardPreferences,
    modifier: Modifier = Modifier,
    layoutId: String = "qwerty_ro",
) {
    val context = LocalContext.current
    val paints = remember { ThemePaints() }
    val layout = remember(layoutId) { LayoutLoader.load(context.assets, layoutId) }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                // Wrapped so the keyboard can be narrower than the row without Compose having
                // to know how one-handed mode positions it.
                FrameLayout(viewContext).apply {
                    addView(
                        KeyboardCanvasView(viewContext, paints).apply {
                            isEnabled = false
                            swipeEnabled = false
                        },
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            },
            update = { frame ->
                val view = frame.getChildAt(0) as KeyboardCanvasView
                paints.update(theme, context.resources.displayMetrics, preferences.heightScale)

                val width = frame.width
                val effective = if (preferences.positionMode == KeyboardPreferences.MODE_DOCKED) {
                    1f
                } else {
                    preferences.widthScale
                }
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.width = if (width > 0) (width * effective).toInt() else params.width
                params.gravity = when (preferences.positionMode) {
                    KeyboardPreferences.MODE_ONE_HANDED_LEFT -> android.view.Gravity.START
                    KeyboardPreferences.MODE_ONE_HANDED_RIGHT -> android.view.Gravity.END
                    else -> android.view.Gravity.CENTER_HORIZONTAL
                }
                view.layoutParams = params

                view.setLayout(
                    if (preferences.numberRow) layout.withNumberRow() else layout,
                )
                view.onThemeChanged()
                view.requestLayout()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The fallback layout, for a preview asked for before the assets are readable. */
internal fun fallbackLayout(): KeyboardLayout = KeyboardLayout.fallbackQwerty()
