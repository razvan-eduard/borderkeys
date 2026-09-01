// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.i18n

/**
 * Technical identifiers for the translation system. Nothing a person reads lives here -- every
 * word on screen is in `assets/translations/{lang}.json`, reached through [Keys].
 */
object Strings {

    /** Where the catalogue lives, relative to the merged asset root. */
    object Translations {
        const val DIR = "translations/"
        const val DIR_LIST = "translations"
        const val JSON_EXTENSION = ".json"
    }

    object Languages {
        /**
         * The catalogue that is always complete, and the one every other language is held
         * against by [TranslationParity]. Also the last resort when a phone's language is one
         * BorderKeys does not ship.
         */
        const val DEFAULT = "en"

        /** Stored in preferences when the user has not picked a language by hand. */
        const val FOLLOW_SYSTEM = ""
    }
}
