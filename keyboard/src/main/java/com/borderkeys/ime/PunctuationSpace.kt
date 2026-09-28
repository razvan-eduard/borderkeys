// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.KeyboardPreferences

/** Whether a sentence mark just typed should carry a space behind it. */
object PunctuationSpace {

    /**
     * True when a space belongs after the mark. [addressField] is [AddressField.isAddress] for the
     * field; [insideNumbers] is [KeyboardPreferences.spaceInsideNumbers]. [before] is the
     * character the mark is written after and [after] the one the caret sits in front of, null at
     * the edge of a field; both are read before the mark is committed, and only once the settings
     * allow a space.
     */
    inline fun follows(
        enabled: Boolean,
        addressField: Boolean,
        insideNumbers: Boolean,
        tightPunctuation: Boolean,
        before: () -> Char?,
        after: () -> Char?,
    ): Boolean {
        if (!enabled || addressField || !tightPunctuation) {
            return false
        }
        // No space after a mark that follows a digit, unless insideNumbers.
        if (!insideNumbers) {
            val preceding = before()
            if (preceding != null && preceding.isDigit()) {
                return false
            }
        }
        // Not before a space.
        return after() != ' '
    }
}
