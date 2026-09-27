// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * The apps typed into as terminals by name, for the ones whose fields declare a text class
 * anyway.
 *
 * [KNOWN] holds terminal and SSH apps by package name; the `terminalPackages` preference holds
 * whatever the person adds.
 */
object TerminalApps {

    val KNOWN: Set<String> = setOf(
        "com.termux",
        "com.termux.nix",
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client",
        "com.android.virtualization.terminal",
        "io.neoterm",
        "jackpal.androidterm",
    )

    /** Whether [packageName] is one of [KNOWN] or of [added]. */
    fun isTerminalApp(packageName: String?, added: List<String>): Boolean =
        packageName != null && (packageName in KNOWN || packageName in added)
}
