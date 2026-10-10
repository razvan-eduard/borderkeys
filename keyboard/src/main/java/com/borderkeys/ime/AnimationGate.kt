// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.animation.ValueAnimator
import com.borderkeys.data.theme.EffectsSettings

/** Whether animations play now under an [EffectsSettings.animationMode], asked at draw time. */
object AnimationGate {
    fun plays(mode: Int): Boolean = when (mode) {
        EffectsSettings.MODE_OFF -> false
        EffectsSettings.MODE_ON -> true
        else -> ValueAnimator.areAnimatorsEnabled()
    }
}
