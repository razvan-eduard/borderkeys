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
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.ime.KeyboardCanvasView
import com.borderkeys.ime.KeyboardLayout
import com.borderkeys.ime.LayoutLoader
import com.borderkeys.theme.ThemeMode
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
 *
 * Takes one [KeyboardAppearance] rather than a theme and a set of preferences as two loose
 * parameters -- see [KeyboardAppearance] for why.
 */
@Composable
fun KeyboardPreview(
    appearance: KeyboardAppearance,
    modifier: Modifier = Modifier,
    layoutId: String = "qwerty",
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val paints = remember { ThemePaints() }
    val layout = remember(layoutId) { LayoutLoader.load(context.assets, layoutId) }
    val (theme, lightTheme, preferences) = appearance

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                // Wrapped so the keyboard can be narrower than the row without Compose having
                // to know how one-handed mode positions it.
                FrameLayout(viewContext).apply {
                    addView(
                        KeyboardCanvasView(viewContext, paints, strings).apply {
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
                val effectiveTheme = ThemeMode.effective(theme, lightTheme, preferences, context)
                paints.update(effectiveTheme, context.resources.displayMetrics, preferences.heightScale, context)

                val width = frame.width
                // Always the full width, whatever the keyboard itself is set to.
                //
                // The preview is here to show colours, shapes and which keys exist. Drawing it
                // at sixty per cent because the keyboard is one-handed made it look like a
                // mistake rather than like a setting, and the setting it was illustrating is on
                // a different screen with its own preview of the placement.
                val effective = 1f
                val params = view.layoutParams as FrameLayout.LayoutParams
                params.width = if (width > 0) (width * effective).toInt() else params.width
                params.gravity = android.view.Gravity.CENTER_HORIZONTAL
                view.layoutParams = params

                // The same three settings the input method composes, in the same order, so
                // that a key the keyboard does not have is not a key the preview shows.
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
                view.setLayout(composed)
                view.onThemeChanged()
                view.requestLayout()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The fallback layout, for a preview asked for before the assets are readable. */
internal fun fallbackLayout(): KeyboardLayout = KeyboardLayout.fallbackQwerty()
