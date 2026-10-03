// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import com.borderkeys.data.theme.CustomLayout

/**
 * Which layout a subtype draws: the one the user chose for it, a built-in by its asset id or one
 * of their own by its `custom-<n>` id, else the subtype's own asset. A custom layout that no
 * longer parses falls back to the asset, so the keyboard always draws.
 */
object LayoutChoice {

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
        // Its own id, not the one written in the JSON: the heatmap keys its totals by it.
        return KeyboardLayout(custom.id, custom.languageTag.ifEmpty { parsed.languageTag }, parsed.rows)
    }
}
