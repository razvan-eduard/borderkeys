// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

/**
 * Which clip the clipboard chip may show, clips named by their signature and apps by their package.
 * With "Offer it only once" on, a clip is withheld once it is used, or once a keyboard session
 * that showed it closes in an app other than the one it came from. A clip came from the app the
 * keyboard was open in when it was copied; one copied while the keyboard was hidden came from the
 * app the keyboard next shows it in, when that is the app it last closed in.
 */
class ClipChipOffers {

    private var withheld: String? = null
    private var shown: String? = null

    /** The clip whose app is known, and that app. */
    private var sourceClip: String? = null
    private var sourceApp: String? = null

    /** The app the keyboard last closed in. */
    private var lastClosedIn: String? = null

    /** Whether the chip may show the clip with [signature]. */
    fun mayShow(signature: String?): Boolean = signature != null && signature != withheld

    /** The chip now shows the clip with [signature] in [app], or nothing when it is null. */
    fun shown(signature: String?, app: String?) {
        shown = signature
        if (signature != null && signature != sourceClip && app != null && app == lastClosedIn) {
            sourceClip = signature
            sourceApp = app
        }
    }

    /** The clip with [signature] was copied while the keyboard is open in [app]. */
    fun copied(signature: String?, app: String?) {
        withheld = null
        sourceClip = signature
        sourceApp = app
    }

    /** The clip with [signature] was used from the chip. */
    fun used(signature: String?, once: Boolean) {
        if (once) {
            withheld = signature
        }
    }

    /** The keyboard closed in [app]; with [once], the clip shown is withheld unless it came from [app]. */
    fun keyboardClosed(once: Boolean, app: String?) {
        if (once && shown != null && !(shown == sourceClip && app != null && app == sourceApp)) {
            withheld = shown
        }
        lastClosedIn = app
    }
}
