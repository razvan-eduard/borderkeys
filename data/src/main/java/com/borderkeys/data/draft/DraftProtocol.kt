// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.draft

/**
 * Opening the draft box from inside the keyboard itself, while a field is being typed into.
 *
 * The keyboard is a Service, and everything the draft box needs -- Compose, the animated ring,
 * the version rail -- lives in `:settings` and cannot live in `:keyboard` (a hard rule, checked
 * at build time: the keyboard renders into a Canvas on the UI thread with a per-key latency
 * budget, and Compose does not fit inside that). So the "Compose" quick action does what
 * [com.borderkeys.data.backup.TransferProtocol] already does for a different reason: it starts
 * `SettingsActivity` by class name with this action set, and the same screen the system's own
 * text-selection menu opens is what draws itself.
 *
 * There is no calling activity on this path -- it was not reached through
 * `ACTION_PROCESS_TEXT` -- so nothing here can set a result the way a selection-menu launch can.
 * The screen answers with the clipboard instead, the same way it already does for a selection
 * that was never editable to begin with.
 */
object DraftProtocol {

    /** Set on the intent [com.borderkeys.settings.SettingsActivity] is started with. */
    const val ACTION_QUICK_DRAFT = "com.borderkeys.action.QUICK_DRAFT"
}
