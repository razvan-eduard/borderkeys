// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import com.borderkeys.language.LanguageTags
import com.borderkeys.settings.AdvancedSection
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SPACE_XSMALL
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberLearned
import kotlinx.coroutines.launch

/**
 * Every word this device has learned, searchable, under a heading per language ([wordGroups]),
 * each with Block and Delete. The search box's text can be added to the dictionary by hand, in the
 * language chosen among the enabled ones; a filter lists the words added by hand, the words learned
 * by use, or both; below, the button that deletes the words listed, and under Advanced settings,
 * Forget everything.
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
    // The spellings the search finds.
    val found by remember(query) { if (filtering) repository.search(query) else repository.words }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the words that count as learned, as the keyboard counts them, in each language, from the origin chosen.
    val saved by repository.words.collectAsStateWithLifecycle(initialValue = emptyList())
    val learnedWords = rememberLearned(saved, PersonalEntries::byLanguage)
    val learned = remember(learnedWords) { learnedWords.flatMapTo(HashSet()) { it.spellings } }
    val words = remember(learnedWords, found, filtering, origin) {
        val matching = found.mapTo(HashSet()) { it.word }
        val searched =
            if (filtering) learnedWords.filter { word -> word.spellings.any { it in matching } } else learnedWords
        PersonalEntries.ofOrigin(searched, origin) { it.added }
    }
    // The languages of the packs switched on, whose words the keyboard suggests.
    val packs by DataGraph.languagePacks.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = remember(packs) { packs.filter { it.enabled }.mapTo(HashSet()) { LanguageTags.primary(it.tag) } }
    val groups = remember(words, active) { wordGroups(words, active) }
    val listedCount = remember(words) { words.distinctBy { it.key }.size }
    // The search box's text as a word to add, once no listed word is that spelling.
    val candidate = query.trim()
    val canAdd = addable(candidate, learned)
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
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_LEARNED_WORDS, listedCount)) {
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
                    LanguageChips(languages, language) { language = it }
                }
                OriginChips(origin) { origin = it }
                DeleteListed(
                    label = strings.getString(deleteKey, listedCount),
                    title = strings[deleteTitleKey],
                    confirmLabel = strings[Keys.DICTIONARY_DELETE],
                    listed = words,
                    // The listed words, every spelling of each, and their phrases; the rest stays.
                    onDelete = { doomed ->
                        scope.launch { repository.forget(doomed.flatMap { it.spellings }.distinct()) }
                    },
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
        learnedWordGroups(
            groups,
            onBlock = { word -> scope.launch { repository.block(word.spelling) } },
            onDelete = { word -> scope.launch { repository.forgetEveryCase(word.spelling) } },
        )
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

/**
 * [groups] as the Learned words page lists them: each language's heading ([languageHeading]), a
 * note under a language that is off, then its words, each in the spelling served with how often it
 * was used there or that it was added by hand, and Block ([onBlock]) and Delete ([onDelete]).
 */
internal fun LazyListScope.learnedWordGroups(
    groups: List<WordGroup>,
    onBlock: (PersonalEntries.ListedWord) -> Unit,
    onDelete: (PersonalEntries.ListedWord) -> Unit,
) {
    for (group in groups) {
        item(key = HEADING_KEY + group.language) {
            val strings = LocalStrings.current
            SectionHeader(languageHeading(group.language, strings))
            if (group.off) {
                Explanation(strings[Keys.DICTIONARY_WORDS_LANGUAGE_OFF])
            }
        }
        items(group.words, key = { group.language + KEY_SEPARATOR + it.key }) { word ->
            val strings = LocalStrings.current
            SettingRow(
                title = word.spelling,
                subtitle = if (word.added > 0) {
                    strings[Keys.DICTIONARY_ADDED_BY_HAND]
                } else {
                    strings.counted(Keys.DICTIONARY_PHRASE_USED, word.count)
                },
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(SPACE_XSMALL)) {
                        TextButton(onClick = { onBlock(word) }) { Text(strings[Keys.DICTIONARY_BLOCK]) }
                        TextButton(onClick = { onDelete(word) }) { Text(strings[Keys.DICTIONARY_DELETE]) }
                    }
                },
            )
        }
    }
}

/** What opens a heading's list key, which no language or word holds. */
private const val HEADING_KEY = "\u0001"

/** Between a word's language and its key in its list key; no word holds it. */
private const val KEY_SEPARATOR = "\u0000"
