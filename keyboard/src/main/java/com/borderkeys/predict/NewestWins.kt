// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * Numbers the requests of one kind so that only the newest one's answer is used: [issue] gives a
 * request its number, [cancel] makes every number issued so far stale, and an answer is used only
 * while [isNewest] holds for its number. Not thread-safe; each owner keeps it on one thread or
 * under its own lock.
 */
internal class NewestWins {

    private var newest = 0

    /** The number of a new request, which makes every earlier number stale. */
    fun issue(): Int = ++newest

    /** Makes every number issued so far stale. */
    fun cancel() {
        newest++
    }

    /** Whether [number] is the last one issued, with no [cancel] since. */
    fun isNewest(number: Int): Boolean = number == newest
}
