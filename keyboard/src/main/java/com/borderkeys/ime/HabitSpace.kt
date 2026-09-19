// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * Whether the space just pressed is the one this keyboard already put there.
 *
 * A space added by the keyboard -- after a sentence mark, or behind a word picked from the strip
 * -- is usually typed again a moment later out of habit, and committing it would leave two.
 * [KeyboardPreferences.autoSpaceHabit] says what to do about that: drop the first one, keep
 * dropping, or keep them all.
 *
 * Pure, and separate from the service, for the reason [AutoCorrection] and [AutoShift] are: the
 * rule is worth testing on the JVM, and the service has an editor, a connection and a keyboard
 * in the way of doing that.
 *
 * **[characterBeforeCursor] is what makes this safe.** The decision used to be taken on the
 * remembered flag alone, and that flag outlived the text it described: it survived a caret
 * moved elsewhere, an emoji or a paste committed over it, a switch to another field, and a
 * backspace over the very space it was remembering. Each of those left the keyboard certain it
 * had put a space behind the caret when it had not, and the next space the user typed was eaten
 * at a position that never had one. Reading the character back costs one `getTextBeforeCursor`
 * on a keystroke that is about to be discarded anyway, and it is the same rule the rest of this
 * package already follows: nothing deletes on the strength of what it remembers when it can look.
 */
object HabitSpace {

    /**
     * True when this keystroke should be dropped rather than committed.
     *
     * [composingEmpty] is false while a word is being typed -- a space then ends that word and is
     * never a habit. [pendingAutoSpace] is the keyboard's own record of having added one.
     * [characterBeforeCursor] answers with the character the caret sits behind, or null at the
     * very start of a field.
     *
     * That last one is a lambda, and this function is `inline`, because reading it is an IPC to
     * the editor and this runs on the space bar. The cheap answers are settled first, so the
     * read happens only on a keystroke that is already about to be discarded -- and the lambda
     * itself never allocates, which is what the hot path asks for (CONTRIBUTING, rule 3).
     */
    inline fun swallows(
        composingEmpty: Boolean,
        pendingAutoSpace: Boolean,
        habit: Int,
        characterBeforeCursor: () -> Char?,
    ): Boolean {
        if (!composingEmpty || !pendingAutoSpace) {
            return false
        }
        if (habit == KeyboardPreferences.AUTO_SPACE_KEEP) {
            return false
        }
        // The space has to still be there. Anything else behind the caret -- a letter, an emoji,
        // a pasted full stop, or nothing at all -- means the remembered one is somewhere the
        // user is no longer typing, and this keystroke is a space they actually want.
        return characterBeforeCursor() == ' '
    }

    /** Whether the flag stays armed for the space after this one. */
    fun staysArmed(habit: Int): Boolean = habit == KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL
}
