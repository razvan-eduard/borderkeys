// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

// The performance-critical module: the IME service, the Canvas keyboard view, the JNI
// bridge and the C++ prediction engine. Note what is *not* in the plugins block.

plugins {
    alias(libs.plugins.android.library)
}

android {
    // Only affects the generated R and BuildConfig. The code packages stay
    // com.borderkeys.ime / .predict / .gesture / .theme.
    namespace = "com.borderkeys.keyboard"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        ndk {
            // 32-bit x86 emulator images are not worth the build time; every shipping
            // device is one of these two.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                // c++_static: one .so, no shared STL to load, nothing for another library in
                // the process to conflict with.
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

    // Same dimension name as :app so AGP matches variants without a manual strategy.
    flavorDimensions += "engine"
    productFlavors {
        create("core") {
            dimension = "engine"
            isDefault = true
        }
        create("plus") {
            dimension = "engine"
            externalNativeBuild {
                cmake {
                    // Compiled out, not dead-code eliminated: libborderkeys.so in the `core`
                    // variant contains no neural decoder because the preprocessor never saw it.
                    arguments += listOf("-DBORDERKEYS_NEURAL_SWIPE=ON")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // The decoder tier is chosen once, at nativeCreate, from this flag. There is no
        // `if (neural)` anywhere near a touch event.
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
    onVariants { variant ->
        variant.buildConfigFields?.put(
            "NEURAL_SWIPE",
            com.android.build.api.variant.BuildConfigField(
                "boolean",
                (variant.flavorName == "plus").toString(),
                "True when libborderkeys.so was built with the neural swipe decoder.",
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Styling of the password manager's inline suggestions only (section 5.4). This module
    // never reads their content -- the platform makes sure of that -- it only says how big
    // and what colour they should be.
    implementation(libs.androidx.autofill)
    implementation(project(":data"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
}
