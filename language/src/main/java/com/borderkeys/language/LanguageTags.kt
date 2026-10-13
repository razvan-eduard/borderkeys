// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.language

import java.util.Locale

/** A language tag's primary language subtag, as every module reads it. */
object LanguageTags {

    /** The language of a tag that names none. */
    const val UNKNOWN = "und"

    /** What ends a tag's primary language subtag. */
    const val SUBTAG_SEPARATOR = '-'
    const val LEGACY_SUBTAG_SEPARATOR = '_'

    /** [tag]'s primary language subtag, lower-cased, before its first separator; [UNKNOWN] for none. */
    fun primary(tag: String?): String =
        tag.orEmpty().substringBefore(SUBTAG_SEPARATOR).substringBefore(LEGACY_SUBTAG_SEPARATOR)
            .lowercase(Locale.ROOT).ifEmpty { UNKNOWN }
}
