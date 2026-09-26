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
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.borderkeys.settings.screen.PrivacyScreen
import com.borderkeys.settings.screen.ProcessTextScreen
import com.borderkeys.settings.screen.QuickActionsScreen
import com.borderkeys.settings.screen.SetupScreen
import com.borderkeys.settings.screen.TransferScreen
import com.borderkeys.settings.screen.SizeScreen
import com.borderkeys.settings.screen.TypingScreen
import com.borderkeys.settings.screen.ThemeScreen

/**
 * The only launchable component in the application.
 *
 * A [ComponentActivity], not an AppCompatActivity: everything on screen is drawn by Compose, so
 * there is nothing for AppCompat to back-port and no reason to carry it.
 *
 * The rule this whole package follows: **the UI reads from the Room and DataStore flows and
 * from nowhere else.** An action writes to the store and waits for the flow to re-emit. There is
 * no `mutableStateOf` holding a copy of the truth beside the database, so what is on screen is
 * always what was actually written -- and a write that failed shows as the switch not moving,
 * which is the correct thing for it to look like.
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
        // Loaded before the first composition rather than collected as a flow: a screen drawn
        // in English for one frame and then redrawn translated is a flash the user would see on
        // every launch. The language changes only from the picker, which recreates the activity.
        val strings = LanguageManager(this).apply {
            loadResolved(DataGraph.themes.currentPreferences().uiLanguage)
        }
        // Started by the other build to ask for this one's settings, rather than by a person
        // opening the application. Null when it is an ordinary launch, or when whoever asked
        // is not who they would have to be.
        val asking = transferRequester()
        // A selection from another application's text-selection menu, reached through the
        // PROCESS_TEXT alias in the manifest rather than the launcher. `getCharSequenceExtra`
        // because that is the type the platform contract specifies; converted once here so the
        // screen itself deals in a plain String like everything else in this application does.
        val selection = intent.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        // Which of the five PROCESS_TEXT aliases actually launched this, if any -- see the
        // manifest's own comment on why `intent.component`, not the target activity name,
        // carries this: the platform preserves the ALIAS's component identity on the intent it
        // hands to the code behind it. `null` for a plain launch, for the un-suffixed
        // `.ProcessTextAlias`, and for anything not reached through PROCESS_TEXT at all.
        //
        // Hardcoded as this module's own namespace ("com.borderkeys.settings", from
        // settings/build.gradle.kts -- the same fixed string android:targetActivity above
        // already uses) rather than built from `packageName`: `packageName` is the RUNTIME
        // application id (com.borderkeys, or com.borderkeys.plus with the plus flavor's
        // applicationIdSuffix), a different string from the namespace an alias's relative
        // android:name resolves against, which does not vary by flavor at all.
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
        // The keyboard's own "Compose" quick action, tapped mid-typing rather than reached
        // through a selection menu. There is no calling activity on this path, so the draft box
        // opens exactly as it would for a selection that was never editable -- the same
        // Insert-becomes-Copy switch, for the same reason: nothing to write back into
        // automatically. Empty is a real value here (nothing was selected when Compose was
        // tapped, so the box opens blank), which is why this checks the intent's action rather
        // than the extra's presence.
        val quickDraft = if (intent.action == DraftProtocol.ACTION_QUICK_DRAFT) {
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        } else {
            null
        }
        setContent {
            CompositionLocalProvider(LocalStrings provides strings) {
                BorderKeysSettingsTheme {
                    if (selection != null) {
                        // Its own insets, for the same reason as the transfer screen below: it
                        // stands in for the whole Scaffold rather than living inside one.
                        ProcessTextScreen(
                            text = selection,
                            readOnly = intent.getBooleanExtra(
                                Intent.EXTRA_PROCESS_TEXT_READONLY, false,
                            ),
                            modifier = Modifier.safeDrawingPadding(),
                            // Closed by default here -- see the parameter's own doc. A
                            // selection was just made to read or act on, not necessarily to
                            // type into, and the swipe hint teaches the way in for when it is.
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
                        // Its own insets, because it is shown instead of the Scaffold that
                        // would otherwise be supplying them, and a request to hand over
                        // somebody's dictionary should not be half hidden behind the clock.
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
     * Who is asking for this build's settings, if anybody, and if they may.
     *
     * Two conditions, and neither is enough alone.
     *
     * The caller must be signed with the certificate this build was signed with. That is what
     * makes it the other BorderKeys and not an application that has merely learned the name of
     * an extra -- a name is public, a signature is not forgeable. `checkSignatures` compares
     * the certificates the platform recorded at install time.
     *
     * And `callingPackage` must exist at all, which it only does for an activity started for a
     * result. An application that fired this off and walked away could not receive the answer
     * anyway, and would not be identifiable while asking.
     *
     * What neither condition can establish is that the person holding the phone meant any of
     * this to happen, which is why what this returns is a screen with a button on it rather
     * than a file.
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

/** Screens whose own content draws the keyboard live (a `PlacementPreview` at the top), so the
 *  "Try it here" probe below them is left out -- see the bottom bar in [SettingsApp]. */
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
    // Read once, at launch, purely to pick the first screen below -- unlike isDefault further
    // down (from rememberBorderKeysDefaultState), this one is deliberately not kept live: which
    // screen the app opens *to* should not jump under the person's feet just because they
    // finished Setup while looking at some other screen already.
    val isDefaultAtLaunch = remember { isBorderKeysDefault(context) }

    // Whether the assistant service can be resolved at all is what tells the two flavours
    // apart at runtime, the same check AboutScreen and HomeScreen already make -- neither this
    // module nor :data can name a build flavour directly, since the flavour is a Gradle-time
    // concept and this is asking about the actual installed package.
    val hasAssistant = remember(context) {
        val intent = Intent().setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        context.packageManager.resolveService(intent, 0) != null
    }

    // A list, used as a back stack. Sixteen screens with no arguments between them do not need a
    // navigation graph, a route parser or argument encoding.
    //
    // Opens straight to Setup, not Home, when BorderKeys is not yet the selected keyboard --
    // the same condition Home's own banner already reacts to, just met on the first frame
    // instead of after noticing a card and tapping it.
    val stack = remember {
        val first = if (isDefaultAtLaunch) Screen.Home else Screen.Setup
        mutableStateListOf<Screen>(first).apply {
            if (openTo != null && openTo != first) {
                add(openTo)
            }
        }
    }
    val current = stack.last()

    // What each screen remembers about itself -- above all where it was scrolled to -- kept
    // while the screen is on the stack and dropped when it is popped. The switch at the bottom
    // takes a screen out of the composition the moment another is opened over it, and a screen
    // composed again from nothing starts at the top: Home came back scrolled to its first card
    // after every trip into a setting, however far down the card that led there was. A screen
    // that has been popped is forgotten on purpose, so opening it again starts at the top, the
    // way a screen entered anew is expected to.
    val screenStates = rememberSaveableStateHolder()
    val pop = {
        val popped = stack.removeAt(stack.size - 1)
        screenStates.removeState(popped.name)
    }

    // The one step Setup cannot do by itself -- enabling BorderKeys sends the user out to system
    // settings, which this application has no way to detect finishing, so that step stays a
    // button the person presses on purpose -- and the one it can offer unasked, the same way a
    // permission prompt is safe to bring up unasked: the picker, kept live across both that trip
    // and picking an entry from the picker's own dialog. See its own doc for why either of those
    // needs more than a plain LaunchedEffect(Unit).
    val isDefault by rememberBorderKeysDefaultState()

    // Leaves Setup for Home the moment isDefault above actually goes true. Scoped to "the
    // keyboard switch happened," not to the whole of Setup -- a pending import from a sibling
    // build (see canTransfer in SetupScreen/HomeScreen) is a second, separate task the person
    // may still want to do, and this does not walk them away from it just because step 2 finished.
    LaunchedEffect(current, isDefault) {
        if (current == Screen.Setup && isDefault) {
            stack.forEach { screenStates.removeState(it.name) }
            stack.clear()
            stack.add(Screen.Home)
            // Setup just finished: the tour of what the keyboard can do, until it is dismissed
            // for good.
            if (!DataGraph.themes.currentPreferences().featuresTourSeen) {
                stack.add(Screen.Features)
            }
        }
    }

    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }
    var statsExpanded by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = statsExpanded) { statsExpanded = false }

    // Global rather than Typing's own: whatever screen a setting was just changed on -- a theme
    // colour, a key size, the swipe trail width -- this is the one place to feel the result
    // immediately, without navigating back to Typing first. One field, shared across the whole
    // stack, so switching screens does not lose whatever was mid-swipe in it either.
    var probe by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            // Not under a screen that already shows the keyboard drawn live at the top -- Theme,
            // Size and position, Quick actions: there the result is already on screen, and a
            // second keyboard popping up over the preview only hides half of it.
            if (current in SCREENS_WITH_KEYBOARD_PREVIEW || current == Screen.Features) {
                return@Scaffold
            }
            // Deliberately not a full SettingsSectionCard: this rides along on every screen, so
            // it needs to cost little enough height to be worth always having on screen. One
            // line, the card's own label doubling as its explanation, same border/shape language
            // as every other card so it still reads as one rather than a stray text field.
            //
            // navigationBarsPadding(), not safeDrawingPadding(): Scaffold hands bottomBar the
            // full window bounds and expects the slot to clear whatever system bars it overlaps
            // itself -- confirmed the hard way, this card sat half behind a three-button nav bar
            // with no bottom padding of its own at all. safeDrawingPadding() over-corrected the
            // other way, adding the status bar and display-cutout insets on top of a bottom-
            // docked card that overlaps neither. The keyboard's own KeyboardHostView solves the
            // equivalent problem by hand, reading WindowInsets.Type.navigationBars() itself,
            // because an IME window's insets have their own timing quirks on first attach that a
            // normal Activity window like this one does not -- navigationBarsPadding() is the
            // same read (live nav-bar inset, applied as padding), through Compose's own insets
            // system, without porting a workaround for a race this window never has.
            //
            // imePadding() on top of that: without it this card stayed pinned to the physical
            // bottom of the window, which is exactly where BorderKeys itself draws once focusing
            // this field opens it -- confirmed the same way, the probe sat hidden behind the
            // keyboard it exists to test. imePadding() tracks the keyboard's own live height, so
            // the card rides up to sit on its top edge the moment it opens and settles back down
            // when it closes, the same as any chat app's input bar does.
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
                // One field, three modes: its label names the mode after a coloured bullet,
                // and a tap on the label moves to the next. The field stays one line tall;
                // in the several-lines mode it takes line breaks and scrolls.
                var probeMode by rememberSaveable { mutableStateOf(ProbeMode.PLAIN) }
                OutlinedTextField(
                    value = probe,
                    onValueChange = { probe = it },
                    label = {
                        Text(
                            ProbeMode.BULLET + strings[probeMode.labelKey],
                            color = probeMode.colour,
                            modifier = Modifier.clickable { probeMode = probeMode.next() },
                        )
                    },
                    singleLine = probeMode != ProbeMode.LINES,
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
                        .height(PROBE_FIELD_HEIGHT)
                        .semantics { contentDescription = probeMode.description + probe },
                )
            }
        },
        topBar = {
            TopAppBar(
                // Home's title is the app's own name, and the plus flavor says so the same way
                // its launcher icon, its keyboard-picker entry and its About screen already do --
                // see app/src/plus/res/values/strings.xml's own comment for why every one of
                // those is spelled out rather than left to whichever build happens to be
                // installed. Every other screen's title is its own, not the app's, so this is
                // the one place that distinction applies.
                title = {
                    val title = strings[current.titleKey]
                    Text(
                        if (current == Screen.Home && hasAssistant) {
                            strings.getString(Keys.HOME_TITLE_PLUS, title)
                        } else {
                            title
                        },
                    )
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
        // Opened, the panel stands in for the screen: an opaque surface the size of the
        // content, so the figures are read against nothing else.
        if (statsExpanded) {
            DebugStatsPanel(modifier)
            return@Scaffold
        }
        screenStates.SaveableStateProvider(current.name) {
            when (current) {
                Screen.Home -> HomeScreen(modifier, open)
                Screen.Setup -> SetupScreen(modifier, open)
                Screen.Features -> FeaturesScreen(modifier, hasAssistant, open, onDone = pop)
                Screen.Languages -> LanguagesScreen(modifier)
                Screen.Layout -> LayoutScreen(modifier)
                Screen.Theme -> ThemeScreen(modifier)
                Screen.Size -> SizeScreen(modifier)
                Screen.Effects -> EffectsScreen(modifier)
                Screen.Typing -> TypingScreen(modifier)
                Screen.Dictionary -> DictionaryScreen(modifier)
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

/** One line of text with its label, whatever mode the probe field is in; more lines scroll inside. */
private val PROBE_FIELD_HEIGHT = 68.dp
