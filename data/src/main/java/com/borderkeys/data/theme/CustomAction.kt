// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An instruction the user wrote and kept, with a short name and an icon for its button.
 *
 * In the preferences rather than in the database, unlike the clipboard: there are a handful of
 * these, they are a few hundred bytes each, and they are settings -- the same kind of thing as
 * which buttons are on the bar. A table would buy a migration and a DAO for a list that fits in
 * one screen.
 *
 * [name] is what fits on a button; [instruction] is what is sent. They are separate because no
 * instruction worth writing fits in the width of a button, and a button that says "rewrite thi..."
 * is a button nobody can tell apart from the next one.
 *
 * Was `SavedPrompt` (name + text only): renamed once its role grew from "a re-runnable prompt"
 * to "something that can also sit on the bar as its own button", which needs a stable [id] to be
 * addressed by ([KeyboardPreferences.composerBar] holds ids, not the actions themselves) and an
 * [icon] to be drawn with. `@SerialName` on both the changed property and the renamed class's
 * former field in [KeyboardPreferences] keeps the on-disk JSON shape identical, so an existing
 * install's saved prompts still decode -- [PERSISTED_JSON]'s `ignoreUnknownKeys` and
 * `encodeDefaults` mean the two new fields below simply default rather than requiring a migration
 * of their own; [KeyboardPreferences.sanitised] is what turns [id]'s default of 0 into a real,
 * stable one, once, the first time an old list is read under this schema.
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

        /**
         * Backfilled ids for entries decoded with [id] still at its 0 default -- only possible
         * for a prompt saved before this field existed. Position-based rather than random so
         * [KeyboardPreferences.sanitised] stays a pure, idempotent function: the same list in
         * the same order must get the same ids back on every call, not a fresh one each time,
         * or a custom action already pinned to the bar would be orphaned by its own next save.
         */
        private const val LEGACY_ID_BASE = 100

        /**
         * Where freshly created custom actions draw their id from -- clear of both
         * [ComposerAction]'s own 1-10 and [LEGACY_ID_BASE]'s range, so no id is ever ambiguous
         * between a built-in action, a backfilled legacy prompt, and a newly created one.
         */
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
