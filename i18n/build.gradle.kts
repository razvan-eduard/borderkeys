// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// Every user-facing word in the product, and the code that looks one up. Deliberately the
// smallest module in the repository: :keyboard depends on it, so anything added here is added
// to the IME process. No Compose, no coroutines, no third-party parser -- org.json ships in
// the framework, so the translation catalogue costs the APK nothing but its own text.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.borderkeys.i18n"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Already in the APK for the preferences store, and it parses identically on the host, so
    // the parity test reads the shipped catalogue with the same code the phone runs. The
    // framework's org.json would have been the other option; it is a throwing stub in unit
    // tests, which is how a catalogue silently becomes empty and every screen shows its keys.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
