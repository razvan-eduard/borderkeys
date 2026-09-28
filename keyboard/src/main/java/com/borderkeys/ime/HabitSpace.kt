// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * Whether the space just pressed repeats the one the keyboard added behind the caret, handled as
 * [KeyboardPreferences.autoSpaceHabit] says.
 */
object HabitSpace {

    /**
     * True when this keystroke should be dropped. [composingEmpty] is false while a word is being
     * typed; [pendingAutoSpace] is the keyboard's record of having added a space;
     * [characterBeforeCursor], null at the start of a field, is read last.
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
        // Only while the added space is still behind the caret.
        return characterBeforeCursor() == ' '
    }

    /** Whether the flag stays armed for the space after this one. */
    fun staysArmed(habit: Int): Boolean = habit == KeyboardPreferences.AUTO_SPACE_SWALLOW_ALL
}
