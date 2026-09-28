// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The load state of tier B's weights in the running process, written by the input method and
 * read by the settings screen. Not persisted; a failed load is also stored in
 * `KeyboardPreferences.swipeModelFailed`.
 */
object SwipeModelLoad {

    enum class State {
        /** Switched off, or no keyboard running to have loaded anything. */
        Off,

        /** Reading the weights, or warming the model with them. */
        Loading,

        /** Loaded and warmed: the next swipe is decoded by tier B. */
        Ready,

        /** The weights could not be read or were not valid. */
        Failed,
    }

    private val mutable = MutableStateFlow(State.Off)

    val state: StateFlow<State> = mutable.asStateFlow()

    internal fun set(value: State) {
        mutable.value = value
    }
}
