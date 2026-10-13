// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

package com.borderkeys.data.backup

import androidx.room.withTransaction
import com.borderkeys.data.BorderKeysDatabase
import com.borderkeys.data.ClipboardRepository
import com.borderkeys.data.DictionaryRepository
import com.borderkeys.data.PhraseKey
import com.borderkeys.data.dao.LearnedBigram
import com.borderkeys.data.dao.LearnedTrigram
import com.borderkeys.data.dao.LearnedWord
import com.borderkeys.data.entity.BlockedWord
import com.borderkeys.data.entity.UserPairContext
import com.borderkeys.data.entity.UserTripleContext
import com.borderkeys.data.theme.ThemeRepository
import kotlinx.coroutines.flow.first

/**
 * Gathering what a keyboard knows, and putting it back, in parts chosen one at a time. Importing
 * adds where it can (a dictionary merges by summing counts); settings, theme, particle effects and
 * size and position are each replaced whole.
 */
class BackupRepository(
    private val database: BorderKeysDatabase,
    private val themes: ThemeRepository,
    private val dictionary: DictionaryRepository,
    /** The language of a restored link that names none and whose words name none either. */
    private val fallbackLanguage: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** What the caller asked to include. Each is a separate answer. */
    data class Parts(
        val settings: Boolean = false,
        val theme: Boolean = false,
        val particleEffects: Boolean = false,
        val sizeAndPosition: Boolean = false,
        val dictionary: Boolean = false,
        val languages: Boolean = false,
        val clipboard: Boolean = false,
    ) {
        val any: Boolean
            get() = settings || theme || particleEffects || sizeAndPosition ||
                dictionary || languages || clipboard
    }

    suspend fun gather(parts: Parts): BackupPayload {
        // Read once for settings and for size and position.
        val rawPreferences = if (parts.settings || parts.sizeAndPosition) {
            themes.preferences.first()
        } else {
            null
        }
        val theme = if (parts.theme) themes.theme.first() else null
        val customThemes = if (parts.theme) {
            themes.customThemes.first().map {
                BackupCustomTheme(id = it.id, name = it.name, theme = it.theme, createdAt = it.createdAt)
            }
        } else {
            emptyList()
        }
        val particleEffects = if (parts.particleEffects) themes.particleEffects.first() else null
        val sizeAndPosition = if (parts.sizeAndPosition) {
            rawPreferences?.let { BackupSizeAndPosition(it.placementFor(false), it.placementFor(true)) }
        } else {
            null
        }
        // The active model, with the settings.
        val models = if (parts.settings) {
            database.assistModelDao().observeAll().first().map {
                BackupModel(fileName = it.fileName, sha256 = it.sha256, active = it.active)
            }
        } else {
            emptyList()
        }

        val packs = if (parts.languages) {
            database.languagePackDao().observeAll().first().map {
                BackupPack(tag = it.tag, enabled = it.enabled, weight = it.weight)
            }
        } else {
            emptyList()
        }

        // Every word.
        val words = if (parts.dictionary) {
            database.userWordDao().topWords(Int.MAX_VALUE).map {
                BackupWord(
                    word = it.word,
                    locale = it.locale,
                    count = it.count,
                    lastUsedAt = it.lastUsedAt,
                    deliberateCapitals = it.deliberateCapitals,
                    asserted = it.asserted,
                )
            }
        } else {
            emptyList()
        }
        val bigrams = if (parts.dictionary) {
            database.userBigramDao().all().map {
                BackupBigram(
                    previous = it.previousWord,
                    word = it.word,
                    count = it.count,
                    language = it.language,
                    lastUsedAt = it.lastUsedAt,
                    contextSeen = it.contextSeen,
                    languageSeen = it.languageSeen,
                )
            }
        } else {
            emptyList()
        }
        val trigrams = if (parts.dictionary) {
            database.userTrigramDao().all().map {
                BackupTrigram(
                    previous2 = it.previousWord2,
                    previous1 = it.previousWord1,
                    word = it.word,
                    count = it.count,
                    language = it.language,
                    lastUsedAt = it.lastUsedAt,
                    contextSeen = it.contextSeen,
                    languageSeen = it.languageSeen,
                )
            }
        } else {
            emptyList()
        }
        // Each context's uses and each language's clocks, with the pairs and triples.
        val pairContexts = if (parts.dictionary) {
            database.userBigramDao().allContexts().map {
                BackupPairContext(it.language, it.previousWord, it.total, it.seen, it.lastUsedAt)
            }
        } else {
            emptyList()
        }
        val tripleContexts = if (parts.dictionary) {
            database.userTrigramDao().allContexts().map {
                BackupTripleContext(it.language, it.previousWord2, it.previousWord1, it.total, it.seen, it.lastUsedAt)
            }
        } else {
            emptyList()
        }
        val phraseClocks = if (parts.dictionary) {
            database.userPhraseClockDao().all().map { BackupPhraseClock(it.language, it.pairs, it.triples) }
        } else {
            emptyList()
        }
        // Blocked words travel with the dictionary.
        val blocked = if (parts.dictionary) database.blockedWordDao().allWords() else emptyList()

        val clips = if (parts.clipboard) {
            database.clipboardDao().observeLive(0L).first()
                // Text only.
                .filter { !it.isImage }
                .map {
                    BackupClip(
                        content = it.content,
                        createdAt = it.createdAt,
                        pinned = it.isPinned,
                        private = it.isPrivate,
                    )
                }
        } else {
            emptyList()
        }

        return BackupPayload(
            preferences = if (parts.settings) rawPreferences else null,
            theme = theme,
            customThemes = customThemes,
            particleEffects = particleEffects,
            sizeAndPosition = sizeAndPosition,
            packs = packs,
            words = words,
            bigrams = bigrams,
            trigrams = trigrams,
            pairContexts = pairContexts,
            tripleContexts = tripleContexts,
            phraseClocks = phraseClocks,
            blocked = blocked,
            clips = clips,
            models = models,
        )
    }

    /** What an import did. */
    data class Applied(
        val settings: Boolean = false,
        val theme: Boolean = false,
        val particleEffects: Boolean = false,
        val sizeAndPosition: Boolean = false,
        val words: Int = 0,
        val pairs: Int = 0,
        val triples: Int = 0,
        val blocked: Int = 0,
        val languages: Int = 0,
        val clips: Int = 0,
        val assistModels: Int = 0,
        val customThemes: Int = 0,
    )

    suspend fun apply(payload: BackupPayload, parts: Parts): Applied {
        var applied = Applied()

        if (parts.settings) {
            payload.preferences?.let { incoming ->
                // The live placement is kept; only sizeAndPosition writes it.
                themes.updatePreferences { current ->
                    incoming
                        .withPlacement(false) { current.placementFor(false) }
                        .withPlacement(true) { current.placementFor(true) }
                        // swipeModelFailed describes this installation, not the file.
                        .copy(swipeModelFailed = false)
                }
            }
            applied = applied.copy(settings = payload.preferences != null)

            // Only a model this device already has, matched by hash.
            var reactivated = 0
            for (model in payload.models) {
                if (!model.active) {
                    continue
                }
                val existing = database.assistModelDao().findBySha256(model.sha256) ?: continue
                database.assistModelDao().setActive(existing.id)
                reactivated += 1
            }
            applied = applied.copy(assistModels = reactivated)
        }

        if (parts.theme) {
            payload.theme?.let { incoming -> themes.updateTheme { incoming.sanitised() } }

            // Added under their own ids, so importing twice does not duplicate them.
            var restoredThemes = 0
            for (customTheme in payload.customThemes) {
                val saved = themes.saveCustomTheme(
                    customTheme.name,
                    customTheme.theme,
                    id = customTheme.id,
                    createdAt = customTheme.createdAt,
                )
                if (saved != null) {
                    restoredThemes += 1
                }
            }
            applied = applied.copy(
                theme = payload.theme != null || payload.customThemes.isNotEmpty(),
                customThemes = restoredThemes,
            )
        }

        if (parts.particleEffects) {
            payload.particleEffects?.let { incoming ->
                themes.updateParticleEffects { incoming.sanitised() }
            }
            applied = applied.copy(particleEffects = payload.particleEffects != null)
        }

        if (parts.sizeAndPosition) {
            payload.sizeAndPosition?.let { incoming ->
                // The only part that writes placement.
                themes.updatePreferences { current ->
                    current
                        .withPlacement(false) { incoming.portrait }
                        .withPlacement(true) { incoming.landscape }
                }
            }
            applied = applied.copy(sizeAndPosition = payload.sizeAndPosition != null)
        }

        if (parts.dictionary) {
            val stamp = now()
            // One transaction across every table the dictionary writes.
            database.withTransaction {
                // A file without a word's or a link's last use reads it as used now.
                fun usedAt(written: Long) = if (written > 0L) written else stamp
                database.userWordDao().incrementAll(
                    payload.words.map {
                        LearnedWord(
                            word = it.word,
                            locale = it.locale,
                            delta = it.count,
                            lastUsedAt = usedAt(it.lastUsedAt),
                        )
                    },
                )
                for (word in payload.words) {
                    if (word.deliberateCapitals > 0) {
                        database.userWordDao().raiseDeliberateCapitals(word.word, word.deliberateCapitals)
                    }
                    if (word.asserted > 0) {
                        database.userWordDao().raiseAsserted(word.word, word.asserted)
                    }
                }
                // A file without languages: each link takes the one its word was learned under, else
                // the one most words were, else the fallback.
                val languageOf = PhraseKey.languageOfWord(
                    payload.words.associate { it.word to it.locale }, fallbackLanguage(),
                )
                // The contexts' carried uses; the stamps and the clocks are not read, a merge
                // stamps at this device's tick.
                dictionary.importPhrases(
                    payload.bigrams.filter { it.count > 0 }.map {
                        LearnedBigram(
                            language = it.language.ifEmpty { languageOf(it.word) },
                            previousWord = PhraseKey.word(it.previous),
                            word = PhraseKey.word(it.word),
                            delta = it.count,
                            lastUsedAt = usedAt(it.lastUsedAt),
                        )
                    },
                    payload.trigrams.filter { it.count > 0 }.map {
                        LearnedTrigram(
                            language = it.language.ifEmpty { languageOf(it.word) },
                            previousWord2 = PhraseKey.word(it.previous2),
                            previousWord1 = PhraseKey.word(it.previous1),
                            word = PhraseKey.word(it.word),
                            delta = it.count,
                            lastUsedAt = usedAt(it.lastUsedAt),
                        )
                    },
                    payload.pairContexts.map {
                        UserPairContext(it.language, PhraseKey.word(it.previous), it.total, it.lastUsedAt, it.seen)
                    },
                    payload.tripleContexts.map {
                        UserTripleContext(
                            it.language, PhraseKey.word(it.previous2), PhraseKey.word(it.previous1), it.total,
                            it.lastUsedAt, it.seen,
                        )
                    },
                )
                for (word in payload.blocked) {
                    database.blockedWordDao().insert(BlockedWord(word))
                }
            }
            dictionary.edited()
            applied = applied.copy(
                words = payload.words.size,
                pairs = payload.bigrams.size,
                triples = payload.trigrams.size,
                blocked = payload.blocked.size,
            )
        }

        if (parts.languages) {
            // Only packs this device already has.
            var touched = 0
            for (pack in payload.packs) {
                val existing = database.languagePackDao().findByTag(pack.tag) ?: continue
                database.languagePackDao().setEnabled(existing.id, pack.enabled)
                database.languagePackDao().setWeight(existing.id, pack.weight)
                touched += 1
            }
            applied = applied.copy(languages = touched)
        }

        if (parts.clipboard) {
            var added = 0
            for (clip in payload.clips) {
                // The same hash ClipboardRepository writes.
                val hash = ClipboardRepository.contentHash(clip.content)
                if (database.clipboardDao().findByHash(hash) != null) {
                    continue
                }
                database.clipboardDao().insertIfAbsent(
                    content = clip.content,
                    createdAt = clip.createdAt,
                    pinnedAt = if (clip.pinned) clip.createdAt else null,
                    contentHash = hash,
                    isPrivate = clip.private,
                )
                added += 1
            }
            applied = applied.copy(clips = added)
        }

        return applied
    }

    /** What a file turned out to contain, for the screen that offers to import it. */
    fun contentsOf(payload: BackupPayload): Parts = Parts(
        settings = payload.preferences != null || payload.models.isNotEmpty(),
        theme = payload.theme != null || payload.customThemes.isNotEmpty(),
        particleEffects = payload.particleEffects != null,
        sizeAndPosition = payload.sizeAndPosition != null,
        dictionary = payload.words.isNotEmpty() || payload.bigrams.isNotEmpty() ||
            payload.trigrams.isNotEmpty() || payload.blocked.isNotEmpty(),
        languages = payload.packs.isNotEmpty(),
        clipboard = payload.clips.isNotEmpty(),
    )
}
