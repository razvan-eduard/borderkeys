// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.settings.ProbeFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * The keyboard driven through a real input connection: keys tapped by their accessibility
 * nodes, the field read back through its own node. Every case here is a path a JVM test
 * cannot reach, because the editor on the other side of the connection is the framework's.
 *
 * The English pack is installed from the bundled assets and autocorrect is switched on before
 * the keyboard is selected, so each case starts from the same keyboard.
 */
@RunWith(AndroidJUnit4::class)
class ImeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val imeId = ComponentName(context.packageName, "com.borderkeys.ime.BorderKeysService").flattenToShortString()

    @Before
    fun prepare() {
        DataGraph.install(context.applicationContext)
        installBundledPack(ENGLISH)
        // The keyboard is taken down before its dictionary is cleaned and its preferences set,
        // and brought back afterwards, so each case starts from a keyboard that has read both.
        device.executeShellCommand("ime disable $imeId")
        runBlocking {
            for (word in TYPED_WORDS) {
                DataGraph.dictionary.forget(word)
            }
            DataGraph.themes.updatePreferences {
                it.copy(
                    learningEnabled = false,
                    autoCorrectOnSpace = true,
                    revertCorrectionOnBackspace = true,
                    autoCapitalise = false,
                    spaceAfterSuggestion = true,
                    radialMenuEnabled = false,
                    showSuggestionStrip = true,
                    quickActionsMode = KeyboardPreferences.QUICK_ACTIONS_COLLAPSED,
                    preferredLanguageTag = "",
                    numberRow = false,
                    modifierRow = false,
                )
            }
        }
        device.executeShellCommand("ime enable $imeId")
        selectKeyboard()
        openProbeField()
    }

    /** Makes the keyboard the selected input method, asking again when the first ask is not answered in time. */
    private fun selectKeyboard() {
        repeat(SELECT_ATTEMPTS) {
            device.executeShellCommand("ime set $imeId")
            val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT
            while (System.currentTimeMillis() < deadline) {
                if (keyboardSelected()) {
                    return
                }
                Thread.sleep(SETTLE_MILLIS)
            }
        }
        check(keyboardSelected()) { "timed out waiting until the keyboard is the selected input method" }
    }

    @Test
    fun aMistypedWordIsCorrectedBySpace() {
        type("teh")
        settle()
        tapKey(SPACE)
        assertField("the ")
    }

    @Test
    fun backspacePutsTheTypedWordBack() {
        type("teh")
        settle()
        tapKey(SPACE)
        assertField("the ")
        tapKey(DELETE)
        assertField("teh ")
    }

    @Test
    fun typingAfterACaretMoveKeepsEveryWord() {
        type("abc")
        tapKey(SPACE)
        type("def")
        settle()
        repeat(4) { device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        settle()
        type("x")
        assertField("abcx def")
    }

    @Test
    fun withShiftLockedASlideAlongTheSpaceBarSelects() {
        type("abc")
        settle()
        val shift = device.wait(Until.findObject(keyMatcher(SHIFT)), KEY_TIMEOUT)
        assertNotNull("the shift key", shift)
        shift.longClick()
        settle()
        val space = device.wait(Until.findObject(keyMatcher(SPACE)), KEY_TIMEOUT)
        assertNotNull("the space bar", space)
        val bar = space.visibleBounds
        device.swipe(bar.right - SLIDE_INSET_PX, bar.centerY(), bar.left + SLIDE_INSET_PX, bar.centerY(), SLIDE_STEPS)
        settle()
        type("x")
        assertField("X")
    }

    @Test
    fun controlOnTheModifierRowSelectsAllWithA() {
        runBlocking { DataGraph.themes.updatePreferences { it.copy(modifierRow = true) } }
        settle()
        type("abc")
        settle()
        tapKey(CONTROL)
        tapKey("a")
        settle()
        type("x")
        assertField("x")
    }

    @Test
    fun aSwipeAcrossTheKeysTypesTheWord() {
        swipe(listOf(keyCentre("t"), keyCentre("h"), keyCentre("e")))
        settle()
        tapKey(SPACE)
        assertField("the ")
    }

    @Test
    fun aPauseInASwipeOpensTheRingAndTheLiftPicksTheTopWord() {
        runBlocking { DataGraph.themes.updatePreferences { it.copy(radialMenuEnabled = true) } }
        settle()
        val end = keyCentre("e")
        val row = device.findObject(keyMatcher("q")).visibleBounds.height()
        val reach = (row * RING_TOP_WEDGE_ROWS).toInt()
        val topWedge = Point(end.x + reach, end.y - reach)
        val path = mutableListOf(keyCentre("t"), keyCentre("h"), end)
        repeat(RING_PAUSE_STEPS) { path += end }
        path += topWedge
        swipe(path)
        settle()
        assertTrue("the ring's top word was picked", field().text.orEmpty().startsWith("the"))
    }

    @Test
    fun aPasswordFieldIsNeverCorrected() {
        focusProbe(ProbeFields.PASSWORD)
        type("teh")
        settle()
        tapKey(SPACE)
        settle()
        assertEquals("teh ", probeText(ProbeFields.PASSWORD))
    }

    @Test
    fun aSlideUpTheSpaceBarMovesTheCaretALine() {
        focusProbe(ProbeFields.LINES)
        type("ab")
        tapKey(ENTER)
        type("cd")
        settle()
        val space = device.wait(Until.findObject(keyMatcher(SPACE)), KEY_TIMEOUT)
        assertNotNull("the space bar", space)
        val bar = space.visibleBounds
        val row = device.findObject(keyMatcher("q")).visibleBounds.height()
        device.swipe(bar.centerX(), bar.centerY(), bar.centerX(), bar.centerY() - row, SLIDE_STEPS)
        settle()
        type("x")
        settle()
        assertEquals("abxcd", probeText(ProbeFields.LINES).replace("\n", ""))
    }

    @Test
    fun aChipPickedWithTheCaretAtZeroKeepsTheNextTypedWord() {
        type("teh")
        settle()
        val typed = field().text.orEmpty()
        device.pressKeyCode(KeyEvent.KEYCODE_MOVE_HOME)
        settle()
        val picked = tapFirstChip()
        settle()
        type("ab")
        tapKey(SPACE)
        assertField("$picked ab $typed")
    }

    // ---- driving the keyboard ---------------------------------------------------------------

    /**
     * Focuses one of the debuggable build's extra probe fields under "Try it here", named by
     * the [ProbeFields] prefix its content description starts with, keyboard up.
     */
    private fun focusProbe(name: String) {
        val field = device.wait(Until.findObject(By.descStartsWith(name)), KEY_TIMEOUT)
        assertNotNull("the $name field", field)
        field.click()
        assertTrue("the keyboard over $name", device.wait(Until.hasObject(keyMatcher("q")), KEY_TIMEOUT))
        settle()
    }

    /** What the probe field named [name] holds, exactly, read from its content description. */
    private fun probeText(name: String): String {
        val field = device.findObject(By.descStartsWith(name))
        assertNotNull("the $name field", field)
        return field.contentDescription.orEmpty().removePrefix(name)
    }

    private fun keyCentre(name: String): Point {
        val key = device.wait(Until.findObject(keyMatcher(name)), KEY_TIMEOUT)
        assertNotNull("key $name", key)
        val bounds = key.visibleBounds
        return Point(bounds.centerX(), bounds.centerY())
    }

    /** One finger through [points] in order, [SWIPE_SEGMENT_STEPS] moves between each pair. */
    private fun swipe(points: List<Point>) {
        device.swipe(points.toTypedArray(), SWIPE_SEGMENT_STEPS)
    }

    private fun openProbeField() {
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .setClassName(context.packageName, SETTINGS_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        assertNotNull(
            "the settings probe field",
            device.wait(Until.findObject(By.clazz(EDIT_TEXT)), LAUNCH_TIMEOUT),
        )
        device.waitForIdle(SETTLE_MILLIS)
        // The activity is still settling when the field first appears, so a node found then
        // can go stale before it is used; each step finds it afresh.
        repeat(ATTEMPTS) { attempt ->
            try {
                val field = field()
                if (field.text.orEmpty().isNotEmpty()) {
                    field.text = ""
                }
                field.click()
            } catch (stale: StaleObjectException) {
                device.waitForIdle(SETTLE_MILLIS)
            }
            if (device.wait(Until.hasObject(keyMatcher(SPACE)), KEY_TIMEOUT)) {
                return
            }
            check(attempt < ATTEMPTS - 1) { "the keyboard did not come up over the probe field" }
        }
    }

    private fun keyboardSelected(): Boolean =
        device.executeShellCommand("dumpsys input_method").contains("mCurMethodId=$imeId")


    private fun field(): UiObject2 {
        val field = device.findObject(By.clazz(EDIT_TEXT).focused(true))
            ?: device.wait(Until.findObject(By.clazz(EDIT_TEXT)), KEY_TIMEOUT)
        assertNotNull("the probe field", field)
        return field
    }

    private fun assertField(expected: String) {
        settle()
        assertEquals(expected, field().text.orEmpty())
    }

    private fun type(word: String) {
        for (letter in word) {
            tapKey(letter.toString())
        }
    }

    /** A key by what it is called to a screen reader: its label, or the key's name. */
    private fun keyMatcher(name: String) =
        By.desc(Pattern.compile("^" + Pattern.quote(name) + "(\\..*)?$"))

    private fun tapKey(name: String) {
        val key = device.wait(Until.findObject(keyMatcher(name)), KEY_TIMEOUT)
        assertNotNull("key $name", key)
        key.click()
    }

    /**
     * Taps the first suggestion on the strip and returns the word it committed. The strip
     * draws its chips itself, so the first slot is found from the keyboard's own geometry:
     * the band above the top key row, in its left third.
     */
    private fun tapFirstChip(): String {
        val topRow = device.findObject(keyMatcher("q"))
        assertNotNull("the top key row", topRow)
        val keys = topRow.visibleBounds
        val windowTop = device.findObjects(By.pkg(context.packageName)).minOf { it.visibleBounds.top }
            .coerceAtMost(keys.top)
        val stripTop = keys.top - STRIP_HEIGHT_FRACTION * keys.height()
        val y = ((stripTop.coerceAtLeast(windowTop.toFloat()) + keys.top) / 2f).toInt()
        val x = device.displayWidth / 6
        val before = field().text.orEmpty()
        device.click(x, y)
        settle()
        val after = field().text.orEmpty()
        val committed = after.removeSuffix(before).trim()
        check(committed.isNotEmpty()) { "no chip was picked at ($x, $y): '$before' -> '$after'" }
        return committed
    }

    private fun settle() {
        device.waitForIdle(SETTLE_MILLIS)
        Thread.sleep(SETTLE_MILLIS)
    }

    // ---- the pack ------------------------------------------------------------------------------

    private fun installBundledPack(tag: String) = runBlocking {
        val repository = DataGraph.languagePacks
        if (repository.hasLanguage(tag)) {
            return@runBlocking
        }
        val entry = BundledDictionaries.ALL.first { it.tag == tag }
        val staged = BundledDictionaries.open(context.assets, entry).use { stream ->
            repository.stage(stream, entry.fileName)
        }.getOrThrow()
        val verdict = LanguagePackInspector.inspect(staged.file)
        check(verdict is LanguagePackInspector.Result.Valid) { "bundled $tag refused: $verdict" }
        repository.register(
            LanguagePackEntry(
                tag = verdict.info.tag,
                displayName = entry.displayName,
                fileName = staged.file.name,
                formatVersion = verdict.info.formatVersion,
                wordCount = verdict.info.wordCount,
                sizeBytes = staged.sizeBytes,
                sha256 = staged.sha256,
                importedAt = System.currentTimeMillis(),
                enabled = true,
                weight = 1f,
                licenseNote = LanguageManager(context)[Keys.LANGUAGES_CC_BY_LEIPZIG],
            ),
        )
    }

    private companion object {
        const val ENGLISH = "en-US"

        /** Every word a case types, forgotten before each case so no run teaches the next. */
        val TYPED_WORDS = listOf("teh", "the", "abc", "def", "abcx", "ab", "cd", "x", "abxcd")
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"
        const val EDIT_TEXT = "android.widget.EditText"
        const val SPACE = "Space"
        const val SHIFT = "Shift"
        const val DELETE = "Delete"
        const val ENTER = "Enter"
        const val CONTROL = "Control"
        const val SLIDE_INSET_PX = 12
        const val SLIDE_STEPS = 40
        const val SWIPE_SEGMENT_STEPS = 20

        /** Enough moves on one spot for the ring's pause to be seen, at about 5 ms a move. */
        const val RING_PAUSE_STEPS = 100

        /** How far from the pause the ring's top wedge sits, up and to the right, in key rows. */
        const val RING_TOP_WEDGE_ROWS = 0.85f
        const val LAUNCH_TIMEOUT = 20_000L
        const val KEY_TIMEOUT = 5_000L
        const val ATTEMPTS = 3
        const val SELECT_ATTEMPTS = 2
        const val SETTLE_MILLIS = 700L

        /** The strip is about this much of a key row tall. */
        const val STRIP_HEIGHT_FRACTION = 1.1f
    }
}
