// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// Everything kept about the user: one encrypted database and the typed DataStores.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.borderkeys.data"
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

    // The in-memory phrase tables the data and keyboard tests share.
    testFixtures {
        enable = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                // The host build of the native library, whose text rules PhraseKey calls.
                it.systemProperty(
                    "java.library.path",
                    rootProject.layout.projectDirectory.dir("native-tests/build").asFile.path,
                )
                it.inputs.files(
                    rootProject.fileTree("native-tests/build") { include("libborderkeys.*") },
                ).withPropertyName("hostLibrary").withPathSensitivity(PathSensitivity.NONE)
                // The engine headers the tests read from disk: a change to one reruns them.
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/engine.hpp"),
                ).withPropertyName("engineHeader").withPathSensitivity(PathSensitivity.NONE)
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/user_model.hpp"),
                ).withPropertyName("userModelHeader").withPathSensitivity(PathSensitivity.NONE)
                it.inputs.file(
                    rootProject.layout.projectDirectory.file("keyboard/src/main/cpp/bkd_format.hpp"),
                ).withPropertyName("packFormatHeader").withPathSensitivity(PathSensitivity.NONE)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

ksp {
    // The schemas are committed.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":geometry"))
    implementation(project(":language"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher's SupportSQLiteOpenHelper.Factory; androidx.sqlite pinned to room-runtime's.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    // Holds the database passphrase, with the master key in the Android Keystore.
    implementation(libs.androidx.security.crypto)

    // Typed DataStore.
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testFixturesImplementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
