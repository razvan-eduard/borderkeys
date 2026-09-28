// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.assist

/**
 * The kinds of task that can run on different models: translation, and everything else. With two
 * or more models imported, each category can be pointed at one; see
 * [com.borderkeys.data.theme.KeyboardPreferences].
 */
enum class AssistCategory {
    WRITE,
    TRANSLATE,
}
