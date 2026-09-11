// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The `fromId`/`fromIds` pair every enum that persists its own choices as a stable per-entry id
 * -- [QuickAction], [ComposerAction] -- writes out identically otherwise: stable ids rather than
 * ordinals so reordering the enum or dropping an entry does not silently turn a saved list into a
 * different one, and an id nothing here recognises is simply dropped rather than failing to read
 * the rest.
 *
 * Free functions taking `entries` and an `idOf` selector rather than an interface both enums
 * implement: `Enum.entries` is a compiler-generated static property with no common supertype to
 * hang a shared signature off, so a shared implementation has to be handed what it needs rather
 * than asking for it.
 */
fun <T> idMatching(entries: Array<T>, id: Int, idOf: (T) -> Int): T? =
    entries.firstOrNull { idOf(it) == id }

/** Every id in [ids] resolved through [idMatching], dropping ones nothing here recognises. */
fun <T> idsMatching(entries: Array<T>, ids: List<Int>, idOf: (T) -> Int): List<T> =
    ids.mapNotNull { idMatching(entries, it, idOf) }.distinct()
