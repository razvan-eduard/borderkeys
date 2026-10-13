// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data

/**
 * The engine's text rules, for the code outside the engine that keys what it keys. Every
 * declaration has a counterpart in `kTextMethods` in `jni_bridge.cpp`.
 */
internal object NativeText {

    init {
        System.loadLibrary("borderkeys")
    }

    /**
     * [text] in lower case as the engine keys a word (`lowerUtf8`), whatever its length; a text
     * holding a character past U+FFFF, which the engine keys no word with, comes back as it is.
     */
    external fun lowerCase(text: String): String
}
