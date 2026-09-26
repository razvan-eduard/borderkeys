// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * The apps whose copies the clipboard history never keeps: a copy made while the keyboard is
 * open in one of them is dropped, whether or not the app marked the clip sensitive.
 *
 * [KNOWN] holds password managers and one-time-code apps by package name; the
 * `clipboardExcludedPackages` preference holds whatever the person adds.
 */
object ClipboardExclusions {

    val KNOWN: Set<String> = setOf(
        "com.x8bit.bitwarden",
        "com.kunzisoft.keepass.free",
        "com.kunzisoft.keepass.libre",
        "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.dashlane",
        "proton.android.pass",
        "io.enpass.app",
        "com.nordpass.android.app.password.manager",
        "com.siber.roboform",
        "com.callpod.android_apps.keeper",
        "com.beemdevelopment.aegis",
        "org.shadowice.flocke.andotp",
        "org.fedorahosted.freeotp",
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.azure.authenticator",
        "app.keemobile.kotpass",
    )

    /** How many packages a person can add. */
    const val MAX_ADDED = 50

    /** Whether [packageName] is one of [KNOWN] or of [added]. Nothing known is never excluded. */
    fun isExcluded(packageName: String?, added: List<String>): Boolean =
        packageName != null && (packageName in KNOWN || packageName in added)

    /** Whether [candidate] has the shape of a package name: dotted identifiers, two at least. */
    fun isPackageName(candidate: String): Boolean = PACKAGE_NAME.matches(candidate)

    /** The list as stored: trimmed, shaped like package names, each once, no more than [MAX_ADDED]. */
    fun sanitised(added: List<String>): List<String> =
        added.map { it.trim() }.filter { isPackageName(it) }.distinct().take(MAX_ADDED)

    private val PACKAGE_NAME = Regex("""[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+""")
}
