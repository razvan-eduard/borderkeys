// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * One button on the quick-action bar, resolved from a raw id: either a fixed [QuickAction] or
 * one of the user's own [CustomQuickAction] macros. [KeyboardPreferences.quickActions] stays a
 * plain `List<Int>` for both, the same trick [ComposerBar]/[ComposerBarItem] already play for the
 * draft box's bar: a custom action's id is drawn from a range ([CustomQuickAction.nextId]) that
 * can never collide with [QuickAction]'s own 1-17, so the two kinds share one ordered list
 * without the persisted shape changing at all. A separate type from [ComposerBarItem] rather than
 * a shared generic one, for the same reason [QuickAction] and [ComposerAction] already do not
 * share code beyond [idMatching]/[idsMatching]: two different enums, no common supertype to hang
 * a shared signature off.
 */
sealed interface QuickActionBarItem {
    data class Builtin(val action: QuickAction) : QuickActionBarItem
    data class Custom(val action: CustomQuickAction) : QuickActionBarItem
}

object QuickActionBar {
    /**
     * [ids] resolved against both id spaces, in order, dropping any id neither recognises --
     * exactly the fate [QuickAction.fromIds] already gives an id it alone does not recognise,
     * extended to also check [customActions].
     */
    fun resolve(ids: List<Int>, customActions: List<CustomQuickAction>): List<QuickActionBarItem> {
        val byId = customActions.associateBy { it.id }
        return ids.distinct().mapNotNull { id ->
            QuickAction.fromId(id)?.let { QuickActionBarItem.Builtin(it) }
                ?: byId[id]?.let { QuickActionBarItem.Custom(it) }
        }
    }

    /** [ids], kept only where they resolve against either id space -- the bar's own half of
     *  [KeyboardPreferences.sanitised], run against an already-sanitised [customActions]. */
    fun sanitisedIds(ids: List<Int>, customActions: List<CustomQuickAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        return ids.distinct().filter { id -> QuickAction.fromId(id) != null || id in known }
    }

    /**
     * [action]'s steps, expanded to the leaf [QuickAction]s to actually run, in the order they
     * should run in. A step naming another [CustomQuickAction] is expanded recursively; one
     * naming neither a known [QuickAction] nor a known custom action (a stale id from a macro
     * that referenced one since deleted) is silently dropped, the same way an unknown id anywhere
     * else in this file is.
     *
     * [seen] guards against a reference cycle that reached this call despite [hasCycle] having
     * been checked at save time -- a hand-edited or otherwise corrupted preferences file is not a
     * trusted file, and this is the one place that would otherwise recurse forever on one. The
     * result is also capped at [CustomQuickAction.MAX_STEPS] squared, which is generous for any
     * macro built through the picker (itself capped at [CustomQuickAction.MAX_STEPS] steps deep)
     * but still finite for a deeply-nested, acyclic chain crafted by hand.
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
     * Whether [candidate] -- a macro about to be created or edited -- would close a reference
     * cycle against [existing]. [existing] may already hold an older version of [candidate]'s own
     * id (mid-edit); that older version is excluded first, so a macro is checked against what it
     * is about to become, not what it used to be.
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
     * [steps] kept only where they name [selfId]'s own id (never itself), a
     * [QuickAction.macroEligible] built-in, or another known custom action in [customActions] --
     * capped at [CustomQuickAction.MAX_STEPS] -- and cleared entirely if, even after all of that,
     * they would still close a reference cycle. The defensive pass [KeyboardPreferences.sanitised]
     * runs over persisted data; the picker's own [hasCycle] check is what normally keeps a cycle
     * from being saved in the first place.
     */
    fun sanitisedSteps(selfId: Int, steps: List<Int>, customActions: List<CustomQuickAction>): List<Int> {
        val known = customActions.mapTo(HashSet()) { it.id }
        val filtered = steps
            .filter { id -> id != selfId && (QuickAction.fromId(id)?.macroEligible == true || id in known) }
            .take(CustomQuickAction.MAX_STEPS)
        val candidate = CustomQuickAction(id = selfId, name = "", steps = filtered)
        return if (hasCycle(candidate, customActions)) emptyList() else filtered
    }

    /** [CustomQuickAction.MAX_STEPS] squared: generous for anything the picker can build, still
     *  finite for a deep, acyclic chain assembled by hand. */
    private const val MAX_FLATTENED_STEPS = CustomQuickAction.MAX_STEPS * CustomQuickAction.MAX_STEPS
}
