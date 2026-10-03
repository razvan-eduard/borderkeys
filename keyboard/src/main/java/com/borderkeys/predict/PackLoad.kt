// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The language packs the engine has loaded and made active in the running process, by tag,
 * written once the chosen set is loaded. Not persisted; empty until the first set is.
 */
object PackLoad {

    private val mutable = MutableStateFlow(emptyList<String>())

    val active: StateFlow<List<String>> = mutable.asStateFlow()

    internal fun set(tags: List<String>) {
        mutable.value = tags
    }
}
