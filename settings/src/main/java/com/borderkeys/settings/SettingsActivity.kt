// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.borderkeys.data.DataGraph
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.settings.screen.AboutScreen
import com.borderkeys.settings.screen.AssistantScreen
import com.borderkeys.data.backup.TransferProtocol
import com.borderkeys.settings.screen.BackupScreen
import com.borderkeys.settings.screen.ClipboardScreen
import com.borderkeys.settings.screen.ComposerScreen
import com.borderkeys.settings.screen.DictionaryScreen
import com.borderkeys.settings.screen.HomeScreen
import com.borderkeys.settings.screen.LanguagesScreen
import com.borderkeys.settings.screen.LayoutScreen
import com.borderkeys.settings.screen.PrivacyScreen
import com.borderkeys.settings.screen.ProcessTextScreen
import com.borderkeys.settings.screen.QuickActionsScreen
import com.borderkeys.settings.screen.SetupScreen
import com.borderkeys.settings.screen.TransferScreen
import com.borderkeys.settings.screen.SizeScreen
import com.borderkeys.settings.screen.CorrectionsScreen
import com.borderkeys.settings.screen.SwipeScreen
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
                        )
                    } else if (asking != null) {
                        // Its own insets, because it is shown instead of the Scaffold that
                        // would otherwise be supplying them, and a request to hand over
                        // somebody's dictionary should not be half hidden behind the clock.
                        TransferScreen(asking, Modifier.safeDrawingPadding())
                    } else {
                        SettingsApp()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsApp() {
    val strings = LocalStrings.current
    // A list, used as a back stack. Ten screens with no arguments between them do not need a
    // navigation graph, a route parser or argument encoding.
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()

    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings[current.titleKey]) },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = { stack.removeAt(stack.size - 1) }) {
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
        when (current) {
            Screen.Home -> HomeScreen(modifier, open)
            Screen.Setup -> SetupScreen(modifier, open)
            Screen.Languages -> LanguagesScreen(modifier)
            Screen.Layout -> LayoutScreen(modifier)
            Screen.Theme -> ThemeScreen(modifier)
            Screen.Size -> SizeScreen(modifier)
            Screen.Swipe -> SwipeScreen(modifier)
            Screen.Corrections -> CorrectionsScreen(modifier)
            Screen.Dictionary -> DictionaryScreen(modifier)
            Screen.Clipboard -> ClipboardScreen(modifier)
            Screen.QuickActions -> QuickActionsScreen(modifier)
            Screen.Composer -> ComposerScreen(modifier)
            Screen.Backup -> BackupScreen(modifier)
            Screen.Assistant -> AssistantScreen(modifier)
            Screen.Privacy -> PrivacyScreen(modifier)
            Screen.About -> AboutScreen(modifier)
        }
    }
}
