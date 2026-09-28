// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys
import com.borderkeys.predict.Candidate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.borderkeys.data.theme.KeyboardAppearance
import com.borderkeys.data.theme.ParticleRegionSettings
import com.borderkeys.ime.SuggestionStripView
import com.borderkeys.ime.fx.applyParticleLayer
import com.borderkeys.theme.ThemeMode
import com.borderkeys.theme.ThemePaints

/**
 * The real suggestion strip, showing sample words, so the slot count can be seen. Inert: touches
 * are consumed and dropped.
 */
@Composable
fun SuggestionStripPreview(
    appearance: KeyboardAppearance,
    modifier: Modifier = Modifier,
    /**
     * When given, drawn regardless of its [ParticleRegionSettings.enabled], for
     * [com.borderkeys.settings.screen.EffectsScreen]'s Suggestion Strip card. Null elsewhere: no
     * particles.
     */
    previewParticles: ParticleRegionSettings? = null,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val paints = remember { ThemePaints() }
    val (theme, lightTheme, preferences) = appearance
    // Resolved into the array the view reads, once per language.
    val sample = remember(strings) {
        SAMPLE_KEYS.map { Candidate(strings[it]) }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        AndroidView(
            factory = { viewContext ->
                SuggestionStripView(viewContext, paints, strings).apply { isEnabled = false }
            },
            update = { view ->
                val effectiveTheme = ThemeMode.effective(theme, lightTheme, preferences, context)
                paints.update(effectiveTheme, context.resources.displayMetrics, preferences.heightScale, context)
                view.visibleLimit = preferences.suggestionCount
                view.setSuggestions(sample)
                // Marked the way the real row marks: the first chip is what was typed, and one
                // in the middle is what a delimiter would put in its place.
                view.typedIndex = 0
                view.appliedIndex = (preferences.suggestionCount / 2)
                    .coerceAtMost(sample.size - 1)
                    .coerceAtLeast(0)
                // Ignores ParticleRegionSettings.enabled, as previewParticles says.
                if (previewParticles != null) {
                    applyParticleLayer(view.particles, previewParticles.copy(enabled = true))
                } else {
                    view.particles.fill.enabled = false
                    view.particles.outline.enabled = false
                }
                view.requestLayout()
                view.invalidate()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Catalogue keys of eight words of realistic length in each language, so the preview narrows the
 * way the real strip will.
 */
private val SAMPLE_KEYS = arrayOf(
    Keys.SUGGESTION_STRIP_PREVIEW_KEYBOARD, Keys.SUGGESTION_STRIP_PREVIEW_BECAUSE, Keys.SUGGESTION_STRIP_PREVIEW_THROUGH, Keys.SUGGESTION_STRIP_PREVIEW_ANOTHER,
    Keys.SUGGESTION_STRIP_PREVIEW_QUESTION, Keys.SUGGESTION_STRIP_PREVIEW_TOGETHER, Keys.SUGGESTION_STRIP_PREVIEW_IMPORTANT, Keys.SUGGESTION_STRIP_PREVIEW_DIFFERENT,
)
