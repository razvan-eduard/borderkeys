// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys
import com.borderkeys.settings.LocalStrings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.settings.BuildConfig
import com.borderkeys.settings.Divider
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.SectionHeader
import com.borderkeys.settings.SettingRow

/**
 * Version, source and licence.
 *
 * The commit hash and the source URL are not decoration: GPL section 6 requires that a binary
 * can point at the source it was built from, and these two values are how this one does it. The
 * button hands the URL to a browser through ACTION_VIEW — this app has no INTERNET permission
 * and does not need one, because the browser has it.
 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    val hasAssistant = remember {
        val intent = Intent().setClassName(context.packageName, AssistProtocol.SERVICE_CLASS)
        context.packageManager.resolveService(intent, 0) != null
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionHeader(strings[Keys.ABOUT_THIS_BUILD])
        SettingRow(strings[Keys.ABOUT_VERSION], version)
        SettingRow(strings[Keys.ABOUT_FLAVOUR], if (hasAssistant) "plus" else "core")
        SettingRow(strings[Keys.ABOUT_COMMIT], BuildConfig.GIT_COMMIT)
        SettingRow(strings[Keys.ABOUT_SOURCE], BuildConfig.SOURCE_URL)
        Button(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, BuildConfig.SOURCE_URL.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) { Text(strings[Keys.ABOUT_OPEN_THE_SOURCE_REPOSITORY]) }
        Explanation(
            strings[Keys.ABOUT_THE_COMMIT_ABOVE_IS_THE_ONE],
        )

        Divider()
        SectionHeader(strings[Keys.ABOUT_LICENCE])
        SettingRow(
            strings[Keys.ABOUT_GPL_3_0_OR_LATER],
            strings[Keys.ABOUT_YOU_MAY_USE_STUDY_CHANGE_AND],
        )
        Explanation(
            strings[Keys.ABOUT_EVERY_DEPENDENCY_AND_EVERY_ASSET_IS],
        )

        if (hasAssistant) {
            Divider()
            SectionHeader(strings[Keys.ABOUT_TEXT_ASSISTANT])
            Explanation(
                strings[Keys.ABOUT_INFERENCE_USES_LLAMA_CPP_MIT_LICENSED],
            )
        }
    }
}
