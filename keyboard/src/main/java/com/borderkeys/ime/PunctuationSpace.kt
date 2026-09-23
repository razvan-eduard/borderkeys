// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences

/**
 * Whether a sentence mark just typed should carry a space behind it.
 *
 * Pure, and separate from the service, for the reason [HabitSpace] and [AutoCorrection] are: the
 * rule is worth testing on the JVM, and the service has an editor and a connection in the way of
 * doing that.
 *
 * Every mark [KeyboardPreferences.spaceAfterPunctuation] adds a space to is also a mark numbers
 * are written with -- "12.55", "12,55", "10:30", "1.500" -- so the character in front of the mark
 * is part of the decision and not merely the one behind it.
 */
object PunctuationSpace {

    /**
     * True when a space belongs after the mark.
     *
     * [insideNumbers] is [KeyboardPreferences.spaceInsideNumbers]: with it on the digit in
     * front stops mattering and every mark is spaced wherever it falls.
     *
     * [before] is the character the mark is about to be written after, and [after] the one the
     * caret already sits in front of; either is null at the edge of a field. Both are read
     * before the mark is committed, which is the one moment [before] is the last thing in the
     * field.
     *
     * Lambdas, and this is `inline`, because each is an IPC to the editor: the settings answer
     * is taken first so an editor that has nothing to say is never asked.
     */
    inline fun follows(
        enabled: Boolean,
        insideNumbers: Boolean,
        tightPunctuation: Boolean,
        before: () -> Char?,
        after: () -> Char?,
    ): Boolean {
        if (!enabled || !tightPunctuation) {
            return false
        }
        // Inside a number rather than at the end of a sentence. A digit in front is the whole of
        // the evidence available at this keystroke -- what follows has not been typed yet -- and
        // splitting "12.55" into "12. 55" is the worse of the two mistakes: a sentence that ends
        // in a number costs one space typed by hand, while a decimal is broken every time.
        if (!insideNumbers) {
            val preceding = before()
            if (preceding != null && preceding.isDigit()) {
                return false
            }
        }
        // Not before something that is already a space, and not at the very end of a field the
        // user may be about to leave -- an editor that trims trailing whitespace would then show
        // the cursor jumping back on its own.
        return after() != ' '
    }
}
