// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

plugins {
    alias(libs.plugins.android.application)
}

// ---------------------------------------------------------------------------------------
// Release signing. The keystore comes from environment variables (CI), then a Gradle property,
// then ~/.borderkeys/. When none resolves, no signing config is created and the release APK is
// unsigned.
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

/** ABIs added to the shipped two, comma-separated: the instrumented job's emulator is x86_64. */
val extraAbis: List<String> = providers.gradleProperty("borderkeys.extraAbis").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

android {
    namespace = "com.borderkeys"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    // For the NDK's llvm-strip, which strips the .so files this module packages.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.borderkeys"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 22
        versionName = "0.10.2"

        ndk {
            // The packaging-level filter, which also applies to a third-party AAR's .so files.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // `-Pborderkeys.extraAbis=x86_64` adds the ABI an emulator on a CI runner has.
            abiFilters += extraAbis
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // `core`: the deterministic engine and the geometric swipe decoder, with no neural code.
    // `plus` adds the neural swipe tier and the text assistant.
    flavorDimensions += "engine"
    productFlavors {
        create("core") {
            dimension = "engine"
            isDefault = true
        }
        create("plus") {
            dimension = "engine"
            versionNameSuffix = "-plus"
            // Its own package, so both builds can be installed side by side.
            applicationIdSuffix = ".plus"
        }
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeystorePassword
                // Stated, not left to AGP's defaults.
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
            // Shown on the About screen.
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
        // Uncompressed and page-aligned .so files, mapped straight out of the APK.
        jniLibs { useLegacyPackaging = false }
    }

    // No dependency-info blob in the APK signing block; F-Droid rejects it.
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

    // The instrumented smoke suite: the keyboard driven through a real input connection on an
    // emulator.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    // The suite installs a pack and sets preferences through the same repositories the
    // keyboard reads, and names the pack's licence from the catalogue.
    androidTestImplementation(project(":data"))
    androidTestImplementation(project(":i18n"))
    androidTestImplementation(project(":settings"))
    // Attached only to the `plus` flavor; the `core` APK never compiles the assistant.
    "plusImplementation"(project(":assist"))
}
