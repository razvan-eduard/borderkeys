// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.ime

import android.content.res.AssetManager
import com.borderkeys.data.BundledDictionaries
import com.borderkeys.data.DataGraph
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.LanguagePackRepository
import com.borderkeys.data.decayed
import com.borderkeys.data.entity.LanguagePackEntry
import com.borderkeys.data.theme.KeyboardPreferences
import com.borderkeys.predict.LanguagePackInspector
import com.borderkeys.predict.PredictionEngine
import com.borderkeys.predict.RefusedWords
import com.borderkeys.predict.WordFold
import com.borderkeys.typing.TypingOrchestrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The dictionaries behind the engine: the enabled language packs, a bundled one replaced where
 * stale, and what each brings (accents, offensive words, emoji keywords, apostrophe spellings);
 * the blocked words; and the personal dictionary. One load runs at a time.
 */
internal class DictionaryLoader(
    private val assets: AssetManager,
    private val engine: PredictionEngine,
    private val orchestrator: TypingOrchestrator,
    /** The settings as they stand. */
    private val preferences: () -> KeyboardPreferences,
    /** Runs on the main thread once a load has read what the packs bring, before they load. */
    private val onPacksChosen: () -> Unit,
) {
    /** Serialises [load] and [reloadPersonal]. */
    private val lock = Mutex()

    /** Diacritics for the enabled languages, merged onto the letter keys. */
    var accentOverlays: Map<Char, String> = emptyMap()
        private set

    /** Distinguishes one enabled-language set from another in the compiled-geometry cache key. */
    var accentSignature: String = ""
        private set

    /** The enabled packs' tags, whose accents [accentOverlays] holds. */
    var accentTags: List<String> = emptyList()
        private set

    /** Every accents file by tag, for the languages the user adds; see [AccentOverlays.withExtra]. */
    var everyAccentOverlay: Map<String, Map<Char, String>> = emptyMap()
        private set

    /** The emoji panel's keywords for the languages switched on; see [EmojiKeywords]. */
    var emojiKeywords: Map<String, List<String>> = emptyMap()
        private set

    /** The offensive-word lists of the enabled packs, merged and folded; see [OffensiveWords]. */
    private var offensiveWords: Set<String> = emptySet()

    /** The pack files [LanguagePackInspector] has accepted in this process, as `path:sha256`. */
    private val readablePacks = HashSet<String>()

    /**
     * Loads the enabled language packs, re-hashing each first, then the blocked words and the
     * personal dictionary. A pack whose hash no longer matches is switched off.
     */
    suspend fun load() {
        lock.withLock {
            val repository = DataGraph.languagePacks
            reinstallOutdatedBundledPacks(repository)
            repository.verifyEnabled()

            // Heaviest first, cut to the engine's slots.
            val everyEnabled = repository.enabledPacks()
            val enabled = everyEnabled.take(LanguagePackRepository.MAX_ENABLED)
            if (enabled.size < everyEnabled.size) {
                android.util.Log.w(
                    "BorderKeys",
                    "${everyEnabled.size} packs enabled, loading the ${enabled.size} heaviest",
                )
            }

            // The accents, offensive words, emoji keywords and contractions of the enabled packs.
            accentOverlays = AccentOverlays.merge(enabled.map { AccentOverlays.load(assets, it.tag) })
            accentSignature = enabled.joinToString(",") { it.tag }
            accentTags = enabled.map { it.tag }
            if (everyAccentOverlay.isEmpty()) {
                everyAccentOverlay = AccentOverlays.loadAll(assets)
            }
            orchestrator.languageTags = enabled.map { it.tag }
            offensiveWords = OffensiveWords.merge(enabled.map { OffensiveWords.load(assets, it.tag) })
            emojiKeywords = EmojiKeywords.load(assets, enabled.map { it.tag })
            orchestrator.contractions = Contractions.of(
                enabled.map { Contractions.load(assets, it.tag) },
                enabled.map { it.tag },
            )
            orchestrator.twins = Contractions.twinsOf(enabled.map { it.tag to Contractions.loadPairs(assets, it.tag) })
            withContext(Dispatchers.Main) { onPacksChosen() }

            // The set is sent before the packs load, to free the slots of packs no longer named,
            // and again after; also when it is empty.
            val tags = Array(enabled.size) { enabled[it].tag }
            val weights = FloatArray(enabled.size) { enabled[it].weight }
            engine.setActiveLanguages(tags, weights)
            for (entry in enabled) {
                val file = repository.fileFor(entry)
                if (!file.isFile) {
                    continue
                }
                runCatching {
                    val descriptor = android.content.res.AssetFileDescriptor(
                        android.os.ParcelFileDescriptor.open(
                            file, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                        ),
                        0L,
                        file.length(),
                    )
                    engine.loadLanguage(entry.tag, descriptor, entry.weight)
                }
            }
            engine.setActiveLanguages(tags, weights)
            engine.markPacksLoaded(tags.toList())

            val dictionary = DataGraph.dictionary
            refreshBlockedWords(dictionary)
            loadPersonalModel(dictionary)
        }
    }

    /** Reloads the blocked words and the personal dictionary. */
    suspend fun reloadPersonal() {
        lock.withLock {
            val dictionary = DataGraph.dictionary
            refreshBlockedWords(dictionary)
            loadPersonalModel(dictionary)
        }
    }

    /**
     * Pushes the blocked words to the engine, which treats them as absent from every dictionary,
     * and them and the offensive words, while their switch is on, to the engine's filter and to
     * the learning buffer. Not under [load]'s lock.
     */
    suspend fun refreshBlockedWords(dictionary: DictionaryRepository) {
        val blocked = dictionary.blockedWordSet()
        val refused = RefusedWords.of(
            blocked,
            if (preferences().blockOffensiveWords) offensiveWords else emptySet(),
        )
        engine.setBlockedWords(blocked)
        engine.setRefusedWords(refused)
        orchestrator.setRefusedWords(refused)
    }

    /**
     * Pushes the personal dictionary into the native model, each entry decayed for how long it
     * has sat unused. Not under [load]'s lock.
     */
    suspend fun loadPersonalModel(dictionary: DictionaryRepository) {
        val now = System.currentTimeMillis()
        // With the offensive-word switch on, words, pairs and triples that contain an offensive
        // word are left out.
        val hidden = if (preferences().blockOffensiveWords) offensiveWords else emptySet()
        fun shown(word: String) = hidden.isEmpty() || WordFold.fold(word) !in hidden
        engine.loadUserWords(
            dictionary.topWords(preferences().learnedWordLimit)
                .filter { shown(it.word) }
                .map { it.decayed(now) },
        )
        engine.loadUserBigrams(
            dictionary.topBigrams()
                .filter { shown(it.previousWord) && shown(it.word) }
                .map { it.decayed(now) },
        )
        engine.loadUserTrigrams(
            dictionary.topTrigrams()
                .filter { shown(it.previousWord2) && shown(it.previousWord1) && shown(it.word) }
                .map { it.decayed(now) },
        )
    }

    /**
     * Replaces bundled packs that this build cannot read or ships a different edition of, from
     * the assets. Imported packs are left alone. Failures are logged.
     */
    private suspend fun reinstallOutdatedBundledPacks(repository: LanguagePackRepository) {
        runCatching { repairBundledPacks(repository) }
            .onFailure { android.util.Log.w("BorderKeys", "pack repair failed", it) }
    }

    private fun packReadable(file: File, sha256: String): Boolean {
        val key = "${file.path}:$sha256"
        if (key in readablePacks) {
            return true
        }
        val readable = LanguagePackInspector.inspect(file) is LanguagePackInspector.Result.Valid
        if (readable) {
            readablePacks += key
        }
        return readable
    }

    private suspend fun repairBundledPacks(repository: LanguagePackRepository) {
        for (entry in repository.allPacks()) {
            val bundled = BundledDictionaries.ALL.firstOrNull { it.tag == entry.tag } ?: continue
            val file = repository.fileFor(entry)
            // Stale: missing, unreadable, not matching its hash, or a different edition than the
            // shipped one by content CRC, word count or size.
            val shipped = runCatching {
                BundledDictionaries.open(assets, bundled).use { BundledDictionaries.contentCrc(it) }
            }.getOrNull()
            val installed = runCatching {
                file.inputStream().use { BundledDictionaries.contentCrc(it) }
            }.getOrNull()
            val stale = !file.isFile ||
                shipped == null || shipped != installed ||
                entry.wordCount != bundled.wordCount ||
                entry.sizeBytes != bundled.sizeBytes ||
                runCatching { repository.cachedSha256(file) }.getOrNull() != entry.sha256 ||
                !packReadable(file, entry.sha256)
            if (!stale) {
                continue
            }
            val staged = runCatching {
                BundledDictionaries.open(assets, bundled).use { stream ->
                    repository.stage(stream, bundled.fileName)
                }
            }.getOrNull()?.getOrNull() ?: continue

            val checked = LanguagePackInspector.inspect(staged.file)
            if (checked !is LanguagePackInspector.Result.Valid) {
                staged.file.delete()
                continue
            }
            readablePacks += "${staged.file.path}:${staged.sha256}"
            repository.replace(
                LanguagePackEntry(
                    id = entry.id,
                    tag = checked.info.tag,
                    displayName = entry.displayName,
                    fileName = staged.file.name,
                    formatVersion = checked.info.formatVersion,
                    wordCount = checked.info.wordCount,
                    sizeBytes = staged.sizeBytes,
                    sha256 = staged.sha256,
                    importedAt = System.currentTimeMillis(),
                    // Switched back on only where an integrity failure switched it off.
                    enabled = entry.enabled || entry.integrityFailedAt != null,
                    weight = entry.weight,
                    integrityFailedAt = null,
                    licenseNote = entry.licenseNote,
                ),
            )
            android.util.Log.i(
                "BorderKeys",
                "replaced the bundled ${entry.tag} pack: unreadable by this build, or an older edition than it ships",
            )
        }
    }
}
