// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBarItem
import com.borderkeys.data.theme.QuickTile
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.theme.ThemePaints
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The keyboard's own views, measured, drawn and touched on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardViewsTest {

    private lateinit var context: Context
    private lateinit var paints: ThemePaints
    private lateinit var strings: LanguageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        paints = ThemePaints().also { it.update(KeyboardTheme(), context.resources.displayMetrics) }
        strings = LanguageManager(context)
    }

    private fun place(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun tap(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    // ---- the key labels --------------------------------------------------------------------

    @Test
    fun `drawing the keys leaves the label paint at the theme's size`() {
        val keys = KeyboardCanvasView(context, paints, strings)
        // Narrow, so the labels are shrunk to fit their keys.
        place(keys, 120, 500)
        keys.draw(Canvas(Bitmap.createBitmap(120, 500, Bitmap.Config.ARGB_8888)))
        assertEquals(paints.labelTextSizePx, paints.label.textSize, 0f)
    }

    @Test
    fun `labels measured after a draw are the size they were before it`() {
        val keys = KeyboardCanvasView(context, paints, strings)
        place(keys, 120, 500)
        val before = (0 until keys.keyCount).map(keys::labelTextSizeAt)
        assertTrue("no label shrunk: $before", before.any { it < paints.labelTextSizePx })
        keys.draw(Canvas(Bitmap.createBitmap(120, 500, Bitmap.Config.ARGB_8888)))
        // A size change compiles the layout again and measures every label anew.
        place(keys, 120, 520)
        val after = (0 until keys.keyCount).map(keys::labelTextSizeAt)
        assertEquals(before, after)
    }

    @Test
    fun `a label size another view left on the paint does not reach the keys`() {
        val keys = KeyboardCanvasView(context, paints, strings)
        place(keys, 1080, 600)
        val before = (0 until keys.keyCount).map(keys::labelTextSizeAt)
        paints.label.textSize = paints.labelTextSizePx / 2f
        place(keys, 1080, 620)
        assertEquals(before, (0 until keys.keyCount).map(keys::labelTextSizeAt))
    }

    // ---- telling taps from swipes -----------------------------------------------------------

    /** What a stroke on the keys came out as: each typed key, and "swipe" for a decoded word. */
    private fun keysFor(stroke: (KeyboardCanvasView) -> Unit): List<String> {
        val keys = KeyboardCanvasView(context, paints, strings)
        keys.swipeEnabled = true
        place(keys, 1080, 600)
        val out = mutableListOf<String>()
        keys.listener = object : KeyboardCanvasView.Listener {
            override fun onKey(code: Int, keyIndex: Int, x: Float, y: Float) {
                out += code.toChar().toString()
            }
            override fun onKeyRepeat(code: Int) = Unit
            override fun onText(text: CharSequence) = Unit
            override fun onKeyDown(code: Int) = Unit
            override fun onGesture(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) {
                out += "swipe"
            }
            override fun onGesturePaused(xs: FloatArray, ys: FloatArray, timestamps: LongArray, count: Int) = Unit
            override fun onGestureSteered(x: Float, y: Float) = Unit
            override fun onGestureRingResolved() = Unit
            override fun onGestureRingCancelled() = Unit
            override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean = false
            override fun onCursorNudge(steps: Int) = Unit
            override fun onCursorNudgeLines(lines: Int) = Unit
            override fun onFlick(keyIndex: Int, direction: Int) {
                out += "flick"
            }
            override fun onBackspaceSelect(steps: Int) = Unit
            override fun onBackspaceSelectLines(lines: Int) = Unit
            override fun onBackspaceSelectionLift() = Unit
        }
        stroke(keys)
        return out
    }

    /** Presses at the first point, moves through the rest 8 ms apart, and lifts at the last. */
    private fun stroke(view: View, points: List<Pair<Float, Float>>) {
        val down = SystemClock.uptimeMillis()
        val (x0, y0) = points.first()
        view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
        for ((i, point) in points.withIndex().drop(1)) {
            view.dispatchTouchEvent(
                MotionEvent.obtain(down, down + 8L * i, MotionEvent.ACTION_MOVE, point.first, point.second, 0),
            )
        }
        val (x1, y1) = points.last()
        view.dispatchTouchEvent(MotionEvent.obtain(down, down + 8L * points.size, MotionEvent.ACTION_UP, x1, y1, 0))
    }

    @Test
    fun `a press that leaps a key's width between two samples types both keys and is no swipe`() {
        val typed = keysFor { keys ->
            val g = keys.keyBounds('g'.code)!!
            val h = keys.keyBounds('h'.code)!!
            // The thumb on g holds still; the screen then reports the other thumb, on h, as it.
            val still = List(6) { g.centerX() + it % 2 to g.centerY() }
            stroke(keys, still + List(3) { h.centerX() to h.centerY() })
        }
        assertEquals(listOf("g", "h"), typed)
    }

    @Test
    fun `a tap that slips half a key into its neighbour types its own key`() {
        val typed = keysFor { keys ->
            val g = keys.keyBounds('g'.code)!!
            val h = keys.keyBounds('h'.code)!!
            val from = g.right - g.width() * 0.15f
            val to = h.left + h.width() * 0.4f
            stroke(keys, (0..12).map { from + (to - from) * it / 12f to g.centerY() })
        }
        assertEquals(listOf("g"), typed)
    }

    @Test
    fun `a short swipe from a key's centre to its neighbour's is still a word`() {
        val typed = keysFor { keys ->
            val a = keys.keyBounds('a'.code)!!
            val s = keys.keyBounds('s'.code)!!
            val to = s.centerX() + s.width() * 0.05f
            stroke(keys, (0..16).map { a.centerX() + (to - a.centerX()) * it / 16f to a.centerY() })
        }
        assertEquals(listOf("swipe"), typed)
    }

    @Test
    fun `a fast swipe, its steps growing past a key's width once moving, is not split`() {
        val typed = keysFor { keys ->
            val g = keys.keyBounds('g'.code)!!
            val l = keys.keyBounds('l'.code)!!
            val w = g.width()
            // From rest: small steps, then steps longer than a key once the finger is moving.
            val xs = listOf(0f, 0.1f, 0.3f, 0.7f, 1.5f, 2.8f).map { g.centerX() + it * w }
            stroke(keys, xs.map { it.coerceAtMost(l.centerX()) to g.centerY() })
        }
        assertEquals(listOf("swipe"), typed)
    }

    // ---- the quick panel -------------------------------------------------------------------

    private fun panel(): Pair<QuickSettingsView, MutableList<QuickTile>> {
        val tapped = mutableListOf<QuickTile>()
        val view = QuickSettingsView(context, paints, strings)
        view.listener = object : QuickSettingsView.Listener {
            override fun onTileTapped(tile: QuickTile) {
                tapped += tile
            }
            override fun onTilesArranged(tiles: List<QuickTile>) = Unit
            override fun onOpenFullSettings() = Unit
            override fun onCloseQuickSettings() = Unit
        }
        view.setState(
            tiles = QuickTile.DEFAULT.map { QuickSettingsView.TileState(it, false) },
            available = QuickTile.entries.filterNot { it in QuickTile.DEFAULT },
            fullSettings = true,
            alignRight = true,
        )
        // The height the keys leave the panel on a phone.
        place(view, 1080, 572)
        return view to tapped
    }

    @Test
    fun `the default tiles and the add tile all fit a keyboard's height`() {
        val (view, _) = panel()
        assertTrue("capacity ${view.tileCapacity}", view.tileCapacity >= QuickTile.DEFAULT.size + 1)
    }

    @Test
    fun `a tap right after the panel opens is ignored, and a later one lands`() {
        val (view, tapped) = panel()
        view.opened()
        val resize = view.cellCentre(0)
        tap(view, resize.x, resize.y)
        assertEquals(emptyList<QuickTile>(), tapped)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        tap(view, resize.x, resize.y)
        assertEquals(listOf(QuickTile.RESIZE), tapped)
    }

    // ---- the suggestion strip's chips -------------------------------------------------------

    /** A strip with the screenshot chip in the first slot and the clipboard chip in the second. */
    private fun chipStrip(): Pair<SuggestionStripView, MutableList<String>> {
        val strip = SuggestionStripView(context, paints, strings)
        val events = mutableListOf<String>()
        strip.listener = object : SuggestionStripView.Listener {
            override fun onSuggestionPicked(index: Int, word: String) {
                events += "picked $word"
            }
            override fun onPrivateRevealToggled() = Unit
            override fun onSuggestionLongPressed(index: Int, word: String) {
                events += "held $word"
            }
            override fun onActionPicked(index: Int) {
                events += "action $index"
            }
            override fun onClipboardPicked() {
                events += "clipboard"
            }
            override fun onScreenshotPicked() {
                events += "screenshot"
            }
            override fun onScreenshotLongPressed() {
                events += "screenshot held"
            }
        }
        strip.screenshotFirst = true
        strip.screenshotChip = "Screenshot"
        strip.clipboardChip = "copied"
        // Attached, so the hold's delayed check runs.
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(strip, android.view.ViewGroup.LayoutParams(1080, 140))
        shadowOf(Looper.getMainLooper()).idle()
        place(strip, 1080, 140)
        return strip to events
    }

    /** Presses at ([x], [y]) past the long-press time, then lifts. */
    private fun hold(view: View, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeyboardCanvasView.LONG_PRESS_MILLIS + 50))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 500, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun `holding the screenshot chip asks to dismiss it, and the lift pastes nothing`() {
        val (strip, events) = chipStrip()
        hold(strip, 270f, 70f)
        assertEquals(listOf("screenshot held"), events)
    }

    @Test
    fun `a tap on the screenshot chip still pastes it`() {
        val (strip, events) = chipStrip()
        tap(strip, 270f, 70f)
        assertEquals(listOf("screenshot"), events)
    }

    @Test
    fun `the clipboard chip has no hold, so holding it is a tap`() {
        val (strip, events) = chipStrip()
        hold(strip, 810f, 70f)
        assertEquals(listOf("clipboard"), events)
    }

    // ---- the quick actions bar -------------------------------------------------------------

    @Test
    fun `a button the field does not allow takes no tap, and an allowed one does`() {
        val ran = mutableListOf<QuickActionBarItem>()
        val bar = QuickActionsView(context, paints, strings)
        bar.listener = object : QuickActionsView.Listener {
            override fun onQuickAction(item: QuickActionBarItem) {
                ran += item
            }
        }
        val paste = QuickActionBarItem.Builtin(QuickAction.PASTE)
        val copy = QuickActionBarItem.Builtin(QuickAction.COPY_ALL)
        // Always open, as the Quick actions screen's first choice; collapsed it shows one opener.
        bar.collapsible = false
        bar.items = listOf(paste, copy)
        place(bar, 1080, 150)
        val copyAt = bar.buttonCentre(1)
        val pasteAt = bar.buttonCentre(0)
        // Allowed, the tap on Copy runs it: the taps land where the buttons are.
        tap(bar, copyAt.x, copyAt.y)
        assertEquals(listOf<QuickActionBarItem>(copy), ran)
        ran.clear()
        bar.allowed = { it == paste }
        tap(bar, copyAt.x, copyAt.y)
        assertEquals(emptyList<QuickActionBarItem>(), ran)
        tap(bar, pasteAt.x, pasteAt.y)
        assertEquals(listOf<QuickActionBarItem>(paste), ran)
    }
}
