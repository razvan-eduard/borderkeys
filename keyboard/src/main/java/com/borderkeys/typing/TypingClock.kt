// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.typing

/** The two clocks the typing flow reads. */
interface TypingClock {
    /** Wall-clock milliseconds, for timestamps and the time between two key presses. */
    fun currentTimeMillis(): Long

    /** Milliseconds since boot, for the keyboard's stats. */
    fun uptimeMillis(): Long
}
