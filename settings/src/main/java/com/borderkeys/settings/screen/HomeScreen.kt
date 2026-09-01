// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.Screen
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow

@Composable
fun HomeScreen(modifier: Modifier = Modifier, open: (Screen) -> Unit) {
    val context = LocalContext.current
    val enabled = isKeyboardEnabled(context)
    val isDefault = isKeyboardDefault(context)
    // Resolving the service is the only honest way to ask "is this the plus build": the class is
    // simply absent otherwise, and a BuildConfig flag would be a claim rather than a fact.
    val hasAssistant = remember(context) {
        val intent = android.content.Intent()
            .setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        context.packageManager.resolveService(intent, 0) != null
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!enabled || !isDefault) {
            SectionHeader("Not finished yet")
            SettingRow(
                title = "Set up BorderKeys",
                subtitle = when {
                    !enabled -> "The keyboard is not enabled in system settings yet."
                    else -> "Enabled, but another keyboard is still the default."
                },
                onClick = { open(Screen.Setup) },
            )
            Divider()
        }

        SectionHeader("Typing")
        SettingRow("Languages", "Which dictionaries are active, and their weights") {
            open(Screen.Languages)
        }
        SettingRow("Layout", "Which keys, and where. Separate from the languages.") {
            open(Screen.Layout)
        }
        SettingRow("Swipe typing", "Gesture input and the trail it draws") { open(Screen.Swipe) }
        SettingRow(
            "Suggestions and corrections",
            "The strip above the keys, and whether space applies what it offers",
        ) { open(Screen.Corrections) }
        SettingRow("Personal dictionary", "What this device has learned") {
            open(Screen.Dictionary)
        }
        SettingRow("Clipboard", "History, pinning and how long it is kept") {
            open(Screen.Clipboard)
        }
        if (hasAssistant) {
            SettingRow("Text assistant", "Summarise, correct and translate, on this device") {
                open(Screen.Assistant)
            }
        }

        SectionHeader("Appearance")
        SettingRow("Theme", "Colours, corners and spacing, with a live preview") {
            open(Screen.Theme)
        }
        SettingRow(
            "Size and position",
            "Height, one-handed mode, floating, and how far off the bottom edge",
        ) { open(Screen.Size) }

        SectionHeader("About")
        SettingRow("Privacy", "What is stored, where, and what is not") { open(Screen.Privacy) }
        SettingRow("About BorderKeys", "Version, source code and licence") { open(Screen.About) }

        Explanation(
            "BorderKeys requests no permissions and contains no code that can open a network " +
                "connection. Nothing it learns leaves this device.",
        )
    }
}

@Composable
private fun isKeyboardEnabled(context: Context): Boolean {
    val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ?: return false
    return manager.enabledInputMethodList.any { it.packageName == context.packageName }
}

@Composable
private fun isKeyboardDefault(context: Context): Boolean {
    val current = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
    )
    return current != null && current.startsWith(context.packageName)
}
