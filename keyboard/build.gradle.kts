import javax.inject.Inject
import org.gradle.process.ExecOperations
// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The performance-critical module: the IME service, the Canvas keyboard view, the JNI
// bridge and the C++ prediction engine. Note what is *not* in the plugins block.

plugins {
    alias(libs.plugins.android.library)
}

android {
    // Only affects the generated R and BuildConfig. The code packages stay
    // com.borderkeys.ime / .predict / .gesture / .theme.
    namespace = "com.borderkeys.keyboard"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            // 32-bit x86 emulator images are not worth the build time; every shipping
            // device is one of these two.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // c++_static: one .so, no shared STL to load, nothing for another library in
                // the process to conflict with.
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

    // Same dimension name as :app so AGP matches variants without a manual strategy.
    flavorDimensions += "engine"
    productFlavors {
        create("core") {
            dimension = "engine"
            isDefault = true
        }
        // `plus` differs from `core` by the :assist module the application links, and by this
        // one flag: BORDERKEYS_NEURAL_SWIPE compiles the tier-B swipe decoder's sources into
        // this flavor's native library (see gesture/CMakeLists.txt option of the same name).
        // `core` never sets it, so its .so carries no trace of tier B, the same "unpack the APK,
        // the code is not in it" guarantee the assistant already has.
        //
        // Wired into Engine::create() (2026-09-13), gated behind an "experimental swipe model"
        // preference, off by default -- see docs/licensing.md section 2.5 and tools/swipe_model/.
        // The checkpoint that shipped with a training-time scale bug, compensated at runtime in
        // gesture/tcn_decoder.cpp, was replaced on 2026-09-16 by one trained under the fix; the
        // shim went with it.
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
        // No BuildConfig: nothing in this module reads one. The application module keeps its
        // own, for the commit hash and source URL the About screen shows.
        buildConfig = false
        viewBinding = false
        dataBinding = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                // Lets a JVM test drive the shipping engine through the shipping JNI bridge.
                // `System.loadLibrary("borderkeys")` in NativePredictor needs no change: the
                // host build in native-tests produces a library of exactly that name, and this
                // points the loader at it. Without it the pipeline test skips itself.
                //
                // What it buys is the half of the correction path suggest_eval cannot see.
                // That tool reaches the engine and stops, so every decision AutoCorrection makes
                // afterwards was either untested end to end or modelled a second time in C++ --
                // and a second implementation is a thing that drifts. Here the Kotlin that ships
                // is the Kotlin under test, against the packs the application ships.
                it.systemProperty(
                    "java.library.path",
                    rootProject.layout.projectDirectory.dir("native-tests/build").asFile.path,
                )
                it.systemProperty(
                    "borderkeys.packs",
                    layout.buildDirectory.dir("generated/dictionaries/dict").get().asFile.path,
                )
                // nativeLoadLanguage takes a file descriptor, and on a JVM the only way to a raw
                // one is FileDescriptor's private field. Opened for the tests alone.
                it.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Styling of the password manager's inline suggestions only (section 5.4). This module
    // never reads their content -- the platform makes sure of that -- it only says how big
    // and what colour they should be.
    implementation(libs.androidx.autofill)
    implementation(project(":data"))
    implementation(project(":i18n"))
    implementation(project(":effects"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}

/**
 * Compiles the bundled dictionaries from their word lists at build time.
 *
 * The lists live in `dictionaries/` as text: reviewable in a diff, licensable by REUSE, and
 * written by this project rather than taken from a corpus, which is what makes shipping them a
 * licence question with an answer. The `.bkd` binaries are build output and are not committed,
 * so a pack in an APK is always exactly what the committed list compiles to.
 *
 * The same `tools/build_dict.py` the maintainer runs by hand and the native tests run in CI, so
 * a format change cannot silently produce packs the engine refuses.
 */
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
            val arguments = mutableListOf(
                "python3", compiler.get().asFile.absolutePath,
                "--words", list.absolutePath,
                // The file name is the BCP-47 tag with the separator a file name can carry.
                "--tag", name.replace('_', '-'),
                "--out", target.resolve("$name.bkd").absolutePath,
            )
            if (ngrams.isFile) {
                arguments += listOf("--ngrams", ngrams.absolutePath)
            }
            // Optional per language. A pack built without one carries no grammar sections and
            // is scored exactly as packs were before they existed, so a language can be added
            // long before anyone finds a treebank for it.
            if (grammar.isFile) {
                arguments += listOf("--grammar", grammar.absolutePath)
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
