// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow

/**
 * What is stored, where, and what is not.
 *
 * The permission list is read from the package manager at runtime rather than written here. A
 * screen that claims "no permissions" from a hardcoded string is a screen that will still claim
 * it after somebody adds one.
 */
@Composable
fun PrivacyScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissions = remember {
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader("Permissions")
        if (permissions.isEmpty()) {
            SettingRow(
                title = "None",
                subtitle = "Read from the installed package just now, not from a string in this " +
                    "screen. If a permission were ever added, this list would say so.",
            )
        } else {
            for (permission in permissions) {
                SettingRow(title = permission)
            }
        }
        Explanation(
            "Haptic feedback uses the API that needs no VIBRATE permission. Clipboard history " +
                "works because the platform delivers clipboard changes to the input method that " +
                "currently has focus, which needs no permission either.",
        )

        Divider()
        SectionHeader("Network")
        SettingRow(
            title = "There is none",
            subtitle = "No INTERNET permission, and no HTTP client anywhere in the dependency " +
                "graph. The build fails if either appears — including through a library that " +
                "brings one in without asking.",
        )
        Explanation(
            "That is why nothing downloads. Dictionaries and models are either inside the app or " +
                "chosen by you from a file on this device.",
        )

        Divider()
        SectionHeader("What is stored")
        SettingRow(
            title = "Encrypted database",
            subtitle = "Clipboard history, the personal dictionary and the list of imported " +
                "packs live in a SQLCipher database. Its key is generated on this device and " +
                "held by the Android Keystore, so the file is useless if it is copied elsewhere.",
        )
        SettingRow(
            title = "Pairs of words you write together",
            subtitle = "So that after \"vreau\" it can offer \"să\". This is the most revealing " +
                "thing here: a list of words says which words you know, a list of pairs says " +
                "how you put them together. It is in the same encrypted database, it is never " +
                "recorded in a password field, and \"forget everything\" in the personal " +
                "dictionary deletes it along with the words.",
        )
        SettingRow(
            title = "Excluded from backup",
            subtitle = "Cloud backup and device-to-device transfer are both switched off for " +
                "this app. Moving your dictionary to a new phone is an explicit CSV export.",
        )
        SettingRow(
            title = "Nothing is learned in a password field",
            subtitle = "In a password field, or when an app asks for no personalised learning, " +
                "there is no learning, no clipboard history and no assistant. The suggestion " +
                "strip says so while it is happening.",
        )

        Divider()
        SectionHeader("What is not stored")
        SettingRow(
            title = "No telemetry, no crash reporting, no identifiers",
            subtitle = "None of it exists to be switched off.",
        )
        SettingRow(
            title = "Per-app language memory is off unless you turn it on",
            subtitle = "It would store a hash of the app's name against learned language " +
                "weights. That is a behavioural profile, however small and however local, so it " +
                "is opt-in and can be deleted.",
        )
    }
}
