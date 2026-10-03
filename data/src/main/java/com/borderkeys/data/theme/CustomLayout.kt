// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

import kotlinx.serialization.Serializable

/**
 * A layout the user wrote, as the JSON a layout asset holds, kept in the preferences so a backup
 * carries it. [id] is `custom-<n>`, stable once given: the heatmap keys its totals by it.
 */
@Serializable
data class CustomLayout(
    val id: String,
    val name: String,
    val languageTag: String = "und",
    val json: String,
) {
    /** Whether the fields are within their bounds; the JSON itself is checked by the keyboard's validator. */
    val isValid: Boolean
        get() = ID.matches(id) && name.isNotBlank() && name.length <= MAX_NAME_CHARS &&
            languageTag.length <= MAX_LANGUAGE_TAG_CHARS && json.isNotBlank() && json.length <= MAX_JSON_CHARS

    companion object {
        const val PREFIX = "custom-"
        const val MAX_NAME_CHARS = 40
        const val MAX_LANGUAGE_TAG_CHARS = 16
        const val MAX_JSON_CHARS = 40_000
        const val MAX_CUSTOM_LAYOUTS = 20

        private val ID = Regex("""^custom-\d{1,9}$""")

        fun isCustomId(layoutId: String): Boolean = ID.matches(layoutId)

        /** The valid layouts, one per id, the later one winning, bounded. */
        fun sanitised(layouts: List<CustomLayout>): List<CustomLayout> {
            val byId = LinkedHashMap<String, CustomLayout>()
            for (layout in layouts) {
                if (layout.isValid) {
                    byId[layout.id] = layout.copy(name = layout.name.trim())
                }
            }
            return byId.values.toList().take(MAX_CUSTOM_LAYOUTS)
        }

        /** A fresh id, one past the highest in [existing]. */
        fun nextId(existing: List<CustomLayout>): String {
            val highest = existing.mapNotNull { it.id.removePrefix(PREFIX).toIntOrNull() }.maxOrNull() ?: 0
            return "$PREFIX${highest + 1}"
        }

        /** The layout choices by subtype layout, both ids bounded and shaped like layout ids. */
        fun sanitisedChoices(choices: Map<String, String>): Map<String, String> =
            choices.entries
                .filter { (subtype, chosen) -> LAYOUT_ID.matches(subtype) && LAYOUT_ID.matches(chosen) && subtype != chosen }
                .take(MAX_CHOICES)
                .associate { it.key to it.value }

        private val LAYOUT_ID = Regex("""^[a-z0-9_-]{1,40}$""")
        const val MAX_CHOICES = 64
    }
}
