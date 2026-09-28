// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An instruction the user wrote and kept, stored in the preferences: [name] is what fits on its
 * button, [instruction] what is sent, [id] what [KeyboardPreferences.composerBar] refers to it by.
 * An [id] of 0, from an older file, is backfilled by [KeyboardPreferences.sanitised].
 */
@Serializable
data class CustomAction(
    val id: Int = 0,
    val name: String,
    @SerialName("text") val instruction: String,
    val icon: Int = CustomIcon.DEFAULT.id,
) {
    companion object {
        /** About what fits on a button at a phone's width, and a hard stop for a corrupt file. */
        const val MAX_NAME_CHARS = 24

        /** More than any prompt needs; the same bound the protocol enforces on the way out. */
        const val MAX_INSTRUCTION_CHARS = 400

        /** As many as anyone will scroll through before writing a new one instead. */
        const val MAX_CUSTOM_ACTIONS = 20

        /** The base of backfilled ids, which follow list position so backfilling is idempotent. */
        private const val LEGACY_ID_BASE = 100

        /** The first id a new custom action may take, clear of built-in and backfilled ids. */
        private const val NEW_ID_MIN = 1_000

        /** [actions] with every 0-[id] entry backfilled -- see [LEGACY_ID_BASE]. */
        fun backfillLegacyIds(actions: List<CustomAction>): List<CustomAction> =
            actions.mapIndexed { index, action ->
                if (action.id != 0) action else action.copy(id = LEGACY_ID_BASE + index)
            }

        /** A fresh id for a newly created custom action, never colliding with [existing]. */
        fun nextId(existing: List<CustomAction>): Int {
            val taken = existing.mapTo(HashSet()) { it.id }
            var candidate: Int
            do {
                candidate = NEW_ID_MIN + kotlin.random.Random.nextInt(Int.MAX_VALUE - NEW_ID_MIN)
            } while (candidate in taken)
            return candidate
        }
    }
}
