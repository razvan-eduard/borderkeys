// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import java.io.File

/** The repository the tests run in: the nearest directory above them holding [SETTINGS_FILE]. */
object RepositoryRoot {

    /** The file that marks the repository's root. */
    const val SETTINGS_FILE = "settings.gradle.kts"

    val directory: File by lazy {
        var candidate: File? = File("").absoluteFile
        while (candidate != null && !File(candidate, SETTINGS_FILE).isFile) {
            candidate = candidate.parentFile
        }
        checkNotNull(candidate) { "repository root not found above ${File("").absolutePath}" }
    }

    /** [path], relative to the repository root. */
    fun file(path: String): File = File(directory, path)
}
