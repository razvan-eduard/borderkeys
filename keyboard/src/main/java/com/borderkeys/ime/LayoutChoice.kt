// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.CustomLayout

/**
 * Which layout a subtype draws: the one the user chose for it, a built-in by its asset id or one
 * of their own by its `custom-<n>` id, else the subtype's own asset. A custom layout that does
 * not parse falls back to the asset.
 */
object LayoutChoice {

    /** The layout a subtype names in its extra value's `layout=`; `*_qwerty`, none or empty is `qwerty`. */
    fun layoutIdOf(extraValue: String?): String {
        val id = extraValue?.split(",")?.firstOrNull { it.startsWith("layout=") }?.removePrefix("layout=").orEmpty()
        return if (id.isEmpty() || id.endsWith("_qwerty")) DEFAULT_LAYOUT else id
    }

    const val DEFAULT_LAYOUT = "qwerty"

    /**
     * The order the layout switch walks subtypes in: their indices into [drawn], the layout id
     * each draws, sorted by that id's place in [order], a layout [order] does not name after the
     * named ones, ties kept as given.
     */
    fun cycleOrder(drawn: List<String>, order: List<String>): List<Int> {
        val place = order.withIndex().associate { (index, id) -> id to index }
        return drawn.indices.sortedBy { place[drawn[it]] ?: Int.MAX_VALUE }
    }

    /** The choices in force: in [landscape], each of [landscapeChoices] over [choices]. */
    fun forOrientation(
        choices: Map<String, String>,
        landscapeChoices: Map<String, String>,
        landscape: Boolean,
    ): Map<String, String> = if (landscape && landscapeChoices.isNotEmpty()) choices + landscapeChoices else choices

    fun resolve(
        subtypeLayoutId: String,
        choices: Map<String, String>,
        customLayouts: List<CustomLayout>,
        loadAsset: (String) -> KeyboardLayout,
    ): KeyboardLayout {
        val chosen = choices[subtypeLayoutId] ?: return loadAsset(subtypeLayoutId)
        if (!CustomLayout.isCustomId(chosen)) {
            return loadAsset(chosen)
        }
        val custom = customLayouts.firstOrNull { it.id == chosen } ?: return loadAsset(subtypeLayoutId)
        val parsed = LayoutValidator.parse(custom.json) ?: return loadAsset(subtypeLayoutId)
        // Its own id, not the one written in the JSON.
        return KeyboardLayout(custom.id, custom.languageTag.ifEmpty { parsed.languageTag }, parsed.rows, parsed.modmap)
    }
}
