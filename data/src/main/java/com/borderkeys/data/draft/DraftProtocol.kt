// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.draft

/**
 * Opening the draft box from inside the keyboard: the "Compose" quick action starts
 * `SettingsActivity` by class name with this action set. With no calling activity, the screen
 * answers through the clipboard.
 */
object DraftProtocol {

    /** Set on the intent [com.borderkeys.settings.SettingsActivity] is started with. */
    const val ACTION_QUICK_DRAFT = "com.borderkeys.action.QUICK_DRAFT"
}
