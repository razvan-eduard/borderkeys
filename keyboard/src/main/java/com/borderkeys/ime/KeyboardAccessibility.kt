// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Makes a keyboard drawn on a Canvas usable with a screen reader.
 *
 * Drawing the keys instead of building a view per key is what keeps `onDraw` under four
 * milliseconds, and it is also what makes the keyboard invisible to TalkBack: there is nothing
 * in the view tree to explore. Nothing about that trade is inevitable, but it has to be paid
 * back deliberately, and this is the payment -- a virtual view hierarchy served from the same
 * compiled geometry the drawing and the hit-testing use, so a key that is drawn is a key that
 * can be explored and there is no second layout to fall out of step.
 *
 * Written against `AccessibilityNodeProvider` from the framework rather than
 * `ExploreByTouchHelper`, which does the same job with less code but lives in
 * `androidx.customview` -- a dependency this module does not have, and one that would be added
 * for convenience rather than for a capability the framework lacks.
 *
 * How exploration works, since it drives every decision below: with a screen reader on, a
 * finger dragged across the keyboard produces hover events rather than touches. Each hover
 * moves accessibility focus to the key under it and the reader speaks that key. A double tap
 * anywhere then sends `ACTION_CLICK` to the focused key, and that is what types it. So the two
 * things that must be right are the bounds of every key and what each one is called.
 */
class KeyboardAccessibility(
    private val host: View,
    private val geometry: KeyboardGeometry,
) {

    /** Called when a virtual key is activated by the reader. */
    fun interface Listener {
        fun onAccessibilityKey(code: Int, keyIndex: Int)
    }

    var listener: Listener? = null

    private val manager =
        host.context.getSystemService(AccessibilityManager::class.java)

    /** The key the reader is focused on, or [HOST_ID] for the keyboard as a whole. */
    private var focusedKey = HOST_ID

    /** The key the finger is hovering over, which is not the same as what has focus. */
    private var hoveredKey = NO_KEY

    private val bounds = Rect()
    private val screenOffset = IntArray(2)

    /**
     * True when something is actually listening.
     *
     * Checked before every event is built, because building one allocates and the overwhelming
     * majority of sessions have no screen reader running. This is the only concession the
     * feature makes to the hot path, and it is a single boolean read.
     */
    private val isActive: Boolean
        get() = manager?.isEnabled == true && manager.isTouchExplorationEnabled

    val provider: AccessibilityNodeProvider = object : AccessibilityNodeProvider() {

        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == HOST_ID) {
                return hostNode()
            }
            if (virtualViewId < 0 || virtualViewId >= geometry.keyCount) {
                return null
            }
            return keyNode(virtualViewId)
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == HOST_ID) {
                return false
            }
            if (virtualViewId < 0 || virtualViewId >= geometry.keyCount) {
                return false
            }
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> {
                    listener?.onAccessibilityKey(geometry.keyCode[virtualViewId], virtualViewId)
                    sendEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    true
                }

                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (focusedKey != virtualViewId) {
                        focusedKey = virtualViewId
                        sendEvent(virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    }
                    true
                }

                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (focusedKey == virtualViewId) {
                        focusedKey = HOST_ID
                        sendEvent(virtualViewId,
                            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    true
                }

                else -> false
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && focusedKey != HOST_ID) {
                createAccessibilityNodeInfo(focusedKey)
            } else {
                null
            }
    }

    /**
     * Routes a hover to the key under it.
     *
     * Returns true when it was consumed, which the view reports back to the framework. Without
     * this the reader has no way to tell which key a finger is over, and exploration reads the
     * whole keyboard as one object.
     */
    fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!isActive || geometry.keyCount == 0) {
            return false
        }
        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val key = geometry.findKeyAt(event.x, event.y)
                if (key != hoveredKey) {
                    if (hoveredKey != NO_KEY) {
                        sendEvent(hoveredKey, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    }
                    hoveredKey = key
                    if (key != NO_KEY) {
                        sendEvent(key, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                    }
                }
                key != NO_KEY
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                if (hoveredKey != NO_KEY) {
                    sendEvent(hoveredKey, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                    hoveredKey = NO_KEY
                }
                true
            }

            else -> false
        }
    }

    /** The geometry was recompiled, so every virtual view moved. */
    fun onGeometryChanged() {
        focusedKey = HOST_ID
        hoveredKey = NO_KEY
        if (isActive) {
            host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    }

    private fun hostNode(): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(host)
        host.onInitializeAccessibilityNodeInfo(node)
        node.className = KEYBOARD_CLASS
        for (index in 0 until geometry.keyCount) {
            node.addChild(host, index)
        }
        return node
    }

    private fun keyNode(index: Int): AccessibilityNodeInfo {
        val node = AccessibilityNodeInfo.obtain(host, index)
        node.packageName = host.context.packageName
        node.className = KEY_CLASS
        node.contentDescription = describe(index)
        node.isEnabled = true
        node.isVisibleToUser = true
        node.isClickable = true
        node.isFocusable = true
        node.setParent(host)
        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        node.addAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
        )
        node.isAccessibilityFocused = focusedKey == index

        bounds.set(
            geometry.keyLeft[index].toInt(), geometry.keyTop[index].toInt(),
            geometry.keyRight[index].toInt(), geometry.keyBottom[index].toInt(),
        )
        node.setBoundsInParent(bounds)
        host.getLocationOnScreen(screenOffset)
        bounds.offset(screenOffset[0], screenOffset[1])
        node.setBoundsInScreen(bounds)
        return node
    }

    /**
     * What a key is called out loud.
     *
     * The label is right for a letter and useless for everything else: a screen reader saying
     * "shift" is helpful and one saying the name of a glyph nobody can see is not. Keys that
     * carry alternates say so, because a long press is otherwise undiscoverable without sight.
     */
    private fun describe(index: Int): CharSequence {
        val code = geometry.keyCode[index]
        val named = when (code) {
            KeyCodes.SHIFT -> "Shift"
            KeyCodes.DELETE -> "Delete"
            KeyCodes.ENTER -> "Enter"
            KeyCodes.SPACE -> "Space"
            KeyCodes.SYMBOLS -> "Symbols"
            KeyCodes.SYMBOLS_SHIFT -> "More symbols"
            KeyCodes.LANGUAGE -> "Language. Hold for keyboard settings"
            KeyCodes.SETTINGS -> "Settings"
            KeyCodes.EMOJI -> "Emoji"
            else -> null
        }
        if (named != null) {
            return named
        }
        val length = geometry.labelLength[index]
        val label = if (length > 0) {
            String(geometry.labelChars, geometry.labelOffset[index], length)
        } else {
            ""
        }
        val alternates = geometry.altLength[index]
        return if (alternates > 0) {
            "$label. Hold for $alternates more"
        } else {
            label
        }
    }

    private fun sendEvent(virtualViewId: Int, eventType: Int) {
        if (!isActive) {
            return
        }
        val event = AccessibilityEvent.obtain(eventType)
        event.packageName = host.context.packageName
        event.className = KEY_CLASS
        event.setSource(host, virtualViewId)
        event.text.add(describe(virtualViewId))
        host.parent?.requestSendAccessibilityEvent(host, event)
    }

    private companion object {
        const val HOST_ID = AccessibilityNodeProvider.HOST_VIEW_ID
        const val NO_KEY = KeyboardCanvasView.NO_KEY

        // The classes a screen reader recognises as a keyboard and a key, which is what makes it
        // announce them the way it announces every other keyboard rather than as generic views.
        const val KEYBOARD_CLASS = "android.inputmethodservice.Keyboard"
        const val KEY_CLASS = "android.inputmethodservice.Keyboard\$Key"
    }
}
