// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

/**
 * Handing settings from one build to the other without going through a file the user has to
 * name, find, and delete afterwards.
 *
 * The asking build starts the other one's settings activity -- already exported, because it is a
 * launcher activity, so nothing new is opened up -- with [EXTRA_REQUEST] set, and waits for a
 * result. The asked build shows what is being asked for, and on approval answers with a
 * `content:` URI carrying a one-shot read grant. Nothing is written where anything else can see
 * it, and nothing is left behind.
 *
 * Two things guard it, and neither is enough alone. The caller's signature must match this
 * build's, which is checkable and cannot be spoofed by an application that merely knows the
 * name. And the person holding the phone has to press a button, having been told which
 * application is asking and for what -- because a signature check answers "is this our other
 * build" and not "did anybody mean for this to happen".
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
