// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SuggestionStripPreview
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.launch

/**
 * What the keyboard does with a word once you have finished typing it.
 *
 * The default is that it does nothing: the delimiter commits your letters and a suggestion is
 * applied only when you tap it. That is the behaviour this keyboard argues for, and it is why
 * the switch below starts off rather than on.
 */
@Composable
fun CorrectionsScreen(modifier: Modifier = Modifier) {
    val repository = remember { DataGraph.themes }
    val scope = rememberCoroutineScope()
    val theme by repository.theme.collectAsStateWithLifecycle(initialValue = KeyboardTheme())
    val preferences by repository.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    fun update(transform: (KeyboardPreferences) -> KeyboardPreferences) {
        scope.launch { repository.updatePreferences(transform) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Suggestions")
        SwitchRow(
            title = "Show the suggestion strip",
            subtitle = "The row above the keys. It carries corrections of the word you are " +
                "typing and, once you finish one, the words that usually follow it.",
            checked = preferences.showSuggestionStrip,
        ) { value -> update { it.copy(showSuggestionStrip = value) } }

        SectionHeader("How many suggestions")
        SuggestionStripPreview(theme, preferences, Modifier.padding(vertical = 8.dp))
        Text(
            "${preferences.suggestionCount} at a time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Slider(
            value = preferences.suggestionCount.toFloat(),
            valueRange = KeyboardPreferences.MIN_SUGGESTIONS.toFloat()..
                KeyboardPreferences.MAX_SUGGESTIONS.toFloat(),
            steps = KeyboardPreferences.MAX_SUGGESTIONS - KeyboardPreferences.MIN_SUGGESTIONS - 1,
            onValueChange = { value ->
                update { it.copy(suggestionCount = value.toInt()) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Explanation(
            "The strip is a fixed width, so every extra slot makes each one narrower and each " +
                "target smaller. The preview above uses words of realistic length rather than " +
                "short filler, because that is where the difference shows.",
        )

        Divider()
        SectionHeader("Two words at once")
        SwitchRow(
            title = "Suggest whole phrases",
            subtitle = "Offers \"vreau să\" where it would otherwise offer \"vreau\", for " +
                "phrases you write over and over.",
            checked = preferences.phraseSuggestions,
        ) { value -> update { it.copy(phraseSuggestions = value) } }
        Explanation(
            "Only from what you have written, never from the dictionary: frequency can chain " +
                "any two common pairs into something grammatical and meaningless. The second " +
                "word needs twice the evidence of the first, so a phrase appears after about " +
                "four repetitions where a single word appears after two — twice the evidence " +
                "for twice the guess. The first word is always offered on its own as well.",
        )

        Divider()
        SectionHeader("Correcting as you type")
        SwitchRow(
            title = "Apply the first suggestion when you press space",
            subtitle = "Off by default. With it off, space commits exactly the letters you " +
                "typed and a correction is applied only when you tap it.",
            checked = preferences.autoCorrectOnSpace,
        ) { value -> update { it.copy(autoCorrectOnSpace = value) } }
        Explanation(
            "The usual objection to autocorrect is not that it is wrong sometimes — it is that " +
                "undoing it costs more than typing the word did. So this only exists together " +
                "with the switch below, and words shorter than three letters are left alone: " +
                "half the alphabet is one edit away from them.",
        )

        SwitchRow(
            title = "Backspace puts back what you typed",
            subtitle = "The backspace straight after a correction restores your original word " +
                "instead of deleting a letter. One key, and it is exactly as you wrote it.",
            checked = preferences.revertCorrectionOnBackspace,
        ) { value -> update { it.copy(revertCorrectionOnBackspace = value) } }
        Explanation(
            "A correction is only learned once it survives that first backspace. Reject it and " +
                "nothing is remembered except your own word, which is what rejecting it says.",
        )
    }
}
