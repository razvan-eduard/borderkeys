// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
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
import com.borderkeys.data.theme.CustomLayout
import com.borderkeys.data.theme.KeyFlick
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.ModifierRowKeys
import com.borderkeys.data.theme.QuickAction
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.settings.ProbeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * The keyboard driven through a real input connection: keys tapped by their accessibility
 * nodes, the field read back through its own node. The English pack is installed from the
 * bundled assets and autocorrect is switched on before the keyboard is selected.
 */
@RunWith(AndroidJUnit4::class)
class ImeSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val imeId = ComponentName(context.packageName, "com.borderkeys.ime.BorderKeysService").flattenToShortString()

    @Before
    fun prepare() {
        keepScreenAwake()
        answerNotRespondingDialogs()
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
                    languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_OFF,
                    rememberDetectedLanguage = false,
                    featuresTourSeen = true,
                )
            }
        }
        device.executeShellCommand("ime enable $imeId")
        selectKeyboard()
        device.executeShellCommand("settings put secure selected_input_method_subtype $ENGLISH_SUBTYPE")
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
    fun keepPrivatelyStoresTheSelectionWithoutTheSystemClipboard() {
        runBlocking {
            DataGraph.clipboard.deleteAll()
            DataGraph.themes.updatePreferences {
                it.copy(
                    quickActionsEnabled = true,
                    quickActions = listOf(QuickAction.PRIVATE_COPY.id),
                    quickActionsMode = KeyboardPreferences.QUICK_ACTIONS_FULL,
                )
            }
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        instrumentation.runOnMainSync { clipboard.setPrimaryClip(ClipData.newPlainText(null, CLIP_BEFORE)) }
        try {
            type("secret")
            settle()
            device.pressKeyCode(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            settle()
            val bar = device.wait(Until.findObject(By.clazz(QUICK_ACTIONS_VIEW)), KEY_TIMEOUT)
            assertNotNull(
                "the quick-action bar; on screen: " +
                    device.findObjects(By.pkg(context.packageName)).map { "${it.className}:${it.contentDescription}" },
                bar,
            )
            val bounds = bar.visibleBounds
            // The one button sits at the bar's start edge.
            device.click(bounds.left + bounds.height() / 2, bounds.centerY())
            settle()
            val row = runBlocking { DataGraph.clipboard.recent(10) }.firstOrNull { it.content == "secret" }
            assertNotNull("the private row", row)
            assertTrue(row!!.isPrivate)
            assertEquals(context.packageName, row.sourcePackage)
            assertField("secret")
            var held: CharSequence? = null
            instrumentation.runOnMainSync { held = clipboard.primaryClip?.getItemAt(0)?.text }
            assertEquals("the system clipboard", CLIP_BEFORE, held?.toString())
        } finally {
            runBlocking {
                DataGraph.clipboard.deleteAll()
                DataGraph.themes.updatePreferences { it.copy(quickActionsEnabled = false) }
            }
        }
    }

    @Test
    fun theOtherKeyboardsActionOpensTheSystemPicker() {
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(
                    quickActionsEnabled = true,
                    quickActions = listOf(QuickAction.PICK_KEYBOARD.id),
                    quickActionsMode = KeyboardPreferences.QUICK_ACTIONS_FULL,
                    pickerKeySwitchesBack = false,
                )
            }
        }
        try {
            val bar = device.wait(Until.findObject(By.clazz(QUICK_ACTIONS_VIEW)), KEY_TIMEOUT)
            assertNotNull("the quick-action bar", bar)
            val bounds = bar.visibleBounds
            device.click(bounds.left + bounds.height() / 2, bounds.centerY())
            // The system's picker lists this keyboard by name ("BorderKeys +" in the plus build),
            // outside this package's windows.
            val listed = device.wait(
                Until.findObject(
                    By.textStartsWith(IME_LABEL).pkg(Pattern.compile("^(?!${Pattern.quote(context.packageName)}$).*")),
                ),
                LAUNCH_TIMEOUT,
            )
            assertNotNull(
                "the keyboard picker, listing $IME_LABEL; on screen: " +
                    device.findObjects(By.clazz(Pattern.compile(".*"))).mapNotNull { node ->
                        node.text?.let { "${node.applicationPackage}:$it" }
                    },
                listed,
            )
            device.pressBack()
            settle()
        } finally {
            runBlocking { DataGraph.themes.updatePreferences { it.copy(quickActionsEnabled = false) } }
        }
    }

    @Test
    fun aDeadAcuteThenEWritesEAcute() {
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(
                    modifierRow = true,
                    deadKeys = true,
                    modifierRowKeys = listOf(ModifierRowKeys.DEAD_ACUTE, ModifierRowKeys.ESCAPE),
                )
            }
        }
        try {
            settle()
            tapKey(DEAD_ACUTE_KEY)
            tapKey("e")
            assertField("é")
        } finally {
            runBlocking {
                DataGraph.themes.updatePreferences {
                    it.copy(modifierRow = false, deadKeys = false, modifierRowKeys = ModifierRowKeys.DEFAULT)
                }
            }
        }
    }

    @Test
    fun theComposeKeySpellsACharacterFromTheKeysAfterIt() {
        withAccentKeys(listOf(ModifierRowKeys.COMPOSE, ModifierRowKeys.ESCAPE)) {
            tapKey(COMPOSE_KEY)
            type("ae")
            assertField("æ")
        }
    }

    @Test
    fun anArrowAfterAnAccentModifierWritesItsBareMark() {
        withAccentKeys(listOf(ModifierRowKeys.DEAD_ACUTE, ModifierRowKeys.LEFT)) {
            type("e")
            tapKey(DEAD_ACUTE_KEY)
            tapKey(LEFT_ARROW_KEY)
            assertField("é")
        }
    }

    @Test
    fun onTheTurkishLayoutShiftTurnsIIntoADottedCapital() {
        selectSubtype(TURKISH_Q_SUBTYPE, firstKey = "q")
        try {
            tapKey(SHIFT)
            type("i")
            assertField("İ")
        } finally {
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    @Test
    fun aLandscapeChoiceDrawsWhenThePhoneTurns() {
        val qwerty = context.assets.open("layouts/qwerty.json").use { it.readBytes().decodeToString() }
        val own = CustomLayout("custom-1", "Sideways", "und", qwerty.replaceFirst("\"c\": \"q\"", "\"c\": \"q\", \"alt\": \"ø\""))
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(customLayouts = listOf(own), subtypeLayoutsLandscape = mapOf("qwerty" to "custom-1"))
            }
        }
        try {
            settle()
            val upright = waitForKey("q").contentDescription.orEmpty()
            device.setOrientationLeft()
            val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT
            while (findKey("q")?.contentDescription.orEmpty() == upright) {
                check(System.currentTimeMillis() < deadline) { "the landscape layout never drew: $upright" }
                Thread.sleep(SETTLE_MILLIS)
            }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            runBlocking {
                DataGraph.themes.updatePreferences { it.copy(customLayouts = emptyList(), subtypeLayoutsLandscape = emptyMap()) }
            }
        }
    }

    @Test
    fun theGlobeStepsThroughTheLayoutsInTheListsOrder() {
        val before = device.executeShellCommand("settings get secure enabled_input_methods").trim()
        val ime = "${context.packageName}/com.borderkeys.ime.BorderKeysService"
        val three = before.split(':').filterNot { it.startsWith(ime) } +
            "$ime;$ENGLISH_SUBTYPE;$GERMAN_QWERTZ_SUBTYPE;$RUSSIAN_SUBTYPE"
        device.executeShellCommand("settings put secure enabled_input_methods ${three.joinToString(":")}")
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(languageKey = true, layoutOrder = listOf("qwerty", "russian", "qwertz"))
            }
        }
        try {
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
            tapKey(LANGUAGE_KEY)
            waitForKey("й", LAUNCH_TIMEOUT)
        } finally {
            runBlocking { DataGraph.themes.updatePreferences { it.copy(languageKey = false, layoutOrder = emptyList()) } }
            device.executeShellCommand("settings put secure enabled_input_methods $before")
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    @Test
    fun anExtraKeySwitchedOffLeavesItsCornerToTheNext() {
        runBlocking { DataGraph.themes.updatePreferences { it.copy(extraKeysOff = listOf("ß")) } }
        selectSubtype(GERMAN_QWERTZ_SUBTYPE, firstKey = "q")
        try {
            val a = waitForKey("a").visibleBounds
            swipe(listOf(Point(a.centerX(), a.centerY()), Point(a.centerX() + a.width() / 2, a.centerY() + a.height() / 2)))
            assertField("€")
        } finally {
            runBlocking { DataGraph.themes.updatePreferences { it.copy(extraKeysOff = emptyList()) } }
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    /** Runs [block] with the modifier row on, the accent modifiers drawn, and [keys] on the row. */
    private fun withAccentKeys(keys: List<String>, block: () -> Unit) {
        runBlocking {
            DataGraph.themes.updatePreferences { it.copy(modifierRow = true, deadKeys = true, modifierRowKeys = keys) }
        }
        try {
            settle()
            block()
        } finally {
            runBlocking {
                DataGraph.themes.updatePreferences {
                    it.copy(modifierRow = false, deadKeys = false, modifierRowKeys = ModifierRowKeys.DEFAULT)
                }
            }
        }
    }

    @Test
    fun withAccentModifiersOffTheRowLeavesThemOut() {
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(modifierRow = true, modifierRowKeys = listOf(ModifierRowKeys.DEAD_ACUTE, ModifierRowKeys.ESCAPE))
            }
        }
        try {
            settle()
            waitForKey(ESCAPE_KEY)
            assertNull(device.findObject(By.descStartsWith(DEAD_ACUTE_KEY)))
        } finally {
            runBlocking {
                DataGraph.themes.updatePreferences {
                    it.copy(modifierRow = false, modifierRowKeys = ModifierRowKeys.DEFAULT)
                }
            }
        }
    }

    @Test
    fun aLanguageSwitchedOnUnderAccentsFromOtherLanguagesLendsItsAccents() {
        settle()
        val before = waitForKey("e").contentDescription.orEmpty()
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(extraAccents = true, extraAccentLanguages = listOf("fr-FR"))
            }
        }
        try {
            val deadline = System.currentTimeMillis() + KEY_TIMEOUT
            while (findKey("e")?.contentDescription.orEmpty() == before) {
                check(System.currentTimeMillis() < deadline) { "the French accents never reached the e key: $before" }
                Thread.sleep(SETTLE_MILLIS)
            }
        } finally {
            runBlocking {
                DataGraph.themes.updatePreferences { it.copy(extraAccents = false, extraAccentLanguages = emptyList()) }
            }
        }
    }

    @Test
    fun aDragLeftOnBackspaceSelectsAndTheLiftDeletes() {
        type("abc")
        tapKey(SPACE)
        type("def")
        settle()
        val delete = waitForKey(DELETE_KEY).visibleBounds
        val a = waitForKey("a").visibleBounds
        // Four character steps left, each about half a key width.
        val from = Point(delete.centerX(), delete.centerY())
        val to = Point(delete.centerX() - (a.width() * 2.4f).toInt(), delete.centerY())
        swipe(listOf(from, to))
        assertField("abc")
    }

    @Test
    fun aShortDragOffAKeyWritesItsFlick() {
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(keyFlicks = listOf(KeyFlick('a'.code, KeyFlick.NORTH, KeyFlick.TEXT, "@")))
            }
        }
        try {
            settle()
            val a = waitForKey("a").visibleBounds
            // Up by most of the key's height: past the flick's start, within its end.
            val from = Point(a.centerX(), a.centerY())
            val to = Point(a.centerX(), a.centerY() - (a.height() * 0.8f).toInt())
            swipe(listOf(from, to))
            assertField("@")
        } finally {
            runBlocking { DataGraph.themes.updatePreferences { it.copy(keyFlicks = emptyList()) } }
        }
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
        // One press at a time: each caret move is reported back to the keyboard, which reads
        // the word under the caret, and the next press waits for that.
        repeat(4) {
            device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
            Thread.sleep(SETTLE_MILLIS / 2)
        }
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
        // Which word the top wedge holds depends on the decoder tier and the screen the swipe
        // was drawn on; what is asserted is the mechanism: the pause opened the ring, the lift
        // on its top wedge committed that wedge's word, and the commit closed it with a space.
        val text = field().text.orEmpty()
        val picked = text.trim()
        assertTrue(
            "the ring's top word was picked and committed, but the field holds '$text'",
            picked.startsWith("t") && picked.none { it == ' ' } && text.endsWith(" "),
        )
    }

    @Test
    fun aPasswordFieldIsNeverCorrected() {
        focusProbe(ProbeMode.PASSWORD)
        type("teh")
        settle()
        tapKey(SPACE)
        settle()
        assertEquals("teh ", probeText(ProbeMode.PASSWORD))
        // The caret back on the word, then a space.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_LEFT)
        settle()
        tapKey(SPACE)
        settle()
        assertEquals("teh  ", probeText(ProbeMode.PASSWORD))
        // No apostrophe restored, and no space after the mark.
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        settle()
        type("dont")
        tapKey(".")
        type("pass")
        settle()
        assertEquals("teh  dont.pass", probeText(ProbeMode.PASSWORD))
    }

    @Test
    fun showOnThePrivateStripRevealsTheTypedTextAndHideCoversItAgain() {
        focusProbe(ProbeMode.PASSWORD)
        type("hunter")
        settle()
        val strip = stripRect()
        val notice = stripText(strip)
        tapPrivateToggle(strip)
        val revealed = stripText(strip)
        assertTrue(
            "after Show the strip still reaches as far as the notice: " +
                "${revealed.inkedColumns()} inked columns against the notice's ${notice.inkedColumns()}",
            revealed.inkedColumns() * 2 < notice.inkedColumns(),
        )
        tapPrivateToggle(strip)
        assertArrayEquals(
            "the strip after Hide is not the notice it showed before Show",
            notice.pixels,
            stripText(strip).pixels,
        )
        assertEquals("hunter", probeText(ProbeMode.PASSWORD))
    }

    @Test
    fun inTermuxEachLetterArrivesAsItIsTypedAndEnterRunsTheCommand() {
        if (!device.executeShellCommand("pm list packages $TERMUX").lines().contains("package:$TERMUX")) {
            check(InstrumentationRegistry.getArguments().getString(TERMUX_ARGUMENT) != TERMUX_REQUIRED) {
                "Termux is required for this run and is not installed"
            }
            assumeTrue("Termux is not installed", false)
        }
        // Termux publishes its screen as the terminal's description when it starts under an
        // accessibility connection, as it does here.
        device.executeShellCommand("am force-stop $TERMUX")
        device.executeShellCommand("pm grant $TERMUX android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("am start -n $TERMUX/$TERMUX_ACTIVITY")
        awaitTerminal("a prompt") { lines -> lines.any { it.trimEnd().endsWith("$") } }
        checkNotNull(terminalView()) { "Termux's terminal" }.click()
        assertTrue("the keyboard over Termux", device.wait(Until.hasObject(keyMatcher("q")), KEY_TIMEOUT))
        settle()
        type("echo")
        // Termux keeps a composing word in a buffer of its own until the word ends, so the
        // letters reach the prompt before the space only when each is committed as it is typed.
        awaitTerminal("the letters before the word ended", KEY_TIMEOUT) { lines ->
            lines.any { it.trimEnd().endsWith("$ echo") }
        }
        tapKey(SPACE)
        type("teh")
        tapKey(ENTER)
        val lines = awaitTerminal("the command's output") { it.contains("teh") }
        assertTrue("the command as typed in $lines", lines.any { it.trimEnd().endsWith("$ echo teh") })
        device.executeShellCommand("am force-stop $TERMUX")
    }

    @Test
    fun aSlideUpTheSpaceBarMovesTheCaretALine() {
        focusProbe(ProbeMode.LINES)
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
        assertEquals("abxcd", probeText(ProbeMode.LINES).replace("\n", ""))
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

    @Test
    fun lettersTypedOnTheRussianLayoutReachTheField() {
        selectSubtype(RUSSIAN_SUBTYPE, firstKey = "й")
        try {
            type("мир")
            assertField("мир")
        } finally {
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    @Test
    fun aLayoutOfTheUsersOwnDrawsForItsSubtype() {
        val qwerty = context.assets.open("layouts/qwerty.json").use { it.readBytes().decodeToString() }
        // The q key's long press gains a letter QWERTY does not have.
        val own = CustomLayout("custom-1", "Smoke", "und", qwerty.replaceFirst("\"c\": \"q\"", "\"c\": \"q\", \"alt\": \"ø\""))
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(customLayouts = listOf(own), subtypeLayouts = mapOf("qwerty" to "custom-1"))
            }
        }
        try {
            settle()
            val q = waitForKey("q")
            assertTrue("the q key's description: ${q.contentDescription}", q.contentDescription.orEmpty().contains("Hold"))
            type("q")
            assertField("q")
        } finally {
            runBlocking {
                DataGraph.themes.updatePreferences { it.copy(customLayouts = emptyList(), subtypeLayouts = emptyMap()) }
            }
        }
    }

    @Test
    fun aHoldOnTheGlobeSwitchesTheLayout() {
        val before = device.executeShellCommand("settings get secure enabled_input_methods").trim()
        val ime = "${context.packageName}/com.borderkeys.ime.BorderKeysService"
        val both = before.split(':').filterNot { it.startsWith(ime) } + "$ime;$ENGLISH_SUBTYPE;$RUSSIAN_SUBTYPE"
        device.executeShellCommand("settings put secure enabled_input_methods ${both.joinToString(":")}")
        runBlocking { DataGraph.themes.updatePreferences { it.copy(languageKey = true) } }
        try {
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
            waitForKey(LANGUAGE_KEY).longClick()
            waitForKey("й", LAUNCH_TIMEOUT)
        } finally {
            runBlocking { DataGraph.themes.updatePreferences { it.copy(languageKey = false) } }
            device.executeShellCommand("settings put secure enabled_input_methods $before")
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    @Test
    fun onTheHebrewLayoutTheFirstSuggestionSitsAtTheRightEndOfTheStrip() {
        selectSubtype(HEBREW_SUBTYPE, firstKey = "ק")
        try {
            assertEquals("the", tapChip(topRowKey = "ק", atRight = true))
            assertField("the ")
        } finally {
            selectSubtype(ENGLISH_SUBTYPE, firstKey = "q")
        }
    }

    @Test
    fun aCorrectionMadeInTheWrongLanguageIsUndoneWhenTheLanguageFlips() {
        installBundledPack(ROMANIAN)
        runBlocking {
            DataGraph.themes.updatePreferences {
                it.copy(languageSwitchCorrectionMode = KeyboardPreferences.LANGUAGE_SWITCH_AUTO_APPLY)
            }
        }
        try {
            // The second pack reaches the engine off the main thread once the repository says
            // so; the keys show its letters on their long press, and the engine has it loaded.
            val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT
            while (findKey("a")?.contentDescription?.contains(ROMANIAN_HOLD_HINT) != true ||
                ROMANIAN !in com.borderkeys.predict.PackLoad.active.value
            ) {
                check(System.currentTimeMillis() < deadline) { "the Romanian pack never reached the keys and the engine" }
                Thread.sleep(SETTLE_MILLIS)
            }
            settle()
            // Romanian settles the verdict, so "in" is spelled the Romanian way; enough English
            // after it turns the verdict, and the word is asked about again, of the English pack.
            for (word in ROMANIAN_PHRASE.split(' ') + "in") {
                type(word)
                tapKey(SPACE)
            }
            settle()
            val romanian = field().text.orEmpty()
            assertTrue("the Romanian verdict wrote în in '$romanian'", romanian.endsWith(" în "))
            for (word in ENGLISH_PHRASE.split(' ')) {
                type(word)
                tapKey(SPACE)
            }
            settle()
            settle()
            val text = field().text.orEmpty()
            assertTrue("the English word came back in '$text'", text.contains(" in ") && !text.contains("în"))
        } finally {
            runBlocking {
                DataGraph.languagePacks.allPacks().firstOrNull { it.tag == ROMANIAN }
                    ?.let { DataGraph.languagePacks.remove(it) }
            }
        }
    }

    /** Switches the keyboard to the subtype [subtypeId] and waits until its [firstKey] is on screen. */
    private fun selectSubtype(subtypeId: Int, firstKey: String) {
        device.executeShellCommand("settings put secure selected_input_method_subtype $subtypeId")
        waitForKey(firstKey, LAUNCH_TIMEOUT)
        settle()
    }

    /**
     * Puts the "Try it here" field into [mode] by tapping its label until the field's content
     * description says so, clears it, and focuses it with the keyboard up.
     */
    private fun focusProbe(mode: ProbeMode) {
        // The label carries the mode switch only while the field has focus.
        focusProbeField()
        // Each tap waits until the field has moved to another mode before the next.
        var taps = 0
        while (!device.hasObject(By.descStartsWith(mode.description)) && taps < ProbeMode.entries.size) {
            val label = device.wait(Until.findObject(By.textStartsWith(ProbeMode.BULLET)), KEY_TIMEOUT)
            assertNotNull("the probe field's mode label", label)
            val before = probeMode()
            label.click()
            taps++
            val deadline = System.currentTimeMillis() + KEY_TIMEOUT
            while (probeMode() == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(SETTLE_MILLIS / 2)
            }
            settle()
        }
        val field = device.wait(Until.findObject(By.descStartsWith(mode.description)), KEY_TIMEOUT)
        assertNotNull("the probe field in its ${mode.name} mode", field)
        field.clear()
        focusProbeField()
    }

    /** Taps the probe field at its right end, clear of the label, and waits for the keyboard. */
    private fun focusProbeField() {
        val field = device.wait(Until.findObject(By.descStartsWith(PROBE_PREFIX)), KEY_TIMEOUT)
        assertNotNull("the probe field", field)
        val bounds = field.visibleBounds
        device.click(bounds.right - SLIDE_INSET_PX * 4, bounds.centerY())
        assertTrue("the keyboard over the probe field", device.wait(Until.hasObject(keyMatcher("q")), KEY_TIMEOUT))
        settle()
    }

    /** The mode the probe field is in right now, as the start of its content description, or null. */
    private fun probeMode(): String? =
        device.findObject(By.descStartsWith(PROBE_PREFIX))?.contentDescription?.substringBefore(':')

    /** What the probe field holds in [mode], exactly, read from its content description. */
    private fun probeText(mode: ProbeMode): String {
        val field = device.findObject(By.descStartsWith(mode.description))
        if (field == null) {
            val probes = device.findObjects(By.descStartsWith(PROBE_PREFIX)).map { it.contentDescription }
            val editors = device.findObjects(By.clazz(EDIT_TEXT))
                .map { "'${it.text}' focused=${it.isFocused} ${it.visibleBounds}" }
            fail(
                "the probe field in its ${mode.name} mode; on a ${device.displayWidth}x${device.displayHeight} " +
                    "display the probe nodes are $probes, the editors $editors, and the keyboard is " +
                    (if (findKey("q") != null) "up" else "down"),
            )
        }
        return field!!.contentDescription.orEmpty().removePrefix(mode.description)
    }

    private fun keyCentre(name: String): Point {
        val bounds = waitForKey(name).visibleBounds
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
        var shown = device.wait(Until.findObject(By.clazz(EDIT_TEXT)), LAUNCH_TIMEOUT) != null
        if (!shown && answerNotRespondingDialogs()) {
            shown = device.wait(Until.findObject(By.clazz(EDIT_TEXT)), LAUNCH_TIMEOUT) != null
        }
        if (!shown) {
            fail("the settings probe field is not on screen; ${screenState()}")
        }
        device.waitForIdle(SETTLE_MILLIS)
        // Each step finds the node afresh; one found while the activity settles can go stale.
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

    /**
     * Whether the keyboard is the selected input method, both to the input method service and in
     * the setting the settings application reads at launch.
     */
    private fun keyboardSelected(): Boolean =
        device.executeShellCommand("dumpsys input_method").contains("mCurMethodId=$imeId") &&
            device.executeShellCommand("settings get secure default_input_method").trim() == imeId

    /**
     * Answers Wait on each "isn't responding" dialog on screen, which leaves the app it names
     * running, and returns whether there was one.
     */
    private fun answerNotRespondingDialogs(): Boolean {
        var answered = false
        repeat(ATTEMPTS) {
            val wait = device.findObject(By.res(SYSTEM_PACKAGE, NOT_RESPONDING_WAIT)) ?: return answered
            wait.click()
            answered = true
            device.waitForIdle(SETTLE_MILLIS)
        }
        return answered
    }

    /** Wakes the screen, keeps it on for as long as the device has power, and puts away a lock screen. */
    private fun keepScreenAwake() {
        device.executeShellCommand("svc power stayon true")
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
    }

    /**
     * What the device shows, for a failure message: the screen's power state, the lock screen,
     * the focused window and app, the resumed activity, and every window on screen by title.
     */
    private fun screenState(): String {
        fun lines(command: String, vararg keys: String) =
            device.executeShellCommand(command).lines().map { it.trim() }
                .filter { line -> keys.any { it in line } }.distinct().joinToString(" | ")
        val power = lines("dumpsys power", "mWakefulness=", "mHoldingDisplaySuspendBlocker=")
        val lock = lines("dumpsys window policy", "showing=", "isKeyguardShowing", "mKeyguardDrawComplete")
        val focus = lines("dumpsys window", "mCurrentFocus=", "mFocusedApp=")
        val activity = lines("dumpsys activity activities", "ResumedActivity")
        val windows = instrumentation.uiAutomation.windows.joinToString { "${it.title} (type ${it.type})" }
        return "power: $power; lock: $lock; focus: $focus; activity: $activity; windows: $windows"
    }


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

    /** What a key is called to a screen reader: its label, or the key's name, then its alternates. */
    private fun keyPattern(name: String): Pattern = Pattern.compile("^" + Pattern.quote(name) + "(\\..*)?$")

    private fun keyMatcher(name: String) = By.desc(keyPattern(name))

    /**
     * The key called [name] among the keyboard's own nodes, or null while it is not on screen
     * or while the keyboard is being laid out again under the search.
     */
    private fun findKey(name: String): UiObject2? {
        val pattern = keyPattern(name)
        return try {
            device.findObjects(By.pkg(context.packageName))
                .firstOrNull { pattern.matcher(it.contentDescription.orEmpty()).matches() }
        } catch (stale: StaleObjectException) {
            null
        }
    }

    private fun waitForKey(name: String, timeout: Long = KEY_TIMEOUT): UiObject2 {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            findKey(name)?.let { return it }
            if (System.currentTimeMillis() > deadline) {
                val keys = device.findObjects(By.pkg(context.packageName)).mapNotNull { it.contentDescription }
                fail("key $name; the keys on screen are $keys")
            }
            Thread.sleep(SETTLE_MILLIS)
        }
    }

    /** Taps the key called [name], finding it again if it went stale before the tap landed. */
    private fun tapKey(name: String) {
        repeat(ATTEMPTS) { attempt ->
            try {
                waitForKey(name).click()
                return
            } catch (stale: StaleObjectException) {
                check(attempt < ATTEMPTS - 1) { "key $name kept going stale" }
                Thread.sleep(SETTLE_MILLIS)
            }
        }
    }

    /**
     * Taps the first suggestion on the strip and returns the word it committed. The strip
     * draws its chips itself, so the first slot is found from the keyboard's own geometry:
     * the band above the top key row, in its left third.
     */
    private fun tapFirstChip(): String = tapChip(topRowKey = "q", atRight = false)

    /**
     * Taps the chip in the strip's left third, or its right third with [atRight], and returns
     * the word it committed. The strip sits above [topRowKey]'s row.
     */
    private fun tapChip(topRowKey: String, atRight: Boolean): String {
        val y = stripRect(topRowKey).centerY()
        val x = if (atRight) device.displayWidth * 5 / 6 else device.displayWidth / 6
        val before = field().text.orEmpty()
        device.click(x, y)
        settle()
        val after = field().text.orEmpty()
        val committed = after.removeSuffix(before).trim()
        check(committed.isNotEmpty()) { "no chip was picked at ($x, $y): '$before' -> '$after'" }
        return committed
    }

    /** Where the suggestion strip is on screen: the full width, above [topRowKey]'s row. */
    private fun stripRect(topRowKey: String = "q"): Rect {
        val keys = waitForKey(topRowKey).visibleBounds
        val windowTop = device.findObjects(By.pkg(context.packageName)).minOf { it.visibleBounds.top }
            .coerceAtMost(keys.top)
        val stripTop = (keys.top - STRIP_HEIGHT_FRACTION * keys.height()).toInt().coerceAtLeast(windowTop)
        return Rect(0, stripTop, device.displayWidth, keys.top)
    }

    /**
     * The middle half of the strip's height across its left part, where the private row draws
     * its notice or the field's text, clear of the Show or Hide at its right end.
     *
     * The strip's top is read from the screenshot: up from the key row, at the strip's left
     * edge, to where the strip's own colour ends.
     */
    private fun stripText(strip: Rect): Band {
        val shot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "no screenshot" }
        val edge = strip.left + strip.width() / 50
        val background = shot.getPixel(edge, strip.bottom - 2)
        var top = strip.bottom - 2
        while (top > 0 && colourDistance(shot.getPixel(edge, top - 1), background) <= FLAT_DISTANCE) {
            top--
        }
        val height = (strip.bottom - top) / 2
        val width = strip.width() * STRIP_TEXT_WIDTH_FRACTION / 100
        val pixels = IntArray(width * height)
        shot.getPixels(pixels, 0, width, strip.left, top + height / 2, width, height)
        shot.recycle()
        check(pixels.distinct().size > 1) { "the strip from y $top came back as one flat colour" }
        return Band(pixels, width)
    }

    /** A screenshot band's pixels, [width] to a row. */
    private class Band(val pixels: IntArray, private val width: Int) {

        /** Columns holding a pixel far from the band's commonest colour: how wide its text runs. */
        fun inkedColumns(): Int {
            val background = pixels.groupBy { it }.maxBy { it.value.size }.key
            return (0 until width).count { column ->
                (column until pixels.size step width).any { colourDistance(pixels[it], background) > INK_DISTANCE }
            }
        }
    }

    /** Taps the Show or Hide at the private row's right end and waits for the strip to redraw. */
    private fun tapPrivateToggle(strip: Rect) {
        device.click(strip.right - strip.height() / 2, strip.centerY())
        settle()
    }

    /** Termux's terminal: its node carrying the longest description, which is its screen. */
    private fun terminalView(): UiObject2? =
        device.findObjects(By.pkg(TERMUX)).maxByOrNull { it.contentDescription?.length ?: 0 }
            ?.takeIf { !it.contentDescription.isNullOrEmpty() }

    /** Waits up to [timeout] for Termux's screen, line by line, to satisfy [until], and returns those lines. */
    private fun awaitTerminal(
        what: String,
        timeout: Long = TERMUX_TIMEOUT,
        until: (List<String>) -> Boolean,
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeout
        while (true) {
            val lines = try {
                terminalView()?.contentDescription?.lines().orEmpty()
            } catch (stale: StaleObjectException) {
                emptyList()
            }
            if (until(lines)) {
                return lines
            }
            check(System.currentTimeMillis() < deadline) { "Termux never showed $what; its screen was $lines" }
            Thread.sleep(SETTLE_MILLIS)
        }
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
        const val CLIP_BEFORE = "on the clipboard before"
        const val ENGLISH = "en-US"
        const val ROMANIAN = "ro-RO"

        /** Words each language holds and the other does not, the phrases LanguageSwitchPipelineTest settles a verdict with. */
        const val ROMANIAN_PHRASE = "acesta trebuie foarte despre pentru"

        /** What the a key says once the Romanian pack's ă and â join its @ on the long press. */
        const val ROMANIAN_HOLD_HINT = "Hold for 3 more"
        const val ENGLISH_PHRASE = "through because another thought between however people water number system"

        /** Every word a case types, forgotten before each case so no run teaches the next. */
        val TYPED_WORDS = listOf("teh", "the", "abc", "def", "abcx", "ab", "cd", "x", "abxcd")
        const val SETTINGS_ACTIVITY = "com.borderkeys.settings.SettingsActivity"

        /** The Wait button of the system's "isn't responding" dialog, by package and resource id. */
        const val SYSTEM_PACKAGE = "android"
        const val NOT_RESPONDING_WAIT = "aerr_wait"

        /** What every mode's content description starts with; the mode's own name follows. */
        const val PROBE_PREFIX = "probe-"
        const val EDIT_TEXT = "android.widget.EditText"

        /** The backspace key's spoken name, from the catalogue. */
        const val DELETE_KEY = "Delete"

        /** The globe key's spoken name, from the catalogue. */
        const val LANGUAGE_KEY = "Language"

        /** The compose and left-arrow keys' spoken names, from the catalogue. */
        const val COMPOSE_KEY = "Compose"
        const val LEFT_ARROW_KEY = "Left arrow"

        /** The escape key's spoken name, from the catalogue. */
        const val ESCAPE_KEY = "Escape"

        /** The acute accent modifier's spoken name, up to the hold hint. */
        const val DEAD_ACUTE_KEY = "Accent modifier: Acute accent"
        const val QUICK_ACTIONS_VIEW = "com.borderkeys.ime.QuickActionsView"

        /** The name the system shows for this keyboard, from the manifest's ime_name. */
        const val IME_LABEL = "BorderKeys"
        const val SPACE = "Space"
        const val SHIFT = "Shift"
        const val DELETE = "Delete"
        const val ENTER = "Enter"
        const val CONTROL = "Control"

        /** Subtype ids from res/xml/method.xml; the secure setting takes them as an Int prints. */
        const val ENGLISH_SUBTYPE = 0x0B0DE002
        const val GERMAN_QWERTZ_SUBTYPE = 0x0B0DE005
        const val TURKISH_Q_SUBTYPE = 0x0B0DE00E
        const val RUSSIAN_SUBTYPE = 0x0B0DE016
        const val HEBREW_SUBTYPE = 0x0B0DE01E
        const val SLIDE_INSET_PX = 12
        const val SLIDE_STEPS = 40
        const val SWIPE_SEGMENT_STEPS = 20

        /**
         * Segments on one spot, [SWIPE_SEGMENT_STEPS] moves each at about 5 ms a move: past the
         * ring's pause dwell, and short of the ring's own pick timeout when the steer follows.
         */
        const val RING_PAUSE_STEPS = 6

        /** How far from the pause the ring's top wedge sits, up and to the right, in key rows. */
        const val RING_TOP_WEDGE_ROWS = 0.85f
        const val LAUNCH_TIMEOUT = 45_000L
        const val KEY_TIMEOUT = 5_000L
        const val ATTEMPTS = 3
        const val SELECT_ATTEMPTS = 2
        const val SETTLE_MILLIS = 700L

        /** The strip is about this much of a key row tall. */
        const val STRIP_HEIGHT_FRACTION = 1.1f

        /** Percent of the strip's width, from its left edge, that the private row's text may fill. */
        const val STRIP_TEXT_WIDTH_FRACTION = 60

        /** How far, summed over red, green and blue, a pixel sits from the background to count as text. */
        const val INK_DISTANCE = 96

        /** How far, the same way, a pixel may sit from the strip's colour and still be the strip. */
        const val FLAT_DISTANCE = 24

        /** The distance between two colours, summed over red, green and blue. */
        fun colourDistance(a: Int, b: Int): Int =
            abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) +
                abs(Color.blue(a) - Color.blue(b))

        const val TERMUX = "com.termux"
        const val TERMUX_ACTIVITY = "com.termux.app.TermuxActivity"

        /** The instrumentation argument, and its value, that make a missing Termux a failure. */
        const val TERMUX_ARGUMENT = "termux"
        const val TERMUX_REQUIRED = "required"

        /** Termux's first start unpacks its system before the prompt appears. */
        const val TERMUX_TIMEOUT = 120_000L
    }
}
