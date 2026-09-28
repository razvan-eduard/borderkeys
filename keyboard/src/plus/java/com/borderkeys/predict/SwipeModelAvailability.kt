// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.predict

/**
 * Whether tier B (the trained swipe decoder) is compiled into this build, mirroring
 * `BORDERKEYS_NEURAL_SWIPE` in `keyboard/src/main/cpp/CMakeLists.txt`. One copy per flavor.
 */
object SwipeModelAvailability {
    val neuralSwipeModelSupported: Boolean = true
}
