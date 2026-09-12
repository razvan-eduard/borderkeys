// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * An instruction the user wrote and kept, with a short name for its button.
 *
 * In the preferences rather than in the database, unlike the clipboard: there are a handful of
 * these, they are a few hundred bytes each, and they are settings -- the same kind of thing as
 * which buttons are on the bar. A table would buy a migration and a DAO for a list that fits in
 * one screen.
 *
 * [name] is what fits on a button; [text] is what is sent. They are separate because no
 * instruction worth writing fits in the width of a button, and a button that says "rewrite thi..."
 * is a button nobody can tell apart from the next one.
 */
@Serializable
data class SavedPrompt(
    val name: String,
    val text: String,
) {
    companion object {
        /** About what fits on a button at a phone's width, and a hard stop for a corrupt file. */
        const val MAX_NAME_CHARS = 24

        /** More than any prompt needs; the same bound the protocol enforces on the way out. */
        const val MAX_TEXT_CHARS = 400

        /** As many as anyone will scroll through before writing a new one instead. */
        const val MAX_SAVED = 20
    }
}
