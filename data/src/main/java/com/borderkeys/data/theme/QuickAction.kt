// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The things a quick-action button can do.
 *
 * Stored as [id] rather than as an ordinal, so reordering this list or removing an action does
 * not silently turn someone's saved bar into a different bar. An id no build knows is dropped
 * when the bar is read, which is what makes it safe to remove one later.
 *
 * Every action here is something that is otherwise several gestures: a long press to select, a
 * drag to extend, a menu to find "copy". That is the whole selection criterion -- a button that
 * saves one tap is not worth the row it sits in.
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

    /**
     * Inserts a line break.
     *
     * Worth a button because in a messaging app the return key sends the message, and the
     * gesture for "new line without sending" is different in every one of them.
     */
    NEWLINE(12),

    /** Switches to the next enabled layout, the same as the globe key. */
    SWITCH_LAYOUT(13),

    /** Opens the settings app. */
    SETTINGS(14),

    /**
     * Steps back through what this keyboard has done to the field this session, one word or
     * paste or deletion at a time.
     *
     * Not in [DEFAULT] -- worth choosing rather than finding, the same as [COMPOSE].
     */
    UNDO(15),

    /**
     * Opens the draft box: a place to write that the application cannot see.
     *
     * Seeded from the selection when there is one. Not in [DEFAULT] -- a bar of five is already
     * a bar of five, and this is worth choosing rather than finding.
     */
    COMPOSE(16),

    /** Steps forward again after [UNDO], as far as the last step back came from. */
    REDO(17),
    ;

    companion object {
        /** What a new install starts with: the five that answer "I want that text somewhere". */
        val DEFAULT: List<QuickAction> = listOf(
            COPY_PREVIOUS_WORD, COPY_ALL, PASTE, CLIPBOARD_HISTORY, SELECT_ALL,
        )

        fun fromId(id: Int): QuickAction? = idMatching(entries.toTypedArray(), id) { it.id }

        /** Drops ids this build does not know, so an older bar opens rather than failing. */
        fun fromIds(ids: List<Int>): List<QuickAction> =
            idsMatching(entries.toTypedArray(), ids) { it.id }
    }
}
