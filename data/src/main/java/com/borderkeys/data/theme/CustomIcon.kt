// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * A glyph a [CustomAction] or a [CustomQuickAction] can be given, so it reads as something other
 * than a nameless row on its bar the way a built-in [ComposerAction] or [QuickAction] does.
 *
 * Generic on purpose, and shared between both kinds of custom action: a composer action's
 * instruction is whatever the user wrote ("make it sound like a pirate"), and a quick-action
 * macro's name is whatever they called it ("select and cut") -- neither is one specific action the
 * way [ComposerAction]/[QuickAction]'s own dedicated glyphs are, so one set fits both rather than
 * duplicating it per feature. Was `ComposerIcon` until a second feature needed the same picker.
 *
 * Same shape as [ComposerAction]/[QuickAction] for the same reason: a stable per-entry [id],
 * read back through [fromId], so a future build adding or reordering entries here never turns
 * a saved choice into a different one -- an id nothing here recognises falls back to [DEFAULT].
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
