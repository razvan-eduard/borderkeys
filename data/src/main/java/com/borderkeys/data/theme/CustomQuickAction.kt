// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A user-made button for the quick-action bar that runs several actions in order, stored in the
 * preferences. [steps] are ids of [QuickAction]s or other [CustomQuickAction]s, checked by
 * [QuickActionBar.sanitisedSteps] and expanded by [QuickActionBar.flatten].
 */
@Serializable
data class CustomQuickAction(
    val id: Int = 0,
    val name: String,
    val steps: List<Int> = emptyList(),
    val icon: Int = CustomIcon.DEFAULT.id,
) {
    companion object {
        /** About what fits on a button at a phone's width, and a hard stop for a corrupt file. */
        const val MAX_NAME_CHARS = 24

        /** The most steps a macro may have. */
        const val MAX_STEPS = 8

        /** As many as anyone will scroll through before writing a new one instead. */
        const val MAX_CUSTOM_QUICK_ACTIONS = 20

        /** The first id a new custom quick action may take, clear of [QuickAction]'s ids. */
        private const val NEW_ID_MIN = 1_000

        /** A fresh id for a newly created custom quick action, never colliding with [existing]. */
        fun nextId(existing: List<CustomQuickAction>): Int {
            val taken = existing.mapTo(HashSet()) { it.id }
            var candidate: Int
            do {
                candidate = NEW_ID_MIN + kotlin.random.Random.nextInt(Int.MAX_VALUE - NEW_ID_MIN)
            } while (candidate in taken)
            return candidate
        }
    }
}
