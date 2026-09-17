// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime.fx

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * A tiny, decorative particle loop traced around or filled inside a settings chip -- for
 * `:settings`' own "this is what the style you picked actually looks like" preview, sitting over
 * whichever chip is currently selected. Not the real keyboard: owns its own [ParticleSurface]
 * sized to nothing but chip-sized shapes, and never receives a key press or a suggestion -- see
 * `com.borderkeys.settings.ParticleChipPreview` for the Compose side that feeds it a live
 * [com.borderkeys.data.theme.ParticleOutlineLayer] or [com.borderkeys.data.theme.ParticleFillLayer].
 *
 * The chip is one [ParticleElement] like any key or wedge: it is *held* for as long as it is
 * selected, so outline traces its perimeter and fill spawns inside it -- the same two placements
 * every real region gets, from the same engine, just at chip size. The preview cannot drift from
 * what ships because it does not have an animation of its own.
 */
class ParticlePreviewView(context: Context) : View(context) {

    init {
        // Purely decorative, and drawn on top of the chip it belongs to (see ParticleChipPreview's
        // own doc) so that fill particles are actually visible rather than hidden behind an
        // opaque container. Without this, a real View sitting over a Compose sibling's bounds
        // reads to the accessibility tree as occluding it -- the chip's own label would stop
        // being announced, not merely stop being drawn, for whichever style is selected.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val particles = ParticleSurface(CAPACITY, CAPACITY, onInvalidate = ::invalidate)

    /**
     * The real chip's real shape, already resolved to this view's pixel bounds by
     * [com.borderkeys.settings.ParticleChipPreview] -- a Compose `Shape` is not something a plain
     * `View` can read on its own, so it arrives finished. `null` (nothing measured yet, or no
     * style selected) is harmless: nothing is held until both a size and a style exist.
     */
    var geometry: ParticleGeometry? = null

    /** The chip as the element the engine reads: whatever [geometry] currently says. */
    private val chip = object : ParticleElement() {
        override val geometry: ParticleGeometry?
            get() = this@ParticlePreviewView.geometry
    }

    // What the surface was last told to hold. Re-holding on every Compose recomposition rather
    // than only on a real change would reset a travelling preset's (Comet's) phase back to its
    // starting point on every one, per ParticleSimulation.setAmbientRectanglePerimeter's own doc
    // -- visible as a stutter rather than the continuous loop this is meant to be.
    private var lastHeld: Triple<ParticleGeometry?, Boolean, Boolean>? = null

    /** Re-holds the chip exactly when its shape or either layer's enabled state actually changed
     *  since the last call -- cheap enough to call after every config update unconditionally. */
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
        /** A chip's outline is short and its area small -- the extent scaling never lifts a
         *  preset above its base cap here, and Sparkle's (28) is the largest of those. */
        const val CAPACITY = 32
    }
}
