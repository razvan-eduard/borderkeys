// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

/**
 * Handing settings from one build to the other. The asking build starts the other's settings
 * activity with [EXTRA_REQUEST] set; the asked build checks that the caller's signature matches
 * its own, shows what is asked for, and on approval answers with a `content:` URI carrying a
 * one-shot read grant.
 */
object TransferProtocol {

    /** Set on the intent that starts the other build's settings activity. */
    const val EXTRA_REQUEST = "com.borderkeys.extra.TRANSFER_REQUEST"

    /** The activity a request is addressed to. Its own constant, never a class reference. */
    const val SETTINGS_CLASS = "com.borderkeys.settings.SettingsActivity"

    /** The file the answer is written into, inside the answering build's own cache. */
    const val FILE_NAME = "transfer.json"

    /** The authority suffix of the provider that serves it. */
    const val PROVIDER_SUFFIX = ".transfer"
}
