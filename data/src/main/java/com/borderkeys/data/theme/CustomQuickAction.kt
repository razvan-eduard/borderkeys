// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A user-made button for the quick-action bar that runs more than one [QuickAction] in order --
 * "select all, then cut" as one tap instead of two.
 *
 * [steps] holds ids rather than [QuickAction]s directly, for the same reason [CustomAction] and
 * every other bar-item list in this file does: an id from a build that no longer exists, or one
 * that named an action later found not to be [QuickAction.macroEligible], is simply dropped when
 * read back ([QuickActionBar.sanitisedSteps]) rather than failing to decode. A step id can name
 * either a plain [QuickAction] or another [CustomQuickAction] -- see [QuickActionBar.flatten] for
 * how the second kind is expanded, and [QuickActionBar.hasCycle] for why one macro referencing
 * another can never be left to reference itself back.
 *
 * In the preferences, not the database, for the same reason as [CustomAction]: a handful of these,
 * a few dozen bytes each, and a setting rather than data.
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

        /** A macro is a shortcut for a handful of taps, not a script -- more than this and it is
         *  no longer faster than doing the steps by hand. */
        const val MAX_STEPS = 8

        /** As many as anyone will scroll through before writing a new one instead. */
        const val MAX_CUSTOM_QUICK_ACTIONS = 20

        /**
         * Where freshly created custom quick actions draw their id from -- clear of
         * [QuickAction]'s own 1-17, so no id is ever ambiguous between a built-in action and a
         * user-made one. Unlike [CustomAction] this type has no prior on-disk shape to backfill
         * ids for, so [nextId] is the only id-allocation this type needs.
         */
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
