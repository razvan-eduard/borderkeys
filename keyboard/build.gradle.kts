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
        // `plus` differs from `core` only by the :assist module the application links, not by
        // anything in this one. The plan allowed a second, neural swipe decoder here; it was
        // not built, and the reason is in docs/licensing.md section 2.3.
        create("plus") {
            dimension = "engine"
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
