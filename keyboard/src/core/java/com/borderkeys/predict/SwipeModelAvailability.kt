// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * Whether tier B (the trained swipe decoder) is compiled into this build at all.
 *
 * A compile-time fact, not a runtime one, mirrored from `BORDERKEYS_NEURAL_SWIPE` in
 * `keyboard/src/main/cpp/CMakeLists.txt`. This is the `core`-flavor copy -- deliberately in
 * `src/core/java`, not `src/main/java`: Kotlin/Java source sets are additive per variant, not
 * override-by-replacement the way `assets`/`res` are, so a same-named class in both `main` and
 * `plus` would compile both into the `plus` variant and fail as a redeclaration. `src/plus/java`
 * carries the other half, and `:settings` depends on this rather than reading a
 * `BuildConfig.FLAVOR` string, so a flavor-specific setting stays expressed as "does this
 * capability exist" the same way `AssistClient.isAvailable()` already is, not as a name
 * comparison scattered through Compose code.
 */
object SwipeModelAvailability {
    val neuralSwipeModelSupported: Boolean = false
}
