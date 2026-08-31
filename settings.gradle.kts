// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

pluginManagement {
    repositories {
        // Content filters are not cosmetic: they stop a typo'd coordinate from being
        // searched for -- and possibly found -- in a repository that has no business
        // serving it.
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
    // Provisions the JDK named by gradle/gradle-daemon-jvm.properties on a machine that
    // does not already have it, so a fresh clone builds without a manual JDK install.
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

// Exactly five modules. The split is the enforcement mechanism for "Compose never enters
// the keyboard process": :keyboard cannot see Compose because no path in the dependency
// graph puts it there, so an accidental import is a compile error rather than a review note.
include(":app")
include(":keyboard")
include(":data")
include(":settings")
include(":assist")
