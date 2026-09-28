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
// Source provenance, required by GPL section 6: the commit and the source URL the About screen
// shows.
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

val borderkeysRepoUrl: String =
    providers.gradleProperty("borderkeys.repoUrl").getOrElse("")

val borderkeysReleasesUrl: String =
    providers.gradleProperty("borderkeys.releasesUrl").getOrElse("")

extra["borderkeysGitCommit"] = borderkeysGitCommit
extra["borderkeysSourceUrl"] = borderkeysSourceUrl
extra["borderkeysRepoUrl"] = borderkeysRepoUrl
extra["borderkeysReleasesUrl"] = borderkeysReleasesUrl

// ---------------------------------------------------------------------------------------
// Verification tasks: no network permission, no forbidden dependency, no Compose inside the
// keyboard.
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
    // On-device ML shipped as a prebuilt AAR.
    "com.google.mediapipe",
    // HTTP clients.
    "com.squareup.okhttp",
    "com.squareup.okhttp3",
    "com.squareup.retrofit",
    "com.squareup.retrofit2",
    "io.ktor",
    "com.android.volley",
    // Dependency injection containers.
    "com.google.dagger",
    "io.insert-koin",
    // Reflection-heavy or oversized helpers.
    "io.reactivex",
    "com.squareup.moshi",
    "com.jakewharton.timber",
    "com.github.bumptech.glide",
    "com.squareup.picasso",
    "io.coil-kt",
)

// com.google.code.gson is not on the list: it arrives through
//     androidx.security:security-crypto -> com.google.crypto.tink:tink-android -> gson
// (docs/licensing.md).

/** Compose in any form. Checked only against `:keyboard`. */
val composeDependencyGroups = listOf(
    "androidx.compose",
    "org.jetbrains.compose",
)

/**
 * The text-selection menu entries that run the assistant, which the core build must not carry
 * -- see app/src/core/AndroidManifest.xml. Full names, as the merged manifest spells them.
 */
val assistantOnlyComponents = listOf(
    "com.borderkeys.settings.ProcessTextCorrectAlias",
    "com.borderkeys.settings.ProcessTextShortenAlias",
    "com.borderkeys.settings.ProcessTextSummariseAlias",
    "com.borderkeys.settings.ProcessTextCustomAlias",
)

/** Fails the build when a merged manifest declares a networking permission. */
abstract class VerifyNoInternetPermission : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifests: ConfigurableFileCollection

    /**
     * Component names that must not appear in these manifests: the assistant's PROCESS_TEXT
     * entries, for the core flavor (app/src/core/AndroidManifest.xml).
     */
    @get:Input
    abstract val forbiddenComponents: ListProperty<String>

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    @TaskAction
    fun verify() {
        val violations = mutableListOf<String>()
        val inspected = mutableListOf<String>()

        mergedManifests.files.filter { it.isFile }.sortedBy { it.absolutePath }.forEach { manifest ->
            inspected += manifest.absolutePath
            val text = manifest.readText()
            forbiddenComponents.get().forEach { component ->
                if (text.contains("android:name=\"$component\"")) {
                    violations += "$component declared in ${manifest.absolutePath} " +
                        "(an assistant entry in a build without the assistant)"
                }
            }
            PERMISSION_ELEMENT.findAll(text).forEach { match ->
                val element = match.value
                // `tools:node="remove"` deletes a permission rather than declaring one.
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
 * forbidden prefix. Takes the resolution result as a lazy `Property`, not a `Configuration`,
 * for the configuration cache.
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

        // Breadth-first over the whole graph, transitive dependencies included.
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
    // Reproducible archives, for F-Droid's byte-for-byte rebuild.
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
                    // Through the artifact API, which also wires the producer dependency.
                    mergedManifests.from(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    // The assistant's PROCESS_TEXT entries, for the core build.
                    forbiddenComponents.set(
                        if (variant.flavorName == "core") assistantOnlyComponents else emptyList(),
                    )
                    receipt.set(
                        app.layout.buildDirectory.file(
                            "reports/borderkeys/no-internet-permission-${variant.name}.txt",
                        ),
                    )
                }
                verifyManifests.configure { dependsOn(manifestCheck) }

                // Also a finalizer on the manifest processing tasks.
                app.tasks.matching {
                    it.name.startsWith("process") &&
                        it.name.endsWith("Manifest") &&
                        it.name.contains(suffix)
                }.configureEach { finalizedBy(manifestCheck) }

                app.tasks.matching { it.name == "assemble$suffix" }.configureEach {
                    dependsOn(manifestCheck)
                    // The keyboard module's own gate, run by assemble as well.
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
