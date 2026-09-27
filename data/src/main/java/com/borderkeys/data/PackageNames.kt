// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * A list of app package names kept by hand in the settings: what counts as a package name, and
 * the list as it is stored. Shared by every list of apps a person can add to.
 */
object PackageNames {

    /** How many packages a person can add to one list. */
    const val MAX_ADDED = 50

    /** Whether [candidate] has the shape of a package name: dotted identifiers, two at least. */
    fun isPackageName(candidate: String): Boolean = PACKAGE_NAME.matches(candidate)

    /** The list as stored: trimmed, shaped like package names, each once, no more than [MAX_ADDED]. */
    fun sanitised(added: List<String>): List<String> =
        added.map { it.trim() }.filter { isPackageName(it) }.distinct().take(MAX_ADDED)

    private val PACKAGE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")
}
