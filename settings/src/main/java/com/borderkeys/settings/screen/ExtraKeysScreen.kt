// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.DeadKeys
import com.borderkeys.ime.ExtraKeys
import com.borderkeys.ime.KeyCodes
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.SwitchRow
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The extra keys the languages of this keyboard's enabled subtypes add where a layout lacks
 * them, one switch each, with the languages that add it and the key it sits beside.
 */
@Composable
fun ExtraKeysScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    var offered by remember { mutableStateOf(emptyList<Offered>()) }
    LaunchedEffect(Unit) {
        offered = withContext(Dispatchers.IO) { offeredExtraKeys(context) }
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SCREEN_EXTRA_KEYS]) {
            Explanation(strings[Keys.LAYOUT_EXTRA_KEYS_NOTE])
            if (offered.isEmpty()) {
                SettingRow(title = strings[Keys.EXTRA_KEYS_NONE])
            }
            for (item in offered) {
                val title = if (item.isAccent) {
                    "${DeadKeys.cap(KeyCodes.named(item.id)).orEmpty()}  ${strings[modifierKeyLabel(item.id)].split('.', '。').first()}"
                } else {
                    item.id
                }
                val where = item.languages.joinToString(", ") { displayNameFor(it) }
                SwitchRow(
                    title = title,
                    subtitle = item.nextTo?.let { "$where · ${strings.getString(Keys.EXTRA_KEYS_BESIDE, it)}" } ?: where,
                    checked = item.id !in preferences.extraKeysOff,
                ) { on ->
                    update {
                        it.copy(extraKeysOff = if (on) it.extraKeysOff - item.id else it.extraKeysOff + item.id)
                    }
                }
            }
        }
    }
}

/** One extra key on offer: what it adds, whether that is an accent modifier, who adds it, beside what. */
private class Offered(val id: String, val isAccent: Boolean, val languages: List<String>, val nextTo: String?)

/** The extra keys of the enabled subtypes' languages, each once, in the order they are added. */
private fun offeredExtraKeys(context: Context): List<Offered> {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return emptyList()
    val ours = manager.enabledInputMethodList.firstOrNull { it.packageName == context.packageName }
        ?: return emptyList()
    val tags = manager.getEnabledInputMethodSubtypeList(ours, true).map { it.languageTag }.filter { it.isNotEmpty() }.distinct()
    val table = ExtraKeys.load(context.assets)
    val byId = LinkedHashMap<String, Offered>()
    for (tag in tags) {
        for (entry in table[tag].orEmpty()) {
            val id = ExtraKeys.idOf(entry)
            val isAccent = entry.isAccent && entry.alternatives.size != 1
            val known = byId[id]
            byId[id] = if (known == null) {
                Offered(id, isAccent, listOf(tag), entry.nextTo)
            } else {
                Offered(id, isAccent, known.languages + tag, known.nextTo ?: entry.nextTo)
            }
        }
    }
    return byId.values.toList()
}
