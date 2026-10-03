// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The things a quick-action button can do, stored by [id]; an id this build does not know is
 * dropped when the bar is read.
 */
enum class QuickAction(val id: Int) {

    /** Copies the word before the cursor, without disturbing the selection. */
    COPY_PREVIOUS_WORD(1),

    /** Copies the line the cursor is on. */
    COPY_LINE(2),

    /** Copies everything in the field. */
    COPY_ALL(3),

    /** Pastes the clipboard at the cursor. */
    PASTE(4),

    /** Opens the clipboard history panel. */
    CLIPBOARD_HISTORY(5),

    /** Selects everything in the field. */
    SELECT_ALL(6),

    /** Cuts the selection, or the current word when there is none. */
    CUT(7),

    /** Selects the word the cursor is in. */
    SELECT_WORD(8),

    /** Deletes the word before the cursor, all of it, in one press. */
    DELETE_WORD(9),

    /** Moves the cursor to the start of the text. */
    CURSOR_START(10),

    /** Moves the cursor to the end of the text. */
    CURSOR_END(11),

    /** Inserts a line break. */
    NEWLINE(12),

    /** Switches to the next enabled layout, the same as the globe key. */
    SWITCH_LAYOUT(13),

    /** Opens the settings app. */
    SETTINGS(14),

    /**
     * Steps back through what this keyboard has done to the field this session, one word or
     * paste or deletion at a time.
     */
    UNDO(15),

    /**
     * Opens the draft box, a place to write that the application cannot see, seeded from the
     * selection when there is one.
     */
    COMPOSE(16),

    /** Steps forward again after [UNDO], as far as the last step back came from. */
    REDO(17),

    /**
     * Flips the first letter of the word the cursor is in, or just after, between capital
     * and lower case: "apple" becomes "Apple", and the next press "apple" again. The cursor
     * stays where it was.
     */
    CAPITAL(18),

    /**
     * Capitalises the first letter of every sentence in the field -- the first word, and the
     * first word after each full stop, question or exclamation mark, or line break -- and
     * changes nothing else: "hello there. i am here" becomes "Hello there. I am here".
     */
    NORMALISE(19),

    /** Moves the cursor one character left. */
    CURSOR_LEFT(20),

    /** Moves the cursor one character right. */
    CURSOR_RIGHT(21),

    /**
     * Writes the date and time at the cursor, in the shape [TimestampPattern] and the
     * `timestampPattern` preference give it.
     */
    TIMESTAMP(22),

    /**
     * Keeps the selection in the clipboard history as a private entry, which never touches the
     * system clipboard and outlives the retention window and the history limit.
     */
    PRIVATE_COPY(23),
    ;

    /**
     * Whether this can be one step of a [CustomQuickAction] macro: an edit through the
     * [android.view.inputmethod.InputConnection] alone that finishes within its tap.
     */
    val macroEligible: Boolean
        get() = this !in setOf(CLIPBOARD_HISTORY, SWITCH_LAYOUT, SETTINGS, COMPOSE)

    companion object {
        /** What a new install starts with. */
        val DEFAULT: List<QuickAction> = listOf(
            COPY_PREVIOUS_WORD, COPY_ALL, PASTE, CLIPBOARD_HISTORY, SELECT_ALL,
        )

        fun fromId(id: Int): QuickAction? = idMatching(entries.toTypedArray(), id) { it.id }

        /** The actions for [ids], dropping ids this build does not know. */
        fun fromIds(ids: List<Int>): List<QuickAction> =
            idsMatching(entries.toTypedArray(), ids) { it.id }
    }
}
