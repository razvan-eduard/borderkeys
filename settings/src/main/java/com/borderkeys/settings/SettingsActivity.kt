// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.borderkeys.data.DataGraph
import com.borderkeys.settings.screen.AboutScreen
import com.borderkeys.settings.screen.AssistantScreen
import com.borderkeys.settings.screen.ClipboardScreen
import com.borderkeys.settings.screen.DictionaryScreen
import com.borderkeys.settings.screen.HomeScreen
import com.borderkeys.settings.screen.LanguagesScreen
import com.borderkeys.settings.screen.LayoutScreen
import com.borderkeys.settings.screen.PrivacyScreen
import com.borderkeys.settings.screen.SetupScreen
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
        setContent { BorderKeysSettingsTheme { SettingsApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsApp() {
    // A list, used as a back stack. Ten screens with no arguments between them do not need a
    // navigation graph, a route parser or argument encoding.
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current = stack.last()

    androidx.activity.compose.BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.title) },
                navigationIcon = {
                    if (stack.size > 1) {
                        IconButton(onClick = { stack.removeAt(stack.size - 1) }) {
                            Icon(
                                painter = painterResource(
                                    android.R.drawable.ic_menu_close_clear_cancel,
                                ),
                                contentDescription = "Back",
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
            Screen.Setup -> SetupScreen(modifier)
            Screen.Languages -> LanguagesScreen(modifier)
            Screen.Layout -> LayoutScreen(modifier)
            Screen.Theme -> ThemeScreen(modifier)
            Screen.Size -> SizeScreen(modifier)
            Screen.Swipe -> SwipeScreen(modifier)
            Screen.Corrections -> CorrectionsScreen(modifier)
            Screen.Dictionary -> DictionaryScreen(modifier)
            Screen.Clipboard -> ClipboardScreen(modifier)
            Screen.Assistant -> AssistantScreen(modifier)
            Screen.Privacy -> PrivacyScreen(modifier)
            Screen.About -> AboutScreen(modifier)
        }
    }
}
