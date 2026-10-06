// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The only module that uses Compose; it runs in the application process, not the IME's.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.borderkeys.settings"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // The :keyboard variant used when :settings is built on its own (unit tests, lint);
        // :app's own `engine` attribute wins in the APKs.
        missingDimensionStrategy("engine", "core")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // Carries the commit hash and the source URL for the About screen (GPL section 6).
        buildConfig = true
        viewBinding = false
        dataBinding = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // The catalogues the index test reads from disk: a change to one reruns them.
                it.inputs.dir(
                    rootProject.layout.projectDirectory.dir("i18n/src/main/assets/translations"),
                ).withPropertyName("catalogues").withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }
}

/**
 * Writes SettingsIndex.kt, the list of every titled row and card on every settings screen, from
 * the screen sources: a `SettingRow`, `SwitchRow` or `SectionHeader` whose title is a catalogue
 * key is one entry, and so is a `Text` of a catalogue key in the bodyLarge style, the title a
 * picker or slider is drawn under. Each is placed under the last `SettingsSectionCard` whose
 * title is a catalogue key. A row's note is its subtitle's key; a heading's or a label's is the
 * first `Explanation` before the next card or row. A row or a card titled from data is not
 * indexed.
 */
abstract class GenerateSettingsIndex : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screens: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val screenEnum: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val known = Regex("""^\s+(\w+)\(Keys\.""", RegexOption.MULTILINE)
            .findAll(screenEnum.get().asFile.readText()).map { it.groupValues[1] }.toSet()
        val body = StringBuilder()
        val files = screens.get().asFile.listFiles()!!.filter { it.extension == "kt" }.sortedBy { it.name }
        for (file in files) {
            val screen = screenOf(file.name) ?: continue
            check(screen in known) { "${file.name}: no Screen.$screen" }
            for (entry in scan(withoutComments(file.readText()), screen)) {
                body.append("        Entry(Screen.${entry[0]}, ")
                    .append(entry[1]?.let { "Keys.$it" } ?: "null")
                    .append(", Keys.${entry[2]}")
                    .append(entry[3]?.let { ", Keys.$it" } ?: "")
                    .append("),\n")
            }
        }
        val out = outputDir.get().asFile.resolve("com/borderkeys/settings/SettingsIndex.kt")
        out.parentFile.mkdirs()
        out.writeText(HEADER + body + "    )\n}\n")
    }

    private fun screenOf(name: String): String? {
        EXTRA_SOURCES[name]?.let { return it }
        if (!name.endsWith("Screen.kt")) return null
        return name.removeSuffix("Screen.kt").takeIf { it !in SKIPPED_SCREENS }
    }

    private fun withoutComments(text: String): String = text.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*")) "" else line
    }

    /** (screen, card key, key, note key) for every card and row in source order, each key once. */
    private fun scan(text: String, screen: String): List<List<String?>> {
        val out = ArrayList<List<String?>>()
        val seen = HashSet<String>()
        var card: String? = null
        val events = (ANY_CARD.findAll(text) + ROW.findAll(text) + LABEL.findAll(text))
            .sortedBy { it.range.first }.toList()
        for ((number, match) in events.withIndex()) {
            val end = if (number + 1 < events.size) events[number + 1].range.first else text.length
            if (match.value.startsWith("SettingsSectionCard")) {
                card = CARD.matchAt(text, match.range.first)?.groupValues?.get(1)
                if (card != null && seen.add(card)) out += listOf(screen, null, card, null)
            } else {
                val key = match.groupValues[1]
                if (seen.add(key)) out += listOf(screen, card, key, noteOf(text, match, end))
            }
        }
        return out
    }

    /** A row's subtitle key, named or second in place, or the first explanation after a heading or label. */
    private fun noteOf(text: String, match: MatchResult, end: Int): String? {
        if (!match.value.startsWith("SettingRow") && !match.value.startsWith("SwitchRow")) {
            return EXPLANATION.find(text.substring(0, end), match.range.last + 1)?.groupValues?.get(1)
        }
        val span = arguments(text, text.indexOf('(', match.range.first))
        val title = match.groupValues[1]
        val rest = span.substring(span.indexOf(title) + title.length)
        NAMED_NOTE.find(rest)?.let { return it.groupValues[1] }
        return POSITIONAL_NOTE.matchAt(rest, 0)?.groupValues?.get(1)
    }

    /** The text between the parenthesis at [open] and the one that closes it. */
    private fun arguments(text: String, open: Int): String {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return text.substring(open + 1, index)
            }
        }
        return text.substring(open + 1)
    }

    private companion object {
        /** Sources that hold rows of a screen without being named after it. */
        val EXTRA_SOURCES = mapOf("EventEffectsSection.kt" to "Animations", "ScreenshotSuggestion.kt" to "Clipboard")

        /**
         * Sources named like a screen whose rows are not indexed: no entry in the Screen enum
         * (opened by an intent), rows that only open other screens, or no fixed row beyond a card
         * titled like the screen itself.
         */
        val SKIPPED_SCREENS = setOf("Home", "Features", "Onboarding", "ProcessText", "Transfer", "LearnedWords", "LearnedPhrases")

        const val TITLE = """\(\s*(?:title\s*=\s*)?strings(?:\[|\.getString\()Keys\.([A-Z0-9_]+)"""
        const val KEY = """strings(?:\[|\.getString\()Keys\.([A-Z0-9_]+)"""
        val CARD = Regex("SettingsSectionCard$TITLE")
        val ANY_CARD = Regex("""SettingsSectionCard\(""")
        val ROW = Regex("(?:SettingRow|SwitchRow|SectionHeader)$TITLE")
        val LABEL = Regex("""Text\(\s*strings\[Keys\.([A-Z0-9_]+)],\s*style\s*=\s*MaterialTheme\.typography\.bodyLarge""")
        val NAMED_NOTE = Regex("""subtitle\s*=\s*(?:if\s*\([^)]*\)\s*)?$KEY""")
        val POSITIONAL_NOTE = Regex("""[^,]*,\s*$KEY""")
        val EXPLANATION = Regex("""Explanation\(\s*$KEY""")

        val HEADER = """
            // SPDX-License-Identifier: GPL-3.0-or-later
            // SPDX-FileCopyrightText: 2026 BorderKeys contributors

            package com.borderkeys.settings

            import com.borderkeys.i18n.Keys

            /**
             * Every row and card on the settings screens whose title is a catalogue key, with the screen
             * it is on and the card it is under, in source order. Generated at build time by
             * settings/build.gradle.kts from the screen sources.
             */
            object SettingsIndex {

                /**
                 * One indexed row or card: where it is, the key its title is drawn from, and the key of the
                 * note under it, if any.
                 */
                class Entry(val screen: Screen, val cardKey: String?, val key: String, val noteKey: String? = null)

                val entries: List<Entry> = listOf(

        """.trimIndent()
    }
}

val generateSettingsIndex = tasks.register<GenerateSettingsIndex>("generateSettingsIndex") {
    screens.set(layout.projectDirectory.dir("src/main/java/com/borderkeys/settings/screen"))
    screenEnum.set(layout.projectDirectory.file("src/main/java/com/borderkeys/settings/Screen.kt"))
    outputDir.set(layout.buildDirectory.dir("generated/source/settingsIndex"))
}

androidComponents {
    val gitCommit = rootProject.extra["borderkeysGitCommit"] as String
    val sourceUrl = rootProject.extra["borderkeysSourceUrl"] as String
    val repoUrl = rootProject.extra["borderkeysRepoUrl"] as String
    val releasesUrl = rootProject.extra["borderkeysReleasesUrl"] as String
    onVariants { variant ->
        (variant.sources.kotlin ?: variant.sources.java)
            ?.addGeneratedSourceDirectory(generateSettingsIndex, GenerateSettingsIndex::outputDir)
        variant.buildConfigFields?.put(
            "GIT_COMMIT",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$gitCommit\"",
                "Commit this binary was built from; shown on the About screen.",
            ),
        )
        variant.buildConfigFields?.put(
            "REPO_URL",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$repoUrl\"",
                "The F-Droid repository that offers the assistant build. Empty when the build " +
                    "was configured without one, and the row is then not shown.",
            ),
        )
        variant.buildConfigFields?.put(
            "RELEASES_URL",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$releasesUrl\"",
                "Where the same file can be downloaded once, for anyone not subscribing.",
            ),
        )
        variant.buildConfigFields?.put(
            "SOURCE_URL",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$sourceUrl\"",
                "Where the corresponding source lives. Opened with ACTION_VIEW; we hold no " +
                    "INTERNET permission and do not need one, the browser has it.",
            ),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.richeditor.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":data"))
    implementation(project(":i18n"))
    implementation(project(":effects"))
    // One direction only: the previews embed the keyboard's own views; :keyboard does not
    // depend on this module.
    implementation(project(":keyboard"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
