// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The settings screen's particle preview over the selected chip, fed by
 * `com.borderkeys.settings.ParticleChipPreview`: the chip is held, so the outline traces it and
 * the fill spawns inside it.
 */
class ParticlePreviewView(context: Context) : View(context) {

    init {
        // Decorative: hidden from accessibility.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val particles = ParticleSurface(CAPACITY, CAPACITY, onInvalidate = ::invalidate)

    /** The chip's shape in this view's pixels; null holds nothing. */
    var geometry: ParticleGeometry? = null

    /** The chip as the element the engine reads: whatever [geometry] currently says. */
    private val chip = object : ParticleElement() {
        override val geometry: ParticleGeometry?
            get() = this@ParticlePreviewView.geometry
    }

    // What the surface was last told to hold.
    private var lastHeld: Triple<ParticleGeometry?, Boolean, Boolean>? = null

    /** Re-holds the chip when its shape or either layer's enabled state changed. */
    fun retrace() {
        val key = Triple(geometry, particles.fill.enabled, particles.outline.enabled)
        if (key == lastHeld) {
            return
        }
        lastHeld = key
        if (geometry != null) {
            particles.hold(chip)
        } else {
            particles.release()
        }
    }

    override fun onDraw(canvas: Canvas) {
        particles.draw(canvas, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        particles.cancel()
    }

    private companion object {
        /** The most particles alive at once on each layer. */
        const val CAPACITY = 32
    }
}
