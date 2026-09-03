// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * A button on the draft box's control bar, in the order the user put them.
 *
 * The same shape as [QuickAction] and for the same reasons: stable ids because the order is
 * written to disk, and a `fromIds` that drops what it does not recognise so a bar written by a
 * later build opens rather than failing.
 *
 * The two arrows that walk the version graph are deliberately not here. They are pinned to the
 * ends of the bar, because a control that moves is a control you have to look for, and walking
 * back through versions is the one thing you do without looking -- the same argument that keeps
 * backspace where it is.
 *
 * Everything here except [INSERT] needs the assistant. In a build without it the bar is Insert
 * alone, which is honest: the box is still a place to write that the application cannot see.
 */
enum class ComposerAction(val id: Int) {

    /** Spelling, grammar and punctuation, without rephrasing. */
    GRAMMAR(1),

    /** Opens the language chooser, then translates. */
    TRANSLATE(2),

    /** Opens the register chooser: formal, casual, direct. */
    TONE(3),

    /** The same text in fewer words. Not a summary. */
    SHORTEN(4),

    /** Opens the prompt input at the bottom of the box. */
    PROMPT(5),

    /** The prompts this device has kept, to run one again. */
    SAVED_PROMPTS(6),

    /**
     * Flips between the original and wherever you are standing.
     *
     * Not "go to the first version" -- it is a toggle, and it returns you to the node you were
     * reading rather than to the newest one, which is what makes it usable from the middle of a
     * chain.
     */
    SHOW_ORIGINAL(7),

    /** Writes the current version into the application's field and closes the box. */
    INSERT(8),
    ;

    /** Whether this button does anything without a model installed. */
    val needsAssistant: Boolean get() = this != INSERT

    companion object {
        /**
         * What a new install starts with.
         *
         * Everything except the saved prompts, which are empty on a new install and would be a
         * button that opens an empty list. It appears the first time one is saved.
         */
        val DEFAULT: List<ComposerAction> = listOf(
            GRAMMAR, TRANSLATE, TONE, SHORTEN, PROMPT, SHOW_ORIGINAL, INSERT,
        )

        fun fromId(id: Int): ComposerAction? = entries.firstOrNull { it.id == id }

        fun fromIds(ids: List<Int>): List<ComposerAction> = ids.mapNotNull(::fromId).distinct()
    }
}
