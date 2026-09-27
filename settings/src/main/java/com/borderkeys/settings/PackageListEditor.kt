// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.borderkeys.data.PackageNames
import com.borderkeys.i18n.Keys

/**
 * A list of app package names a person keeps by hand: a row for each with its Remove, then a
 * field for the next one and an Add that takes it once it reads as a package name the list does
 * not hold yet.
 *
 * [update] receives each change as a function of the stored list, so it is applied to the list
 * as it stands when it is written. [fieldLabel] names the field, with an example of the kind of
 * app the list is for.
 */
@Composable
fun PackageListEditor(
    packages: List<String>,
    fieldLabel: String,
    update: ((List<String>) -> List<String>) -> Unit,
) {
    val strings = LocalStrings.current
    for (packageName in packages) {
        SettingRow(
            title = packageName,
            trailing = {
                TextButton(onClick = { update { it - packageName } }) { Text(strings[Keys.COMMON_REMOVE]) }
            },
        )
    }
    var newPackage by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = newPackage,
        onValueChange = { newPackage = it },
        label = { Text(fieldLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
    val candidate = newPackage.trim()
    TextButton(
        enabled = PackageNames.isPackageName(candidate) && candidate !in packages &&
            packages.size < PackageNames.MAX_ADDED,
        onClick = {
            update { it + candidate }
            newPackage = ""
        },
        modifier = Modifier.padding(horizontal = 12.dp),
    ) { Text(strings[Keys.COMMON_ADD_THIS_APP]) }
}
