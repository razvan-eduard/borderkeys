// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
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
