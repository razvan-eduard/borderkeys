// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// How something is shown when the keyboard has news: a word that was learned, a correction that
// was applied, a word a swipe settled on. The stage knows when, a style knows where, and a
// content knows what -- so a style can be chosen in settings and a word, an emoji or an icon can
// play through the same animation.
//
// Depends on nothing but the framework, on purpose. :keyboard owns ThemePaints and :keyboard
// depends on this, so taking a paint from there would be a cycle; the stage is handed a Paint
// instead and never learns what a theme is. That is also what keeps the module reusable and its
// styles testable on the host.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.borderkeys.effects"
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
    testImplementation(libs.junit)
}
