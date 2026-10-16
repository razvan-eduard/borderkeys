// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.PhraseShape
import com.borderkeys.data.UserPhrase
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.language.LanguageTags
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SPACE_LARGE
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberLearned
import kotlinx.coroutines.launch

/**
 * Every pair and triple this device has learned, searchable by the text each row shows, each with
 * Delete. The search box's text, two or three words, can be added as a phrase by hand in the
 * language chosen among the enabled ones; a filter lists the phrases added by hand, the phrases
 * learned by use, or both; below, the button that deletes the phrases listed.
 */
@Composable
fun LearnedPhrasesScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val repository = remember { DataGraph.dictionary }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var origin by rememberSaveable { mutableStateOf(PersonalEntries.Origin.ANY) }
    val filtering = query.isNotBlank()
    val saved by repository.phrases.collectAsStateWithLifecycle(initialValue = emptyList())
    // Only the phrases that count as learned, as the keyboard counts them, from the origin chosen.
    val learned = rememberLearned(saved, PersonalEntries::phrases)
    val phrases = PersonalEntries.ofOrigin(learned, origin) { it.added }
    // Each word in the spelling it was learned in when its capital was typed on purpose.
    val words by repository.words.collectAsStateWithLifecycle(initialValue = emptyList())
    val spellings = remember(words) {
        words.filter { it.deliberateCapitals > 0 }.associate { PhraseKey.word(it.word) to it.word }
    }
    val listed = remember(phrases, query, spellings) {
        if (filtering) {
            phrases.filter { textOf(it, spellings, strings).contains(query, ignoreCase = true) }
        } else {
            phrases
        }
    }
    // Under a heading per language.
    val byLanguage = remember(listed) { listed.groupBy { it.language } }
    // The search box's text as a phrase to add, once no listed phrase is the same in its language.
    val candidate = remember(query) { PhraseShape.wordsOf(query) }
    val languages = remember(candidate) {
        languageChoices(
            KeyboardSubtypes.enabledTags(context),
            KeyboardSubtypes.inUse(context).firstOrNull()?.languageTag,
        )
    }
    var language by rememberSaveable(languages) {
        mutableStateOf(languages.firstOrNull() ?: KeyboardSubtypes.language(context))
    }
    val canAdd = remember(candidate, language, learned) {
        candidate != null && !PhraseShape.isListed(candidate, LanguageTags.primary(language), learned)
    }
    // Set while an add is being written; the button stays disabled until the write returns.
    var adding by remember { mutableStateOf(false) }
    var confirmingBlocked by remember { mutableStateOf<List<String>?>(null) }
    val narrowedList = narrowed(query, origin)
    val (deleteKey, deleteTitleKey) = phraseDeleteKeys(narrowedList)

    fun add(phrase: List<String>, unblock: Boolean) {
        adding = true
        scope.launch {
            try {
                if (repository.addPhrase(phrase, language, System.currentTimeMillis(), unblock) ==
                    DictionaryRepository.Added.BLOCKED
                ) {
                    confirmingBlocked = phrase
                }
            } finally {
                adding = false
            }
        }
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SettingsSectionCard(strings.getString(Keys.DICTIONARY_PHRASES, listed.size)) {
                Explanation(strings[Keys.DICTIONARY_PHRASES_NOTE])
                SearchBox(query) { query = it }
                TextButton(
                    enabled = canAdd && !adding,
                    onClick = {
                        val phrase = candidate ?: return@TextButton
                        adding = true
                        scope.launch {
                            if (repository.isBlocked(phrase)) {
                                adding = false
                                confirmingBlocked = phrase
                            } else {
                                add(phrase, unblock = false)
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
                    label = if (narrowedList) strings.getString(deleteKey, listed.size) else strings[deleteKey],
                    title = strings[deleteTitleKey],
                    confirmLabel = strings[Keys.DICTIONARY_DELETE],
                    listed = listed,
                    // The listed phrases; their words and the rest stay.
                    onDelete = { doomed -> scope.launch { repository.forgetPhrases(doomed) } },
                )
            }
        }
        if (listed.isEmpty()) {
            item {
                SettingRow(
                    title = if (narrowedList) {
                        strings[Keys.DICTIONARY_NO_MATCH]
                    } else {
                        strings[Keys.DICTIONARY_NOTHING_LEARNED_YET]
                    },
                )
            }
        }
        for ((phraseLanguage, inLanguage) in byLanguage) {
            item(key = phraseLanguage) {
                SectionHeader(
                    if (phraseLanguage == LanguageTags.UNKNOWN) {
                        strings[Keys.DICTIONARY_PHRASES_UNKNOWN_LANGUAGE]
                    } else {
                        displayNameFor(phraseLanguage)
                    },
                )
            }
            items(inLanguage, key = { (listOf(it.language) + it.words).joinToString(KEY_SEPARATOR) }) { phrase ->
                SettingRow(
                    title = textOf(phrase, spellings, strings),
                    subtitle = if (phrase.added > 0) {
                        strings[Keys.DICTIONARY_ADDED_BY_HAND]
                    } else {
                        strings.counted(Keys.DICTIONARY_PHRASE_USED, phrase.count)
                    },
                    trailing = {
                        TextButton(onClick = { scope.launch { repository.forgetPhrase(phrase) } }) {
                            Text(strings[Keys.DICTIONARY_DELETE])
                        }
                    },
                )
            }
        }
    }

    confirmingBlocked?.let { phrase ->
        ConfirmDialog(
            title = strings[Keys.DICTIONARY_ADD_BLOCKED_TITLE],
            text = strings[Keys.DICTIONARY_ADD_BLOCKED_TEXT],
            confirmLabel = strings[Keys.DICTIONARY_ADD_TO_DICTIONARY],
            onDismiss = { confirmingBlocked = null },
        ) { add(phrase, unblock = true) }
    }
}

/**
 * The row text of [phrase]: its words, or for a sentence opener its word and where it stood, each
 * word in the spelling [spellings] holds for it.
 */
private fun textOf(phrase: UserPhrase, spellings: Map<String, String>, strings: LanguageManager): String {
    val shown = phrase.words.map { spellings[it] ?: it }
    return if (phrase.opensSentence) {
        strings.getString(Keys.DICTIONARY_SENTENCE_OPENER, shown.last())
    } else {
        shown.joinToString(WORD_SEPARATOR)
    }
}

/** Between the words of a listed phrase. */
private const val WORD_SEPARATOR = " "

/** Between the words of a phrase in its list key; no word holds it. */
private const val KEY_SEPARATOR = "\u0000"
