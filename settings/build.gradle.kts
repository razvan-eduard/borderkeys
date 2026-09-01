// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The only module in the repository that is allowed to know Compose exists. It runs in the
// application process, not in the IME process, so its frame budget is irrelevant to typing.

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

        // This module has no flavors, but it depends on :keyboard which does. Without a
        // strategy AGP cannot pick a variant of :keyboard when :settings is built on its own
        // (unit tests, lint). When :app resolves the graph its own `engine` attribute wins,
        // so the `plus` APK still gets the `plus` keyboard.
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
        }
    }
}

androidComponents {
    val gitCommit = rootProject.extra["borderkeysGitCommit"] as String
    val sourceUrl = rootProject.extra["borderkeysSourceUrl"] as String
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "GIT_COMMIT",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$gitCommit\"",
                "Commit this binary was built from; shown on the About screen.",
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
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":data"))
    implementation(project(":i18n"))
    // One direction only. The theme editor embeds the real KeyboardCanvasView in an
    // AndroidView so that the preview and the keyboard cannot diverge; the keyboard never
    // learns that this module exists.
    implementation(project(":keyboard"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
