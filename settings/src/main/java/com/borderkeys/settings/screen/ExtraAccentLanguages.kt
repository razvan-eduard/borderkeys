// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.AccentOverlays
import com.borderkeys.ime.KeyFlags
import com.borderkeys.ime.LayoutChoice
import com.borderkeys.ime.LayoutLoader
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One switch per language whose accents reach a letter of the selected layout, showing the
 * forms it adds, for "Accents from other languages"; a language whose pack is on shows on and
 * greyed.
 */
@Composable
internal fun ExtraAccentLanguages(
    preferences: KeyboardPreferences,
    update: ((KeyboardPreferences) -> KeyboardPreferences) -> Unit,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val packs by DataGraph.languagePacks.packs.collectAsStateWithLifecycle(initialValue = emptyList())
    val dictionaryTags = packs.filter { it.enabled }.mapTo(HashSet()) { it.tag }
    var lending by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape, preferences.customLayouts) {
        lending = withContext(Dispatchers.IO) { lendingLanguages(context, preferences) }
    }

    for ((tag, forms) in lending) {
        val fromDictionary = tag in dictionaryTags
        SwitchRow(
            title = displayNameFor(tag),
            subtitle = if (fromDictionary) strings[Keys.LAYOUT_EXTRA_ACCENTS_FROM_DICTIONARY] else forms,
            checked = fromDictionary || tag in preferences.extraAccentLanguages,
            enabled = preferences.accentedCharacters && preferences.extraAccents && !fromDictionary,
        ) { on ->
            update {
                it.copy(
                    extraAccentLanguages = if (on) {
                        (it.extraAccentLanguages + tag).distinct().sorted()
                    } else {
                        it.extraAccentLanguages - tag
                    },
                )
            }
        }
    }
}

/**
 * The languages whose accents file lends an accent to a letter of the selected layout, each
 * with the forms it lends there, by tag. The selected layout is the current subtype's while
 * this keyboard is the one in use, else every enabled subtype's, upright and in landscape.
 */
private fun lendingLanguages(context: Context, preferences: KeyboardPreferences): List<Pair<String, String>> {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return emptyList()
    val ours = manager.enabledInputMethodList.firstOrNull { it.packageName == context.packageName }
        ?: return emptyList()
    val inUse = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/') == context.packageName
    val current = manager.currentInputMethodSubtype
    val subtypes = if (inUse && current != null) {
        listOf(current)
    } else {
        manager.getEnabledInputMethodSubtypeList(ours, true)
    }
    val letters = HashSet<Char>()
    val layouts = subtypes.flatMap { subtype ->
        listOf(false, true).map { landscape ->
            LayoutChoice.resolve(
                LayoutChoice.layoutIdOf(subtype.extraValue),
                LayoutChoice.forOrientation(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape, landscape),
                preferences.customLayouts,
            ) { LayoutLoader.load(context.assets, it) }
        }
    }
    for (layout in layouts) {
        for (row in layout.rows) {
            for (key in row.keys) {
                if (KeyFlags.has(key.flags, KeyFlags.LETTER) && key.label.length == 1) {
                    letters += key.label[0].lowercaseChar()
                }
            }
        }
    }
    val overlays = AccentOverlays.loadAll(context.assets)
    return AccentOverlays.lendingTo(letters, overlays).map { tag ->
        val forms = overlays.getValue(tag).filterKeys { it in letters }.values.joinToString("")
        tag to forms.toList().distinct().joinToString(" ")
    }
}
