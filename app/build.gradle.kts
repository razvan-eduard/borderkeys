// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------------------
// Release signing.
//
// An input method is not a normal app to test: it has to be enabled in system settings, it
// is the process every other app types through, and the numbers this project is built around
// -- 2 ms to commit a keystroke, 4 ms to redraw, 30 ms to decode a swipe -- are meaningless
// on a debug build, which has R8 off and JNI debugging on. So a device must never carry a
// debug build, which means a release build has to be installable, which means it has to be
// signed. `./gradlew :app:installCoreRelease` is the command; the key below is what makes it
// work.
//
// Three sources, in order. Environment variables come first because that is what CI sets
// (decoded from a repository secret into the runner's temp directory). A Gradle property is
// the escape hatch for a different local layout. Otherwise ~/.borderkeys/, which is where a
// developer machine keeps it.
//
// If none of them resolve -- a fresh clone, a fork's CI, a contributor without the key --
// no signing config is created at all and the release APK comes out unsigned, exactly as it
// did before. That is a deliberate non-failure: an outside contributor must be able to build
// and test this project without holding the release key.
// ---------------------------------------------------------------------------------------

val keystoreFileProvider: Provider<RegularFile> = layout.file(
    providers.environmentVariable("RELEASE_KEYSTORE_PATH")
        .orElse(providers.gradleProperty("borderkeys.keystore.path"))
        .orElse(
            providers.systemProperty("user.home")
                .map { "$it/.borderkeys/borderkeys-release.jks" },
        )
        .map { File(it) },
)

val keystorePasswordProvider: Provider<String> =
    providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD")
        .orElse(
            providers.fileContents(
                layout.file(
                    providers.systemProperty("user.home")
                        .map { File("$it/.borderkeys/keystore_password.txt") },
                ),
            ).asText.map { it.trim() },
        )

val releaseKeystore: File? = keystoreFileProvider.orNull?.asFile?.takeIf { it.isFile }
val releaseKeystorePassword: String? = keystorePasswordProvider.orNull?.takeIf { it.isNotEmpty() }
val releaseKeyAlias: String = providers.environmentVariable("RELEASE_KEY_ALIAS")
    .orElse(providers.gradleProperty("borderkeys.keystore.alias"))
    .getOrElse("borderkeys")
val canSignRelease = releaseKeystore != null && releaseKeystorePassword != null

android {
    namespace = "com.borderkeys"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    // This module compiles no native code of its own, but it is the one that packages the .so
    // files coming out of :keyboard, and stripping them needs llvm-strip from the NDK. Without
    // an ndkVersion here AGP cannot find the toolchain, warns once, and packages the libraries
    // unstripped -- 868 KB of DWARF per ABI for 154 KB of actual code.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.borderkeys"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 3
        versionName = "0.2.0"

        ndk {
            // Packaging-level filter, and the only one that decides what actually lands in
            // the APK for .so files that arrive inside a third-party AAR. Without it,
            // SQLCipher alone contributes four copies of a 4-7 MB library and the APK is
            // 23 MB before a single line of our own native code exists.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // `core` is the free build: deterministic engine plus the geometric swipe decoder, and
    // physically no neural code. `plus` adds the neural swipe tier (step 6) and the text
    // assistant (step 7). The split is auditable from the outside -- unpack the APK and the
    // heavy parts are absent -- which a runtime feature flag could never be.
    flavorDimensions += "engine"
    productFlavors {
        create("core") {
            dimension = "engine"
            isDefault = true
        }
        create("plus") {
            dimension = "engine"
            versionNameSuffix = "-plus"
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword
                // Stated, not defaulted. An installed app only accepts an update signed by
                // the same certificate under the same scheme, so this is not something to
                // let an AGP default change underneath a shipped release. v1 is JAR signing,
                // which nothing above API 24 reads and minSdk here is 30.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isJniDebuggable = true
            // So that a build that somehow reached a device announces itself on the About
            // screen instead of being mistaken for the real thing while someone measures
            // frame times on it.
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        // Uncompressed and page-aligned .so files: the loader maps them straight out of the
        // APK instead of extracting them, which is both smaller on disk and faster on the
        // first keystroke after a cold start.
        jniLibs { useLegacyPackaging = false }
    }

    // The dependency blob AGP normally writes into the APK signing block is encrypted with a
    // Google public key and is not reproducible. F-Droid rejects it for that reason, and we
    // have no use for it either.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":keyboard"))
    implementation(project(":settings"))
    // Attached only to the `plus` flavor. This is why the `core` APK does not contain the
    // assistant: not because R8 removed it, but because it never entered the compilation.
    "plusImplementation"(project(":assist"))
}
