// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.theme.CustomLayout
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.LayoutValidator
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.PlacementPreview
import com.borderkeys.settings.SettingRow
import com.borderkeys.settings.SettingsSectionCard
import com.borderkeys.settings.rememberPreferencesUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The layouts the user wrote: a list with edit and delete, and an editor that validates the
 * JSON as it is typed with the keyboard's own rules, previews it, and imports or exports a file.
 */
@Composable
fun CustomLayoutsScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val themes = remember { DataGraph.themes }
    val update = rememberPreferencesUpdater()
    val appearance by themes.appearance
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentAppearance() })
    val preferences = appearance.preferences
    val builtIns = remember { context.assets.list("layouts").orEmpty().map { it.removeSuffix(".json") }.sorted() }

    var editing by remember { mutableStateOf<CustomLayout?>(null) }
    var deleting by remember { mutableStateOf<CustomLayout?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SCREEN_CUSTOM_LAYOUTS]) {
            Explanation(strings[Keys.CUSTOM_LAYOUTS_NOTE])
            if (preferences.customLayouts.isEmpty()) {
                Explanation(strings[Keys.CUSTOM_LAYOUTS_NONE])
            }
            for (layout in preferences.customLayouts) {
                val parsed = remember(layout.json) { LayoutValidator.parse(layout.json) }
                SettingRow(
                    title = layout.name,
                    subtitle = if (parsed != null) {
                        strings.getString(Keys.CUSTOM_LAYOUTS_ROWS, parsed.rows.size, parsed.keyCount)
                    } else {
                        strings[Keys.LAYOUT_ERROR_JSON]
                    },
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editing = layout }) { Text(strings[Keys.CUSTOM_LAYOUTS_EDIT]) }
                            TextButton(onClick = { deleting = layout }) { Text(strings[Keys.CUSTOM_LAYOUTS_DELETE]) }
                        }
                    },
                )
            }
            if (preferences.customLayouts.size < CustomLayout.MAX_CUSTOM_LAYOUTS) {
                TextButton(
                    onClick = { picking = !picking },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) { Text(strings[Keys.CUSTOM_LAYOUTS_ADD]) }
                if (picking) {
                    Explanation(strings[Keys.CUSTOM_LAYOUTS_COPY_FROM])
                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                        for (id in builtIns) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    picking = false
                                    val json = context.assets.open("layouts/$id.json").use { it.readBytes().decodeToString() }
                                    editing = CustomLayout(
                                        id = CustomLayout.nextId(preferences.customLayouts),
                                        name = id,
                                        languageTag = LayoutValidator.parse(json)?.languageTag ?: "und",
                                        json = json,
                                    )
                                },
                                label = { Text(id) },
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val target = editing
    if (target != null) {
        LayoutEditor(
            layout = target,
            flicks = preferences.keyFlicks,
            onSave = { saved ->
                update { current ->
                    current.copy(customLayouts = current.customLayouts.filterNot { it.id == saved.id } + saved)
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    val doomed = deleting
    if (doomed != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(doomed.name) },
            text = { Text(strings[Keys.CUSTOM_LAYOUTS_DELETE_CONFIRM]) },
            confirmButton = {
                TextButton(
                    onClick = {
                        update { current ->
                            current.copy(
                                customLayouts = current.customLayouts.filterNot { it.id == doomed.id },
                                subtypeLayouts = current.subtypeLayouts.filterValues { it != doomed.id },
                            )
                        }
                        deleting = null
                    },
                ) { Text(strings[Keys.CUSTOM_LAYOUTS_DELETE]) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings[Keys.CUSTOM_LAYOUTS_CANCEL]) } },
        )
    }
}

/** One layout's name, language and JSON, validated as typed, with a preview while it is valid. */
@Composable
private fun LayoutEditor(
    layout: CustomLayout,
    flicks: List<KeyFlick>,
    onSave: (CustomLayout) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val themes = remember { DataGraph.themes }
    val appearance by themes.appearance
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentAppearance() })
    var name by remember { mutableStateOf(layout.name) }
    var languageTag by remember { mutableStateOf(layout.languageTag) }
    var json by remember { mutableStateOf(layout.json) }
    val problems = remember(json) { LayoutValidator.validate(json) }
    val parsed = remember(json) { if (problems.isEmpty()) LayoutValidator.parse(json) else null }
    val candidate = CustomLayout(layout.id, name, languageTag, json)

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            if (text != null && text.length <= CustomLayout.MAX_JSON_CHARS) {
                json = text
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(withFlicksBaked(json, flicks).toByteArray())
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name.ifBlank { strings[Keys.CUSTOM_LAYOUTS_ADD] }) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(CustomLayout.MAX_NAME_CHARS) },
                    label = { Text(strings[Keys.CUSTOM_LAYOUTS_NAME_HINT]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = languageTag,
                    onValueChange = { languageTag = it.take(CustomLayout.MAX_LANGUAGE_TAG_CHARS) },
                    label = { Text(strings[Keys.CUSTOM_LAYOUTS_LANGUAGE_HINT]) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it.take(CustomLayout.MAX_JSON_CHARS) },
                    label = { Text(strings[Keys.CUSTOM_LAYOUTS_JSON]) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                    isError = problems.isNotEmpty(),
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problems.isEmpty()) {
                    Text(strings[Keys.CUSTOM_LAYOUTS_VALID])
                } else {
                    for (problem in problems.take(MAX_PROBLEMS_SHOWN)) {
                        Text(
                            if (problem.key >= 0) {
                                strings.getString(problem.messageKey, problem.row + 1, problem.key + 1)
                            } else {
                                strings[problem.messageKey]
                            },
                        )
                    }
                }
                if (parsed != null) {
                    PlacementPreview(appearance, layout = parsed)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { importFile.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                        Text(strings[Keys.CUSTOM_LAYOUTS_IMPORT])
                    }
                    TextButton(enabled = problems.isEmpty(), onClick = { exportFile.launch("${name.ifBlank { layout.id }}.json") }) {
                        Text(strings[Keys.CUSTOM_LAYOUTS_EXPORT])
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = problems.isEmpty() && candidate.isValid, onClick = { onSave(candidate) }) {
                Text(strings[Keys.CUSTOM_LAYOUTS_SAVE])
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings[Keys.CUSTOM_LAYOUTS_CANCEL]) } },
    )
}

/** The layout's JSON with the user's text flicks written into each key's "flicks" object. */
internal fun withFlicksBaked(json: String, flicks: List<KeyFlick>): String {
    if (flicks.none { it.kind == KeyFlick.TEXT }) {
        return json
    }
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return json
    val rows: JSONArray = root.optJSONArray("rows") ?: return json
    for (rowIndex in 0 until rows.length()) {
        val keys = rows.optJSONObject(rowIndex)?.optJSONArray("keys") ?: continue
        for (keyIndex in 0 until keys.length()) {
            val key = keys.optJSONObject(keyIndex) ?: continue
            val character = key.optString("c", "")
            val code = if (character.isNotEmpty()) character.codePointAt(0) else com.borderkeys.ime.KeyCodes.named(key.optString("code", ""))
            val own = flicks.filter { it.keyCode == code && it.kind == KeyFlick.TEXT }
            if (own.isEmpty()) {
                continue
            }
            val target = key.optJSONObject("flicks") ?: JSONObject()
            for (flick in own) {
                target.put(com.borderkeys.ime.LayoutLoader.FLICK_NAMES[flick.direction], flick.value)
            }
            key.put("flicks", target)
        }
    }
    return root.toString(2)
}

private const val MAX_PROBLEMS_SHOWN = 6
