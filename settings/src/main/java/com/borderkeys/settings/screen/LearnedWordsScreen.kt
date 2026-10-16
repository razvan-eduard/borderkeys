// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.KeyboardSubtypes
import com.borderkeys.data.PersonalEntries
import com.borderkeys.i18n.Keys
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PickerChip
import com.borderkeys.settings.SPACE_GUTTER
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SPACE_MEDIUM
import com.borderkeys.settings.SPACE_XSMALL
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberLearned
import kotlinx.coroutines.launch

/**
 * Every word this device has learned, searchable, each with Block and Delete. The search box's
 * text can be added to the dictionary by hand, in the language chosen among the enabled ones; a
 * filter lists the words added by hand, the words learned by use, or both; below, the button that
 * deletes the words listed, and under Advanced settings, Forget everything.
 */
@Composable
fun LearnedWordsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf(PersonalEntries.Origin.ANY) }
    val filtering = query.isNotBlank()
    val listed = remember(query) { if (filtering) repository.search(query) else repository.words }
    val found by listed.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the words that count as learned, as the keyboard counts them, from the origin chosen.
    val saved by repository.words.collectAsStateWithLifecycle(initialValue = emptyList())
    val learnedWords = rememberLearned(saved, PersonalEntries::words)
    val learned = remember(learnedWords) { learnedWords.mapTo(HashSet()) { it.word } }
    val words = remember(found, learned, origin) {
        PersonalEntries.ofOrigin(found.filter { it.word in learned }, origin)
    }
    // The search box's text as a word to add, once no stored row is that spelling.
    val stored = remember(saved) { saved.mapTo(HashSet()) { it.word } }
    val candidate = query.trim()
    val canAdd = addable(candidate, stored)
    val languages = remember(canAdd) {
        languageChoices(
            KeyboardSubtypes.enabledTags(context),
            KeyboardSubtypes.inUse(context).firstOrNull()?.languageTag,
        )
    }
    var language by rememberSaveable(languages) {
        mutableStateOf(languages.firstOrNull() ?: KeyboardSubtypes.language(context))
    }
    // Set while an add is being written; the button stays disabled until the write returns.
    var adding by remember { mutableStateOf(false) }
    var confirmingBlocked by remember { mutableStateOf<String?>(null) }
    var confirmingForget by remember { mutableStateOf(false) }
    val narrowedList = narrowed(query, origin)
    val (deleteKey, deleteTitleKey) = deleteKeys(narrowedList)

    fun add(word: String, unblock: Boolean) {
        adding = true
        scope.launch {
            try {
                if (repository.add(word, language, System.currentTimeMillis(), unblock) == DictionaryRepository.Added.BLOCKED) {
                    confirmingBlocked = word
                }
            } finally {
                adding = false
            }
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, words.size)) {
                SearchBox(query) { query = it }
                TextButton(
                    enabled = canAdd && !adding,
                    onClick = {
                        adding = true
                        scope.launch {
                            if (repository.isBlocked(candidate)) {
                                adding = false
                                confirmingBlocked = candidate
                            } else {
                                add(candidate, unblock = false)
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = SPACE_LARGE),
                ) { Text(strings[Keys.DICTIONARY_ADD_TO_DICTIONARY]) }
                if (canAdd && languages.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
                        horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
                    ) {
                        for (tag in languages) {
                            PickerChip(displayNameFor(tag), selected = tag == language) { language = tag }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = SPACE_GUTTER, vertical = SPACE_XSMALL),
                    horizontalArrangement = Arrangement.spacedBy(SPACE_MEDIUM),
                ) {
                    for ((value, labelKey) in ORIGIN_LABELS) {
                        PickerChip(strings[labelKey], selected = origin == value) { origin = value }
                    }
                }
                DeleteListed(
                    label = strings.getString(deleteKey, words.size),
                    title = strings[deleteTitleKey],
                    confirmLabel = strings[Keys.DICTIONARY_DELETE],
                    listed = words,
                    // The listed words and their phrases; the rest stays.
                    onDelete = { doomed -> scope.launch { repository.forget(doomed.map { it.word }) } },
                )
                AdvancedSection(summary = strings[Keys.DICTIONARY_FORGET_EVERYTHING]) {
                    TextButton(
                        onClick = { confirmingForget = true },
                        modifier = Modifier.padding(horizontal = SPACE_LARGE),
                    ) { Text(strings[Keys.DICTIONARY_FORGET_EVERYTHING]) }
                }
            }
        }
        if (words.isEmpty()) {
            item {
                SettingRow(
                    title = if (narrowedList) {
                        strings[Keys.DICTIONARY_NO_MATCH]
                    } else {
                        strings[Keys.DICTIONARY_NOTHING_LEARNED_YET]
                    },
                    subtitle = if (narrowedList) null else strings[Keys.DICTIONARY_A_WORD_IS_LEARNED_WHEN_YOU],
                )
            }
        }
        items(words, key = { it.word }) { word ->
            SettingRow(
                title = word.word,
                subtitle = if (word.added > 0) {
                    strings.getString(Keys.DICTIONARY_ADDED_BY_HAND_ON, word.locale)
                } else {
                    strings.counted(Keys.DICTIONARY_CHOSEN_TIMES_TYPED_ON, word.count, word.count, word.locale)
                },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(SPACE_XSMALL)) {
                        TextButton(onClick = { scope.launch { repository.block(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_BLOCK])
                        }
                        TextButton(onClick = { scope.launch { repository.forgetEveryCase(word.word) } }) {
                            Text(strings[Keys.DICTIONARY_DELETE])
                        }
                    }
                },
            )
        }
    }

    confirmingBlocked?.let { word ->
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_ADD_BLOCKED_TITLE],
            text = strings[Keys.DICTIONARY_ADD_BLOCKED_TEXT],
            confirmLabel = strings[Keys.DICTIONARY_ADD_TO_DICTIONARY],
            onDismiss = { confirmingBlocked = null },
        ) { add(word, unblock = true) }
    }
    if (confirmingForget) {
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_FORGET_EVERYTHING_TITLE],
            text = strings[Keys.COMMON_CANNOT_BE_UNDONE],
            confirmLabel = strings[Keys.DICTIONARY_FORGET_EVERYTHING],
            onDismiss = { confirmingForget = false },
        ) {
            // Every learned word and phrase, and the heatmap.
            scope.launch { repository.forgetEverything() }
        }
    }
}

/** The filter chips, in order: each origin with its label's key. */
private val ORIGIN_LABELS = listOf(
    PersonalEntries.Origin.ANY to Keys.DICTIONARY_FILTER_ALL,
    PersonalEntries.Origin.LEARNED to Keys.DICTIONARY_FILTER_LEARNED,
    PersonalEntries.Origin.ADDED to Keys.DICTIONARY_FILTER_ADDED,
)
