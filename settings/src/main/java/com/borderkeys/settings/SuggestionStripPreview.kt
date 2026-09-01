// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.ime.SuggestionStripView
import com.borderkeys.theme.ThemePaints

/**
 * The real suggestion strip, showing sample words, so the count can be seen rather than imagined.
 *
 * The same class the keyboard shows and the same paints, for the same reason the theme screen
 * embeds the real keyboard: a preview drawn separately is a second implementation that is free
 * to be wrong, and the thing it is most likely to be wrong about is exactly what this preview
 * exists to show -- how narrow eight slots get.
 *
 * Inert. Touches are consumed and dropped, because a suggestion accepted inside a settings
 * screen would have nowhere to go.
 */
@Composable
fun SuggestionStripPreview(
    theme: KeyboardTheme,
    preferences: KeyboardPreferences,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val paints = remember { ThemePaints() }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                SuggestionStripView(viewContext, paints).apply { isEnabled = false }
            },
            update = { view ->
                paints.update(theme, context.resources.displayMetrics, preferences.heightScale)
                view.visibleLimit = preferences.suggestionCount
                view.setSuggestions(SAMPLE, SAMPLE.size)
                view.requestLayout()
                view.invalidate()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Eight words of realistic length, so the preview narrows the way the real strip will.
 *
 * Short filler words would make eight slots look comfortable and then surprise the user on the
 * first long word, which is the opposite of what a preview is for.
 */
private val SAMPLE: Array<String?> = arrayOf(
    "keyboard", "because", "through", "another",
    "question", "together", "important", "different",
)
