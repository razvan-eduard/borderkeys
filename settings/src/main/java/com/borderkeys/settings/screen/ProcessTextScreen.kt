// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.assist.AssistClient
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.SavedPrompt
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.Composer
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import kotlinx.coroutines.launch

/**
 * The draft box, reached from a selection in *any* application rather than from inside the
 * keyboard.
 *
 * That distinction is the whole reason this screen exists. The keyboard's own composer
 * (`ComposerView`, in `:keyboard`) can only ever be opened while the software keyboard is on
 * screen, which Android only shows over a focused editable field -- so it can rewrite a message
 * you are drafting, but never a paragraph you are merely reading. `ACTION_PROCESS_TEXT` is
 * Android's own mechanism for the second case: an entry in every application's text-selection
 * menu, offered for *any* selection, editable or not, with no keyboard involved at all.
 *
 * A fresh screen rather than a reuse of `ComposerView`. That view exists to be typed into by the
 * keyboard's *own* key-press handling through a fake `InputConnection`
 * (`ComposerInputConnection`) -- it has no way to accept ordinary typing from whatever keyboard
 * happens to be active while a plain Activity is on screen, which is the normal case here. What
 * *is* reused is everything underneath the drawing: [Composer], the pure version graph, and
 * [AssistClient], which was already `Context`-generic and needed no change at all.
 */
@Composable
fun ProcessTextScreen(
    text: String,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val themes = remember { DataGraph.themes }
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = KeyboardPreferences())

    val composer = remember { Composer() }
    var current by remember { mutableStateOf(text) }
    var rail by remember { mutableStateOf(Rail()) }
    var requestId by remember { mutableIntStateOf(-1) }
    var notice by remember { mutableStateOf("") }
    var promptOpen by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var pendingInstruction by remember { mutableStateOf("") }
    var offeringSaveName by remember { mutableStateOf<String?>(null) }
    var translateMenuOpen by remember { mutableStateOf(false) }
    var toneMenuOpen by remember { mutableStateOf(false) }
    var savedMenuOpen by remember { mutableStateOf(false) }

    val busy = requestId >= 0

    fun syncFromComposer() {
        current = composer.current() ?: current
        rail = Rail(composer.size, composer.index, composer.canGoBack, composer.canGoForward, composer.atOriginal)
    }

    val assist = remember { AssistClient(context) }
    // Resolved once: whether the plus flavor's assistant is even present does not change while
    // this screen is open, and asking again on every recomposition would be a PackageManager
    // call for an answer that cannot have changed.
    val assistAvailable = remember { assist.isAvailable() }

    DisposableEffect(Unit) {
        assist.listener = object : AssistClient.Listener {
            override fun onAssistResult(id: Int, resultText: String, modelName: String?) {
                if (id != requestId) {
                    // An answer to a request this screen has already moved past -- a second
                    // action tapped before the first came back cancels it, and its answer
                    // arriving late must not overwrite what replaced it.
                    return
                }
                requestId = -1
                composer.addResult(resultText)
                syncFromComposer()
                notice = ""
                if (pendingInstruction.isNotEmpty() &&
                    preferences.savedPrompts.none { it.text == pendingInstruction } &&
                    preferences.savedPrompts.size < SavedPrompt.MAX_SAVED
                ) {
                    offeringSaveName = Composer.suggestedName(pendingInstruction)
                }
            }

            override fun onAssistError(id: Int, error: Int) {
                if (id != requestId) {
                    return
                }
                requestId = -1
                notice = assistErrorMessage(strings, error)
            }

            override fun onAssistAvailability(available: Boolean, modelName: String?) = Unit
        }
        onDispose {
            // Told to stop rather than merely let go of: a generation nobody will read is still
            // seconds of the phone's own CPU, the same reasoning as the keyboard's composer.
            assist.cancel()
            assist.disconnect()
        }
    }

    fun runTask(task: AssistTask, instruction: String = "") {
        if (busy || current.isEmpty()) {
            return
        }
        composer.captureBeforeRun(current)
        val id = assist.run(task, current, instruction)
        if (id < 0) {
            notice = strings[Keys.ASSISTANT_THE_ASSISTANT_IS_NOT_INSTALLED]
            return
        }
        requestId = id
        pendingInstruction = if (task == AssistTask.CUSTOM) instruction else ""
        notice = strings[Keys.COMPOSER_WORKING]
    }

    fun goTo(step: () -> String?) {
        if (!composer.isEmpty()) {
            composer.updateCurrent(current)
        }
        step() ?: return
        syncFromComposer()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                activity?.setResult(Activity.RESULT_CANCELED)
                activity?.finish()
            }) {
                Icon(
                    painter = painterResource(R.drawable.bk_composer_close),
                    contentDescription = strings[Keys.COMPOSER_CLOSE],
                )
            }
            Text(
                strings[Keys.COMPOSER_TITLE],
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = { goTo { composer.back() } },
                enabled = rail.canBack,
            ) {
                Icon(
                    painter = painterResource(R.drawable.bk_composer_back),
                    contentDescription = strings[Keys.COMPOSER_BACK],
                )
            }
            IconButton(
                onClick = { goTo { composer.forward() } },
                enabled = rail.canForward,
            ) {
                Icon(
                    painter = painterResource(R.drawable.bk_composer_forward),
                    contentDescription = strings[Keys.COMPOSER_FORWARD],
                )
            }
        }

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                value = current,
                onValueChange = { value ->
                    current = value
                    if (!composer.isEmpty()) {
                        composer.updateCurrent(value)
                    }
                },
                placeholder = { Text(strings[Keys.COMPOSER_EMPTY]) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            )

            if (rail.size > 1) {
                VersionRail(
                    rail = rail,
                    onSelect = { index -> goTo { composer.goTo(index) } },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            if (notice.isNotEmpty()) {
                Explanation(notice)
            }

            if (offeringSaveName != null) {
                SavePromptRow(
                    name = offeringSaveName.orEmpty(),
                    onNameChange = { offeringSaveName = it },
                    onSave = {
                        val name = offeringSaveName.orEmpty().trim()
                        val instruction = pendingInstruction
                        offeringSaveName = null
                        if (name.isEmpty() || instruction.isEmpty()) {
                            return@SavePromptRow
                        }
                        scope.launch {
                            themes.updatePreferences { prefs ->
                                prefs.copy(
                                    savedPrompts = prefs.savedPrompts +
                                        SavedPrompt(name = name, text = instruction),
                                )
                            }
                        }
                    },
                    onSkip = { offeringSaveName = null },
                )
            }

            if (assistAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ActionIcon(R.drawable.bk_composer_grammar, strings[Keys.COMPOSER_ACTION_GRAMMAR], busy) {
                        runTask(AssistTask.CORRECT)
                    }
                    Box {
                        ActionIcon(
                            R.drawable.bk_composer_translate, strings[Keys.COMPOSER_ACTION_TRANSLATE], busy,
                        ) { translateMenuOpen = true }
                        DropdownMenu(translateMenuOpen, onDismissRequest = { translateMenuOpen = false }) {
                            for (task in TRANSLATE_TASKS) {
                                DropdownMenuItem(
                                    text = { Text(translateLabel(strings, task)) },
                                    onClick = { translateMenuOpen = false; runTask(task) },
                                )
                            }
                        }
                    }
                    Box {
                        ActionIcon(
                            R.drawable.bk_composer_tone, strings[Keys.COMPOSER_ACTION_TONE], busy,
                        ) { toneMenuOpen = true }
                        DropdownMenu(toneMenuOpen, onDismissRequest = { toneMenuOpen = false }) {
                            for (task in TONE_TASKS) {
                                DropdownMenuItem(
                                    text = { Text(toneLabel(strings, task)) },
                                    onClick = { toneMenuOpen = false; runTask(task) },
                                )
                            }
                        }
                    }
                    ActionIcon(R.drawable.bk_composer_shorten, strings[Keys.COMPOSER_ACTION_SHORTEN], busy) {
                        runTask(AssistTask.SHORTEN)
                    }
                    ActionIcon(R.drawable.bk_composer_prompt, strings[Keys.COMPOSER_ACTION_PROMPT], busy) {
                        promptOpen = !promptOpen
                    }
                    if (preferences.savedPrompts.isNotEmpty()) {
                        Box {
                            ActionIcon(
                                R.drawable.bk_composer_saved, strings[Keys.COMPOSER_ACTION_SAVED], busy,
                            ) { savedMenuOpen = true }
                            DropdownMenu(savedMenuOpen, onDismissRequest = { savedMenuOpen = false }) {
                                for (prompt in preferences.savedPrompts) {
                                    DropdownMenuItem(
                                        text = { Text(prompt.name) },
                                        onClick = {
                                            savedMenuOpen = false
                                            runTask(AssistTask.CUSTOM, prompt.text)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (rail.hasHistory) {
                        ActionIcon(
                            R.drawable.bk_composer_original,
                            if (rail.atOriginal) {
                                strings[Keys.COMPOSER_ACTION_SHOW_CURRENT]
                            } else {
                                strings[Keys.COMPOSER_ACTION_SHOW_ORIGINAL]
                            },
                            busy,
                        ) { goTo { composer.flip() } }
                    }
                }

                if (promptOpen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = { Text(strings[Keys.COMPOSER_PROMPT_HINT]) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                val written = promptText.trim()
                                if (written.isNotEmpty()) {
                                    promptOpen = false
                                    val instruction = written.take(AssistTask.MAX_INSTRUCTION_CHARS)
                                    promptText = ""
                                    runTask(AssistTask.CUSTOM, instruction)
                                }
                            }),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { promptOpen = false; promptText = "" }) {
                            Icon(
                                painter = painterResource(R.drawable.bk_composer_close),
                                contentDescription = strings[Keys.COMPOSER_PROMPT_DISMISS],
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            if (readOnly) {
                // Nowhere to write back to -- the selection came from a view that never offered
                // to accept a replacement, which is what read-only means here. Copying is the
                // whole of what this screen can hand back.
                Button(
                    enabled = current.isNotEmpty() && !busy,
                    onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText(null, current))
                        notice = strings[Keys.ASSIST_COPY]
                    },
                ) { Text(strings[Keys.ASSIST_COPY]) }
            } else {
                Button(
                    enabled = current.isNotEmpty() && !busy,
                    onClick = {
                        activity?.setResult(
                            Activity.RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, current),
                        )
                        activity?.finish()
                    },
                ) { Text(strings[Keys.COMPOSER_INSERT]) }
            }
        }
    }
}

/** A snapshot of [Composer]'s state that Compose can observe, taken after every mutation. */
private data class Rail(
    val size: Int = 0,
    val index: Int = 0,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val atOriginal: Boolean = false,
) {
    val hasHistory: Boolean get() = size > 1
}

@Composable
private fun ActionIcon(icon: Int, label: String, busy: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !busy) {
        Icon(painter = painterResource(icon), contentDescription = label)
    }
}

/**
 * The dots between the two arrows: one per version, the current one filled, the ends coloured
 * apart from the rest -- the same reading as the keyboard's own timeline, at a fraction of its
 * drawing complexity, because a full-screen Compose layout has no cramped strip to fit it into
 * and no reason to reproduce a drag gesture a tap already covers.
 */
@Composable
private fun VersionRail(rail: Rail, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (index in 0 until rail.size) {
            val current = index == rail.index
            val edge = index == 0 || index == rail.size - 1
            val color = when {
                current -> MaterialTheme.colorScheme.primary
                edge -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .size(if (current) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SavePromptRow(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(strings[Keys.COMPOSER_SAVE_PROMPT_NAME]) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave) { Text(strings[Keys.COMPOSER_SAVE_PROMPT]) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onSkip) { Text(strings[Keys.COMPOSER_PROMPT_DISMISS]) }
    }
}

private fun translateLabel(strings: com.borderkeys.i18n.LanguageManager, task: AssistTask): String =
    when (task) {
        AssistTask.TRANSLATE_TO_ENGLISH -> strings[Keys.LANGUAGE_ENGLISH]
        AssistTask.TRANSLATE_TO_ROMANIAN -> strings[Keys.LANGUAGE_ROMANIAN]
        AssistTask.TRANSLATE_TO_GERMAN -> strings[Keys.LANGUAGE_GERMAN]
        AssistTask.TRANSLATE_TO_SPANISH -> strings[Keys.LANGUAGE_SPANISH]
        AssistTask.TRANSLATE_TO_FRENCH -> strings[Keys.LANGUAGE_FRENCH]
        AssistTask.TRANSLATE_TO_ITALIAN -> strings[Keys.LANGUAGE_ITALIAN]
        else -> ""
    }

private fun toneLabel(strings: com.borderkeys.i18n.LanguageManager, task: AssistTask): String =
    when (task) {
        AssistTask.REWRITE_FORMAL -> strings[Keys.TONE_FORMAL]
        AssistTask.REWRITE_CASUAL -> strings[Keys.TONE_CASUAL]
        AssistTask.REWRITE_DIRECT -> strings[Keys.TONE_DIRECT]
        else -> ""
    }

/** Every failure is a sentence somebody can act on. Mirrors BorderKeysService.assistErrorMessage. */
private fun assistErrorMessage(strings: com.borderkeys.i18n.LanguageManager, error: Int): String =
    when (error) {
        AssistProtocol.ERROR_NO_MODEL -> strings[Keys.ASSISTANT_NO_MODEL_IMPORTED_YET_SETTINGS_TEXT]
        AssistProtocol.ERROR_MODEL_CHANGED -> strings[Keys.ASSISTANT_THE_MODEL_FILE_CHANGED_SINCE_IT]
        AssistProtocol.ERROR_LOAD_FAILED -> strings[Keys.ASSISTANT_THE_MODEL_COULD_NOT_BE_LOADED]
        AssistProtocol.ERROR_TOO_LONG -> strings[Keys.ASSISTANT_THE_SELECTION_IS_LONGER_THAN_THIS]
        AssistProtocol.ERROR_BUSY -> strings[Keys.ASSISTANT_STILL_WORKING_ON_THE_PREVIOUS_REQUEST]
        AssistProtocol.ERROR_NO_INSTRUCTION -> strings[Keys.ASSISTANT_NO_INSTRUCTION_WAS_WRITTEN]
        else -> strings[Keys.ASSISTANT_THE_ASSISTANT_COULD_NOT_FINISH]
    }

/** The six languages the translate button offers, matching the in-keyboard composer's list. */
private val TRANSLATE_TASKS = listOf(
    AssistTask.TRANSLATE_TO_ENGLISH,
    AssistTask.TRANSLATE_TO_ROMANIAN,
    AssistTask.TRANSLATE_TO_GERMAN,
    AssistTask.TRANSLATE_TO_SPANISH,
    AssistTask.TRANSLATE_TO_FRENCH,
    AssistTask.TRANSLATE_TO_ITALIAN,
)

private val TONE_TASKS = listOf(
    AssistTask.REWRITE_FORMAL,
    AssistTask.REWRITE_CASUAL,
    AssistTask.REWRITE_DIRECT,
)
