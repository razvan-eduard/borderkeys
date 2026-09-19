// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where tier B's weights have got to, so the settings screen can say so.
 *
 * The weights are read from the assets only when the "experimental swipe model" preference is
 * turned on, and freed again when it is turned off -- two and a half megabytes is too much to
 * hold for a feature that is off by default. That makes turning the switch on a real piece of
 * work with a beginning and an end, and this is the only way the screen that owns the switch can
 * know which it is in.
 *
 * **Process-wide, and deliberately not persisted.** The settings activity and the input method
 * share one process (nothing in any manifest says `android:process` except the text assistant),
 * so this is a plain object rather than anything crossing a boundary. It describes what the
 * running engine is doing right now; the *permanent* record of a load that failed is
 * `KeyboardPreferences.swipeModelFailed`, which is stored, because the switch has to stay
 * disabled after a restart.
 *
 * The state is written by the input method alone -- hence `internal`, which stops `:settings`
 * from doing anything but reading it -- and defaults to [State.Off], which is also the honest
 * answer when the input method is not running at all.
 */
object SwipeModelLoad {

    enum class State {
        /** Switched off, or no keyboard running to have loaded anything. */
        Off,

        /** Reading the weights, or warming the model with them. The switch is a spinner. */
        Loading,

        /** Loaded and warmed: the next swipe is decoded by tier B. */
        Ready,

        /**
         * The weights could not be read or were not valid. Permanent for this installation --
         * see `KeyboardPreferences.swipeModelFailed`, which is what survives a restart.
         */
        Failed,
    }

    private val mutable = MutableStateFlow(State.Off)

    val state: StateFlow<State> = mutable.asStateFlow()

    internal fun set(value: State) {
        mutable.value = value
    }
}
