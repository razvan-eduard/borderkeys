// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * One button on the draft box's bar, resolved from a raw id: a [ComposerAction] or one of the
 * user's [CustomAction]s, whose ids ([CustomAction.nextId]) never collide.
 */
sealed interface ComposerBarItem {
    data class Builtin(val action: ComposerAction) : ComposerBarItem
    data class Custom(val action: CustomAction) : ComposerBarItem
}

object ComposerBar {
    /** How many buttons the bar holds. */
    const val MAX_ITEMS = 8

    /**
     * [ids] resolved against both id spaces, in order, dropping any id neither knows and
     * [ComposerAction.INSERT].
     */
    fun resolve(ids: List<Int>, customActions: List<CustomAction>): List<ComposerBarItem> {
        val byId = customActions.associateBy { it.id }
        return ids.distinct().mapNotNull { id ->
            ComposerAction.fromId(id)
                ?.takeIf { it != ComposerAction.INSERT }
                ?.let { ComposerBarItem.Builtin(it) }
                ?: byId[id]?.let { ComposerBarItem.Custom(it) }
        }.take(MAX_ITEMS)
    }

    /**
     * [ids] that resolve against either id space, at most [MAX_ITEMS], against an
     * already-sanitised [customActions].
     */
    fun sanitisedIds(ids: List<Int>, customActions: List<CustomAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        return ids.distinct()
            .filter { id ->
                (ComposerAction.fromId(id) != null && id != ComposerAction.INSERT.id) || id in known
            }
            .take(MAX_ITEMS)
    }
}
