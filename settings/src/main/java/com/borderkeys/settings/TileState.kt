// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import com.borderkeys.i18n.Keys

/**
 * What the quick-settings tile says about this build: lit while it is the keyboard in use,
 * with the line under its name drawn from [subtitleKey].
 */
enum class TileState(val active: Boolean, val subtitleKey: String) {
    InUse(true, Keys.TILE_IN_USE),
    Enabled(false, Keys.TILE_NOT_IN_USE),
    NotEnabled(false, Keys.TILE_NOT_ENABLED),
    ;

    companion object {
        /** The state for a build that is [enabled] in the system's list and [inUse] as its keyboard. */
        fun of(enabled: Boolean, inUse: Boolean): TileState = when {
            inUse -> InUse
            enabled -> Enabled
            else -> NotEnabled
        }
    }
}
