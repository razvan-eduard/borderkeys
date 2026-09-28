// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * A button on the draft box's control bar, stored by [id]; `fromIds` drops an id this build does
 * not know. The version arrows are fixed at the bar's ends, and [INSERT] is the fixed button the
 * box draws itself, dropped by [ComposerBar.resolve]. Everything else needs the assistant.
 */
enum class ComposerAction(val id: Int) {

    /** Spelling, grammar and punctuation, without rephrasing. */
    CORRECT(1),

    /** Opens the language chooser, then translates. */
    TRANSLATE(2),

    /** Opens the register chooser: formal, casual, direct. */
    TONE(3),

    /** The same text in fewer words. Not a summary. */
    SHORTEN(4),

    /** A short summary of the text; see [AssistTask.minWords]. */
    SUMMARISE(9),

    /**
     * Drops everything but the selected span, as a new version, without a model. Shown only while
     * something is selected.
     */
    KEEP_SELECTION(10),

    /** Opens the prompt input at the bottom of the box. */
    PROMPT(5),

    /** The prompts this device has kept, to run one again. */
    SAVED_PROMPTS(6),

    /**
     * Flips between the original and the version you are on, returning to that version rather
     * than to the newest.
     */
    SHOW_ORIGINAL(7),

    /** Writes the current version into the application's field and closes the box. */
    INSERT(8),
    ;

    /** Whether this button does anything without a model installed. */
    val needsAssistant: Boolean get() = this != INSERT

    companion object {
        /**
         * What a new install starts with: everything except the saved prompts, which appear once
         * one is saved, and [INSERT].
         */
        val DEFAULT: List<ComposerAction> = listOf(
            CORRECT, TRANSLATE, TONE, SHORTEN, SUMMARISE, KEEP_SELECTION, PROMPT, SHOW_ORIGINAL,
        )

        fun fromId(id: Int): ComposerAction? = idMatching(entries.toTypedArray(), id) { it.id }

        fun fromIds(ids: List<Int>): List<ComposerAction> =
            idsMatching(entries.toTypedArray(), ids) { it.id }
    }
}
