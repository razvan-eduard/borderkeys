// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

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
    val strings = LocalStrings.current
    val paints = remember { ThemePaints() }
    // Resolved into the array the view reads, once per language rather than once per frame.
    val sample = remember(strings) {
        Array<String?>(SAMPLE_KEYS.size) { strings[SAMPLE_KEYS[it]] }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                SuggestionStripView(viewContext, paints, strings).apply { isEnabled = false }
            },
            update = { view ->
                paints.update(theme, context.resources.displayMetrics, preferences.heightScale)
                view.visibleLimit = preferences.suggestionCount
                view.setSuggestions(sample, sample.size)
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
 * Keys rather than words: the point is realistic *length*, and a language whose words are longer
 * than English's would make the preview a lie if it kept showing English ones.
 *
 * Short filler words would make eight slots look comfortable and then surprise the user on the
 * first long word, which is the opposite of what a preview is for.
 */
private val SAMPLE_KEYS = arrayOf(
    Keys.SUGGESTION_STRIP_PREVIEW_KEYBOARD, Keys.SUGGESTION_STRIP_PREVIEW_BECAUSE, Keys.SUGGESTION_STRIP_PREVIEW_THROUGH, Keys.SUGGESTION_STRIP_PREVIEW_ANOTHER,
    Keys.SUGGESTION_STRIP_PREVIEW_QUESTION, Keys.SUGGESTION_STRIP_PREVIEW_TOGETHER, Keys.SUGGESTION_STRIP_PREVIEW_IMPORTANT, Keys.SUGGESTION_STRIP_PREVIEW_DIFFERENT,
)
