// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

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
        SectionHeader("This build")
        SettingRow("Version", version)
        SettingRow("Flavour", if (hasAssistant) "plus" else "core")
        SettingRow("Commit", BuildConfig.GIT_COMMIT)
        SettingRow("Source", BuildConfig.SOURCE_URL)
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
        ) { Text("Open the source repository") }
        Explanation(
            "The commit above is the one this binary was built from. Opening the link hands it " +
                "to your browser; this app holds no network permission of its own.",
        )

        Divider()
        SectionHeader("Licence")
        SettingRow(
            "GPL-3.0-or-later",
            "You may use, study, change and share this. If you distribute a changed version, " +
                "you pass the same freedoms on with it.",
        )
        Explanation(
            "Every dependency and every asset is listed in docs/licensing.md in the repository, " +
                "with its licence and whether it is compatible. The core build carries no " +
                "non-free asset at all.",
        )

        if (hasAssistant) {
            Divider()
            SectionHeader("Text assistant")
            Explanation(
                "Inference uses llama.cpp, MIT licensed, compiled from source. Models are not " +
                    "bundled; the ones this build will load are Apache-2.0 and are imported by " +
                    "you from a file.",
            )
        }
    }
}
