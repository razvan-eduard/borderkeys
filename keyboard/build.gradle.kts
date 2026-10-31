import org.gradle.process.ExecOperations
import javax.inject.Inject
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The IME service, the Canvas keyboard view, the JNI bridge and the C++ prediction engine.

plugins {
    alias(libs.plugins.android.library)
}

android {
    // The package of the generated R only.
    namespace = "com.borderkeys.keyboard"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // `-Pborderkeys.extraAbis=x86_64` adds more ABIs.
            providers.gradleProperty("borderkeys.extraAbis").orNull
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.let { abiFilters += it }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += "-std=c++17"
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    // The same dimension as :app's.
    flavorDimensions += "engine"
    productFlavors {
        create("core") {
            dimension = "engine"
            isDefault = true
        }
        // BORDERKEYS_NEURAL_SWIPE compiles the tier-B swipe decoder into the native library.
        create("plus") {
            dimension = "engine"
            externalNativeBuild {
                cmake {
                    arguments += "-DBORDERKEYS_NEURAL_SWIPE=ON"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = false
        viewBinding = false
        dataBinding = false
    }

    testOptions {
        /**
         * The directory the Gradle property [name] names, relative to the repository's root; null
         * when it is unset. An empty name or one that is no directory stops the build.
         */
        fun namedDirectory(name: String): File? {
            val value = providers.gradleProperty(name).orNull ?: return null
            if (value.isBlank()) {
                throw GradleException("-P$name names no directory")
            }
            val directory = rootProject.file(value)
            if (!directory.isDirectory) {
                throw GradleException("-P$name: no directory at $directory")
            }
            return directory
        }

        unitTests {
            isReturnDefaultValues = true
            // The drawables and the merged resources, for the Robolectric view tests.
            isIncludeAndroidResources = true
            all {
                // The host build of the native library and the compiled packs, for the JVM
                // tests that drive the engine; without them the pipeline tests skip themselves.
                // `borderkeys.libraryDir` and `borderkeys.packsDir` name another host library's
                // directory and another directory of packs, as a design sheet's arm reads its own
                // engine; unset, the tree's own.
                val otherLibraryDir = namedDirectory("borderkeys.libraryDir")
                val otherPacksDir = namedDirectory("borderkeys.packsDir")
                val hostLibraryDir = otherLibraryDir
                    ?: rootProject.layout.projectDirectory.dir("native-tests/build").asFile
                it.systemProperty("java.library.path", hostLibraryDir.path)
                it.systemProperty(
                    "borderkeys.packs",
                    (otherPacksDir ?: layout.buildDirectory.dir("generated/dictionaries/dict").get().asFile).path,
                )
                // For FileDescriptor's private fd field, which the tests hand nativeLoadLanguage.
                it.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
                // The host library and the packs the pipeline tests load: a change to either reruns
                // the tests, and the tree's out-of-date packs are built first.
                it.inputs.files(
                    rootProject.fileTree(hostLibraryDir) { include("libborderkeys.*") },
                ).withPropertyName("hostLibrary").withPathSensitivity(PathSensitivity.NONE)
                // The tree's host library older than an engine source would run an older engine: the
                // tests stop with the command that rebuilds it.
                val engineSources = rootProject.layout.projectDirectory.dir("keyboard/src/main/cpp").asFile
                it.inputs.dir(engineSources)
                    .withPropertyName("engineSources").withPathSensitivity(PathSensitivity.RELATIVE)
                if (otherLibraryDir == null) {
                    it.doFirst {
                        val library = hostLibraryDir.listFiles { file -> file.name.startsWith("libborderkeys.") }
                            ?.maxByOrNull { file -> file.lastModified() } ?: return@doFirst
                        val newest = engineSources.walkTopDown().filter { file -> file.isFile }
                            .maxByOrNull { file -> file.lastModified() } ?: return@doFirst
                        if (newest.lastModified() > library.lastModified()) {
                            throw GradleException(
                                "${library.name} is older than ${newest.relativeTo(engineSources)}: rebuild it with " +
                                    "cmake --build native-tests/build --target borderkeys",
                            )
                        }
                    }
                }
                if (otherPacksDir == null) {
                    it.inputs.files(tasks.named("buildDictionaries"))
                        .withPropertyName("packs").withPathSensitivity(PathSensitivity.RELATIVE)
                } else {
                    it.inputs.dir(otherPacksDir)
                        .withPropertyName("packs").withPathSensitivity(PathSensitivity.RELATIVE)
                }
                // The assets, the plus flavour's swipe models, drawables and corpora the tests read
                // from disk: a change to one reruns them.
                it.inputs.dir("src/main/assets")
                    .withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
                it.inputs.dir("src/plus/assets")
                    .withPropertyName("plusAssets").withPathSensitivity(PathSensitivity.RELATIVE)
                it.inputs.dir("src/main/res/drawable")
                    .withPropertyName("drawables").withPathSensitivity(PathSensitivity.RELATIVE)
                it.inputs.dir(rootProject.layout.projectDirectory.dir("native-tests/data"))
                    .withPropertyName("corpora").withPathSensitivity(PathSensitivity.RELATIVE)
                // The engine headers NativeSlotsTest reads.
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/jni_slots.hpp"),
                ).withPropertyName("bridgeSlotsHeader").withPathSensitivity(PathSensitivity.NONE)
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/engine.hpp"),
                ).withPropertyName("engineHeader").withPathSensitivity(PathSensitivity.NONE)
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/context_slice.hpp"),
                ).withPropertyName("contextSliceHeader").withPathSensitivity(PathSensitivity.NONE)
                // The directory the pipeline tests write one line per case into; unset writes none.
                providers.gradleProperty("borderkeys.readings").orNull?.let { directory ->
                    it.systemProperty("borderkeys.readings", directory)
                }
                // The languages PersonalCorpusTest reads, by code (`en,ro`); unset reads them all.
                providers.gradleProperty("borderkeys.personalLanguages").orNull?.let { languages ->
                    it.systemProperty("borderkeys.personalLanguages", languages)
                    it.inputs.property("personalLanguages", languages)
                }
                // The tap corpora TapCorpusTest types, one per profile; unset, it skips itself.
                providers.gradleProperty("borderkeys.taps").orNull?.let { directory ->
                    it.systemProperty("borderkeys.taps", directory)
                    it.inputs.dir(directory).withPropertyName("taps")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
                // The swipe traces SwipePersonalCorpusTest replays, one per language; unset, it skips
                // its corpus.
                providers.gradleProperty("borderkeys.swipes").orNull?.let { directory ->
                    it.systemProperty("borderkeys.swipes", directory)
                    it.inputs.dir(directory).withPropertyName("swipes")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
                // The DEV corpora CorrectionDevReadingsTest types (tools/make_dev_corpus.py); unset,
                // it skips its reading.
                namedDirectory("borderkeys.dev")?.let { directory ->
                    it.systemProperty("borderkeys.dev", directory.path)
                    it.inputs.dir(directory).withPropertyName("dev")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":geometry"))
    implementation(project(":language"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Styles the password manager's inline suggestions.
    implementation(libs.androidx.autofill)
    implementation(project(":data"))
    implementation(project(":i18n"))
    implementation(project(":effects"))

    testImplementation(testFixtures(project(":data")))
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    // Views measured, drawn and touched on the JVM.
    testImplementation(libs.robolectric)
    // org.json is a stub on the JVM; the real one lets the tests parse the layout assets.
    testImplementation(libs.org.json)
}

/** Compiles the word lists in `dictionaries/` into `.bkd` packs with `tools/build_dict.py`. */
abstract class BuildDictionaries : DefaultTask() {
    @get:InputDirectory
    abstract val sources: DirectoryProperty

    @get:InputFile
    abstract val compiler: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun build() {
        val target = outputDirectory.get().asFile.resolve("dict")
        target.deleteRecursively()
        target.mkdirs()
        val lists = sources.get().asFile.listFiles { file -> file.name.endsWith(".tsv") }
            ?.sortedBy { it.name } ?: emptyList()
        check(lists.isNotEmpty()) { "no word lists in ${sources.get().asFile}" }
        for (list in lists) {
            val name = list.name.removeSuffix(".tsv")
            val ngrams = list.parentFile.resolve("$name.ngrams")
            val grammar = list.parentFile.resolve("$name.pos")
            val known = list.parentFile.resolve("$name.known")
            val case = list.parentFile.resolve("$name.case")
            val arguments = mutableListOf(
                "python3", compiler.get().asFile.absolutePath,
                "--words", list.absolutePath,
                // The file name is the BCP-47 tag with '_' for '-'.
                "--tag", name.replace('_', '-'),
                "--out", target.resolve("$name.bkd").absolutePath,
            )
            if (ngrams.isFile) {
                arguments += listOf("--ngrams", ngrams.absolutePath)
            }
            // Optional per language.
            if (grammar.isFile) {
                arguments += listOf("--grammar", grammar.absolutePath)
            }
            if (known.isFile) {
                arguments += listOf("--known", known.absolutePath)
            }
            if (case.isFile) {
                arguments += listOf("--case", case.absolutePath)
            }
            execOperations.exec { commandLine(arguments) }
        }
    }
}

val buildDictionaries = tasks.register<BuildDictionaries>("buildDictionaries") {
    sources.set(layout.projectDirectory.dir("../dictionaries"))
    compiler.set(layout.projectDirectory.file("../tools/build_dict.py"))
    outputDirectory.set(layout.buildDirectory.dir("generated/dictionaries"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            buildDictionaries,
            BuildDictionaries::outputDirectory,
        )
    }
}
