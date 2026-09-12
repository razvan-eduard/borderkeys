// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * One button on the draft box's bar, resolved from a raw id: either one of the fixed
 * [ComposerAction]s or one of the user's own [CustomAction]s. [KeyboardPreferences.composerBar]
 * stays a plain `List<Int>` for both -- a custom action's id is simply drawn from a range
 * ([CustomAction.nextId]) that can never collide with [ComposerAction]'s own 1-10, so the two
 * kinds share one ordered list without the persisted shape changing at all.
 */
sealed interface ComposerBarItem {
    data class Builtin(val action: ComposerAction) : ComposerBarItem
    data class Custom(val action: CustomAction) : ComposerBarItem
}

object ComposerBar {
    /**
     * [ids] resolved against both id spaces, in order, dropping any id neither recognises --
     * exactly the fate [ComposerAction.fromIds] already gives an id it alone does not
     * recognise, extended to also check [customActions].
     */
    fun resolve(ids: List<Int>, customActions: List<CustomAction>): List<ComposerBarItem> {
        val byId = customActions.associateBy { it.id }
        return ids.distinct().mapNotNull { id ->
            ComposerAction.fromId(id)?.let { ComposerBarItem.Builtin(it) }
                ?: byId[id]?.let { ComposerBarItem.Custom(it) }
        }
    }

    /** [ids], kept only where they resolve against either id space -- the bar's own half of
     *  [KeyboardPreferences.sanitised], run against an already-sanitised [customActions]. */
    fun sanitisedIds(ids: List<Int>, customActions: List<CustomAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        return ids.distinct().filter { id -> ComposerAction.fromId(id) != null || id in known }
    }
}
