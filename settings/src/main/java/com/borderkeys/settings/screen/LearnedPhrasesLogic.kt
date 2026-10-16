// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import com.borderkeys.i18n.Keys

/** The label and title keys of the phrases page's delete button, over the phrases listed. */
internal fun phraseDeleteKeys(narrowed: Boolean): Pair<String, String> =
    if (narrowed) {
        Keys.DICTIONARY_DELETE_FILTERED_PHRASES to Keys.DICTIONARY_DELETE_FILTERED_PHRASES_TITLE
    } else {
        Keys.DICTIONARY_DELETE_ALL_PHRASES to Keys.DICTIONARY_DELETE_ALL_PHRASES_TITLE
    }
