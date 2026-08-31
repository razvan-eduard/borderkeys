// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.util.Locale

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// ---------------------------------------------------------------------------------------
// Source provenance, required by GPL section 6.
//
// The binary has to be able to point at the exact source it was built from. The About
// screen renders these two values; the app never fetches anything to do it, it hands an
// ACTION_VIEW intent to whatever browser the user already has.
// ---------------------------------------------------------------------------------------

val borderkeysGitCommit: String = runCatching {
    val output = providers.exec {
        workingDir = rootDir
        commandLine("git", "rev-parse", "HEAD")
        isIgnoreExitValue = true
    }
    if (output.result.get().exitValue == 0) {
        output.standardOutput.asText.get().trim().ifEmpty { "unknown" }
    } else {
        "unknown"
    }
}.getOrDefault("unknown")

val borderkeysSourceUrl: String =
    providers.gradleProperty("borderkeys.sourceUrl").getOrElse("unknown")

extra["borderkeysGitCommit"] = borderkeysGitCommit
extra["borderkeysSourceUrl"] = borderkeysSourceUrl

// ---------------------------------------------------------------------------------------
// Verification tasks.
//
// Every promise this project makes about itself -- no network, no forbidden dependency, no
// Compose inside the keyboard -- is checked mechanically here. A promise a build cannot
// falsify is not a promise, it is a README sentence.
// ---------------------------------------------------------------------------------------

/**
 * Group prefixes that must never appear on a release runtime classpath. Matching is on a
 * group *boundary* (equal, or followed by a dot) so that blocking `com.google.android.gms`
 * does not also block an unrelated `com.google.android.material`.
 */
val forbiddenDependencyGroups = listOf(
    // Telemetry, crash reporting, Play integration.
    "com.google.firebase",
    "com.google.android.gms",
    "com.google.android.datatransport",
    // On-device ML shipped as a prebuilt AAR; step 7 compiles inference from source instead.
    "com.google.mediapipe",
    // HTTP clients. An app with no INTERNET permission that still links one is an app whose
    // next maintainer will add the permission.
    "com.squareup.okhttp",
    "com.squareup.okhttp3",
    "com.squareup.retrofit",
    "com.squareup.retrofit2",
    "io.ktor",
    "com.android.volley",
    // Dependency injection containers. Wiring is a single `object AppGraph` with `lazy`.
    "com.google.dagger",
    "io.insert-koin",
    // Reflection-heavy or oversized helpers that the hot path must never depend on.
    "io.reactivex",
    "com.squareup.moshi",
    "com.jakewharton.timber",
    "com.github.bumptech.glide",
    "com.squareup.picasso",
    "io.coil-kt",
)

// Deliberately absent from the list above: com.google.code.gson.
//
// It is on the project's written blacklist, but it arrives transitively and unavoidably
// through a dependency that is on the *whitelist*:
//     androidx.security:security-crypto -> com.google.crypto.tink:tink-android -> gson
// Tink stores its keyset as JSON, and EncryptedSharedPreferences -- which holds the
// SQLCipher passphrase -- stores its keyset through Tink. Listing gson here would make the
// build fail on a dependency the specification asks for, which turns a gate into noise.
//
// The way to actually remove it is to drop androidx.security:security-crypto and wrap the
// passphrase with an AES-256-GCM key from the Android Keystore directly (roughly sixty lines
// in :data, and Jetpack Security is deprecated upstream anyway). That is a step 4 decision,
// recorded in docs/licensing.md so it does not quietly become permanent.

/** Compose in any form. Checked only against `:keyboard`. */
val composeDependencyGroups = listOf(
    "androidx.compose",
    "org.jetbrains.compose",
)

/**
 * Fails the build when a merged manifest declares a networking permission.
 *
 * Reads the merged artifact rather than the module's own manifest on purpose: the module
 * manifest is trivially clean, and the interesting failure is a library injecting the
 * permission during the merge, where nobody would notice it.
 */
abstract class VerifyNoInternetPermission : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifests: ConfigurableFileCollection

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        val inspected = mutableListOf<String>()

        mergedManifests.files.filter { it.isFile }.sortedBy { it.absolutePath }.forEach { manifest ->
            inspected += manifest.absolutePath
            val text = manifest.readText()
            PERMISSION_ELEMENT.findAll(text).forEach { match ->
                val element = match.value
                // `tools:node="remove"` is a request to delete a permission, not to declare
                // one. The merger normally strips these before the output we read, but an
                // intermediate artifact may still carry one and it must not be misread.
                val isRemoval = element.contains("tools:node", ignoreCase = true) &&
                    element.contains("remove", ignoreCase = true)
                if (isRemoval) return@forEach
                val name = NAME_ATTRIBUTE.find(element)?.groupValues?.get(1) ?: return@forEach
                if (name in FORBIDDEN_PERMISSIONS) {
                    violations += "$name declared in ${manifest.absolutePath}"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("BorderKeys never talks to the network. A merged manifest disagrees:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Find the dependency that injected it and remove the dependency.")
                    appendLine("Do not paper over this with tools:node=\"remove\": that hides the")
                    appendLine("code which expected to have network access, it does not delete it.")
                },
            )
        }

        val out = receipt.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("no forbidden permission found")
                inspected.forEach { appendLine("inspected: $it") }
            },
        )
    }

    private companion object {
        val FORBIDDEN_PERMISSIONS = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )
        val PERMISSION_ELEMENT =
            Regex("<uses-permission(?:-sdk-23)?\\b[^>]*>", RegexOption.IGNORE_CASE)
        val NAME_ATTRIBUTE = Regex("android:name\\s*=\\s*\"([^\"]*)\"")
    }
}

/**
 * Walks a resolved runtime classpath and fails on any component whose group matches a
 * forbidden prefix.
 *
 * Takes the resolution *result* as a lazy `Property` rather than holding a `Configuration`:
 * that is the only shape that both keeps the configuration cache valid and defers the
 * actual resolution to execution time.
 */
abstract class VerifyDependencyGroups : DefaultTask() {

    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:Input
    abstract val forbiddenGroupPrefixes: ListProperty<String>

    @get:Input
    abstract val classpathName: Property<String>

    @get:Input
    abstract val rationale: Property<String>

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    @TaskAction
    fun verify() {
        val prefixes = forbiddenGroupPrefixes.get()
        val visited = HashSet<String>()
        val components = sortedSetOf<String>()
        val violations = sortedSetOf<String>()

        // Breadth-first over the whole graph, transitive dependencies included: a banned
        // artifact that arrives three levels down is exactly as present in the APK as one
        // written into a build script.
        val queue = ArrayDeque<ResolvedComponentResult>()
        queue += rootComponent.get()
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component.id.displayName)) continue
            val id = component.id
            if (id is ModuleComponentIdentifier) {
                val coordinates = "${id.group}:${id.module}:${id.version}"
                components += coordinates
                if (prefixes.any { id.group == it || id.group.startsWith("$it.") }) {
                    violations += coordinates
                }
            }
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { queue += it.selected }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Forbidden dependency on ${classpathName.get()}:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine(rationale.get())
                },
            )
        }

        val out = receipt.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                appendLine("classpath: ${classpathName.get()}")
                appendLine("components: ${components.size}")
                components.forEach { appendLine("  $it") }
            },
        )
    }
}

fun String.capitalized(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

allprojects {
    // Reproducible archives. F-Droid rebuilds the APK and byte-compares it; a zip entry
    // timestamp or a filesystem-dependent entry order is enough to fail that comparison.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

project(":app") {
    // `plugins.withId` hands its block an AppliedPlugin, not the Project, so the receiver
    // has to be captured out here.
    val app = this
    plugins.withId("com.android.application") {
        val verifyManifests = app.tasks.register("verifyNoInternetPermission") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if any merged manifest declares INTERNET or ACCESS_NETWORK_STATE."
        }
        val verifyDependencies = app.tasks.register("verifyNoForbiddenDependencies") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if a release runtime classpath contains a blacklisted group."
        }

        app.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            .onVariants { variant ->
                val suffix = variant.name.capitalized()

                val manifestCheck = app.tasks.register(
                    "verifyNoInternetPermission$suffix",
                    VerifyNoInternetPermission::class.java,
                ) {
                    // Resolves to build/intermediates/merged_manifests/<variant>/AndroidManifest.xml,
                    // but asking the artifact API for it also wires the producer dependency.
                    mergedManifests.from(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    receipt.set(
                        app.layout.buildDirectory.file(
                            "reports/borderkeys/no-internet-permission-${variant.name}.txt",
                        ),
                    )
                }
                verifyManifests.configure { dependsOn(manifestCheck) }

                // Finalizer on the manifest processing tasks, so the check also runs when
                // somebody builds a manifest without going through `assemble`.
                app.tasks.matching {
                    it.name.startsWith("process") &&
                        it.name.endsWith("Manifest") &&
                        it.name.contains(suffix)
                }.configureEach { finalizedBy(manifestCheck) }

                app.tasks.matching { it.name == "assemble$suffix" }.configureEach {
                    dependsOn(manifestCheck)
                    // The keyboard module's own gate, pulled in from here so that the two
                    // shipped commands -- assembleCoreRelease and assemblePlusRelease -- are
                    // enough to enforce every rule.
                    dependsOn(":keyboard:verifyKeyboardHasNoCompose$suffix")
                }

                if (variant.buildType == "release") {
                    val dependencyCheck = app.tasks.register(
                        "verifyNoForbiddenDependencies$suffix",
                        VerifyDependencyGroups::class.java,
                    ) {
                        classpathName.set(variant.runtimeConfiguration.name)
                        forbiddenGroupPrefixes.set(forbiddenDependencyGroups)
                        rootComponent.set(
                            variant.runtimeConfiguration.incoming.resolutionResult.rootComponent,
                        )
                        rationale.set(
                            "BorderKeys ships no telemetry, no HTTP client and no DI container. " +
                                "If this artifact is needed, the design is wrong, not the check.",
                        )
                        receipt.set(
                            app.layout.buildDirectory.file(
                                "reports/borderkeys/forbidden-dependencies-${variant.name}.txt",
                            ),
                        )
                    }
                    verifyDependencies.configure { dependsOn(dependencyCheck) }
                    app.tasks.matching { it.name == "assemble$suffix" }.configureEach {
                        dependsOn(dependencyCheck)
                    }
                }
            }
    }
}

project(":keyboard") {
    val keyboard = this
    plugins.withId("com.android.library") {
        val verifyCompose = keyboard.tasks.register("verifyKeyboardHasNoCompose") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Fails if :keyboard resolves any Compose artifact, in any variant."
        }

        keyboard.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
            .onVariants { variant ->
                val suffix = variant.name.capitalized()
                val composeCheck = keyboard.tasks.register(
                    "verifyKeyboardHasNoCompose$suffix",
                    VerifyDependencyGroups::class.java,
                ) {
                    classpathName.set(variant.runtimeConfiguration.name)
                    forbiddenGroupPrefixes.set(composeDependencyGroups)
                    rootComponent.set(
                        variant.runtimeConfiguration.incoming.resolutionResult.rootComponent,
                    )
                    rationale.set(
                        "The keyboard renders into a Canvas on the UI thread with a per-key " +
                            "latency budget of 2 ms. Compose must stay in :settings. If a class " +
                            "here needs Compose, that class belongs in :settings.",
                    )
                    receipt.set(
                        keyboard.layout.buildDirectory.file(
                            "reports/borderkeys/no-compose-${variant.name}.txt",
                        ),
                    )
                }
                verifyCompose.configure { dependsOn(composeCheck) }
                keyboard.tasks.matching { it.name == "assemble$suffix" }.configureEach {
                    dependsOn(composeCheck)
                }
            }
    }
}
