// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings.screen

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.assist.AssistClient
import com.borderkeys.assist.ChunkedAssistRunner
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import com.borderkeys.data.theme.ComposerAction
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.data.theme.SavedPrompt
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.Composer
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.openKeyboardPicker
import com.borderkeys.settings.rememberBorderKeysDefaultState
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The draft box, reached from a selection in *any* application rather than from inside the
 * keyboard.
 *
 * That distinction is the whole reason this screen exists. The keyboard's own composer
 * (`ComposerView`, in `:keyboard`) can only ever be opened while the software keyboard is on
 * screen, which Android only shows over a focused editable field -- so it can rewrite a message
 * you are drafting, but never a paragraph you are merely reading. `ACTION_PROCESS_TEXT` is
 * Android's own mechanism for the second case: an entry in every application's text-selection
 * menu, offered for *any* selection, editable or not, with no keyboard involved at all.
 *
 * A fresh screen rather than a reuse of `ComposerView`. That view exists to be typed into by the
 * keyboard's *own* key-press handling through a fake `InputConnection`
 * (`ComposerInputConnection`) -- it has no way to accept ordinary typing from whatever keyboard
 * happens to be active while a plain Activity is on screen, which is the normal case here. What
 * *is* reused is everything underneath the drawing: [Composer], the pure version graph, and
 * [AssistClient], which was already `Context`-generic and needed no change at all.
 */
@Composable
fun ProcessTextScreen(
    text: String,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    // On for the quick-draft path (see the LaunchedEffect below for why: a fresh task started
    // from the service has no keyboard to inherit and no swipe to have discovered yet). Off for
    // a real selection reached through the platform's PROCESS_TEXT menu -- there the box now
    // opens closed by default, with the swipe hint below teaching the one way to open it, rather
    // than a keyboard appearing over a paragraph the user may only have meant to read.
    autoFocus: Boolean = true,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val themes = remember { DataGraph.themes }
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })

    // This screen is reached from any application's selection menu, through the manifest alias,
    // whether or not BorderKeys is the keyboard actually in use -- Android does not gate a
    // PROCESS_TEXT entry on that. The box itself is the keyboard's own feature wearing a
    // different host (see the class doc), so offering it while some other keyboard is the one
    // in use would be a box that opens but belongs to nothing currently typing anything.
    // Kept live the same way Setup's own step 2 is, sharing that implementation: this activity
    // survives a trip to the system keyboard picker and back, and picking BorderKeys there
    // doesn't even pause this one, so this needs the same picker-timing wait and the same poll
    // that plain resume-tracking alone would miss -- not just re-reading on resume, which is
    // all this used to do, and which is exactly the gap rememberBorderKeysDefaultState closes.
    val isDefaultKeyboard by rememberBorderKeysDefaultState()

    // The original is "the exact copy gathered from the initial page selection, nothing else"
    // -- captured immediately, before a single keystroke can happen, the same as the in-keyboard
    // composer does. text is never empty for a real PROCESS_TEXT selection, but the check keeps
    // this correct even if some caller ever hands over an empty one.
    val composer = remember { Composer().apply { if (text.isNotEmpty()) captureBeforeRun(text) } }
    var current by remember { mutableStateOf(text) }
    // The field's own selection, alongside current rather than instead of it: everything but
    // the swipe gesture below only ever needs the text itself, and rewriting every one of those
    // sites to unwrap a TextFieldValue for a plain string would be a second, wider change for a
    // feature this narrow. Kept in sync with current whenever something other than typing moves
    // it -- a version switch, an assistant result, the initial selection -- so a stale selection
    // range is never carried onto text that replaced what it was measured against.
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    // Whether the field itself currently has focus -- not the same question as whether the
    // keyboard is on screen (the two can drift apart for a moment around an animation), but the
    // one the swipe hint below actually needs: once the field is focused there is nothing left
    // for the hint to teach.
    var isFocused by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (textFieldValue.text != current) {
            textFieldValue = TextFieldValue(current, selection = TextRange(current.length))
        }
    }
    var rail by remember {
        mutableStateOf(
            Rail(composer.size, composer.index, composer.canGoBack, composer.canGoForward, composer.atOriginal),
        )
    }
    var requestId by remember { mutableIntStateOf(-1) }
    var notice by remember { mutableStateOf("") }
    var promptOpen by remember { mutableStateOf(false) }
    var promptText by remember { mutableStateOf("") }
    var pendingInstruction by remember { mutableStateOf("") }
    // The span of `current` a running task was asked about, when it was asked about only part of
    // it -- null when the whole text was sent. The task works on the substring; its answer goes
    // back into exactly this range, leaving everything outside it untouched.
    var pendingSpan by remember { mutableStateOf<TextRange?>(null) }
    var offeringSaveName by remember { mutableStateOf<String?>(null) }
    var translateMenuOpen by remember { mutableStateOf(false) }
    var toneMenuOpen by remember { mutableStateOf(false) }
    var savedMenuOpen by remember { mutableStateOf(false) }

    val busy = requestId >= 0

    // Requested once, on the first composition, when autoFocus asks for it -- see the parameter
    // doc for which path that is and why. A launch from the keyboard's own Quick Action has no
    // calling activity handing a result back and no field left focused anywhere -- unlike the
    // platform's own PROCESS_TEXT toolbar, which starts this from inside a foreground activity,
    // this one starts it from a service and needs FLAG_ACTIVITY_NEW_TASK to do it at all, and a
    // fresh task does not inherit anyone's keyboard.
    val textFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) textFieldFocus.requestFocus() }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // The swipe-up/down gesture on the draft box itself, the same idea as the FastMap rules
    // window's own swipe handle in VoxApps -- a drag decides it, nothing drawn for it. Cursor
    // moved to the end rather than left wherever it was: a swipe is "I want to keep writing",
    // and continuing from the end is what that means for a box with no caret visible to aim at
    // yet.
    fun showKeyboardAtEnd() {
        textFieldValue = textFieldValue.copy(selection = TextRange(textFieldValue.text.length))
        textFieldFocus.requestFocus()
        keyboardController?.show()
    }

    fun hideKeyboardAndUnfocus() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun syncFromComposer() {
        current = composer.current() ?: current
        rail = Rail(composer.size, composer.index, composer.canGoBack, composer.canGoForward, composer.atOriginal)
    }

    val assistClient = remember { AssistClient(context) }
    // ChunkedAssistRunner owns assistClient's listener from here on -- see its own class doc for
    // why a caller talks to it instead of the client directly.
    val assist = remember { ChunkedAssistRunner(assistClient) }
    // Resolved once: whether the plus flavor's assistant is even present does not change while
    // this screen is open, and asking again on every recomposition would be a PackageManager
    // call for an answer that cannot have changed.
    val assistAvailable = remember { assist.isAvailable() }
    // The active model's own context window, for sizing chunks against -- see
    // ChunkedAssistRunner.maxChunkChars's own doc for why this is asked for rather than assumed.
    // A default rather than a wait for the first emission: the very first task run this screen
    // ever sees can arrive before this flow has collected anything, and undersizing a chunk
    // costs one extra request, not a wrong answer.
    val activeModel by DataGraph.assistModels.models
        .map { models -> models.firstOrNull { it.active } }
        .collectAsStateWithLifecycle(initialValue = null)
    val contextTokens = activeModel?.contextTokens ?: DEFAULT_CONTEXT_TOKENS

    DisposableEffect(Unit) {
        assist.listener = object : ChunkedAssistRunner.Listener {
            override fun onChunkedResult(
                id: Int,
                resultText: String,
                modelName: String?,
                truncated: Boolean,
            ) {
                if (id != requestId) {
                    // An answer to a request this screen has already moved past -- a second
                    // action tapped before the first came back cancels it, and its answer
                    // arriving late must not overwrite what replaced it.
                    return
                }
                requestId = -1
                val span = pendingSpan
                pendingSpan = null
                if (span != null && span.max <= current.length) {
                    // Back into the range it came from, with everything outside it kept exactly.
                    val whole = current.substring(0, span.min) + resultText +
                        current.substring(span.max)
                    composer.addResult(whole)
                    syncFromComposer()
                    // Leave the replaced part selected -- it is what changed, and running another
                    // action now works on it rather than on the whole line again.
                    textFieldValue = TextFieldValue(
                        current,
                        selection = TextRange(span.min, span.min + resultText.length),
                    )
                } else {
                    composer.addResult(resultText)
                    syncFromComposer()
                }
                notice = if (truncated) strings[Keys.ASSIST_ANSWER_MAY_BE_INCOMPLETE] else ""
                if (pendingInstruction.isNotEmpty() &&
                    preferences.savedPrompts.none { it.text == pendingInstruction } &&
                    preferences.savedPrompts.size < SavedPrompt.MAX_SAVED
                ) {
                    offeringSaveName = Composer.suggestedName(pendingInstruction)
                }
            }

            override fun onChunkedError(id: Int, error: Int) {
                if (id != requestId) {
                    return
                }
                requestId = -1
                pendingSpan = null
                notice = assistErrorMessage(strings, error)
            }

            override fun onAssistAvailability(available: Boolean, modelName: String?) = Unit
        }
        onDispose {
            // Told to stop rather than merely let go of: a generation nobody will read is still
            // seconds of the phone's own CPU, the same reasoning as the keyboard's composer.
            assist.cancel()
            assist.disconnect()
        }
    }

    // The span a task will actually be sent -- a real selection grown out to whole words
    // (unless that has been switched off), or null for "the whole field". One place, because
    // runTask, keepSelection and the word-count gate all have to agree on what "the selection"
    // means. A bare cursor (collapsed selection) is not a selection.
    //
    // Growing the ends: a selection that starts or ends inside a word ("st text este de sel")
    // is not something a model can translate or correct sensibly, so each end that landed
    // mid-word is pushed out to that word's edge.
    fun resolveSpan(): TextRange? {
        val selection = textFieldValue.selection
        return selection.takeIf {
            !it.collapsed && it.min >= 0 && it.max <= current.length
        }?.let { raw ->
            if (!preferences.composerSnapSelectionToWords) {
                return@let TextRange(raw.min, raw.max)
            }
            var start = raw.min
            var end = raw.max
            while (start > 0 && !current[start - 1].isWhitespace()) start--
            while (end < current.length && !current[end].isWhitespace()) end++
            TextRange(start, end)
        }
    }

    /** The text a task/gate looks at: the [resolveSpan] substring, or the whole field. */
    fun targetText(): String = resolveSpan()?.let { current.substring(it.min, it.max) } ?: current

    /**
     * Drops everything but the selection, as a new version -- no model. The "carry just this
     * part forward" step: after it, Insert and the next action work on the kept span alone.
     */
    fun keepSelection() {
        if (busy) {
            return
        }
        val span = resolveSpan() ?: return
        val kept = current.substring(span.min, span.max).trim()
        if (kept.isEmpty() || kept == current) {
            return
        }
        composer.captureBeforeRun(current)
        composer.addResult(kept)
        syncFromComposer()
        textFieldValue = textFieldValue.copy(selection = TextRange(current.length))
        notice = ""
    }

    fun runTask(task: AssistTask, instruction: String = "") {
        if (busy || current.isEmpty()) {
            return
        }
        val span = resolveSpan()
        // The field's own selection is moved to match the grown span, so what will be worked
        // on is what is shown selected.
        if (span != null && (span.min != textFieldValue.selection.min || span.max != textFieldValue.selection.max)) {
            textFieldValue = textFieldValue.copy(selection = span)
        }
        val sent = if (span != null) current.substring(span.min, span.max) else current
        // Below the task's own floor -- the button that started this is greyed out at the same
        // threshold, so this catches only a bypass (a menu item, a saved prompt).
        if (sent.isBlank() || composerWordCount(sent) < task.minWords) {
            return
        }
        composer.captureBeforeRun(current)
        val id = assist.run(task, sent, contextTokens, instruction)
        if (id < 0) {
            notice = strings[Keys.ASSISTANT_THE_ASSISTANT_IS_NOT_INSTALLED]
            return
        }
        requestId = id
        pendingSpan = span
        pendingInstruction = if (task == AssistTask.CUSTOM) instruction else ""
        notice = workingLabel(strings, task)
    }

    // Which way the version transition below slides in from -- derived here, the one place
    // every navigation path (the arrows, the rail, the horizontal swipe) already funnels
    // through, rather than threaded in separately by each of them. Left at 0 until the first
    // real navigation happens, which is also what tells the transition effect not to play on
    // the box's very first appearance -- there is nothing to have slid in from yet.
    var versionDirection by remember { mutableIntStateOf(0) }

    fun goTo(step: () -> String?) {
        if (busy) {
            return
        }
        if (!composer.isEmpty()) {
            composer.updateCurrent(current)
        }
        val before = composer.index
        step() ?: return
        val moved = composer.index - before
        if (moved != 0) {
            versionDirection = if (moved > 0) 1 else -1
        }
        syncFromComposer()
    }

    // A rectangle sitting above where the keyboard would be, not a page: the whole point of the
    // in-keyboard draft box is that it is a bounded thing over the keys, not the keys' own
    // screen. This is the same box wearing a different host. The Spacer rests it against the
    // bottom of the screen when nothing else is claiming that space; imePadding lifts it clear
    // of the real system keyboard the instant one rises (the text field below summons whatever
    // keyboard is actually installed, which is not necessarily this one), so the box tracks the
    // keyboard's top edge exactly the way it sits above the keys inside the IME.
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    // No `label` argument: NoHardcodedTextTest reads every `label = "..."` as user-facing
    // text, and Compose's animation label is Android Studio inspector tooling only -- never
    // shown to anyone using the app.
    val ring = rememberInfiniteTransition()
    val ringShift by ring.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(RING_PERIOD_MILLIS, easing = LinearEasing),
        ),
    )

    if (!isDefaultKeyboard) {
        Column(modifier = modifier.fillMaxSize()) {
            Spacer(Modifier.weight(1f).clickable {
                activity?.setResult(Activity.RESULT_CANCELED)
                activity?.finish()
            })
            NotDefaultKeyboardBox(
                shape = shape,
                ringShift = ringShift,
                onChooseKeyboard = { openKeyboardPicker(context) },
                onDismiss = {
                    activity?.setResult(Activity.RESULT_CANCELED)
                    activity?.finish()
                },
            )
        }
        return
    }

    // Both gestures' commit thresholds, in px once rather than re-derived on every drag event --
    // read from the same Density both the live-drag callbacks below and the gesture detectors
    // themselves use, so a swipe that visually looks like it crossed the line is exactly the one
    // that did.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val versionThresholdPx = with(density) { VERSION_SWIPE_THRESHOLD.toPx() }

    // Tracks the drag itself, live -- 1f at rest, shrinking towards FOCUS_SETTLE_SCALE as the
    // swipe crosses towards SWIPE_THRESHOLD, not a fixed pulse played back only once the finger
    // has already lifted. The system's own keyboard slides up or down on its own timeline this
    // application has no hold over at all, but the box that asked for it can still visibly
    // follow the swipe that did, which is as close to "an effect on the keyboard" as reaching
    // into a separate process's window is ever going to get.
    //
    // Also caught by a plain focus change that did not come from this drag at all -- tapping
    // straight into the field skips the gesture below entirely, and this is what still gives
    // that path the same small settle, landing on FOCUS_SETTLE_SCALE exactly where the drag's
    // own live tracking already would have by the time a swipe actually commits (see
    // SWIPE_THRESHOLD's relation to FOCUS_SETTLE_SCALE below), so the two meet without a visible
    // jump between them.
    val focusSettle = remember { Animatable(1f) }
    LaunchedEffect(isFocused) {
        focusSettle.snapTo(FOCUS_SETTLE_SCALE)
        focusSettle.animateTo(1f, tween(FOCUS_SETTLE_MILLIS, easing = FastOutSlowInEasing))
    }

    Column(modifier = modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f).clickable {
            activity?.setResult(Activity.RESULT_CANCELED)
            activity?.finish()
        })
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .heightIn(max = 480.dp)
                // Drawn before the clip, which is what puts it outside the rounded corners
                // instead of underneath them.
                .shadow(elevation = BOX_ELEVATION, shape = shape)
                .clip(shape)
                // The ring around the box, not a plain outline: the moving gradient is what
                // says "a model may touch this" before anyone reads a word of the bar beneath
                // it, the same way a coloured LED says a microphone is live.
                .background(ringBrush(ringShift))
                .swipeToToggleKeyboard { delta, released ->
                    if (!released) {
                        // Live: the box eases towards FOCUS_SETTLE_SCALE as the drag approaches
                        // swipeThresholdPx, following the finger rather than waiting for it to
                        // lift.
                        val t = (kotlin.math.abs(delta) / swipeThresholdPx).coerceIn(0f, 1f)
                        focusSettle.snapTo(1f - t * (1f - FOCUS_SETTLE_SCALE))
                        return@swipeToToggleKeyboard
                    }
                    if (delta <= -swipeThresholdPx) {
                        showKeyboardAtEnd()
                    } else if (delta >= swipeThresholdPx) {
                        hideKeyboardAndUnfocus()
                    } else {
                        // Released short of the threshold -- nothing committed, so nothing but
                        // this eases the box back; a real commit instead lets the
                        // isFocused-driven effect above pick it up already this close to rest.
                        focusSettle.animateTo(1f, tween(FOCUS_SETTLE_MILLIS, easing = FastOutSlowInEasing))
                    }
                },
        ) {
        // Surface, not a Column with a background modifier painted on: Surface is what sets
        // LocalContentColor for everything inside it. A background modifier only paints a
        // colour -- it does not say what reads against it -- so every icon below defaulted to
        // Compose's own fallback of plain black, invisible against a dark surface in exactly
        // the cases a background modifier cannot tell it apart from a light one.
        Surface(
            modifier = Modifier.padding(RING_WIDTH).scale(focusSettle.value),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
            // A Box, not a Row -- the hint below belongs truly centred between the title and the
            // close button, not sharing a weight with either of them, which is what a Row of
            // three weighted children would give instead.
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    strings[Keys.COMPOSER_TITLE],
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                if (!busy) {
                    SwipeUpHint(
                        ringShift = ringShift,
                        focused = isFocused,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                // Close top-right, per spec.
                IconButton(
                    onClick = {
                        activity?.setResult(Activity.RESULT_CANCELED)
                        activity?.finish()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_close),
                        contentDescription = strings[Keys.COMPOSER_CLOSE],
                    )
                }
            }

            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // -1..1, where 0 is at rest. Driven two ways: live, by the horizontal swipe
                    // below, following the finger as it drags (see that gesture's onChange); and
                    // by this effect, for a version reached any other way -- the arrows, the
                    // rail, or a swipe that already released past versionThresholdPx, which is
                    // handed off to here rather than finished by the gesture itself (see that
                    // callback for why the two meet without a visible jump). versionDirection
                    // (set in goTo) is what says which side a non-drag change slides in from, and
                    // 0 (nothing navigated yet) is what keeps this from playing on the box's own
                    // first appearance.
                    val versionSlide = remember { Animatable(0f) }
                    LaunchedEffect(rail.index) {
                        if (versionDirection == 0) {
                            return@LaunchedEffect
                        }
                        versionSlide.snapTo(versionDirection.toFloat())
                        versionSlide.animateTo(0f, tween(VERSION_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
                    }
                    // One shape for the field and the copy notch together -- see
                    // NotchedTopFieldShape's own doc for why this is a single outline rather
                    // than two shapes placed so they touch.
                    val fieldShape = remember {
                        NotchedTopFieldShape(
                            notchWidth = COPY_TAB_WIDTH,
                            notchHeight = COPY_TAB_HEIGHT,
                            fieldCornerRadius = FIELD_CORNER_RADIUS,
                            notchCornerRadius = COPY_TAB_CORNER_RADIUS,
                        )
                    }
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = FIELD_SIDE_GAP, vertical = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, fieldShape)
                            .border(
                                FIELD_BORDER_WIDTH,
                                if (isFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                fieldShape,
                            ),
                    ) {
                        OutlinedTextField(
                            value = textFieldValue,
                            enabled = !busy,
                            onValueChange = { value ->
                                textFieldValue = value
                                current = value.text
                                if (!composer.isEmpty()) {
                                    composer.updateCurrent(value.text)
                                }
                            },
                            placeholder = { Text(strings[Keys.COMPOSER_EMPTY]) },
                            // Transparent everywhere the field would otherwise paint its own
                            // border and background: fieldShape above is now the only outline
                            // and fill this area has, and the field drawing its own on top would
                            // either double that border or paint over the notch entirely.
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                disabledBorderColor = Color.Transparent,
                            ),
                            // A multiplier on the ambient size rather than a fixed sp value, so
                            // Small/Medium/Large still track a system font-size setting the same
                            // way the field's unscaled default already does.
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = LocalTextStyle.current.fontSize *
                                    composerFontScale(preferences.composerTextSize),
                            ),
                            modifier = Modifier.fillMaxWidth()
                                // Clears the notch: the field's own text never reaches under it,
                                // whatever the field's height ends up being.
                                .padding(top = COPY_TAB_HEIGHT)
                                .focusRequester(textFieldFocus)
                                .onFocusChanged { isFocused = it.isFocused }
                                // Left/right on the text itself steps through versions, the same
                                // move as the arrows at the bottom of the box or picking a node on
                                // the rail -- reaching either of those means looking away from what
                                // was just written to find them.
                                .swipeToChangeVersion { delta, released ->
                                    if (!released) {
                                        // Live: the text follows the finger, capped at ±1 exactly at
                                        // versionThresholdPx -- which is also where a commit below
                                        // hands off to the rail.index effect above, so the two never
                                        // visibly disagree about where the text already is.
                                        versionSlide.snapTo((delta / versionThresholdPx).coerceIn(-1f, 1f))
                                        return@swipeToChangeVersion
                                    }
                                    if (delta <= -versionThresholdPx) {
                                        goTo { composer.forward() }
                                    } else if (delta >= versionThresholdPx) {
                                        goTo { composer.back() }
                                    } else {
                                        // Released short of the threshold -- nothing navigated, so
                                        // nothing but this eases the text back to where it started.
                                        versionSlide.animateTo(0f, tween(VERSION_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
                                    }
                                }
                                .offset(x = VERSION_TRANSITION_DISTANCE * versionSlide.value)
                                .alpha(1f - kotlin.math.abs(versionSlide.value) * VERSION_TRANSITION_FADE),
                        )
                        // Sits in the notch fieldShape already cut for it -- no background, no
                        // border and no offset of its own to place, since the shape both of
                        // those belong to is drawn by the Box around this one already.
                        IconButton(
                            enabled = current.isNotEmpty() && !busy,
                            onClick = {
                                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? ClipboardManager
                                manager?.setPrimaryClip(ClipData.newPlainText(null, current))
                                notice = strings[Keys.ASSIST_COPY]
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(width = COPY_TAB_WIDTH, height = COPY_TAB_HEIGHT),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bk_action_copy_all),
                                contentDescription = strings[Keys.ASSIST_COPY],
                                modifier = Modifier.size(COPY_TAB_ICON_SIZE),
                            )
                        }

                        // A model may be rewriting what's on screen, so what's on screen has to
                        // stop being editable while it does -- typing into text that is about to
                        // be replaced is a race the user cannot win. Cut to the field's own
                        // outline, notch and all: the same shape the border draws, so the "a
                        // model may touch this" signal covers exactly the thing it is touching.
                        if (busy) {
                            val pulse = rememberInfiniteTransition()
                            val workingAlpha by pulse.animateFloat(
                                initialValue = 1f,
                                targetValue = 0.35f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(WORKING_PULSE_MILLIS, easing = LinearEasing),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                            )
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(fieldShape)
                                    .background(ringBrush(ringShift, alpha = WORKING_SCRIM_ALPHA))
                                    .padding(RING_WIDTH)
                                    .clip(fieldShape)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = WORKING_SURFACE_ALPHA),
                                    ),
                            ) {
                                Text(
                                    // notice is exactly this task's label the whole time busy is
                                    // true -- runTask is the only place that sets it before this
                                    // reads it, and nothing touches it again until requestId (and
                                    // so busy) goes false. Reading it rather than recomputing
                                    // workingLabel keeps the two from disagreeing about the same run.
                                    notice,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = workingAlpha),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(horizontal = 24.dp),
                                )
                                Button(
                                    onClick = {
                                        assist.cancel()
                                        requestId = -1
                                        notice = ""
                                    },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                                ) { Text(strings[Keys.ASSISTANT_CANCEL]) }
                            }
                        }
                    }

                    if (rail.size > 1) {
                        VersionRail(
                            rail = rail,
                            onSelect = { index -> goTo { composer.goTo(index) } },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }

                    // While busy this would just repeat "Working" a second time -- the overlay
                    // below already says it. An error notice (busy is false by the time one is
                    // set; see onAssistError) still shows here as before.
                    if (notice.isNotEmpty() && !busy) {
                        Explanation(notice)
                    }
                }
                if (offeringSaveName != null) {
                    SavePromptRow(
                        name = offeringSaveName.orEmpty(),
                        onNameChange = { offeringSaveName = it },
                        onSave = {
                            val name = offeringSaveName.orEmpty().trim()
                            val instruction = pendingInstruction
                            offeringSaveName = null
                            if (name.isEmpty() || instruction.isEmpty()) {
                                return@SavePromptRow
                            }
                            scope.launch {
                                themes.updatePreferences { prefs ->
                                    prefs.copy(
                                        savedPrompts = prefs.savedPrompts +
                                            SavedPrompt(name = name, text = instruction),
                                    )
                                }
                            }
                        },
                        onSkip = { offeringSaveName = null },
                    )
                }

                if (promptOpen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = { promptText = it },
                            placeholder = { Text(strings[Keys.COMPOSER_PROMPT_HINT]) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                val written = promptText.trim()
                                if (written.isNotEmpty()) {
                                    promptOpen = false
                                    val instruction = written.take(AssistTask.MAX_INSTRUCTION_CHARS)
                                    promptText = ""
                                    runTask(AssistTask.CUSTOM, instruction)
                                }
                            }),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { promptOpen = false; promptText = "" }) {
                            Icon(
                                painter = painterResource(R.drawable.bk_composer_close),
                                contentDescription = strings[Keys.COMPOSER_PROMPT_DISMISS],
                            )
                        }
                    }
                }
            }

            // One bar, arrows pinned to its ends -- the same shape as the in-keyboard composer's
            // own control bar, not a page with buttons scattered across it. Insert/Copy lives in
            // here too, as the last icon before the forward arrow, exactly where it sits in the
            // keyboard's version: the one affirmative action on the bar, not a separate call to
            // action bolted underneath it.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { goTo { composer.back() } }, enabled = rail.canBack) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_back),
                        contentDescription = strings[Keys.COMPOSER_BACK],
                    )
                }
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (assistAvailable) {
                        // In the user's own order -- the same preference the in-keyboard bar
                        // used to read, ComposerAction.fromIds(preferences.composerBar), so
                        // reordering it in Settings means the same thing here as it always did.
                        // INSERT is skipped: it stays the fixed button at the bar's end, because
                        // what it does (Insert vs Copy) already depends on how this screen was
                        // reached in a way a reorderable slot does not fit.
                        // Words in what an action would actually be sent -- the selection, grown
                        // to whole words, or the whole field. Below a task's own floor its
                        // button greys out: summarising three words is the three words back.
                        val targetWords = composerWordCount(targetText())
                        for (action in ComposerAction.fromIds(preferences.composerBar)) {
                            when (action) {
                                ComposerAction.GRAMMAR -> ActionIcon(
                                    R.drawable.bk_composer_grammar, strings[Keys.COMPOSER_ACTION_GRAMMAR], busy,
                                ) { runTask(AssistTask.CORRECT) }
                                ComposerAction.TRANSLATE -> Box {
                                    ActionIcon(
                                        R.drawable.bk_composer_translate, strings[Keys.COMPOSER_ACTION_TRANSLATE], busy,
                                    ) { translateMenuOpen = true }
                                    AssistMenu(
                                        translateMenuOpen,
                                        onDismissRequest = { translateMenuOpen = false },
                                        ringShift = ringShift,
                                    ) {
                                        TRANSLATE_TASKS.forEachIndexed { index, task ->
                                            if (index > 0) AssistMenuDivider()
                                            DropdownMenuItem(
                                                text = { Text(translateLabel(strings, task)) },
                                                onClick = { translateMenuOpen = false; runTask(task) },
                                            )
                                        }
                                    }
                                }
                                ComposerAction.TONE -> Box {
                                    ActionIcon(
                                        R.drawable.bk_composer_tone, strings[Keys.COMPOSER_ACTION_TONE],
                                        busy || targetWords < AssistTask.REWRITE_FORMAL.minWords,
                                    ) { toneMenuOpen = true }
                                    AssistMenu(
                                        toneMenuOpen,
                                        onDismissRequest = { toneMenuOpen = false },
                                        ringShift = ringShift,
                                    ) {
                                        TONE_TASKS.forEachIndexed { index, task ->
                                            if (index > 0) AssistMenuDivider()
                                            DropdownMenuItem(
                                                text = { Text(toneLabel(strings, task)) },
                                                onClick = { toneMenuOpen = false; runTask(task) },
                                            )
                                        }
                                    }
                                }
                                ComposerAction.SHORTEN -> ActionIcon(
                                    R.drawable.bk_composer_shorten, strings[Keys.COMPOSER_ACTION_SHORTEN],
                                    busy || targetWords < AssistTask.SHORTEN.minWords,
                                ) { runTask(AssistTask.SHORTEN) }
                                ComposerAction.SUMMARISE -> ActionIcon(
                                    R.drawable.bk_composer_summarise, strings[Keys.COMPOSER_ACTION_SUMMARISE],
                                    busy || targetWords < AssistTask.SUMMARISE.minWords,
                                ) { runTask(AssistTask.SUMMARISE) }
                                ComposerAction.KEEP_SELECTION -> if (resolveSpan() != null) {
                                    ActionIcon(
                                        R.drawable.bk_composer_crop,
                                        strings[Keys.COMPOSER_ACTION_KEEP_SELECTION], busy,
                                    ) { keepSelection() }
                                }
                                ComposerAction.PROMPT -> ActionIcon(
                                    R.drawable.bk_composer_prompt, strings[Keys.COMPOSER_ACTION_PROMPT], busy,
                                ) { promptOpen = !promptOpen }
                                ComposerAction.SAVED_PROMPTS -> if (preferences.savedPrompts.isNotEmpty()) {
                                    Box {
                                        ActionIcon(
                                            R.drawable.bk_composer_saved, strings[Keys.COMPOSER_ACTION_SAVED], busy,
                                        ) { savedMenuOpen = true }
                                        AssistMenu(
                                            savedMenuOpen,
                                            onDismissRequest = { savedMenuOpen = false },
                                            ringShift = ringShift,
                                        ) {
                                            preferences.savedPrompts.forEachIndexed { index, prompt ->
                                                if (index > 0) AssistMenuDivider()
                                                DropdownMenuItem(
                                                    text = { Text(prompt.name) },
                                                    onClick = {
                                                        savedMenuOpen = false
                                                        runTask(AssistTask.CUSTOM, prompt.text)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                ComposerAction.SHOW_ORIGINAL -> if (rail.hasHistory) {
                                    ActionIcon(
                                        R.drawable.bk_composer_original,
                                        if (rail.atOriginal) {
                                            strings[Keys.COMPOSER_ACTION_SHOW_CURRENT]
                                        } else {
                                            strings[Keys.COMPOSER_ACTION_SHOW_ORIGINAL]
                                        },
                                        busy,
                                    ) { goTo { composer.flip() } }
                                }
                                ComposerAction.INSERT -> Unit
                            }
                        }
                    }
                }
                // Read-only (nowhere to write back to -- the selection came from a view that
                // never offered to accept a replacement) has no affirmative action of its own
                // any more: Copy is the badge on the field itself now, available either way this
                // screen was reached, so there is nothing read-only still needs a bar button for.
                if (!readOnly) {
                    IconButton(
                        enabled = current.isNotEmpty() && !busy,
                        onClick = {
                            activity?.setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, current),
                            )
                            activity?.finish()
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.bk_composer_insert),
                            contentDescription = strings[Keys.COMPOSER_INSERT],
                            // Fixed, not the theme's colour -- see ComposerView.insertGreen. A
                            // play button reads as "send" by its colour before its shape, and a
                            // theme with a red or orange accent would otherwise tint the one
                            // affirmative action on the bar to look like a stop.
                            tint = if (current.isNotEmpty() && !busy) INSERT_GREEN else LocalContentColor.current,
                        )
                    }
                }
                // Its own button rather than folded into Copy or Insert: sharing hands the text
                // to another app entirely, neither the clipboard round trip Copy is nor the
                // "send this screen's answer back to where the selection came from" Insert is,
                // and available either way this screen was reached -- read-only or not, there is
                // always somewhere else on the phone the current text could usefully go.
                IconButton(
                    enabled = current.isNotEmpty() && !busy,
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, current)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_share),
                        contentDescription = strings[Keys.COMPOSER_SHARE],
                    )
                }
                IconButton(onClick = { goTo { composer.forward() } }, enabled = rail.canForward) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_forward),
                        contentDescription = strings[Keys.COMPOSER_FORWARD],
                    )
                }
            }
            }
        }
        }
    }
}

/**
 * What the box shows instead of itself when BorderKeys is not the keyboard in use.
 *
 * Same frame as the real box -- shape, ring and all -- so this reads as the draft box declining
 * to open rather than as a different screen. The one thing it can offer is the system's own
 * keyboard picker; it cannot switch the keyboard itself; see [openKeyboardPicker].
 */
@Composable
private fun NotDefaultKeyboardBox(
    shape: RoundedCornerShape,
    ringShift: Float,
    onChooseKeyboard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .clip(shape)
            .background(ringBrush(ringShift)),
    ) {
        Surface(
            modifier = Modifier.padding(RING_WIDTH),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        strings[Keys.COMPOSER_TITLE],
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            painter = painterResource(R.drawable.bk_composer_close),
                            contentDescription = strings[Keys.COMPOSER_CLOSE],
                        )
                    }
                }
                Explanation(strings[Keys.PROCESS_TEXT_NEEDS_BORDERKEYS_AS_THE_KEYBOARD])
                Button(
                    onClick = onChooseKeyboard,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) { Text(strings[Keys.SETUP_CHOOSE_KEYBOARD]) }
            }
        }
    }
}

/** Mirrors ComposerView.insertGreen; kept as its own constant rather than shared across a
 *  Compose/Canvas boundary neither side has a reason to cross for one colour. */
private val INSERT_GREEN = Color(0xFF43A047)

/**
 * The moving gradient around the box: purple, violet, red, blue, back to purple so the loop has
 * no seam. Diagonal offsets rather than a rotation, and deliberately not rotating the box itself
 * -- the shape's top corners are rounded and its bottom is not, and spinning a brush with that
 * asymmetry would spin the corners out of place with it. Sliding the gradient's own start and
 * end along the diagonal instead moves the colours without moving the shape.
 */
private fun ringBrush(shift: Float, alpha: Float = 1f): Brush {
    val span = 900f
    val x = (shift * span * 2f) - span
    val colours = if (alpha >= 1f) AI_RING_COLOURS else AI_RING_COLOURS.map { it.copy(alpha = alpha) }
    return Brush.linearGradient(
        colors = colours,
        start = Offset(x, 0f),
        end = Offset(x + span, span),
    )
}

private val AI_RING_COLOURS = listOf(
    Color(0xFF8B5CF6), // violet
    Color(0xFF3B82F6), // blue
    Color(0xFFEF4444), // red
    Color(0xFF6D28D9), // purple
    Color(0xFF8B5CF6), // back to violet -- the loop has no seam
)

/**
 * The context window assumed for chunk sizing before the active model's own row has been
 * collected from the database at least once. Smaller than every model KnownAssistModels.kt
 * currently ships (all 4096) rather than the middle of that range: undersizing a chunk here
 * costs one extra request the first time this runs in a session, oversizing it risks the
 * request this whole mechanism exists to avoid.
 */
private const val DEFAULT_CONTEXT_TOKENS = 2048

/** How long one pass of the ring takes to loop. */
private const val RING_PERIOD_MILLIS = 5000

/** The ring's own thickness. */
private val RING_WIDTH = 2.5.dp

/** The drop shadow both the draft box and the working overlay cast under their own frame. */
private val BOX_ELEVATION = 16.dp

/**
 * A vertical swipe on the draft box shows or hides the keyboard -- the same drag-decides-it
 * gesture as VoxApps' Commander/FastMap rules window, with no handle drawn for it here either.
 *
 * SWIPE_THRESHOLD is the distance a drag has to cross to *commit* (checked by the caller, in its
 * own onChange -- see where this is called), which is a separate question from [SWIPE_DEAD_ZONE]
 * below, the much smaller distance this claims the gesture at, past which it is already live and
 * following the finger rather than waiting for the commit line to be crossed.
 */
private val SWIPE_THRESHOLD = 56.dp

/** A little more forgiving than [SWIPE_THRESHOLD] -- dragging across letters to select a word or
 *  a phrase is a common, legitimate horizontal drag on this field, and this stays clear of it. */
private val VERSION_SWIPE_THRESHOLD = 72.dp

/**
 * How far a drag has to move, on its dominant axis, before either gesture below claims it and
 * starts reporting it live. Small on purpose, and the same for both: a tap to place the cursor
 * or a press on a button never reaches this distance at all, so neither gesture ever touches it,
 * but a real swipe is claimed early enough to visibly follow the finger well before it reaches
 * an actual commit threshold, which is what makes either one read as a live drag rather than an
 * effect that only appears once the finger has already lifted.
 */
private val SWIPE_DEAD_ZONE = 12.dp

/**
 * Claims a vertical drag once it clears [SWIPE_DEAD_ZONE] and is more vertical than horizontal,
 * then calls [onChange] with the running total on every further move (`released = false`) and
 * once more when the pointer lifts (`released = true`, total 0f if the drag was never claimed at
 * all). Deciding what a given total means -- committed, or short of the threshold -- is the
 * caller's job; see where this is used for that half of it.
 *
 * Read in [PointerEventPass.Initial] -- before the text field beneath gets its own turn at the
 * same events in the Main pass -- so a swipe that passes over the text is seen here first. Only
 * once it is claimed (past the dead zone) is anything actually consumed; everything before that
 * point is left unconsumed and reaches the field or a button exactly as if this modifier were
 * not here at all.
 */
private fun Modifier.swipeToToggleKeyboard(onChange: suspend (delta: Float, released: Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        val deadZone = SWIPE_DEAD_ZONE.toPx()
        awaitAxisSwipe(deadZone, primary = { it.y }, secondary = { it.x }, onChange)
    }

/**
 * A horizontal swipe on the draft text steps through versions -- right for the previous one,
 * left for the next -- the same move as the back/forward arrows at the bottom of the box or a
 * node picked on the rail, reached without looking away from what was just written to find
 * either of those.
 *
 * Shares [awaitAxisSwipe] with [swipeToToggleKeyboard] above, axes swapped: a drag that commits
 * to horizontal is this gesture's, one that commits to vertical is that one's, and the same
 * "nothing claimed below the dead zone" rule is what leaves an ordinary tap or a drag to select
 * text in the field beneath reaching it untouched either way.
 */
private fun Modifier.swipeToChangeVersion(onChange: suspend (delta: Float, released: Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        val deadZone = SWIPE_DEAD_ZONE.toPx()
        awaitAxisSwipe(deadZone, primary = { it.x }, secondary = { it.y }, onChange)
    }

/**
 * The engine both directional swipes above share: track a drag until it clearly commits to one
 * axis past [deadZone] -- more of that axis's own movement than the other's -- claim the rest of
 * that one gesture once it does (consuming every further event, which is what keeps it from also
 * being read as a tap or a text selection by whatever is underneath), and report the running
 * signed total to [onChange] on every claimed move, then once more on release. A drag that never
 * crosses [deadZone] is never claimed at all, and reaches whatever is underneath exactly as if
 * this modifier were not there.
 */
private suspend fun PointerInputScope.awaitAxisSwipe(
    deadZone: Float,
    primary: (Offset) -> Float,
    secondary: (Offset) -> Float,
    onChange: suspend (delta: Float, released: Boolean) -> Unit,
) = coroutineScope {
    // PointerInputScope is not itself a CoroutineScope (only Density), and
    // awaitEachGesture's own scope permits awaiting only its own suspend functions
    // (awaitPointerEvent and the like) -- onChange, an arbitrary suspend lambda that ends up
    // calling Animatable.snapTo/animateTo, needs a real one to run as a child of, which is what
    // wrapping this in coroutineScope actually provides. this@coroutineScope, not a bare
    // launch, because the closest implicit receiver inside the block below is
    // AwaitPointerEventScope, not this one. Each call is its own short-lived launch rather than
    // a single long-running collector: a drag reports a handful of moves, not a stream worth a
    // channel over.
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        var totalPrimary = 0f
        var totalSecondary = 0f
        var claimed = false
        while (true) {
            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                val total = if (claimed) totalPrimary else 0f
                this@coroutineScope.launch { onChange(total, true) }
                break
            }
            val delta = change.position - change.previousPosition
            totalPrimary += primary(delta)
            totalSecondary += secondary(delta)
            if (!claimed && kotlin.math.abs(totalPrimary) >= deadZone &&
                kotlin.math.abs(totalPrimary) > kotlin.math.abs(totalSecondary)
            ) {
                claimed = true
            }
            if (claimed) {
                change.consume()
                val total = totalPrimary
                this@coroutineScope.launch { onChange(total, false) }
            }
        }
    }
}

/**
 * Whether the swipe hint has already played, for the lifetime of this process.
 *
 * Not saved anywhere, and deliberately not -- this is "have I already shown this once this
 * session," not a permanent "the user has seen onboarding" preference, and the two do not
 * belong in the same kind of storage. A fresh process is a fresh session; the same process
 * being backgrounded and brought back, even if the activity hosting it gets torn down and
 * recreated along the way (this device does that fairly readily), is not.
 */
private var swipeHintShown = false

/**
 * "Swipe up for keyboard," rising and fading a handful of times and then gone -- the same idea
 * as the arrows a chat app pulses over its own input the first time it opens, teaching the
 * gesture above without a word of onboarding copy and without staying on screen once it has had
 * its chances to be seen.
 *
 * Plays once per process, [SWIPE_HINT_REPEAT_COUNT] times, via [swipeHintShown] above -- not
 * once per composition, which a naive `remember` would give for free right up until the first
 * thing that removes and re-adds this composable (a focus blip, a return from the background),
 * at which point it would play again. [focused] is read reactively instead, through
 * [rememberUpdatedState], so the caller can leave this mounted continuously and still have it
 * stop the moment focus actually happens, without either of them tearing the other down.
 *
 * Painted with [ringShift]'s own moving gradient rather than a flat theme colour -- the same
 * brush the box's border animates with, so the hint reads as coming from the border itself
 * ("this colour is what a swipe reaches for") instead of as an unrelated label sitting near it.
 *
 * One [progress] Animatable drives both position and opacity, rather than the two separately
 * timed ones this used to be -- a rise-then-snap-then-fade-in built from independently-timed
 * pieces reads as three small events, and a single value swept smoothly from 0 to 1 is what
 * makes it read as one: alpha follows a half sine of it ([kotlin.math.sin], peaking mid-flight),
 * so the text is already transparent at both ends of the sweep and the [Animatable.snapTo] that
 * starts the next cycle over at the bottom is invisible when it happens, not a visible jump --
 * which is the whole of what "finishes at the top, starts again at the bottom" needs, done in
 * one motion instead of stitched from several.
 */
@Composable
private fun SwipeUpHint(ringShift: Float, focused: Boolean, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val progress = remember { Animatable(0f) }
    // A second, independent fader for focus alone -- progress keeps meaning "how far through
    // this rise," and this is what actually hides the text the instant focus happens, whatever
    // point of the rise it happened at. Multiplied together below rather than fighting over the
    // same value.
    val focusFade = remember { Animatable(1f) }
    val latestFocused = rememberUpdatedState(focused)
    LaunchedEffect(Unit) {
        if (swipeHintShown) {
            return@LaunchedEffect
        }
        // Marked immediately, not after the loop below finishes -- an attempt interrupted by an
        // early focus still counts as this session's one chance, the same as one that ran to
        // its last rep untouched.
        swipeHintShown = true
        repeat(SWIPE_HINT_REPEAT_COUNT) {
            if (latestFocused.value) {
                return@LaunchedEffect
            }
            progress.snapTo(0f)
            progress.animateTo(1f, tween(SWIPE_HINT_CYCLE_MILLIS, easing = FastOutSlowInEasing))
        }
    }
    // Focus can happen mid-cycle (the user swiped before the hint finished its run) -- faded
    // out right away rather than left to finish its current rise, since it would otherwise be
    // sitting over a field the keyboard is now covering.
    LaunchedEffect(focused) {
        if (focused) {
            focusFade.animateTo(0f, tween(SWIPE_HINT_FOCUS_FADE_MILLIS))
        }
    }
    val baseStyle = MaterialTheme.typography.labelLarge
    val eased = progress.value
    val alpha = sin(eased * PI.toFloat()).coerceIn(0f, 1f) * focusFade.value
    Text(
        strings[Keys.COMPOSER_SWIPE_HINT],
        style = baseStyle.copy(
            fontSize = baseStyle.fontSize * SWIPE_HINT_SIZE_MULTIPLIER,
            brush = ringBrush(ringShift, alpha = alpha),
        ),
        modifier = modifier.offset(y = -SWIPE_HINT_DISTANCE * eased),
    )
}

/** The field's own side gap, in from the card -- smaller than it used to be, so the field itself
 *  reads as wider inside the same card rather than floating in a wide margin. */
private val FIELD_SIDE_GAP = 12.dp

private val MENU_CORNER_RADIUS = 14.dp
private val MENU_SHADOW_ELEVATION = 10.dp
private val MENU_BORDER_WIDTH = 1.5.dp

/**
 * The copy notch's own width and height. The width is not a free choice: the close button
 * above it is centred in the same 48.dp Material gives every icon button by default with no
 * size of its own set, and both it and the notch sit the same [FIELD_SIDE_GAP] in from the
 * card's right edge -- so a centred icon in a notch of that same width lands on the same
 * vertical line as the close button above it, with no offset of its own needed to put it there.
 * The height is free to stay smaller, since only the width decides where the icon sits sideways.
 */
private val COPY_TAB_WIDTH = 48.dp
private val COPY_TAB_HEIGHT = 32.dp

private val COPY_TAB_ICON_SIZE = 16.dp

/** [OutlinedTextField]'s own default corner radius and outline width, matched here because
 *  [NotchedTopFieldShape] replaces that field's own border and background entirely -- see its
 *  colors in the field below for why -- and a border that used to belong to Material's default
 *  shape now has to keep looking like it still does. */
private val FIELD_CORNER_RADIUS = 4.dp
private val COPY_TAB_CORNER_RADIUS = 4.dp
private val FIELD_BORDER_WIDTH = 1.dp

/**
 * One outline around a field and a small notch clipped onto its top-right corner, rather than
 * two separately bordered shapes placed so they touch -- the second reads as two things next to
 * each other no matter how exactly they meet; only tracing both as one path reads as a single
 * border going around both.
 *
 * The two inner corners, where the notch meets the field's own top edge, are left sharp on
 * purpose: a notch cut into a corner has a corner of its own there, the same way a torn-off
 * ticket stub does. Every other corner -- the field's own three, and the notch's two outer ones
 * -- is rounded.
 *
 * LTR only: the notch is always at the end edge in a left-to-right layout, and nothing here
 * mirrors it for a right-to-left one. None of this application's shipped languages are RTL.
 */
private class NotchedTopFieldShape(
    private val notchWidth: Dp,
    private val notchHeight: Dp,
    private val fieldCornerRadius: Dp,
    private val notchCornerRadius: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val notchW = with(density) { notchWidth.toPx() }
        val notchH = with(density) { notchHeight.toPx() }
        val rf = with(density) { fieldCornerRadius.toPx() }
        val rn = with(density) { notchCornerRadius.toPx() }
        val notchLeft = w - notchW

        val path = Path().apply {
            // Field's top edge, from just past its own top-left corner to where the notch's
            // left edge begins.
            moveTo(rf, notchH)
            lineTo(notchLeft, notchH)
            // Straight up into the notch -- the one sharp corner, unrounded on purpose.
            lineTo(notchLeft, rn)
            // Notch's top-left corner.
            arcTo(Rect(notchLeft, 0f, notchLeft + 2 * rn, 2 * rn), 180f, 90f, false)
            // Notch's top edge.
            lineTo(w - rn, 0f)
            // Notch's top-right corner, which is also the field's own top-right corner --
            // their edges share the same x = w, so nothing marks where one becomes the other.
            arcTo(Rect(w - 2 * rn, 0f, w, 2 * rn), 270f, 90f, false)
            // Field's right edge.
            lineTo(w, h - rf)
            // Field's bottom-right corner.
            arcTo(Rect(w - 2 * rf, h - 2 * rf, w, h), 0f, 90f, false)
            // Field's bottom edge.
            lineTo(rf, h)
            // Field's bottom-left corner.
            arcTo(Rect(0f, h - 2 * rf, 2 * rf, h), 90f, 90f, false)
            // Field's left edge, back up to the top-left corner.
            lineTo(0f, notchH + rf)
            // Field's top-left corner, closing exactly where this path started.
            arcTo(Rect(0f, notchH, 2 * rf, notchH + 2 * rf), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** 70% of [MaterialTheme.typography.labelLarge]'s own size, doubled -- 1.4x in total. */
private const val SWIPE_HINT_SIZE_MULTIPLIER = 1.4f

/** How many times the hint rises and fades before it stops appearing for good, this process. */
private const val SWIPE_HINT_REPEAT_COUNT = 2

/** A slow, deliberate float rather than a snap -- what "cursive" means for a one-shot nudge. */
private const val SWIPE_HINT_SPEED_DP_PER_SECOND = 16f

/** How far each rise travels. */
private val SWIPE_HINT_DISTANCE = 22.dp

/** [SWIPE_HINT_DISTANCE] at [SWIPE_HINT_SPEED_DP_PER_SECOND] -- computed from the two, rather
 *  than a duration guessed at and left to drift out of sync with either if one of them changes. */
private val SWIPE_HINT_CYCLE_MILLIS =
    (SWIPE_HINT_DISTANCE.value / SWIPE_HINT_SPEED_DP_PER_SECOND * 1000).roundToInt()

/** How quickly the hint gets out of the way once the field is actually focused -- quick, since
 *  by then it is sitting on top of what the keyboard is about to cover. */
private const val SWIPE_HINT_FOCUS_FADE_MILLIS = 150

/** How far the field slides in from, on a version change -- a nudge, the same scale as the
 *  swipe hint's own rise, not a full page's worth of travel. */
private val VERSION_TRANSITION_DISTANCE = 24.dp

/** How much of the field's opacity the slide dips at its furthest point (versionSlide at ±1). */
private const val VERSION_TRANSITION_FADE = 0.6f

private const val VERSION_TRANSITION_MILLIS = 220

/** How far the box shrinks for its own settle, each time focus flips -- barely there on
 *  purpose: a hint that something changed, not a bounce that competes with the system
 *  keyboard's own slide for attention. */
private const val FOCUS_SETTLE_SCALE = 0.985f

private const val FOCUS_SETTLE_MILLIS = 220

/**
 * The busy overlay's two layers: the ring's own gradient over a surface-coloured scrim
 * underneath it. Scrim higher than surface -- more of the moving colour, less of the flat
 * tint -- is what keeps this reading as "the model is working," not "this box is disabled";
 * the surface tint under it still keeps the box legibly unavailable, in both light and dark
 * theme.
 */
private const val WORKING_SCRIM_ALPHA = 0.55f
private const val WORKING_SURFACE_ALPHA = 0.4f

/** How long one breath of the "Working" text's fade takes, in each direction. */
private const val WORKING_PULSE_MILLIS = 1100

/** A snapshot of [Composer]'s state that Compose can observe, taken after every mutation. */
private data class Rail(
    val size: Int = 0,
    val index: Int = 0,
    val canBack: Boolean = false,
    val canForward: Boolean = false,
    val atOriginal: Boolean = false,
) {
    val hasHistory: Boolean get() = size > 1
}

/**
 * The dropdown a bar action with more than one target opens -- translate into which language,
 * which register, which saved prompt.
 *
 * Not a plain [DropdownMenu]: rounded, dropped well clear of the bar with a real shadow, and
 * edged with the same moving gradient the box's own ring is drawn in, so a menu the assistant
 * opens looks like it belongs to the assistant rather than to the platform.
 */
@Composable
private fun AssistMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    ringShift: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(MENU_CORNER_RADIUS),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = MENU_SHADOW_ELEVATION,
        border = BorderStroke(MENU_BORDER_WIDTH, ringBrush(ringShift)),
        content = content,
    )
}

/** A hairline between two [AssistMenu] rows -- inset from the border so it does not run into it. */
@Composable
private fun AssistMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Whitespace-separated words in [text] -- the unit the draft box's per-task gate counts in. */
private fun composerWordCount(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

@Composable
private fun ActionIcon(icon: Int, label: String, disabled: Boolean, onClick: () -> Unit) {
    // What an icon alone left to guessing: "the wand" told you nothing about grammar versus
    // tone versus a saved prompt until you had pressed it once and remembered. The label is the
    // same string the icon used to carry only as a screen reader's contentDescription -- now
    // said once, out loud on the button itself, so the icon's own description is redundant and
    // dropped rather than read twice.
    val alpha = if (disabled) DISABLED_ALPHA else 1f
    Column(
        modifier = Modifier
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(vertical = 4.dp)
            .widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = LocalContentColor.current.copy(alpha = alpha),
            modifier = Modifier.size(22.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Matches the alpha Compose's own `IconButton` uses for a disabled icon. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The dots between the two arrows: one per version, the current one filled, the ends coloured
 * apart from the rest -- the same reading as the keyboard's own timeline, at a fraction of its
 * drawing complexity, because a full-screen Compose layout has no cramped strip to fit it into
 * and no reason to reproduce a drag gesture a tap already covers.
 */
@Composable
private fun VersionRail(rail: Rail, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (index in 0 until rail.size) {
            val current = index == rail.index
            val edge = index == 0 || index == rail.size - 1
            val color = when {
                current -> MaterialTheme.colorScheme.primary
                edge -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Box(
                modifier = Modifier
                    .size(if (current) 12.dp else 9.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SavePromptRow(
    name: String,
    onNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(strings[Keys.COMPOSER_SAVE_PROMPT_NAME]) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSave) { Text(strings[Keys.COMPOSER_SAVE_PROMPT]) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onSkip) { Text(strings[Keys.COMPOSER_PROMPT_DISMISS]) }
    }
}

/**
 * What the working overlay says while a request is in flight -- what it is actually doing,
 * not a generic "Working," and specific about which language or which tone rather than a bare
 * "Translating" or "Changing tone." An exhaustive `when` on purpose and no `else`: a task added
 * to AssistTask.kt without a line added here is a compile error, not a silent fallback to a
 * label that says nothing about what that new task does.
 */
/**
 * A multiplier on the field's own ambient text size for [KeyboardPreferences.composerTextSize]'s
 * three steps -- Medium is exactly the size the field already had before this setting existed,
 * so nobody's box changes size until they actually reach for the new control.
 */
private fun composerFontScale(step: Int): Float = when (step) {
    KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL -> 0.85f
    KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE -> 1.25f
    else -> 1f
}

private fun workingLabel(strings: com.borderkeys.i18n.LanguageManager, task: AssistTask): String =
    when (task) {
        AssistTask.SUMMARISE -> strings[Keys.COMPOSER_WORKING_SUMMARISE]
        AssistTask.CORRECT -> strings[Keys.COMPOSER_WORKING_CORRECT]
        AssistTask.SHORTEN -> strings[Keys.COMPOSER_WORKING_SHORTEN]
        AssistTask.TRANSLATE_TO_ENGLISH -> strings[Keys.COMPOSER_WORKING_TRANSLATE_ENGLISH]
        AssistTask.TRANSLATE_TO_ROMANIAN -> strings[Keys.COMPOSER_WORKING_TRANSLATE_ROMANIAN]
        AssistTask.TRANSLATE_TO_GERMAN -> strings[Keys.COMPOSER_WORKING_TRANSLATE_GERMAN]
        AssistTask.TRANSLATE_TO_SPANISH -> strings[Keys.COMPOSER_WORKING_TRANSLATE_SPANISH]
        AssistTask.TRANSLATE_TO_FRENCH -> strings[Keys.COMPOSER_WORKING_TRANSLATE_FRENCH]
        AssistTask.TRANSLATE_TO_ITALIAN -> strings[Keys.COMPOSER_WORKING_TRANSLATE_ITALIAN]
        AssistTask.REWRITE_FORMAL -> strings[Keys.COMPOSER_WORKING_TONE_FORMAL]
        AssistTask.REWRITE_CASUAL -> strings[Keys.COMPOSER_WORKING_TONE_CASUAL]
        AssistTask.REWRITE_DIRECT -> strings[Keys.COMPOSER_WORKING_TONE_DIRECT]
        // Nothing fixed to name -- the instruction is whatever the user wrote.
        AssistTask.CUSTOM -> strings[Keys.COMPOSER_WORKING_CUSTOM]
    }

private fun translateLabel(strings: com.borderkeys.i18n.LanguageManager, task: AssistTask): String =
    when (task) {
        AssistTask.TRANSLATE_TO_ENGLISH -> strings[Keys.LANGUAGE_ENGLISH]
        AssistTask.TRANSLATE_TO_ROMANIAN -> strings[Keys.LANGUAGE_ROMANIAN]
        AssistTask.TRANSLATE_TO_GERMAN -> strings[Keys.LANGUAGE_GERMAN]
        AssistTask.TRANSLATE_TO_SPANISH -> strings[Keys.LANGUAGE_SPANISH]
        AssistTask.TRANSLATE_TO_FRENCH -> strings[Keys.LANGUAGE_FRENCH]
        AssistTask.TRANSLATE_TO_ITALIAN -> strings[Keys.LANGUAGE_ITALIAN]
        else -> ""
    }

private fun toneLabel(strings: com.borderkeys.i18n.LanguageManager, task: AssistTask): String =
    when (task) {
        AssistTask.REWRITE_FORMAL -> strings[Keys.TONE_FORMAL]
        AssistTask.REWRITE_CASUAL -> strings[Keys.TONE_CASUAL]
        AssistTask.REWRITE_DIRECT -> strings[Keys.TONE_DIRECT]
        else -> ""
    }

/** Every failure is a sentence somebody can act on. Mirrors BorderKeysService.assistErrorMessage. */
private fun assistErrorMessage(strings: com.borderkeys.i18n.LanguageManager, error: Int): String =
    when (error) {
        AssistProtocol.ERROR_NO_MODEL -> strings[Keys.ASSISTANT_NO_MODEL_IMPORTED_YET_SETTINGS_TEXT]
        AssistProtocol.ERROR_MODEL_CHANGED -> strings[Keys.ASSISTANT_THE_MODEL_FILE_CHANGED_SINCE_IT]
        AssistProtocol.ERROR_LOAD_FAILED -> strings[Keys.ASSISTANT_THE_MODEL_COULD_NOT_BE_LOADED]
        AssistProtocol.ERROR_TOO_LONG -> strings[Keys.ASSISTANT_THE_SELECTION_IS_LONGER_THAN_THIS]
        AssistProtocol.ERROR_BUSY -> strings[Keys.ASSISTANT_STILL_WORKING_ON_THE_PREVIOUS_REQUEST]
        AssistProtocol.ERROR_NO_INSTRUCTION -> strings[Keys.ASSISTANT_NO_INSTRUCTION_WAS_WRITTEN]
        else -> strings[Keys.ASSISTANT_THE_ASSISTANT_COULD_NOT_FINISH]
    }

/** The six languages the translate button offers, matching the in-keyboard composer's list. */
private val TRANSLATE_TASKS = listOf(
    AssistTask.TRANSLATE_TO_ENGLISH,
    AssistTask.TRANSLATE_TO_ROMANIAN,
    AssistTask.TRANSLATE_TO_GERMAN,
    AssistTask.TRANSLATE_TO_SPANISH,
    AssistTask.TRANSLATE_TO_FRENCH,
    AssistTask.TRANSLATE_TO_ITALIAN,
)

private val TONE_TASKS = listOf(
    AssistTask.REWRITE_FORMAL,
    AssistTask.REWRITE_CASUAL,
    AssistTask.REWRITE_DIRECT,
)
