// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserCorrection
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord

/**
 * The personal dictionary's rows a backup carries: as [DictionaryRepository.contents] reads them,
 * and as [DictionaryRepository.restore] merges them back.
 */
data class DictionaryRows(
    val words: List<UserWord> = emptyList(),
    val pairs: List<UserBigram> = emptyList(),
    val pairContexts: List<UserPairContext> = emptyList(),
    val triples: List<UserTrigram> = emptyList(),
    val tripleContexts: List<UserTripleContext> = emptyList(),
    val corrections: List<UserCorrection> = emptyList(),
    val blocked: List<String> = emptyList(),
) {
    /** How many words, links and corrections it holds: what a restore can leave out for a block. */
    val entries: Int get() = words.size + pairs.size + triples.size + corrections.size
}
