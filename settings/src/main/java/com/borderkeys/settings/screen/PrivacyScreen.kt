// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

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
    val strings = LocalStrings.current
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
        SectionHeader(strings[Keys.PRIVACY_PERMISSIONS])
        if (permissions.isEmpty()) {
            SettingRow(
                title = strings[Keys.PRIVACY_NONE],
                subtitle = strings[Keys.PRIVACY_READ_FROM_THE_INSTALLED_PACKAGE_JUST],
            )
        } else {
            for (permission in permissions) {
                SettingRow(title = permission)
            }
        }
        Explanation(
            strings[Keys.PRIVACY_HAPTIC_FEEDBACK_USES_THE_API_THAT],
        )

        Divider()
        SectionHeader(strings[Keys.PRIVACY_NETWORK])
        SettingRow(
            title = strings[Keys.PRIVACY_THERE_IS_NONE],
            subtitle = strings[Keys.PRIVACY_NO_INTERNET_PERMISSION_AND_NO_HTTP],
        )
        Explanation(
            strings[Keys.PRIVACY_THAT_IS_WHY_NOTHING_DOWNLOADS_DICTIONARIES],
        )

        Divider()
        SectionHeader(strings[Keys.PRIVACY_WHAT_IS_STORED])
        SettingRow(
            title = strings[Keys.PRIVACY_ENCRYPTED_DATABASE],
            subtitle = strings[Keys.PRIVACY_CLIPBOARD_HISTORY_THE_PERSONAL_DICTIONARY_AN],
        )
        SettingRow(
            title = strings[Keys.PRIVACY_PAIRS_OF_WORDS_YOU_WRITE_TOGETHER],
            subtitle = strings[Keys.PRIVACY_SO_THAT_AFTER_VREAU_IT_CAN],
        )
        SettingRow(
            title = strings[Keys.PRIVACY_EXCLUDED_FROM_BACKUP],
            subtitle = strings[Keys.PRIVACY_CLOUD_BACKUP_AND_DEVICE_TO_DEVICE],
        )
        SettingRow(
            title = strings[Keys.PRIVACY_NOTHING_IS_LEARNED_IN_A_PASSWORD],
            subtitle = strings[Keys.PRIVACY_IN_A_PASSWORD_FIELD_OR_WHEN],
        )

        Divider()
        SectionHeader(strings[Keys.PRIVACY_WHAT_IS_NOT_STORED])
        SettingRow(
            title = strings[Keys.PRIVACY_NO_TELEMETRY_NO_CRASH_REPORTING_NO],
            subtitle = strings[Keys.PRIVACY_NONE_OF_IT_EXISTS_TO_BE],
        )
        SettingRow(
            title = strings[Keys.PRIVACY_PER_APP_LANGUAGE_MEMORY_IS_OFF],
            subtitle = strings[Keys.PRIVACY_IT_WOULD_STORE_A_HASH_OF],
        )
    }
}
