// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

/**
 * The two extra fields under "Try it here" in debuggable builds, a password field and a field
 * of several lines, named for the smoke suite. Each carries its name and then its exact text
 * in its content description, so the suite reads a password field's text where the
 * accessibility tree shows it masked.
 */
object ProbeFields {
    const val PASSWORD = "probe-password:"
    const val LINES = "probe-lines:"
}
