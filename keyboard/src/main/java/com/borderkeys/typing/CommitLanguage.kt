// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

import com.borderkeys.language.LanguageTags

/** The language a commit is learned under and a request reads its pairs and triples in. */
object CommitLanguage {

    /**
     * The primary language subtag of [answerPack], the tag of the pack the engine's last answer
     * leaned on, else of [subtype], the input-method subtype's tag.
     */
    fun decide(answerPack: String?, subtype: String): String =
        LanguageTags.primary(answerPack?.takeIf { it.isNotEmpty() } ?: subtype)
}
