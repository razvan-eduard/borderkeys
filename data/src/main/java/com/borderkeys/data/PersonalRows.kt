// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

import com.borderkeys.data.entity.UserBigram
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserPhraseClock
import com.borderkeys.data.entity.UserTrigram
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.entity.UserWord

/** The personal dictionary's rows the keyboard loads, as [DictionaryRepository.personalRows] reads them. */
data class PersonalRows(
    val words: List<UserWord>,
    val pairs: List<UserBigram>,
    val pairContexts: List<UserPairContext>,
    val triples: List<UserTrigram>,
    val tripleContexts: List<UserTripleContext>,
    val clocks: List<UserPhraseClock>,
)
