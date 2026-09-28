// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.theme

/**
 * The `fromId`/`fromIds` lookup for an enum stored by a stable per-entry id, such as [QuickAction]
 * and [ComposerAction]; an unknown id is dropped.
 */
fun <T> idMatching(entries: Array<T>, id: Int, idOf: (T) -> Int): T? =
    entries.firstOrNull { idOf(it) == id }

/** Every id in [ids] resolved through [idMatching], dropping ones nothing here recognises. */
fun <T> idsMatching(entries: Array<T>, ids: List<Int>, idOf: (T) -> Int): List<T> =
    ids.mapNotNull { idMatching(entries, it, idOf) }.distinct()
