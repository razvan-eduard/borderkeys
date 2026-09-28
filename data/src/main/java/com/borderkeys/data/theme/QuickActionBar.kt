// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * One button on the quick-action bar, resolved from a raw id: a [QuickAction] or one of the
 * user's [CustomQuickAction] macros, whose ids ([CustomQuickAction.nextId]) never collide.
 */
sealed interface QuickActionBarItem {
    data class Builtin(val action: QuickAction) : QuickActionBarItem
    data class Custom(val action: CustomQuickAction) : QuickActionBarItem
}

object QuickActionBar {
    /** [ids] resolved against both id spaces, in order, dropping any id neither knows. */
    fun resolve(ids: List<Int>, customActions: List<CustomQuickAction>): List<QuickActionBarItem> {
        val byId = customActions.associateBy { it.id }
        return ids.distinct().mapNotNull { id ->
            QuickAction.fromId(id)?.let { QuickActionBarItem.Builtin(it) }
                ?: byId[id]?.let { QuickActionBarItem.Custom(it) }
        }
    }

    /** [ids] that resolve against either id space, against an already-sanitised [customActions]. */
    fun sanitisedIds(ids: List<Int>, customActions: List<CustomQuickAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        return ids.distinct().filter { id -> QuickAction.fromId(id) != null || id in known }
    }

    /**
     * [action]'s steps expanded, recursively, to the [QuickAction]s to run, in order; an unknown
     * id is dropped. [seen] stops a reference cycle, and the result is capped at
     * [MAX_FLATTENED_STEPS].
     */
    fun flatten(
        action: CustomQuickAction,
        customActions: List<CustomQuickAction>,
        seen: MutableSet<Int> = mutableSetOf(),
    ): List<QuickAction> {
        if (!seen.add(action.id)) {
            return emptyList()
        }
        val byId = customActions.associateBy { it.id }
        val result = mutableListOf<QuickAction>()
        for (stepId in action.steps) {
            if (result.size >= MAX_FLATTENED_STEPS) {
                break
            }
            QuickAction.fromId(stepId)?.let { result.add(it) }
                ?: byId[stepId]?.let { result.addAll(flatten(it, customActions, seen)) }
        }
        return result
    }

    /**
     * Whether [candidate], a macro being created or edited, would close a reference cycle against
     * [existing], leaving out an older version of [candidate] there.
     */
    fun hasCycle(candidate: CustomQuickAction, existing: List<CustomQuickAction>): Boolean {
        val byId = (existing.filterNot { it.id == candidate.id } + candidate).associateBy { it.id }

        fun visits(id: Int, visiting: MutableSet<Int>): Boolean {
            val node = byId[id] ?: return false
            if (!visiting.add(id)) {
                return true
            }
            val closesLoop = node.steps.any { stepId -> byId.containsKey(stepId) && visits(stepId, visiting) }
            visiting.remove(id)
            return closesLoop
        }

        return visits(candidate.id, mutableSetOf())
    }

    /**
     * [steps] kept where they name a [QuickAction.macroEligible] built-in or another known custom
     * action, never [selfId], capped at [CustomQuickAction.MAX_STEPS]; cleared entirely if they
     * would still close a reference cycle.
     */
    fun sanitisedSteps(selfId: Int, steps: List<Int>, customActions: List<CustomQuickAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        val filtered = steps
            .filter { id -> id != selfId && (QuickAction.fromId(id)?.macroEligible == true || id in known) }
            .take(CustomQuickAction.MAX_STEPS)
        val candidate = CustomQuickAction(id = selfId, name = "", steps = filtered)
        return if (hasCycle(candidate, customActions)) emptyList() else filtered
    }

    /** [CustomQuickAction.MAX_STEPS] squared. */
    private const val MAX_FLATTENED_STEPS = CustomQuickAction.MAX_STEPS * CustomQuickAction.MAX_STEPS
}
