// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borderkeys.data.DataGraph
import com.borderkeys.data.KeyboardStats
import com.borderkeys.i18n.Keys
import com.borderkeys.i18n.LanguageManager
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** The one-line handle above the probe field: the title and an arrow that opens the panel. */
@Composable
fun DebugStatsLine(expanded: Boolean, onToggle: () -> Unit) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            strings[Keys.DEBUG_STATS],
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) EXPANDED_GLYPH else COLLAPSED_GLYPH,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The keyboard's own measurements, re-read twice a second while shown: the strip and search
 * latency per keystroke, the swipe decode, lift-to-text, path, duration and sample figures,
 * the decoder in use, the pack load time, the typing speed since the last reset, and the
 * process's memory. A figure with a baseline carries a green, amber or red dot against it and
 * a line on what it is and whether anything changes it; a figure that is only a fact about
 * the typing carries neither. Fills the space it is given, on the surface colour, so nothing
 * shows through from behind it.
 */
@Composable
fun DebugStatsPanel(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MILLIS)
            tick++
        }
    }
    val themes = remember { DataGraph.themes }
    val preferences by themes.preferences
        .collectAsStateWithLifecycle(initialValue = remember { themes.currentPreferences() })
    val neuralSetting = preferences.experimentalSwipeModelEnabled
    val rows = remember(tick, strings, neuralSetting) { statsRows(strings, neuralSetting) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            strings[Keys.DEBUG_STATS_LEGEND],
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RatingLegend(strings)
        for (row in rows) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.rating != null) {
                        RatingDot(row.rating)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (row.hint != null) {
                    Text(
                        row.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(top = 12.dp)) {
            TextButton(onClick = { KeyboardStats.reset(); tick++ }) {
                Text(strings[Keys.DEBUG_STATS_RESET])
            }
            TextButton(onClick = { shareStats(context, strings, statsRows(strings, neuralSetting, Locale.ROOT)) }) {
                Text(strings[Keys.DEBUG_STATS_SHARE])
            }
        }
    }
}

/**
 * Hands the panel as plain text to the system's share sheet: a header naming the build, the
 * device and the moment, the legend, then every row with its rating word.
 */
private fun shareStats(context: Context, strings: LanguageManager, rows: List<StatRow>) {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
    val title = strings[Keys.DEBUG_STATS_SHARE_TITLE]
    val text = buildString {
        appendLine(title)
        appendLine(
            strings.getString(
                Keys.DEBUG_STATS_SHARE_HEADER,
                version,
                Build.MANUFACTURER + " " + Build.MODEL,
                Build.VERSION.RELEASE,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date()),
            ),
        )
        appendLine(strings[Keys.DEBUG_STATS_LEGEND])
        for (row in rows) {
            val rating = when (row.rating) {
                KeyboardStats.Rating.GOOD -> strings[Keys.DEBUG_STATS_RATING_GOOD]
                KeyboardStats.Rating.FAIR -> strings[Keys.DEBUG_STATS_RATING_FAIR]
                KeyboardStats.Rating.POOR -> strings[Keys.DEBUG_STATS_RATING_POOR]
                null -> null
            }
            appendLine(
                if (rating == null) {
                    strings.getString(Keys.DEBUG_STATS_SHARE_LINE, row.label, row.value)
                } else {
                    strings.getString(Keys.DEBUG_STATS_SHARE_LINE_RATED, row.label, row.value, rating)
                },
            )
        }
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching {
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** One line of the panel: its label, its value, its rating against a baseline, and a hint. */
private class StatRow(
    val label: String,
    val value: String,
    val rating: KeyboardStats.Rating? = null,
    val hint: String? = null,
)

@Composable
private fun RatingDot(rating: KeyboardStats.Rating) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
                when (rating) {
                    KeyboardStats.Rating.GOOD -> GOOD_COLOUR
                    KeyboardStats.Rating.FAIR -> FAIR_COLOUR
                    KeyboardStats.Rating.POOR -> POOR_COLOUR
                },
            ),
    )
}

@Composable
private fun RatingLegend(strings: LanguageManager) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        for ((rating, key) in listOf(
            KeyboardStats.Rating.GOOD to Keys.DEBUG_STATS_RATING_GOOD,
            KeyboardStats.Rating.FAIR to Keys.DEBUG_STATS_RATING_FAIR,
            KeyboardStats.Rating.POOR to Keys.DEBUG_STATS_RATING_POOR,
        )) {
            RatingDot(rating)
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                strings[key],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

private fun statsRows(
    strings: LanguageManager,
    neuralSetting: Boolean,
    locale: Locale = Locale.getDefault(),
): List<StatRow> {
    val none = strings[Keys.DEBUG_STATS_NONE]
    fun decimal(value: Double): String = String.format(locale, "%.1f", value)
    fun millis(series: KeyboardStats.Series): String {
        val s = series.snapshot()
        if (s.count == 0) {
            return none
        }
        return strings.getString(
            Keys.DEBUG_STATS_ROW, decimal(s.last), decimal(s.mean), decimal(s.max), s.count,
        )
    }
    fun plain(series: KeyboardStats.Series): String {
        val s = series.snapshot()
        if (s.count == 0) {
            return none
        }
        return strings.getString(
            Keys.DEBUG_STATS_COUNT_ROW, decimal(s.last), decimal(s.mean), decimal(s.max), s.count,
        )
    }
    val first = KeyboardStats.firstInputAt
    val minutes = if (first == 0L) 0.0 else (android.os.SystemClock.uptimeMillis() - first) / 60_000.0
    val speed = if (minutes > 0.0 && (KeyboardStats.words > 0 || KeyboardStats.keystrokes > 0)) {
        strings.getString(
            Keys.DEBUG_STATS_SPEED_VALUE,
            (KeyboardStats.words / minutes).toInt(),
            (KeyboardStats.keystrokes / minutes).toInt(),
        )
    } else {
        none
    }
    // The decoder the next swipe goes to, from the setting, named in the swipe rows' labels;
    // the last swipe's own tier follows when the switch has been flipped since.
    fun tier(neural: Boolean): String =
        strings[if (neural) Keys.DEBUG_STATS_DECODER_NEURAL else Keys.DEBUG_STATS_DECODER_GEOMETRIC]
    val swiped = KeyboardStats.decodeMillis.snapshot().count > 0
    val decoder = if (swiped && KeyboardStats.neuralDecoder != neuralSetting) {
        strings.getString(Keys.DEBUG_STATS_DECODER_CHANGED, tier(neuralSetting), tier(KeyboardStats.neuralDecoder))
    } else {
        strings.getString(Keys.DEBUG_STATS_DECODER_TYPE, tier(neuralSetting))
    }
    fun withDecoder(label: String) = strings.getString(Keys.DEBUG_STATS_WITH_DECODER, label, decoder)

    // A rated millisecond row: the dot judges the mean, the hint carries the baseline.
    fun ratedMillis(label: String, series: KeyboardStats.Series, good: Double, fair: Double, hintKey: String): StatRow {
        val s = series.snapshot()
        val baseline = strings.getString(
            Keys.DEBUG_STATS_BASELINE,
            strings.getString(Keys.DEBUG_STATS_MS, good.toInt()),
            strings.getString(Keys.DEBUG_STATS_MS, fair.toInt()),
        )
        return StatRow(
            label, millis(series),
            if (s.count == 0) null else KeyboardStats.rate(s.mean, good, fair),
            strings[hintKey] + " " + baseline,
        )
    }
    val packLoad = KeyboardStats.packLoadMillis
    val pss = android.os.Debug.getPss() / 1024
    val native = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
    val samples = KeyboardStats.swipeSamples.snapshot()
    val samplesBaseline = strings.getString(
        Keys.DEBUG_STATS_BASELINE_FLOOR, SAMPLES_GOOD.toInt(), SAMPLES_FAIR.toInt(),
    )
    val packsBaseline = strings.getString(
        Keys.DEBUG_STATS_BASELINE,
        strings.getString(Keys.DEBUG_STATS_MS, PACKS_GOOD_MS.toInt()),
        strings.getString(Keys.DEBUG_STATS_MS, PACKS_FAIR_MS.toInt()),
    )
    val memoryBaseline = strings.getString(
        Keys.DEBUG_STATS_BASELINE,
        strings.getString(Keys.DEBUG_STATS_MB, MEMORY_GOOD_MB.toInt()),
        strings.getString(Keys.DEBUG_STATS_MB, MEMORY_FAIR_MB.toInt()),
    )
    return listOf(
        ratedMillis(strings[Keys.DEBUG_STATS_SUGGESTIONS], KeyboardStats.suggestionMillis, SUGGESTIONS_GOOD_MS, SUGGESTIONS_FAIR_MS, Keys.DEBUG_STATS_HINT_SUGGESTIONS),
        ratedMillis(strings[Keys.DEBUG_STATS_SEARCH], KeyboardStats.searchMillis, SEARCH_GOOD_MS, SEARCH_FAIR_MS, Keys.DEBUG_STATS_HINT_SEARCH),
        ratedMillis(withDecoder(strings[Keys.DEBUG_STATS_DECODE]), KeyboardStats.decodeMillis, DECODE_GOOD_MS, DECODE_FAIR_MS, Keys.DEBUG_STATS_HINT_DECODE),
        ratedMillis(withDecoder(strings[Keys.DEBUG_STATS_LIFT]), KeyboardStats.liftToTextMillis, LIFT_GOOD_MS, LIFT_FAIR_MS, Keys.DEBUG_STATS_HINT_LIFT),
        StatRow(strings[Keys.DEBUG_STATS_SWIPE_DURATION], millis(KeyboardStats.swipeMillis)),
        StatRow(strings[Keys.DEBUG_STATS_PATH], plain(KeyboardStats.swipePathKeys)),
        StatRow(
            strings[Keys.DEBUG_STATS_SAMPLES], plain(KeyboardStats.swipeSamples),
            if (samples.count == 0) null else KeyboardStats.rate(samples.mean, SAMPLES_GOOD, SAMPLES_FAIR, higherIsBetter = true),
            strings[Keys.DEBUG_STATS_HINT_SAMPLES] + " " + samplesBaseline,
        ),
        StatRow(strings[Keys.DEBUG_STATS_CANDIDATES], plain(KeyboardStats.swipeCandidates)),
        StatRow(
            strings[Keys.DEBUG_STATS_PACKS],
            if (packLoad < 0) none else strings.getString(Keys.DEBUG_STATS_MS, packLoad),
            if (packLoad < 0) null else KeyboardStats.rate(packLoad.toDouble(), PACKS_GOOD_MS, PACKS_FAIR_MS),
            strings[Keys.DEBUG_STATS_HINT_PACKS] + " " + packsBaseline,
        ),
        StatRow(strings[Keys.DEBUG_STATS_SPEED], speed, null, strings[Keys.DEBUG_STATS_HINT_SPEED]),
        StatRow(
            strings[Keys.DEBUG_STATS_MEMORY],
            strings.getString(Keys.DEBUG_STATS_MEMORY_VALUE, pss, native),
            KeyboardStats.rate(pss.toDouble(), MEMORY_GOOD_MB, MEMORY_FAIR_MB),
            strings[Keys.DEBUG_STATS_HINT_MEMORY] + " " + memoryBaseline,
        ),
    )
}

private const val REFRESH_MILLIS = 500L

/** Baselines: the mean up to which a figure is fine, and up to which it is merely noticeable. */
private const val SUGGESTIONS_GOOD_MS = 40.0
private const val SUGGESTIONS_FAIR_MS = 100.0
private const val SEARCH_GOOD_MS = 5.0
private const val SEARCH_FAIR_MS = 15.0
private const val DECODE_GOOD_MS = 10.0
private const val DECODE_FAIR_MS = 30.0
private const val LIFT_GOOD_MS = 80.0
private const val LIFT_FAIR_MS = 150.0
private const val PACKS_GOOD_MS = 300.0
private const val PACKS_FAIR_MS = 1000.0
private const val MEMORY_GOOD_MB = 200.0
private const val MEMORY_FAIR_MB = 300.0

/** Touch samples per swipe: fine from this many up, merely noticeable from the second. */
private const val SAMPLES_GOOD = 12.0
private const val SAMPLES_FAIR = 6.0

private val GOOD_COLOUR = Color(0xFF43A047)
private val FAIR_COLOUR = Color(0xFFFFB300)
private val POOR_COLOUR = Color(0xFFE53935)
private const val COLLAPSED_GLYPH = "▲"
private const val EXPANDED_GLYPH = "▼"
