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
