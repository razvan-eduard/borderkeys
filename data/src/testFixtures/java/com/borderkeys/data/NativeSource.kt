// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/** The native engine's sources, read from the repository the tests run in. */
object NativeSource {

    const val ENGINE_HEADER = "keyboard/src/main/cpp/engine.hpp"
    const val USER_MODEL_HEADER = "keyboard/src/main/cpp/user_model.hpp"
    const val BRIDGE_SLOTS_HEADER = "keyboard/src/main/cpp/jni_slots.hpp"
    const val PACK_FORMAT_HEADER = "keyboard/src/main/cpp/bkd_format.hpp"

    /** The text of [path], relative to the repository root. */
    fun read(path: String): String = RepositoryRoot.file(path).readText()

    /** The whole number the constant [name] is declared as in [path]; null when none is. */
    fun number(path: String, name: String): Int? =
        Regex("""\b$name\s*=\s*(-?\d+)""").find(read(path))?.groupValues?.get(1)?.toInt()
}
