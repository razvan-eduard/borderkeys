// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which clip the clipboard chip may show, clips named by their signature. With "Offer it only
 * once" on, a clip is withheld once it is used, or once the keyboard closes after a session that
 * showed it; a clip copied while the keyboard is open is shown through the next session too.
 */
class ClipChipOffers {

    private var withheld: String? = null
    private var shown: String? = null

    /** The clip copied while the keyboard was open, since it last opened. */
    private var copiedThisSession: String? = null

    /** Whether the chip may show the clip with [signature]. */
    fun mayShow(signature: String?): Boolean = signature != null && signature != withheld

    /** The chip now shows the clip with [signature], or nothing when it is null. */
    fun shown(signature: String?) {
        shown = signature
    }

    /** The clip with [signature] was copied while the keyboard is open. */
    fun copied(signature: String?) {
        withheld = null
        copiedThisSession = signature
    }

    /** The clip with [signature] was used from the chip. */
    fun used(signature: String?, once: Boolean) {
        if (once) {
            withheld = signature
        }
    }

    /** The keyboard closed; with [once], the clip shown is withheld unless it was copied since it opened. */
    fun keyboardClosed(once: Boolean) {
        if (once && shown != null && shown != copiedThisSession) {
            withheld = shown
        }
        copiedThisSession = null
    }
}
