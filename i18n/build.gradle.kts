// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// Every user-facing word in the product, and the code that looks one up. :keyboard depends on
// it, so it is part of the IME process. No Compose, no coroutines.

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
    // The catalogue parser, the same on the phone and in host unit tests.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
