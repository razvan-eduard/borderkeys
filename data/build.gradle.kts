// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The single source of truth. Everything the user has ever typed that we keep lives behind
// this module, in one encrypted database and one typed DataStore. No module owns a second
// copy of that state.

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

ksp {
    // Schemas are committed so that a migration is reviewed as a diff rather than
    // discovered on a user's device.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // SQLCipher supplies the SupportSQLiteOpenHelper.Factory that Room opens the database
    // through; androidx.sqlite is pinned to the version room-runtime resolves to so the two
    // cannot drift apart silently.
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    // Holds the database passphrase, with the master key in the Android Keystore.
    implementation(libs.androidx.security.crypto)

    // Typed DataStore, not the preferences variant: the keyboard theme is a schema, and a
    // string-keyed bag would turn every rename into a silent default.
    implementation(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
