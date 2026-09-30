// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import com.borderkeys.data.draft.DraftProtocol
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.screen.AboutScreen
import com.borderkeys.settings.screen.AssistantScreen
import com.borderkeys.data.backup.TransferProtocol
import com.borderkeys.settings.screen.BackupScreen
import com.borderkeys.settings.screen.ClipboardScreen
import com.borderkeys.settings.screen.ComposerScreen
import com.borderkeys.settings.screen.DictionaryScreen
import com.borderkeys.settings.screen.EffectsScreen
import com.borderkeys.settings.screen.FeaturesScreen
import com.borderkeys.settings.screen.HomeScreen
import com.borderkeys.settings.screen.LanguagesScreen
import com.borderkeys.settings.screen.LayoutScreen
import com.borderkeys.settings.screen.LearnedPhrasesScreen
import com.borderkeys.settings.screen.LearnedWordsScreen
import com.borderkeys.settings.screen.PrivacyScreen
import com.borderkeys.settings.screen.ProcessTextScreen
import com.borderkeys.settings.screen.QuickActionsScreen
import com.borderkeys.settings.screen.SetupScreen
import com.borderkeys.settings.screen.TransferScreen
import com.borderkeys.settings.screen.SizeScreen
import com.borderkeys.settings.screen.TypingScreen
import com.borderkeys.settings.screen.ThemeScreen

/**
 * The only launchable component in the application, drawn entirely in Compose. The UI reads from
 * the Room and DataStore flows only: an action writes to the store and waits for the flow.
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        /** A [Screen] name to open on, above Home. The keyboard sends it by string. */
        const val EXTRA_SCREEN = "com.borderkeys.settings.SCREEN"

        /** The clipboard entry the Clipboard screen opens for editing. */
        const val EXTRA_CLIP_ID = "com.borderkeys.settings.CLIP_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DataGraph.install(applicationContext)
        // Loaded before the first composition; the language picker recreates the activity.
        val strings = LanguageManager(this).apply {
            loadResolved(DataGraph.themes.currentPreferences().uiLanguage)
        }
        // The other build asking for this one's settings, or null.
        val asking = transferRequester()
        // A selection from another application's text-selection menu, through the PROCESS_TEXT
        // alias.
        val selection = intent.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        // Which of the five PROCESS_TEXT aliases launched this, from `intent.component`; null for
        // the plain `.ProcessTextAlias` and anything else. Matched against this module's
        // namespace, "com.borderkeys.settings", which unlike `packageName` is the same in both
        // flavors.
        val processTextAlias = intent.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.component?.className
        val autoRunTask = when (processTextAlias) {
            "com.borderkeys.settings.ProcessTextCorrectAlias" -> AssistTask.CORRECT
            "com.borderkeys.settings.ProcessTextShortenAlias" -> AssistTask.SHORTEN
            "com.borderkeys.settings.ProcessTextSummariseAlias" -> AssistTask.SUMMARISE
            else -> null
        }
        val offerCustomActionPicker =
            processTextAlias == "com.borderkeys.settings.ProcessTextCustomAlias"
        // The keyboard's Compose quick action: the box opens as for a read-only selection, empty
        // when nothing was selected.
        val quickDraft = if (intent.action == DraftProtocol.ACTION_QUICK_DRAFT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        } else {
            null
        }
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                BorderKeysSettingsTheme {
                    if (selection != null) {
                        // Its own insets: it stands in for the Scaffold.
                        ProcessTextScreen(
                            text = selection,
                            readOnly = intent.getBooleanExtra(
                                Intent.EXTRA_PROCESS_TEXT_READONLY, false,
                            ),
                            modifier = Modifier.safeDrawingPadding(),
                            autoFocus = false,
                            autoRunTask = autoRunTask,
                            offerCustomActionPicker = offerCustomActionPicker,
                        )
                    } else if (quickDraft != null) {
                        ProcessTextScreen(
                            text = quickDraft,
                            readOnly = true,
                            modifier = Modifier.safeDrawingPadding(),
                        )
                    } else if (asking != null) {
                        // Its own insets: it stands in for the Scaffold.
                        TransferScreen(asking, Modifier.safeDrawingPadding())
                    } else {
                        SettingsApp(
                            openTo = intent.getStringExtra(EXTRA_SCREEN)
                                ?.let { name -> Screen.entries.firstOrNull { it.name == name } },
                            editClipId = intent.getLongExtra(EXTRA_CLIP_ID, -1L).takeIf { it >= 0L },
                        )
                    }
                }
            }
        }
    }

    /**
     * The package asking for this build's settings, or null: set only when the caller started
     * this activity for a result and is signed with this build's certificate.
     */
    private fun transferRequester(): String? {
        if (!intent.getBooleanExtra(TransferProtocol.EXTRA_REQUEST, false)) {
            return null
        }
        val caller = callingPackage ?: return null
        val matches = packageManager.checkSignatures(caller, packageName) ==
            android.content.pm.PackageManager.SIGNATURE_MATCH
        if (!matches) {
            return null
        }
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(caller, 0),
            ).toString()
        }.getOrDefault(caller)
    }
}

/** Screens that draw the keyboard live at the top; the "Try it here" probe is left out on them. */
private val SCREENS_WITH_KEYBOARD_PREVIEW = setOf(Screen.Theme, Screen.Size, Screen.QuickActions)

/**
 * The settings, opened on Home or Setup, or on [openTo] above Home when the intent named a
 * screen; [editClipId] is the clipboard entry that screen opens for editing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsApp(openTo: Screen? = null, editClipId: Long? = null) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    // Read once, at launch, to pick the first screen.
    val isDefaultAtLaunch = remember { isBorderKeysDefault(context) }

    // Whether the assistant service resolves, which is true in the plus flavor only.
    val hasAssistant = remember(context) {
        val intent = Intent().setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        context.packageManager.resolveService(intent, 0) != null
    }

    // The back stack, a list. Opens on Setup when BorderKeys is not the selected keyboard.
    val stack = remember {
        val first = if (isDefaultAtLaunch) Screen.Home else Screen.Setup
        mutableStateListOf<Screen>(first).apply {
            if (openTo != null && openTo != first) {
                add(openTo)
            }
        }
    }
    val current = stack.last()
    // The settings search, typed into the title row of Home and answered by Home's own list.
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Each screen's saved state, its scroll included, kept while it is on the stack and dropped
    // when it is popped.
    val screenStates = rememberSaveableStateHolder()
    val pop = {
        val popped = stack.removeAt(stack.size - 1)
        screenStates.removeState(popped.name)
    }

    // Whether BorderKeys is the selected keyboard, kept live across a trip to the system picker.
    val isDefault by rememberBorderKeysDefaultState()

    // Leaves Setup for Home once isDefault turns true.
    LaunchedEffect(current, isDefault) {
        if (current == Screen.Setup && isDefault) {
            stack.forEach { screenStates.removeState(it.name) }
            stack.clear()
            stack.add(Screen.Home)
            // Setup finished: the features tour, until it is dismissed for good.
            if (!DataGraph.themes.currentPreferences().featuresTourSeen) {
                stack.add(Screen.Features)
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = statsExpanded) { statsExpanded = false }

    // The "Try it here" probe's text, shared across every screen.
    var probe by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            // Not under a screen that draws the keyboard live, nor under Features.
            if (current in SCREENS_WITH_KEYBOARD_PREVIEW || current == Screen.Features) {
                return@Scaffold
            }
            // A one-line card, padded clear of the navigation bar (navigationBarsPadding) and
            // lifted above the keyboard (imePadding).
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .navigationBarsPadding()
                    .imePadding(),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                colors = CardDefaults.elevatedCardColors(),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                DebugStatsLine(statsExpanded) { statsExpanded = !statsExpanded }
                // One field, three modes. Resting inside the empty field the label names the
                // mode and takes no tap of its own, so a tap anywhere in the field types.
                // Once it has floated onto the outline it offers the mode switch instead, and
                // the typing hint inside the empty field names the mode. Both spellings of the
                // label lead with a bullet in the mode's colour. In the several-lines mode the
                // field shows three rows and scrolls past them; the other modes are one line.
                var probeMode by rememberSaveable { mutableStateOf(ProbeMode.PLAIN) }
                var probeFocused by remember { mutableStateOf(false) }
                val labelFloated = probeFocused || probe.isNotEmpty()
                OutlinedTextField(
                    value = probe,
                    onValueChange = { probe = it },
                    label = {
                        if (labelFloated) {
                            Text(
                                ProbeMode.BULLET + strings[Keys.SWIPE_TAP_TO_CHANGE_MODE],
                                color = probeMode.colour,
                                modifier = Modifier.clickable { probeMode = probeMode.next() },
                            )
                        } else {
                            Text(
                                ProbeMode.BULLET + strings[probeMode.labelKey],
                                color = probeMode.colour,
                            )
                        }
                    },
                    placeholder = { Text(strings[probeMode.labelKey]) },
                    singleLine = probeMode != ProbeMode.LINES,
                    minLines = if (probeMode == ProbeMode.LINES) PROBE_LINES_SHOWN else 1,
                    maxLines = if (probeMode == ProbeMode.LINES) PROBE_LINES_SHOWN else 1,
                    visualTransformation = if (probeMode == ProbeMode.PASSWORD) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (probeMode == ProbeMode.PASSWORD) KeyboardType.Password else KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .then(if (probeMode == ProbeMode.LINES) Modifier else Modifier.height(PROBE_FIELD_HEIGHT))
                        .onFocusChanged { probeFocused = it.isFocused }
                        .semantics { contentDescription = probeMode.description + probe },
                )
            }
        },
        topBar = {
            TopAppBar(
                // Home's title marks the plus flavor (HOME_TITLE_PLUS); every other screen has its
                // own title.
                title = {
                    val title = strings[current.titleKey]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (current == Screen.Home && hasAssistant) {
                                strings.getString(Keys.HOME_TITLE_PLUS, title)
                            } else {
                                title
                            },
                        )
                        if (current == Screen.Home) {
                            Spacer(Modifier.width(16.dp))
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = {
                                    Text(strings[Keys.HOME_SEARCH], style = MaterialTheme.typography.bodyMedium)
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = pop) {
                            Icon(
                                painter = painterResource(
                                    android.R.drawable.ic_menu_close_clear_cancel,
                                ),
                                contentDescription = strings[Keys.SETTINGS_ACTIVITY_BACK],
                            )
                        }
                    }
                },
            )
        },
    ) { insets ->
        val open: (Screen) -> Unit = { stack.add(it) }
        val modifier = Modifier.padding(insets)
        // Opened, the panel covers the content with an opaque surface.
        if (statsExpanded) {
            DebugStatsPanel(modifier)
            return@Scaffold
        }
        screenStates.SaveableStateProvider(current.name) {
            when (current) {
                Screen.Home -> HomeScreen(searchQuery, modifier, open)
                Screen.Setup -> SetupScreen(modifier, open)
                Screen.Features -> FeaturesScreen(modifier, hasAssistant, open, onDone = pop)
                Screen.Languages -> LanguagesScreen(modifier)
                Screen.Layout -> LayoutScreen(modifier)
                Screen.Theme -> ThemeScreen(modifier)
                Screen.Size -> SizeScreen(modifier)
                Screen.Effects -> EffectsScreen(modifier)
                Screen.Typing -> TypingScreen(modifier)
                Screen.Dictionary -> DictionaryScreen(modifier, open)
                Screen.LearnedWords -> LearnedWordsScreen(modifier)
                Screen.LearnedPhrases -> LearnedPhrasesScreen(modifier)
                Screen.Clipboard -> ClipboardScreen(modifier, editClipId)
                Screen.QuickActions -> QuickActionsScreen(modifier)
                Screen.Composer -> ComposerScreen(modifier)
                Screen.Backup -> BackupScreen(modifier)
                Screen.Assistant -> AssistantScreen(modifier)
                Screen.Privacy -> PrivacyScreen(modifier)
                Screen.About -> AboutScreen(modifier)
            }
        }
    }
}

/** One line of text with its label, the probe field's height in its one-line modes. */
private val PROBE_FIELD_HEIGHT = 68.dp

/** Rows the probe field shows in its several-lines mode; more lines scroll inside. */
private const val PROBE_LINES_SHOWN = 3
