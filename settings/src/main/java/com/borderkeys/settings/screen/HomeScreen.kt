// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

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
    val strings = LocalStrings.current
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
            SectionHeader(strings[Keys.HOME_NOT_FINISHED_YET])
            SettingRow(
                title = strings[Keys.HOME_SET_UP_BORDERKEYS],
                subtitle = when {
                    !enabled -> strings[Keys.HOME_THE_KEYBOARD_IS_NOT_ENABLED_IN]
                    else -> strings[Keys.HOME_ENABLED_BUT_ANOTHER_KEYBOARD_IS_STILL]
                },
                onClick = { open(Screen.Setup) },
            )
            Divider()
        }

        SectionHeader(strings[Keys.HOME_TYPING])
        SettingRow(strings[Keys.HOME_LANGUAGES], strings[Keys.HOME_WHICH_DICTIONARIES_ARE_ACTIVE_AND_THEIR]) {
            open(Screen.Languages)
        }
        SettingRow(strings[Keys.HOME_LAYOUT], strings[Keys.HOME_WHICH_KEYS_AND_WHERE_SEPARATE_FROM]) {
            open(Screen.Layout)
        }
        SettingRow(strings[Keys.HOME_SWIPE_TYPING], strings[Keys.HOME_GESTURE_INPUT_AND_THE_TRAIL_IT]) { open(Screen.Swipe) }
        SettingRow(
            strings[Keys.HOME_SUGGESTIONS_AND_CORRECTIONS],
            strings[Keys.HOME_THE_STRIP_ABOVE_THE_KEYS_AND],
        ) { open(Screen.Corrections) }
        SettingRow(strings[Keys.HOME_PERSONAL_DICTIONARY], strings[Keys.HOME_WHAT_THIS_DEVICE_HAS_LEARNED]) {
            open(Screen.Dictionary)
        }
        SettingRow(strings[Keys.HOME_CLIPBOARD], strings[Keys.HOME_HISTORY_PINNING_AND_HOW_LONG_IT]) {
            open(Screen.Clipboard)
        }
        if (hasAssistant) {
            SettingRow(strings[Keys.HOME_TEXT_ASSISTANT], strings[Keys.HOME_SUMMARISE_CORRECT_AND_TRANSLATE_ON_THIS]) {
                open(Screen.Assistant)
            }
        }

        SectionHeader(strings[Keys.HOME_APPEARANCE])
        SettingRow(strings[Keys.HOME_THEME], strings[Keys.HOME_COLOURS_CORNERS_AND_SPACING_WITH_A]) {
            open(Screen.Theme)
        }
        SettingRow(
            strings[Keys.HOME_SIZE_AND_POSITION],
            strings[Keys.HOME_HEIGHT_ONE_HANDED_MODE_FLOATING_AND],
        ) { open(Screen.Size) }

        SectionHeader(strings[Keys.HOME_ABOUT])
        SettingRow(strings[Keys.HOME_PRIVACY], strings[Keys.HOME_WHAT_IS_STORED_WHERE_AND_WHAT]) { open(Screen.Privacy) }
        SettingRow(strings[Keys.HOME_ABOUT_BORDERKEYS], strings[Keys.HOME_VERSION_SOURCE_CODE_AND_LICENCE]) { open(Screen.About) }

        Explanation(
            strings[Keys.HOME_BORDERKEYS_REQUESTS_NO_PERMISSIONS_AND_CONTA],
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
