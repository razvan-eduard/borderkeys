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
import com.borderkeys.settings.SettingsSectionCard
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

    // Shown while anything in the setup is outstanding -- including, in the assistant build,
    // the settings still sitting in the other one. Without that last condition the card would
    // vanish the moment the keyboard was switched on, taking the transfer step with it, and the
    // one thing somebody most wants right after installing would become unreachable.
    val sibling = remember(context) { siblingPackage(context) }
    val canTransfer = remember(context, sibling) {
        sibling != null && runCatching {
            context.packageManager.getPackageInfo(sibling, 0)
        }.isSuccess
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (!enabled || !isDefault || canTransfer) {
            SettingsSectionCard(strings[Keys.HOME_NOT_FINISHED_YET]) {
                SettingRow(
                    title = strings[Keys.HOME_SET_UP_BORDERKEYS],
                    subtitle = when {
                        !enabled -> strings[Keys.HOME_THE_KEYBOARD_IS_NOT_ENABLED_IN]
                        !isDefault -> strings[Keys.HOME_ENABLED_BUT_ANOTHER_KEYBOARD_IS_STILL]
                        else -> strings[Keys.SETUP_IMPORT_NOTE]
                    },
                    onClick = { open(Screen.Setup) },
                )
            }
        }
        SettingsSectionCard(strings[Keys.HOME_TYPING]) {
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
            SettingRow(
                strings[Keys.HOME_QUICK_ACTIONS],
                strings[Keys.HOME_QUICK_ACTIONS_NOTE],
            ) { open(Screen.QuickActions) }
            // Shown in both builds, unlike the assistant's own row: the box is worth having
            // without a model, and the screen says which of its buttons need one.
            SettingRow(
                strings[Keys.SCREEN_DRAFT_BOX],
                strings[Keys.HOME_DRAFT_BOX],
            ) { open(Screen.Composer) }
            SettingRow(
                strings[Keys.SCREEN_BACKUP],
                strings[Keys.HOME_BACKUP],
            ) { open(Screen.Backup) }
            if (hasAssistant) {
                SettingRow(strings[Keys.HOME_TEXT_ASSISTANT], strings[Keys.HOME_SUMMARISE_CORRECT_AND_TRANSLATE_ON_THIS]) {
                    open(Screen.Assistant)
                }
            }
        }
        SettingsSectionCard(strings[Keys.HOME_APPEARANCE]) {
            SettingRow(strings[Keys.HOME_THEME], strings[Keys.HOME_COLOURS_CORNERS_AND_SPACING_WITH_A]) {
                open(Screen.Theme)
            }
            SettingRow(
                strings[Keys.HOME_SIZE_AND_POSITION],
                strings[Keys.HOME_HEIGHT_ONE_HANDED_MODE_FLOATING_AND],
            ) { open(Screen.Size) }
        }
        SettingsSectionCard(strings[Keys.SHORTCUTS_TITLE]) {
            // Listed because a gesture nobody is told about is a gesture nobody uses. Holding
            // enter to reach this screen was added in the same change as this card, and would
            // otherwise be discoverable only by accident.
            SettingRow(strings[Keys.SHORTCUTS_ENTER])
            SettingRow(strings[Keys.SHORTCUTS_GLOBE])
            SettingRow(strings[Keys.SHORTCUTS_SPACE_HOLD])
            SettingRow(strings[Keys.SHORTCUTS_SPACE])
            SettingRow(strings[Keys.SHORTCUTS_SUGGESTION])
            PinShortcutRow()
        }

        SettingsSectionCard(strings[Keys.HOME_ABOUT]) {
            SettingRow(strings[Keys.HOME_PRIVACY], strings[Keys.HOME_WHAT_IS_STORED_WHERE_AND_WHAT]) { open(Screen.Privacy) }
            SettingRow(strings[Keys.HOME_ABOUT_BORDERKEYS], strings[Keys.HOME_VERSION_SOURCE_CODE_AND_LICENCE]) { open(Screen.About) }
            Explanation(
                strings[Keys.HOME_BORDERKEYS_REQUESTS_NO_PERMISSIONS_AND_CONTA],
            )
        }
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

/**
 * Offers to put these settings on the home screen.
 *
 * A keyboard's settings are awkward to reach: the launcher icon is one of the few ways in, and
 * on a phone with a full app drawer it is not a fast one. The launcher does the placing --
 * requestPinShortcut asks, and the user accepts in whatever dialog their launcher shows -- so
 * this needs no permission and cannot place anything on its own.
 */
@Composable
private fun PinShortcutRow() {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(android.content.pm.ShortcutManager::class.java)
    }
    val supported = manager?.isRequestPinShortcutSupported == true
    SettingRow(
        title = strings[Keys.SHORTCUTS_ADD],
        subtitle = if (supported) null else strings[Keys.SHORTCUTS_UNSUPPORTED],
    ) {
        if (!supported) {
            return@SettingRow
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .setClassName(context.packageName, SETTINGS_ACTIVITY)
        val shortcut = android.content.pm.ShortcutInfo.Builder(context, "settings")
            .setShortLabel(strings[Keys.SCREEN_BORDERKEYS])
            .setIcon(android.graphics.drawable.Icon.createWithResource(
                context, com.borderkeys.keyboard.R.drawable.bk_action_settings,
            ))
            .setIntent(intent)
            .build()
        runCatching { manager?.requestPinShortcut(shortcut, null) }
    }
}

/** The same class name method.xml uses, and the only reference to it from this screen. */
private const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"

/**
 * The package name of the other build, or null when this one has no sibling.
 *
 * The same derivation the setup screen uses, and for the same reason: the assistant build is
 * this one's name with a suffix, so one is the other with the suffix removed rather than a
 * second constant that could drift.
 */
private fun siblingPackage(context: android.content.Context): String? {
    val self = context.packageName
    return if (self.endsWith(".plus")) self.removeSuffix(".plus") else null
}
