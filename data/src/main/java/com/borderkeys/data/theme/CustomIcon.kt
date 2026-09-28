// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * A glyph a [CustomAction] or a [CustomQuickAction] can be given, stored by [id]; an unknown id
 * reads as [DEFAULT].
 */
enum class CustomIcon(val id: Int) {
    WAND(1),
    CHAT(2),
    STAR(3),
    TAG(4),
    QUOTE(5),
    PENCIL(6),
    BOOK(7),
    GLOBE(8),
    LIGHTBULB(9),
    FLAG(10),
    REFRESH(11),
    CHECK(12),
    MEGAPHONE(13),
    HEART(14),
    COMPASS(15),
    BOOKMARK(16),
    ;

    companion object {
        val DEFAULT: CustomIcon = WAND

        fun fromId(id: Int): CustomIcon = idMatching(entries.toTypedArray(), id) { it.id } ?: DEFAULT
    }
}
