// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

pluginManagement {
    repositories {
        // Only these groups are looked up in Google's repository.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Provisions the JDK named by gradle/gradle-daemon-jvm.properties where it is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BorderKeys"

// The modules. No path in the dependency graph gives :keyboard Compose.
include(":app")
include(":keyboard")
include(":data")
include(":settings")
include(":assist")
include(":i18n")
include(":effects")
