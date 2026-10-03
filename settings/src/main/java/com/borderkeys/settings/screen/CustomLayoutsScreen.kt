// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Context
import android.net.Uri
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
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
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.ime.LayoutChoice
import com.borderkeys.keyboard.R
import com.borderkeys.settings.move
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

    val usage = remember(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape) { layoutUsage(context, preferences) }
    var editing by remember { mutableStateOf<CustomLayout?>(null) }
    var deleting by remember { mutableStateOf<CustomLayout?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SettingsSectionCard(strings[Keys.SCREEN_CUSTOM_LAYOUTS]) {
            Explanation(strings[Keys.CUSTOM_LAYOUTS_NOTE])
            if (preferences.customLayouts.isEmpty()) {
                Explanation(strings[Keys.CUSTOM_LAYOUTS_NONE])
            }
            Explanation(strings[Keys.CUSTOM_LAYOUTS_ORDER_NOTE])
            val listed = builtIns.filterNot { it.startsWith("symbols") || it == "numpad" }
            val order = CustomLayout.ordered(preferences.customLayouts.map { it.id } + listed, preferences.layoutOrder)
            order.forEachIndexed { index, id ->
                val custom = preferences.customLayouts.firstOrNull { it.id == id }
                val parsed = remember(custom?.json) { custom?.let { LayoutValidator.parse(it.json) } }
                val badges = buildList {
                    add(strings[if (custom != null) Keys.CUSTOM_LAYOUTS_OWN else Keys.CUSTOM_LAYOUTS_BUILT_IN])
                    usage.portrait[id]?.let { addAll(it) }
                    usage.landscape[id]?.forEach { add(strings.getString(Keys.CUSTOM_LAYOUTS_USED_LANDSCAPE, it)) }
                }
                LayoutListRow(
                    title = custom?.name ?: id,
                    detail = when {
                        custom == null -> null
                        parsed != null -> strings.getString(
                            Keys.CUSTOM_LAYOUTS_SIZE,
                            strings.counted(Keys.CUSTOM_LAYOUTS_ROWS, parsed.rows.size),
                            strings.counted(Keys.CUSTOM_LAYOUTS_KEYS, parsed.keyCount),
                        )
                        else -> strings[Keys.LAYOUT_ERROR_JSON]
                    },
                    badges = badges,
                    index = index,
                    onMoveTop = { update { it.copy(layoutOrder = move(order, index, 0)) } },
                    onMoveUp = { update { it.copy(layoutOrder = move(order, index, index - 1)) } },
                    onEdit = {
                        editing = custom ?: copyOf(context, id, preferences.customLayouts)
                    },
                    onDelete = if (custom != null) ({ deleting = custom }) else null,
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
                                    editing = copyOf(context, id, preferences.customLayouts)
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
                                subtypeLayoutsLandscape = current.subtypeLayoutsLandscape.filterValues { it != doomed.id },
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

/** A new layout of the user's own, started from the built-in [id]. */
private fun copyOf(context: Context, id: String, existing: List<CustomLayout>): CustomLayout {
    val json = context.assets.open("layouts/$id.json").use { it.readBytes().decodeToString() }
    return CustomLayout(
        id = CustomLayout.nextId(existing),
        name = id,
        languageTag = LayoutValidator.parse(json)?.languageTag ?: "und",
        json = json,
    )
}

/** Which enabled subtypes draw each layout id, by language tag, upright and, where it differs, in landscape. */
private class LayoutUsage(val portrait: Map<String, List<String>>, val landscape: Map<String, List<String>>)

private fun layoutUsage(context: Context, preferences: KeyboardPreferences): LayoutUsage {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    val ours = manager?.enabledInputMethodList?.firstOrNull { it.packageName == context.packageName }
    val subtypes = ours?.let { manager.getEnabledInputMethodSubtypeList(it, true) }.orEmpty()
    val portrait = HashMap<String, MutableList<String>>()
    val landscape = HashMap<String, MutableList<String>>()
    val sideways = LayoutChoice.forOrientation(preferences.subtypeLayouts, preferences.subtypeLayoutsLandscape, landscape = true)
    for (subtype in subtypes) {
        val own = LayoutChoice.layoutIdOf(subtype.extraValue)
        val tag = subtype.languageTag.ifEmpty { own }
        val upright = preferences.subtypeLayouts[own] ?: own
        portrait.getOrPut(upright) { ArrayList() } += tag
        val turned = sideways[own] ?: own
        if (turned != upright) {
            landscape.getOrPut(turned) { ArrayList() } += tag
        }
    }
    return LayoutUsage(portrait, landscape)
}

/** One row of the Your layouts list: its name, a detail line, its badges, and its controls. */
@Composable
private fun LayoutListRow(
    title: String,
    detail: String?,
    badges: List<String>,
    index: Int,
    onMoveTop: () -> Unit,
    onMoveUp: () -> Unit,
    onEdit: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onMoveTop, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_top),
                    contentDescription = strings[Keys.QUICK_MOVE_TOP],
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(R.drawable.bk_reorder_up),
                    contentDescription = strings[Keys.QUICK_MOVE_UP],
                    modifier = Modifier.size(18.dp),
                )
            }
            TextButton(onClick = onEdit) { Text(strings[Keys.CUSTOM_LAYOUTS_EDIT]) }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(strings[Keys.CUSTOM_LAYOUTS_DELETE]) }
            }
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
            for (badge in badges) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}
