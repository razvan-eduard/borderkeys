// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import com.borderkeys.data.theme.KeyboardTheme
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.data.theme.QuickActionBarItem
import com.borderkeys.data.theme.QuickTile
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.theme.ThemePaints
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
import java.time.Duration

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

    /** Attaches [view] to a window and places it at [width] by [height]. */
    private fun attach(view: View, width: Int, height: Int) {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        activity.setContentView(view, android.view.ViewGroup.LayoutParams(width, height))
        shadowOf(Looper.getMainLooper()).idle()
        place(view, width, height)
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

    /**
     * What a stroke on the keys came out as: each typed key, "held" and the key for a long press,
     * "flick" for a flick, and "swipe" for a decoded word.
     */
    private fun keysFor(stroke: (KeyboardCanvasView) -> Unit): List<String> {
        val keys = KeyboardCanvasView(context, paints, strings)
        keys.swipeEnabled = true
        attach(keys, 1080, 600)
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
            override fun onKeyLongPress(code: Int, keyIndex: Int): Boolean {
                out += "held ${code.toChar()}"
                return false
            }
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

    /** Presses at the first point, moves through the rest [SAMPLE_MILLIS] apart, and lifts at the last. */
    private fun stroke(view: View, points: List<Pair<Float, Float>>) {
        val down = SystemClock.uptimeMillis()
        val (x0, y0) = points.first()
        view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
        for ((i, point) in points.withIndex().drop(1)) {
            view.dispatchTouchEvent(
                MotionEvent.obtain(down, down + SAMPLE_MILLIS * i, MotionEvent.ACTION_MOVE, point.first, point.second, 0),
            )
        }
        val (x1, y1) = points.last()
        view.dispatchTouchEvent(MotionEvent.obtain(down, down + SAMPLE_MILLIS * points.size, MotionEvent.ACTION_UP, x1, y1, 0))
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
    fun `the samples after a leap move the press that went down there`() {
        val typed = keysFor { keys ->
            val g = keys.keyBounds('g'.code)!!
            val h = keys.keyBounds('h'.code)!!
            val down = SystemClock.uptimeMillis()
            keys.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, g.centerX(), g.centerY(), 0))
            // One move: the thumb on g holds still, the screen reports the other thumb, on h, as it,
            // and that thumb slides past the touch slop, still on h.
            val slid = h.centerX() + keys.touchSlopPx + 1
            var time = down + SAMPLE_MILLIS
            val move = MotionEvent.obtain(down, time, MotionEvent.ACTION_MOVE, g.centerX(), g.centerY(), 0)
            for (x in listOf(h.centerX(), slid)) {
                time += SAMPLE_MILLIS
                move.addBatch(time, x, h.centerY(), 1f, 1f, 0)
            }
            keys.dispatchTouchEvent(move)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(keys.longPressDelayMillis + 1))
            val up = SystemClock.uptimeMillis()
            keys.dispatchTouchEvent(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, slid, h.centerY(), 0))
        }
        assertEquals("the slide took the hold off h", listOf("g", "h"), typed)
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

    /**
     * The default panel on a phone, the tiles in [disabled] not allowed by the field; [onArranged]
     * gets each order the panel reports.
     */
    /** Gives [view] the default tiles, [disabled] ones dimmed, and returns the tiles it reports tapped. */
    private fun fillPanel(
        view: QuickSettingsView,
        disabled: Set<QuickTile> = emptySet(),
        onArranged: (List<QuickTile>) -> Unit = {},
    ): MutableList<QuickTile> {
        val tapped = mutableListOf<QuickTile>()
        view.listener = object : QuickSettingsView.Listener {
            override fun onTileTapped(tile: QuickTile) {
                tapped += tile
            }
            override fun onTilesArranged(tiles: List<QuickTile>) = onArranged(tiles)
            override fun onOpenFullSettings() = Unit
            override fun onCloseQuickSettings() = Unit
        }
        view.setState(
            tiles = QuickTile.DEFAULT.map { QuickSettingsView.TileState(it, false, enabled = it !in disabled) },
            available = QuickTile.entries.filterNot { it in QuickTile.DEFAULT },
            fullSettings = true,
            alignRight = true,
        )
        return tapped
    }

    private fun panel(
        disabled: Set<QuickTile> = emptySet(),
        onArranged: (List<QuickTile>) -> Unit = {},
    ): Pair<QuickSettingsView, MutableList<QuickTile>> {
        val view = QuickSettingsView(context, paints, strings)
        val tapped = fillPanel(view, disabled, onArranged)
        attach(view, PANEL_WIDTH, PANEL_HEIGHT)
        return view to tapped
    }

    /** One touchscreen event of [pointers], each a pointer id and where it is; [action] carries its pointer's index. */
    private fun fingers(action: Int, downTime: Long, pointers: List<Pair<Int, PointF>>): MotionEvent {
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = pointers.map { (_, at) ->
            MotionEvent.PointerCoords().apply {
                x = at.x
                y = at.y
            }
        }
        return MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, pointers.size,
            properties.toTypedArray(), coords.toTypedArray(),
            0, 0, SAMPLE_PRECISION, SAMPLE_PRECISION, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    /** The centre of the key typing [code], in [host]'s coordinates. */
    private fun keyIn(host: KeyboardHostView, code: Int): PointF {
        val bounds = checkNotNull(host.keyboard.keyBounds(code))
        return PointF(host.keyboard.left + bounds.centerX(), host.keyboard.top + bounds.centerY())
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
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OPEN_GUARD_MILLIS + 1))
        tap(view, resize.x, resize.y)
        assertEquals(listOf(QuickTile.RESIZE), tapped)
    }

    @Test
    fun `fingers already down on the keys when the panel opens tap no tile, and a fresh tap does`() {
        val host = KeyboardHostView(context, paints, strings)
        val tapped = fillPanel(host.quickSettings)
        host.measure(
            View.MeasureSpec.makeMeasureSpec(PANEL_WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        attach(host, PANEL_WIDTH, host.measuredHeight)
        val onG = keyIn(host, 'g'.code)
        val onH = keyIn(host, 'h'.code)
        val down = SystemClock.uptimeMillis()
        val secondFinger = SECOND_FINGER shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        host.dispatchTouchEvent(fingers(MotionEvent.ACTION_DOWN, down, listOf(FIRST_FINGER to onG)))
        host.dispatchTouchEvent(
            fingers(
                MotionEvent.ACTION_POINTER_DOWN or secondFinger, down,
                listOf(FIRST_FINGER to onG, SECOND_FINGER to onH),
            ),
        )
        host.showQuickSettings(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OPEN_GUARD_MILLIS + 1))
        val panel = host.quickSettings
        val tile = panel.cellCentre(QuickTile.DEFAULT.indexOf(QuickTile.RESIZE))
            .apply { offset(panel.left.toFloat(), panel.top.toFloat()) }
        val both = listOf(FIRST_FINGER to tile, SECOND_FINGER to tile)
        host.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, down, both))
        host.dispatchTouchEvent(fingers(MotionEvent.ACTION_POINTER_UP or secondFinger, down, both))
        host.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, down, listOf(FIRST_FINGER to tile)))
        assertEquals(emptyList<QuickTile>(), tapped)
        tap(host, tile.x, tile.y)
        assertEquals(listOf(QuickTile.RESIZE), tapped)
    }

    @Test
    fun `a move and lift the panel never saw pressed tap no tile`() {
        val (view, tapped) = panel()
        view.opened()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OPEN_GUARD_MILLIS + 1))
        val resize = view.cellCentre(QuickTile.DEFAULT.indexOf(QuickTile.RESIZE))
        val down = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(fingers(MotionEvent.ACTION_MOVE, down, listOf(FIRST_FINGER to resize)))
        view.dispatchTouchEvent(fingers(MotionEvent.ACTION_UP, down, listOf(FIRST_FINGER to resize)))
        assertEquals(emptyList<QuickTile>(), tapped)
    }

    @Test
    fun `a tile the field does not allow ignores a tap, and the one beside it takes it`() {
        val (view, tapped) = panel(disabled = setOf(QuickTile.RESIZE))
        view.opened()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OPEN_GUARD_MILLIS + 1))
        val resize = view.cellCentre(QuickTile.DEFAULT.indexOf(QuickTile.RESIZE))
        val dock = view.cellCentre(QuickTile.DEFAULT.indexOf(QuickTile.DOCK))
        tap(view, resize.x, resize.y)
        tap(view, dock.x, dock.y)
        assertEquals(listOf(QuickTile.DOCK), tapped)
    }

    @Test
    fun `in arrange mode a press on a tile's badge removes it, and a press on the tile keeps it`() {
        val arranged = mutableListOf<List<QuickTile>>()
        val (view, _) = panel(onArranged = { arranged += it })
        view.opened()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(OPEN_GUARD_MILLIS + 1))
        val first = view.cellCentre(0)
        hold(view, first.x, first.y, ViewConfiguration.getLongPressTimeout().toLong())

        val second = view.cellCentre(1)
        tap(view, second.x, second.y)
        assertTrue("no tile removed by a press on one", arranged.all { it.size == QuickTile.DEFAULT.size })
        val badge = view.badgeCentre(1)
        tap(view, badge.x, badge.y)
        assertEquals(QuickTile.DEFAULT - QuickTile.DEFAULT[1], arranged.last())
    }

    // ---- the clipboard panel ------------------------------------------------------------------

    @Test
    fun `the clipboard panel says the history is off while it is, and that it is empty otherwise`() {
        val panel = ClipboardPanelView(context, paints, strings)
        attach(panel, PANEL_WIDTH, PANEL_HEIGHT)
        panel.setEntries(emptyList(), emptyMap())
        assertEquals(strings[Keys.CLIPBOARD_EMPTY], panel.centredMessage)
        panel.historyOn = false
        assertEquals(strings[Keys.CLIPBOARD_HISTORY_OFF], panel.centredMessage)
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
        attach(strip, 1080, 140)
        return strip to events
    }

    /** Presses at ([x], [y]) until just past [delayMillis], then lifts. */
    private fun hold(view: View, x: Float, y: Float, delayMillis: Long = KeyboardCanvasView.LONG_PRESS_MILLIS) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delayMillis + 1))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + delayMillis + 1, MotionEvent.ACTION_UP, x, y, 0))
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

    private companion object {
        /** About how far apart a touchscreen's samples come. */
        const val SAMPLE_MILLIS = 8L

        /** A phone's width, and the height the keys leave a panel on it. */
        const val PANEL_WIDTH = 1080
        const val PANEL_HEIGHT = 572

        /** Two fingers' pointer ids, each also its index while both are down. */
        const val FIRST_FINGER = 0
        const val SECOND_FINGER = 1

        /** A test sample's precision: exact. */
        const val SAMPLE_PRECISION = 1f
    }
}
