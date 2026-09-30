// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The only module that uses Compose; it runs in the application process, not the IME's.

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

        // The :keyboard variant used when :settings is built on its own (unit tests, lint);
        // :app's own `engine` attribute wins in the APKs.
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
            all {
                // The English catalogue the index test reads from disk: a change to it reruns them.
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("i18n/src/main/assets/translations/en.json"),
                ).withPropertyName("englishCatalogue").withPathSensitivity(PathSensitivity.NONE)
            }
        }
    }
}

androidComponents {
    val gitCommit = rootProject.extra["borderkeysGitCommit"] as String
    val sourceUrl = rootProject.extra["borderkeysSourceUrl"] as String
    val repoUrl = rootProject.extra["borderkeysRepoUrl"] as String
    val releasesUrl = rootProject.extra["borderkeysReleasesUrl"] as String
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
            "REPO_URL",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$repoUrl\"",
                "The F-Droid repository that offers the assistant build. Empty when the build " +
                    "was configured without one, and the row is then not shown.",
            ),
        )
        variant.buildConfigFields?.put(
            "RELEASES_URL",
            com.android.build.api.variant.BuildConfigField(
                "String",
                "\"$releasesUrl\"",
                "Where the same file can be downloaded once, for anyone not subscribing.",
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
    implementation(libs.richeditor.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(project(":data"))
    implementation(project(":i18n"))
    implementation(project(":effects"))
    // One direction only: the previews embed the keyboard's own views; :keyboard does not
    // depend on this module.
    implementation(project(":keyboard"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
