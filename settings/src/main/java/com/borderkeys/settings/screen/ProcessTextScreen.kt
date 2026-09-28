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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.assist.AssistClient
import com.borderkeys.assist.ChunkedAssistRunner
import com.borderkeys.data.DataGraph
import com.borderkeys.data.assist.AssistProtocol
import com.borderkeys.data.assist.AssistTask
import com.borderkeys.data.theme.ComposerAction
import com.borderkeys.data.theme.ComposerBar
import com.borderkeys.data.theme.ComposerBarItem
import com.borderkeys.data.theme.CustomIcon
import com.borderkeys.data.theme.CustomAction
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.i18n.Keys
import com.borderkeys.ime.Composer
import com.borderkeys.keyboard.R
import com.borderkeys.settings.Explanation
import com.borderkeys.settings.LocalStrings
import com.borderkeys.settings.openKeyboardPicker
import com.borderkeys.settings.rememberBorderKeysDefaultState
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The draft box, reached from a selection in any application through `ACTION_PROCESS_TEXT`, and
 * from the keyboard's Compose quick action through `DraftProtocol`. Built on [Composer], the
 * version graph, and [AssistClient].
 */
@Composable
fun ProcessTextScreen(
    text: String,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
    // Whether the field is focused on open: on for the quick-draft path, off for a selection from
    // the PROCESS_TEXT menu.
    autoFocus: Boolean = true,
    // Set by SettingsActivity for one of the four task aliases: run once, on open, on the incoming
    // selection.
    autoRunTask: AssistTask? = null,
    // Set by the fifth alias, `.ProcessTextCustomAlias`: opens the custom-action picker on open.
    offerCustomActionPicker: Boolean = false,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val themes = remember { DataGraph.themes }
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })

    // Whether BorderKeys is the keyboard in use, kept live across a trip to the system picker.
    val isDefaultKeyboard by rememberBorderKeysDefaultState()

    // The original: the incoming text, captured before any keystroke and normalised once through
    // the editor's Markdown parser. Every version is Markdown.
    val initialMarkdown = remember(text) { RichTextState().apply { setMarkdown(text) }.toMarkdown() }
    // A plain remember, not the saveable rememberRichTextState: the draft starts over when the
    // activity is recreated, as the composer does.
    val richTextState = remember { RichTextState().apply { setMarkdown(initialMarkdown) } }
    val composer = remember { Composer().apply { if (initialMarkdown.isNotEmpty()) captureBeforeRun(initialMarkdown) } }

    // Whether the field has focus.
    var isFocused by remember { mutableStateOf(false) }

    // The editor viewport's scroll and the text's last layout, which showKeyboard reads.
    val scrollState = rememberScrollState()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

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
    // The span a running task was sent, or null for the whole text; its answer replaces exactly
    // this range.
    var pendingSpan by remember { mutableStateOf<TextRange?>(null) }
    var offeringSaveName by remember { mutableStateOf<String?>(null) }
    var offeringSaveIcon by remember { mutableStateOf(CustomIcon.DEFAULT) }
    var offeringPinToBar by remember { mutableStateOf(false) }
    var translateMenuOpen by remember { mutableStateOf(false) }
    var toneMenuOpen by remember { mutableStateOf(false) }
    var savedMenuOpen by remember { mutableStateOf(false) }
    // Insert and Share each open a picker: markdown as written, or plain text.
    var insertMenuOpen by remember { mutableStateOf(false) }
    var shareMenuOpen by remember { mutableStateOf(false) }
    // The custom-action menu opened by `offerCustomActionPicker`, whether or not SAVED_PROMPTS is
    // on the bar.
    var externalCustomPickerOpen by remember { mutableStateOf(false) }

    val busy = requestId >= 0

    // Requested once, on the first composition, when autoFocus asks for it.
    val textFieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) textFieldFocus.requestFocus() }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Focuses the field for a swipe up on the box, without moving the text. The caret goes to the
    // end of the text, or, while it is scrolled, to the end of the topmost fully visible line.
    fun showKeyboard() {
        val layout = textLayout
        val caret = if (layout == null || scrollState.maxValue <= 0 || scrollState.maxValue == Int.MAX_VALUE) {
            richTextState.annotatedString.length
        } else {
            val top = scrollState.value.toFloat()
            var line = layout.getLineForVerticalPosition(top)
            if (layout.getLineTop(line) < top && line + 1 < layout.lineCount) {
                line++
            }
            layout.getLineEnd(line, visibleEnd = true)
        }
        richTextState.selection = TextRange(caret)
        // Holds the scroll through the focus handoff, at a priority every bring-into-view request
        // loses to; launched before requestFocus, and the position restored on release.
        val anchor = scrollState.value
        scope.launch {
            scrollState.scroll(MutatePriority.PreventUserInput) {
                delay(SHOW_KEYBOARD_SCROLL_HOLD_MILLIS)
                scrollBy((anchor - scrollState.value).toFloat())
            }
        }
        textFieldFocus.requestFocus()
        keyboardController?.show()
    }

    fun hideKeyboardAndUnfocus() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun refreshRail() {
        rail = Rail(composer.size, composer.index, composer.canGoBack, composer.canGoForward, composer.atOriginal)
    }

    // Loads the composer's current version into the editor, after navigation.
    fun loadFromComposer() {
        composer.current()?.let { richTextState.setMarkdown(it) }
        richTextState.selection = TextRange(richTextState.annotatedString.length)
        refreshRail()
    }

    val assistClient = remember { AssistClient(context) }
    // ChunkedAssistRunner owns assistClient's listener from here on.
    val assist = remember { ChunkedAssistRunner(assistClient) }
    // Whether the plus flavor's assistant is present, resolved once.
    val assistAvailable = remember { assist.isAvailable() }
    // The active model's context window, for sizing chunks; DEFAULT_CONTEXT_TOKENS until the flow
    // emits.
    val activeModel by remember {
        DataGraph.assistModels.models
            .map { models -> models.firstOrNull { it.active } }
    }.collectAsStateWithLifecycle(initialValue = null)
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
                    // An answer to a request already replaced.
                    return
                }
                requestId = -1
                val span = pendingSpan
                pendingSpan = null
                if (span != null && span.max <= richTextState.annotatedString.length) {
                    // The Markdown reply replaces the span in place; the rest is untouched.
                    val lengthBefore = richTextState.annotatedString.length
                    richTextState.removeTextRange(span)
                    richTextState.insertMarkdown(resultText, span.min)
                    val inserted =
                        richTextState.annotatedString.length - lengthBefore + span.max - span.min
                    // The replaced part is left selected.
                    richTextState.selection = TextRange(span.min, span.min + inserted)
                } else {
                    richTextState.setMarkdown(resultText)
                }
                composer.addResult(richTextState.toMarkdown())
                refreshRail()
                notice = if (truncated) strings[Keys.ASSIST_ANSWER_MAY_BE_INCOMPLETE] else ""
                if (pendingInstruction.isNotEmpty() &&
                    preferences.customActions.none { it.instruction == pendingInstruction } &&
                    preferences.customActions.size < CustomAction.MAX_CUSTOM_ACTIONS
                ) {
                    offeringSaveName = Composer.suggestedName(pendingInstruction)
                    offeringSaveIcon = CustomIcon.DEFAULT
                    offeringPinToBar = false
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
            assist.cancel()
            assist.disconnect()
        }
    }

    // The span a task is sent: the selection with each end grown out to its word's edge (unless
    // that is switched off), or null for the whole field. A collapsed selection is not one.
    fun resolveSpan(): TextRange? {
        val selection = richTextState.selection
        val plainText = richTextState.annotatedString.text
        return selection.takeIf {
            !it.collapsed && it.min >= 0 && it.max <= plainText.length
        }?.let { raw ->
            if (!preferences.composerSnapSelectionToWords) {
                return@let TextRange(raw.min, raw.max)
            }
            var start = raw.min
            var end = raw.max
            while (start > 0 && !plainText[start - 1].isWhitespace()) start--
            while (end < plainText.length && !plainText[end].isWhitespace()) end++
            TextRange(start, end)
        }
    }

    /** The text a task/gate looks at: the [resolveSpan] substring, or the whole field. */
    fun targetText(): String = resolveSpan()?.let { 
        richTextState.annotatedString.text.substring(it.min, it.max) 
    } ?: richTextState.annotatedString.text

    /** Drops everything but the selection, as a new version, without a model. */
    fun keepSelection() {
        if (busy) {
            return
        }
        val span = resolveSpan() ?: return
        val keptPlain = richTextState.toText(span).trim()
        if (keptPlain.isEmpty() || keptPlain == richTextState.toText().trim()) {
            return
        }
        // The selection's markdown, formatting included.
        val keptMarkdown = richTextState.toMarkdown(span)
        composer.captureBeforeRun(richTextState.toMarkdown())
        richTextState.setMarkdown(keptMarkdown)
        composer.addResult(richTextState.toMarkdown())
        refreshRail()
        richTextState.selection = TextRange(richTextState.annotatedString.length)
        notice = ""
    }

    fun runTask(task: AssistTask, instruction: String = "") {
        if (busy || richTextState.annotatedString.text.isEmpty()) {
            return
        }
        val span = resolveSpan()
        // The field's selection is moved to the grown span.
        if (span != null && (span.min != richTextState.selection.min || span.max != richTextState.selection.max)) {
            richTextState.selection = span
        }
        // Sent as markdown; the word gate counts the plain text.
        val sent = if (span != null) richTextState.toMarkdown(span) else richTextState.toMarkdown()
        // Below the task's floor, where its button is greyed out.
        if (sent.isBlank() || composerWordCount(targetText()) < task.minWords) {
            return
        }
        composer.captureBeforeRun(richTextState.toMarkdown())
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

    // Runs once, on the first composition, for autoRunTask and offerCustomActionPicker. Declared
    // after runTask, which it calls.
    LaunchedEffect(Unit) {
        if (autoRunTask != null) {
            runTask(autoRunTask)
        } else if (offerCustomActionPicker && preferences.customActions.isNotEmpty()) {
            externalCustomPickerOpen = true
        }
    }

    // Which way the version transition slides in from, set by goTo; 0 until the first navigation,
    // so the first appearance does not slide.
    var versionDirection by remember { mutableIntStateOf(0) }

    // The outer ring Box's and the editor viewport's live coordinates, mapped between when a
    // finger lands, for the swipe exclusion below.
    var outerCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var editorCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun goTo(step: () -> String?) {
        if (busy) {
            return
        }
        // A hand edit is folded in before the index is read; an edit on node 0 adds a node.
        if (!composer.isEmpty()) {
            composer.updateCurrent(richTextState.toMarkdown())
        }
        val before = composer.index
        step() ?: return
        val moved = composer.index - before
        if (moved != 0) {
            versionDirection = if (moved > 0) 1 else -1
            loadFromComposer()
        } else {
            // Still on the node shown; the editor keeps its caret and scroll.
            refreshRail()
        }
    }

    // A box resting against the bottom of the screen, lifted by imePadding above whatever
    // keyboard rises.
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    // No `label` argument: NoHardcodedTextTest reads every `label = "..."` as user-facing text.
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

    // Both gestures' commit thresholds, in px.
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { SWIPE_THRESHOLD.toPx() }
    val versionThresholdPx = with(density) { VERSION_SWIPE_THRESHOLD.toPx() }

    // The box's scale: 1f at rest, following a swipe down to FOCUS_SETTLE_SCALE at
    // SWIPE_THRESHOLD, and settling from FOCUS_SETTLE_SCALE on every focus change.
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
                // Before the clip, so the shadow falls outside the rounded corners.
                .shadow(elevation = BOX_ELEVATION, shape = shape)
                .clip(shape)
                // The moving gradient ring around the box.
                .background(ringBrush(ringShift))
                // Beside the gesture below, so the two share one coordinate space.
                .onGloballyPositioned { outerCoords = it }
                .swipeToToggleKeyboard(
                    // A gesture that starts on the editor's scroll viewport belongs to its scroll.
                    exclusion = {
                        val outer = outerCoords
                        val editor = editorCoords
                        if (outer != null && editor != null && outer.isAttached && editor.isAttached) {
                            outer.localBoundingBoxOf(editor, clipBounds = false)
                        } else {
                            null
                        }
                    },
                ) { delta, released ->
                    if (!released) {
                        // The box eases towards FOCUS_SETTLE_SCALE as the drag nears the threshold.
                        val t = (kotlin.math.abs(delta) / swipeThresholdPx).coerceIn(0f, 1f)
                        focusSettle.snapTo(1f - t * (1f - FOCUS_SETTLE_SCALE))
                        return@swipeToToggleKeyboard
                    }
                    if (delta <= -swipeThresholdPx) {
                        showKeyboard()
                    } else if (delta >= swipeThresholdPx) {
                        hideKeyboardAndUnfocus()
                    } else {
                        // Released short of the threshold: eased back to rest.
                        focusSettle.animateTo(1f, tween(FOCUS_SETTLE_MILLIS, easing = FastOutSlowInEasing))
                    }
                },
        ) {
        // A Surface, which sets LocalContentColor for everything inside.
        Surface(
            modifier = Modifier.padding(RING_WIDTH).scale(focusSettle.value),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
            // The header: the title, the hint centred, and the close button. Its sides share
            // FIELD_SIDE_GAP with the field, and the close button the format bar's square.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = FIELD_SIDE_GAP, end = FIELD_SIDE_GAP, top = 4.dp),
            ) {
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
                // Close, top right.
                IconButton(
                    onClick = {
                        activity?.setResult(Activity.RESULT_CANCELED)
                        activity?.finish()
                    },
                    modifier = Modifier.align(Alignment.CenterEnd).size(FORMAT_BAR_HEIGHT),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_close),
                        contentDescription = strings[Keys.COMPOSER_CLOSE],
                    )
                }
            }

            Box(modifier = Modifier.weight(1f, fill = false)) {
                Column {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = FIELD_SIDE_GAP, end = FIELD_SIDE_GAP, top = 2.dp, bottom = 4.dp)
                            .weight(1f, fill = false)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                            .border(
                                FIELD_BORDER_WIDTH,
                                if (isFocused) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                MaterialTheme.shapes.medium,
                            )
                            .clip(MaterialTheme.shapes.medium),
                    ) {
                        val versionSlide = remember { Animatable(0f) }
                        LaunchedEffect(rail.index) {
                            if (versionDirection == 0) return@LaunchedEffect
                            versionSlide.snapTo(versionDirection.toFloat())
                            versionSlide.animateTo(0f, tween(VERSION_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
                        }

                        Column {
                            // The format bar across the field's top: style toggles left,
                            // clipboard actions right, inside the field's border.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(FORMAT_BAR_HEIGHT)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // Bold and italic, drawn as a styled B and I.
                                    FormatToggle(
                                        active = richTextState.currentSpanStyle.fontWeight == FontWeight.Bold,
                                        description = strings[Keys.COMPOSER_FORMAT_BOLD],
                                        onClick = { richTextState.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
                                    ) {
                                        Text(
                                            "B",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        )
                                    }
                                    FormatToggle(
                                        active = richTextState.currentSpanStyle.fontStyle == FontStyle.Italic,
                                        description = strings[Keys.COMPOSER_FORMAT_ITALIC],
                                        onClick = { richTextState.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) },
                                    ) {
                                        Text(
                                            "I",
                                            style = MaterialTheme.typography.labelLarge.copy(fontStyle = FontStyle.Italic),
                                        )
                                    }
                                    FormatToggle(
                                        active = richTextState.isUnorderedList,
                                        description = strings[Keys.COMPOSER_FORMAT_BULLETS],
                                        onClick = { richTextState.toggleUnorderedList() },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.bk_composer_bullets),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                // Copy, on the field.
                                IconButton(
                                    enabled = richTextState.annotatedString.text.isNotEmpty() && !busy,
                                    onClick = {
                                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                            as? ClipboardManager
                                        // One clip with both faces: HTML and markdown.
                                        manager?.setPrimaryClip(
                                            ClipData.newHtmlText(
                                                null,
                                                richTextState.toMarkdown(),
                                                richTextState.toHtml(),
                                            ),
                                        )
                                        notice = strings[Keys.ASSIST_COPY]
                                    },
                                    modifier = Modifier.size(FORMAT_BAR_HEIGHT),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.bk_action_copy_all),
                                        contentDescription = strings[Keys.ASSIST_COPY],
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp,
                            )

                            // The scroll viewport, which the keyboard swipe excludes, measured
                            // first so the rect leaves out the version-slide offset.
                            // weight(fill = false): wraps short text, fills the box's cap after.
                            Box(
                                modifier = Modifier
                                    .onGloballyPositioned { editorCoords = it }
                                    .weight(1f, fill = false)
                                    .fillMaxWidth()
                                    .verticalScroll(scrollState)
                                    .offset(x = VERSION_TRANSITION_DISTANCE * versionSlide.value)
                                    .alpha(1f - kotlin.math.abs(versionSlide.value) * VERSION_TRANSITION_FADE)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                BasicRichTextEditor(
                                    state = richTextState,
                                    enabled = !busy,
                                    textStyle = LocalTextStyle.current.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        // A multiplier on the ambient size.
                                        fontSize = LocalTextStyle.current.fontSize *
                                            composerFontScale(preferences.composerTextSize),
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    onTextLayout = { textLayout = it },
                                    decorationBox = { innerTextField ->
                                        // The empty-field hint, in the editor's decoration slot.
                                        Box {
                                            if (richTextState.annotatedString.text.isEmpty()) {
                                                Text(
                                                    strings[Keys.COMPOSER_EMPTY],
                                                    style = LocalTextStyle.current.copy(
                                                        fontSize = LocalTextStyle.current.fontSize *
                                                            composerFontScale(preferences.composerTextSize),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                    ),
                                                )
                                            }
                                            innerTextField()
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = EDITOR_MIN_HEIGHT)
                                        .focusRequester(textFieldFocus)
                                        .onFocusChanged { isFocused = it.isFocused }
                                        // A horizontal swipe on the text steps through versions.
                                        .swipeToChangeVersion { delta, released ->
                                            if (!released) {
                                                // The text follows the finger, ±1 at the threshold.
                                                versionSlide.snapTo((delta / versionThresholdPx).coerceIn(-1f, 1f))
                                                return@swipeToChangeVersion
                                            }
                                            if (delta <= -versionThresholdPx) {
                                                goTo { composer.forward() }
                                            } else if (delta >= versionThresholdPx) {
                                                goTo { composer.back() }
                                            } else {
                                                // Released short of the threshold: eased back.
                                                versionSlide.animateTo(0f, tween(VERSION_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
                                            }
                                        },
                                )
                            }
                        }

                        // The scrollbar, painted over the field; matchParentSize keeps it out of
                        // the field's measurement.
                        Box(
                            Modifier
                                .matchParentSize()
                                .padding(top = FORMAT_BAR_HEIGHT + 4.dp, bottom = 4.dp, end = 4.dp),
                        ) {
                            VerticalScrollbar(
                                scrollState = scrollState,
                                isFocused = isFocused,
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(SCROLLBAR_WIDTH)
                                    .fillMaxHeight(),
                            )
                        }

                        // While a model runs, the field is covered, cut to its own outline.
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
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(ringBrush(ringShift, alpha = WORKING_SCRIM_ALPHA))
                                    .padding(RING_WIDTH)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = WORKING_SURFACE_ALPHA),
                                    ),
                            ) {
                                Text(
                                    // The task's label, set by runTask for the whole run.
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

                    // Hidden while busy, when the overlay shows it.
                    if (notice.isNotEmpty() && !busy) {
                        Explanation(notice)
                    }
                }
                if (offeringSaveName != null) {
                    SavePromptRow(
                        name = offeringSaveName.orEmpty(),
                        onNameChange = { offeringSaveName = it },
                        icon = offeringSaveIcon,
                        onIconChange = { offeringSaveIcon = it },
                        pinToBar = offeringPinToBar,
                        onPinToBarChange = { offeringPinToBar = it },
                        onSave = {
                            val name = offeringSaveName.orEmpty().trim()
                            val instruction = pendingInstruction
                            val icon = offeringSaveIcon
                            val pinToBar = offeringPinToBar
                            offeringSaveName = null
                            if (name.isEmpty() || instruction.isEmpty()) {
                                return@SavePromptRow
                            }
                            scope.launch {
                                themes.updatePreferences { prefs ->
                                    // Saved and, if asked, pinned to the bar in one update.
                                    val action = CustomAction(
                                        id = CustomAction.nextId(prefs.customActions),
                                        name = name,
                                        instruction = instruction,
                                        icon = icon.id,
                                    )
                                    prefs.copy(
                                        customActions = prefs.customActions + action,
                                        composerBar = if (pinToBar) prefs.composerBar + action.id
                                                     else prefs.composerBar,
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

            // The bar, with the arrows at its ends and Insert before the forward arrow. The Box
            // anchors the menu externalCustomPickerOpen opens.
            Box {
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
                        // The actions in the user's order, without INSERT. A task whose minWords
                        // is above the words it would be sent is greyed out.
                        val targetWords = composerWordCount(targetText())
                        for (item in ComposerBar.resolve(preferences.composerBar, preferences.customActions)) {
                            if (item is ComposerBarItem.Custom) {
                                ActionIcon(
                                    iconFor(CustomIcon.fromId(item.action.icon)), item.action.name, busy,
                                    custom = true,
                                ) { runTask(AssistTask.CUSTOM, item.action.instruction) }
                                continue
                            }
                            val action = (item as ComposerBarItem.Builtin).action
                            when (action) {
                                ComposerAction.CORRECT -> ActionIcon(
                                    R.drawable.bk_composer_correct, strings[Keys.COMPOSER_ACTION_CORRECT], busy,
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
                                ComposerAction.SAVED_PROMPTS -> if (preferences.customActions.isNotEmpty()) {
                                    Box {
                                        ActionIcon(
                                            R.drawable.bk_composer_saved, strings[Keys.COMPOSER_ACTION_SAVED], busy,
                                        ) { savedMenuOpen = true }
                                        AssistMenu(
                                            savedMenuOpen,
                                            onDismissRequest = { savedMenuOpen = false },
                                            ringShift = ringShift,
                                        ) {
                                            preferences.customActions.forEachIndexed { index, custom ->
                                                if (index > 0) AssistMenuDivider()
                                                DropdownMenuItem(
                                                    text = { Text(custom.name) },
                                                    onClick = {
                                                        savedMenuOpen = false
                                                        runTask(AssistTask.CUSTOM, custom.instruction)
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
                // Insert, only when the selection can be replaced, and Share each offer the
                // markdown as written or the plain rendering (toText).
                val hasText = richTextState.annotatedString.text.isNotEmpty()
                if (!readOnly) {
                    Box {
                        IconButton(
                            enabled = hasText && !busy,
                            onClick = { insertMenuOpen = true },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.bk_composer_insert),
                                contentDescription = strings[Keys.COMPOSER_INSERT],
                                // A fixed green, not the theme's colour.
                                tint = if (hasText && !busy) INSERT_GREEN else LocalContentColor.current,
                            )
                        }
                        AssistMenu(
                            insertMenuOpen,
                            onDismissRequest = { insertMenuOpen = false },
                            ringShift = ringShift,
                        ) {
                            fun finishWith(result: String) {
                                insertMenuOpen = false
                                activity?.setResult(
                                    Activity.RESULT_OK,
                                    Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result),
                                )
                                activity?.finish()
                            }
                            DropdownMenuItem(
                                text = { Text(strings[Keys.COMPOSER_WITH_FORMATTING]) },
                                onClick = { finishWith(richTextState.toMarkdown()) },
                            )
                            AssistMenuDivider()
                            DropdownMenuItem(
                                text = { Text(strings[Keys.COMPOSER_PLAIN_TEXT]) },
                                onClick = { finishWith(richTextState.toText()) },
                            )
                        }
                    }
                }
                // Share, to another app.
                Box {
                    IconButton(
                        enabled = hasText && !busy,
                        onClick = { shareMenuOpen = true },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.bk_composer_share),
                            contentDescription = strings[Keys.COMPOSER_SHARE],
                        )
                    }
                    AssistMenu(
                        shareMenuOpen,
                        onDismissRequest = { shareMenuOpen = false },
                        ringShift = ringShift,
                    ) {
                        fun shareWith(result: String, html: String?) {
                            shareMenuOpen = false
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, result)
                                // HTML alongside the text, for a target that reads it.
                                if (html != null) {
                                    putExtra(Intent.EXTRA_HTML_TEXT, html)
                                }
                            }
                            context.startActivity(Intent.createChooser(sendIntent, null))
                        }
                        DropdownMenuItem(
                            text = { Text(strings[Keys.COMPOSER_WITH_FORMATTING]) },
                            onClick = { shareWith(richTextState.toMarkdown(), richTextState.toHtml()) },
                        )
                        AssistMenuDivider()
                        DropdownMenuItem(
                            text = { Text(strings[Keys.COMPOSER_PLAIN_TEXT]) },
                            onClick = { shareWith(richTextState.toText(), null) },
                        )
                    }
                }
                IconButton(onClick = { goTo { composer.forward() } }, enabled = rail.canForward) {
                    Icon(
                        painter = painterResource(R.drawable.bk_composer_forward),
                        contentDescription = strings[Keys.COMPOSER_FORWARD],
                    )
                }
            }
            AssistMenu(
                externalCustomPickerOpen,
                onDismissRequest = { externalCustomPickerOpen = false },
                ringShift = ringShift,
            ) {
                preferences.customActions.forEachIndexed { index, custom ->
                    if (index > 0) AssistMenuDivider()
                    DropdownMenuItem(
                        text = { Text(custom.name) },
                        onClick = {
                            externalCustomPickerOpen = false
                            runTask(AssistTask.CUSTOM, custom.instruction)
                        },
                    )
                }
            }
            }
            }
        }
        }
    }
}

/**
 * What the box shows instead of itself when BorderKeys is not the keyboard in use: the same
 * frame, offering the system keyboard picker ([openKeyboardPicker]).
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

/** The green of the keyboard's caps-lock light (KeyboardCanvasView's shiftLockLedPaint). */
private val INSERT_GREEN = Color(0xFF43A047)

/**
 * The moving gradient around the box, looping without a seam; [shift] slides its start and end
 * along the diagonal.
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
    Color(0xFF8B5CF6), // violet again
)

/**
 * The context window assumed for chunk sizing until the active model's row has been read; below
 * every model in KnownAssistModels.
 */
private const val DEFAULT_CONTEXT_TOKENS = 2048

/** How long one pass of the ring takes to loop. */
private const val RING_PERIOD_MILLIS = 5000

/** The ring's own thickness. */
private val RING_WIDTH = 2.5.dp

/** The drop shadow both the draft box and the working overlay cast under their own frame. */
private val BOX_ELEVATION = 16.dp

/** How far a vertical swipe on the box must travel to show or hide the keyboard. */
private val SWIPE_THRESHOLD = 56.dp

/** How far a horizontal swipe on the text must travel to change version. */
private val VERSION_SWIPE_THRESHOLD = 72.dp

/** How far a drag must move on its dominant axis before either swipe claims it. */
private val SWIPE_DEAD_ZONE = 12.dp

/**
 * Claims a vertical drag once it clears [SWIPE_DEAD_ZONE] and is more vertical than horizontal,
 * then calls [onChange] with the running total on every further move (`released = false`) and
 * once more on lift (`released = true`, total 0f if never claimed). Read in
 * [PointerEventPass.Initial]; nothing is consumed before the claim. A gesture whose down lands in
 * [exclusion]'s rect, read per gesture, is not watched.
 */
private fun Modifier.swipeToToggleKeyboard(
    exclusion: () -> Rect? = { null },
    onChange: suspend (delta: Float, released: Boolean) -> Unit,
): Modifier =
    pointerInput(Unit) {
        val deadZone = SWIPE_DEAD_ZONE.toPx()
        awaitAxisSwipe(deadZone, primary = { it.y }, secondary = { it.x }, exclusion = exclusion, onChange = onChange)
    }

/**
 * [swipeToToggleKeyboard]'s horizontal counterpart, on the text: right for the previous version,
 * left for the next.
 */
private fun Modifier.swipeToChangeVersion(onChange: suspend (delta: Float, released: Boolean) -> Unit): Modifier =
    pointerInput(Unit) {
        val deadZone = SWIPE_DEAD_ZONE.toPx()
        awaitAxisSwipe(deadZone, primary = { it.x }, secondary = { it.y }, onChange = onChange)
    }

/**
 * Tracks a drag until it commits to one axis past [deadZone], then consumes the rest of the
 * gesture and reports the running signed total to [onChange] on every move and once on release.
 * A drag that never crosses [deadZone] is left unconsumed.
 */
private suspend fun PointerInputScope.awaitAxisSwipe(
    deadZone: Float,
    primary: (Offset) -> Float,
    secondary: (Offset) -> Float,
    exclusion: () -> Rect? = { null },
    onChange: suspend (delta: Float, released: Boolean) -> Unit,
) = coroutineScope {
    // Each onChange call is launched in this coroutineScope: awaitEachGesture's own scope can
    // await only its own suspend functions.
    awaitEachGesture {
        val down = awaitFirstDown(pass = PointerEventPass.Initial)
        // A gesture that starts in the excluded region is left to what is under it.
        if (exclusion()?.contains(down.position) == true) {
            return@awaitEachGesture
        }
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

/** Whether the swipe hint has already played in this process; not saved. */
private var swipeHintShown = false

/**
 * One toggle on the format bar: a round chip filled with the primary colour while its style is
 * active at the cursor. [content] draws the chip; [description] is its accessibility label.
 */
@Composable
private fun FormatToggle(
    active: Boolean,
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = FORMAT_TOGGLE_ACTIVE_ALPHA)
        } else {
            Color.Transparent
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .size(FORMAT_TOGGLE_SIZE)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}

/**
 * The thin thumb along the field's edge while the text can scroll: an indicator that takes no
 * touches, shown on any scroll and faded out once the text has settled.
 */
@Composable
private fun VerticalScrollbar(
    scrollState: ScrollState,
    isFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    // Matching the field's border logic: primary when focused, outline otherwise.
    val color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val visibility = remember { Animatable(0f) }
    LaunchedEffect(scrollState) {
        // drop(1): the first value is not a movement. collectLatest restarts the linger.
        snapshotFlow { scrollState.value }.drop(1).collectLatest {
            visibility.snapTo(1f)
            delay(SCROLLBAR_FADE_DELAY_MILLIS)
            visibility.animateTo(0f, tween(SCROLLBAR_FADE_MILLIS))
        }
    }
    Canvas(modifier = modifier) {
        // maxValue is Int.MAX_VALUE until the first real measure -- not a scrollable range.
        if (scrollState.maxValue <= 0 || scrollState.maxValue == Int.MAX_VALUE || visibility.value <= 0f) {
            return@Canvas
        }
        val viewportHeight = size.height
        val contentHeight = scrollState.maxValue.toFloat() + viewportHeight
        val thumbHeight = (viewportHeight * viewportHeight / contentHeight)
            .coerceAtLeast(SCROLLBAR_MIN_THUMB_HEIGHT.toPx())
        val travel = viewportHeight - thumbHeight
        if (travel <= 0f) {
            return@Canvas
        }
        val top = travel * (scrollState.value.toFloat() / scrollState.maxValue)
        drawRoundRect(
            color = color.copy(alpha = SCROLLBAR_ALPHA * visibility.value),
            topLeft = Offset(0f, top),
            size = Size(size.width, thumbHeight),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}

/**
 * "Swipe up for keyboard", rising and fading [SWIPE_HINT_REPEAT_COUNT] times, once per process
 * ([swipeHintShown]), painted in [ringShift]'s gradient. One [progress] drives the rise and the
 * alpha, a half sine of it, so each cycle starts and ends transparent. [focused] stops it.
 */
@Composable
private fun SwipeUpHint(ringShift: Float, focused: Boolean, modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val progress = remember { Animatable(0f) }
    // Hides the text once focus arrives, multiplied with the rise's alpha.
    val focusFade = remember { Animatable(1f) }
    val latestFocused = rememberUpdatedState(focused)
    LaunchedEffect(Unit) {
        if (swipeHintShown) {
            return@LaunchedEffect
        }
        // Marked at the start, so an interrupted run counts.
        swipeHintShown = true
        repeat(SWIPE_HINT_REPEAT_COUNT) {
            if (latestFocused.value) {
                return@LaunchedEffect
            }
            progress.snapTo(0f)
            progress.animateTo(1f, tween(SWIPE_HINT_CYCLE_MILLIS, easing = FastOutSlowInEasing))
        }
    }
    // Fades out at once when focus arrives mid-cycle.
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

/** The field's side gap inside the card. */
private val FIELD_SIDE_GAP = 12.dp

private val MENU_CORNER_RADIUS = 14.dp
private val MENU_SHADOW_ELEVATION = 10.dp
private val MENU_BORDER_WIDTH = 1.5.dp

/** The format bar's height, and the square size of each clipboard button on it. */
private val FORMAT_BAR_HEIGHT = 40.dp

/** The round chip behind each style toggle, and how strongly it fills while active. */
private val FORMAT_TOGGLE_SIZE = 32.dp
private const val FORMAT_TOGGLE_ACTIVE_ALPHA = 0.15f

/** The editor's least height. */
private val EDITOR_MIN_HEIGHT = 48.dp

private val FIELD_BORDER_WIDTH = 1.dp

private val SCROLLBAR_WIDTH = 4.dp
private val SCROLLBAR_MIN_THUMB_HEIGHT = 32.dp

/** The thumb's rest opacity while visible; [VerticalScrollbar]'s own fade scales it away. */
private const val SCROLLBAR_ALPHA = 0.5f

/** How long the thumb lingers after the last scroll movement before starting to fade. */
private const val SCROLLBAR_FADE_DELAY_MILLIS = 800L

private const val SCROLLBAR_FADE_MILLIS = 300

/** The hint's size, as a multiple of [MaterialTheme.typography.labelLarge]'s. */
private const val SWIPE_HINT_SIZE_MULTIPLIER = 1.4f

/** How many times the hint rises and fades, once per process. */
private const val SWIPE_HINT_REPEAT_COUNT = 2

/** The hint's rise speed. */
private const val SWIPE_HINT_SPEED_DP_PER_SECOND = 16f

/** How far each rise travels. */
private val SWIPE_HINT_DISTANCE = 22.dp

/** [SWIPE_HINT_DISTANCE] at [SWIPE_HINT_SPEED_DP_PER_SECOND]. */
private val SWIPE_HINT_CYCLE_MILLIS =
    (SWIPE_HINT_DISTANCE.value / SWIPE_HINT_SPEED_DP_PER_SECOND * 1000).roundToInt()

/** How quickly the hint fades once the field is focused. */
private const val SWIPE_HINT_FOCUS_FADE_MILLIS = 150

/** How far the field slides in from on a version change. */
private val VERSION_TRANSITION_DISTANCE = 24.dp

/** How much of the field's opacity the slide dips at its furthest point (versionSlide at ±1). */
private const val VERSION_TRANSITION_FADE = 0.6f

private const val VERSION_TRANSITION_MILLIS = 220

/** How far the box shrinks for its settle when focus flips. */
private const val FOCUS_SETTLE_SCALE = 0.985f

private const val FOCUS_SETTLE_MILLIS = 220

/** How long [showKeyboard]'s scroll hold outlasts the focus handoff. */
private const val SHOW_KEYBOARD_SCROLL_HOLD_MILLIS = 900L

/** The busy overlay's two layers: the ring's gradient, and a surface-coloured layer inside it. */
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
 * The dropdown a bar action with more than one target opens: rounded, shadowed, and edged with
 * the ring's gradient.
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

/** A hairline between two [AssistMenu] rows, inset from the border. */
@Composable
private fun AssistMenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp),
        thickness = Dp.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Whitespace-separated words in [text], as the per-task gate counts them. */
private fun composerWordCount(text: String): Int =
    text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

/** A bar button: an icon with its [label] under it; the icon carries no description of its own. */
@Composable
private fun ActionIcon(icon: Int, label: String, disabled: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    val alpha = if (disabled) DISABLED_ALPHA else 1f
    Column(
        modifier = Modifier
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(vertical = 4.dp)
            .widthIn(min = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = alpha),
                modifier = Modifier.size(22.dp),
            )
            // A dot marks a custom action, as on QuickActionsView's bar.
            if (custom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
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
 * The dots between the two arrows: one per version, the current one larger, the ends coloured
 * apart. A tap goes to that version.
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
    icon: CustomIcon,
    onIconChange: (CustomIcon) -> Unit,
    pinToBar: Boolean,
    onPinToBarChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
) {
    val strings = LocalStrings.current
    var pickingIcon by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { pickingIcon = true }) {
                    Icon(
                        painter = painterResource(iconFor(icon)),
                        contentDescription = strings[Keys.COMPOSER_ICON_PICK],
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pickingIcon) {
                    IconPickerDialog(
                        selected = icon,
                        onPick = { pickingIcon = false; onIconChange(it) },
                        onDismiss = { pickingIcon = false },
                    )
                }
            }
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
        // Pins the saved action to the bar in the same step.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onPinToBarChange(!pinToBar) },
        ) {
            Checkbox(checked = pinToBar, onCheckedChange = onPinToBarChange)
            Text(strings[Keys.COMPOSER_CUSTOM_ACTION_PIN_TO_BAR], style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The field's text-size multiplier for [KeyboardPreferences.composerTextSize]; Medium is 1. */
private fun composerFontScale(step: Int): Float = when (step) {
    KeyboardPreferences.COMPOSER_TEXT_SIZE_SMALL -> 0.85f
    KeyboardPreferences.COMPOSER_TEXT_SIZE_LARGE -> 1.25f
    else -> 1f
}

/**
 * What the working overlay says while a request runs, naming the task, its language or its tone.
 * Exhaustive, with no `else`.
 */
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

/** The message for an assistant error; mirrors BorderKeysService.assistErrorMessage. */
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
