import javax.inject.Inject
import org.gradle.process.ExecOperations
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
        unitTests {
            isReturnDefaultValues = true
            all {
                // The host build of the native library and the compiled packs, for the JVM
                // tests that drive the engine; without them the pipeline tests skip themselves.
                it.systemProperty(
                    "java.library.path",
                    rootProject.layout.projectDirectory.dir("native-tests/build").asFile.path,
                )
                it.systemProperty(
                    "borderkeys.packs",
                    layout.buildDirectory.dir("generated/dictionaries/dict").get().asFile.path,
                )
                // For FileDescriptor's private fd field, which the tests hand nativeLoadLanguage.
                it.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
                // The host library and the packs the pipeline tests load: a change to either reruns
                // the tests, and out-of-date packs are built first.
                it.inputs.files(
                    rootProject.fileTree("native-tests/build") { include("libborderkeys.*") },
                ).withPropertyName("hostLibrary").withPathSensitivity(PathSensitivity.NONE)
                it.inputs.files(tasks.named("buildDictionaries"))
                    .withPropertyName("packs").withPathSensitivity(PathSensitivity.RELATIVE)
                // The assets and the corpora the tests read from disk: a change to one reruns them.
                it.inputs.dir("src/main/assets")
                    .withPropertyName("assets").withPathSensitivity(PathSensitivity.RELATIVE)
                it.inputs.dir(rootProject.layout.projectDirectory.dir("native-tests/data"))
                    .withPropertyName("corpora").withPathSensitivity(PathSensitivity.RELATIVE)
                // The directory the pipeline tests write one line per case into; unset writes none.
                providers.gradleProperty("borderkeys.readings").orNull?.let { directory ->
                    it.systemProperty("borderkeys.readings", directory)
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Styles the password manager's inline suggestions.
    implementation(libs.androidx.autofill)
    implementation(project(":data"))
    implementation(project(":i18n"))
    implementation(project(":effects"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
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
