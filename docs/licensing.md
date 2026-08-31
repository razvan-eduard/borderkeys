<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Licensing inventory

Project licence: **GPL-3.0-or-later**. Full text in [`LICENSE`](../LICENSE) and, for REUSE, in
[`LICENSES/GPL-3.0-or-later.txt`](../LICENSES/GPL-3.0-or-later.txt).

Version 3 is a constraint, not a preference. Every AndroidX, Compose, Room and Kotlin artifact
below is Apache-2.0, which is compatible with GPLv3 and **incompatible with GPLv2**. A `LICENSE`
containing GPLv2 would have made this project undistributable on day one.

Two things this file records that no tool can derive:

1. Whether a dependency's licence is actually compatible with GPL-3.0-or-later, as opposed to
   merely being *a* licence.
2. Where every non-code asset came from — word lists, dictionaries, model weights. Those have
   licences too, and they are the ones that will get this project removed from F-Droid if they
   are wrong.

The dependency section is refreshed from `./gradlew :app:verifyNoForbiddenDependencies`, whose
report lists the full resolved release runtime classpath. Assets are maintained by hand.

---

## 1. Compile-time and runtime dependencies

Resolved from `coreReleaseRuntimeClasspath` and `plusReleaseRuntimeClasspath` at the versions
pinned in `gradle/libs.versions.toml`. Both flavors currently resolve to the same 128
components — `:assist` adds only modules already present — so there is one table.

### 1.1 AndroidX, Compose, Room, DataStore

| Group | Licence | Verdict |
|---|---|---|
| `androidx.activity` · `androidx.annotation` · `androidx.arch.core` · `androidx.autofill` · `androidx.collection` · `androidx.compose.*` · `androidx.concurrent` · `androidx.core` · `androidx.customview` · `androidx.datastore` · `androidx.documentfile` · `androidx.dynamicanimation` · `androidx.emoji2` · `androidx.graphics` · `androidx.interpolator` · `androidx.legacy` · `androidx.lifecycle` · `androidx.loader` · `androidx.localbroadcastmanager` · `androidx.navigationevent` · `androidx.print` · `androidx.profileinstaller` · `androidx.room` · `androidx.savedstate` · `androidx.security` · `androidx.sqlite` · `androidx.startup` · `androidx.tracing` · `androidx.transition` · `androidx.versionedparcelable` · `androidx.window` | Apache-2.0 | Compatible. Apache-2.0 is one-way compatible with GPLv3; the combined work is distributed under GPLv3. |

Each of these ships its licence text inside its AAR under `META-INF/`, and those files are
packaged into the APK — that is why `META-INF/androidx/**/LICENSE.txt` entries appear in the
built artifact and must not be stripped by a packaging exclusion.

### 1.2 Kotlin and JetBrains

| Component | Licence | Verdict |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib:2.4.10` | Apache-2.0 | Compatible. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-*:1.11.0` | Apache-2.0 | Compatible. |
| `org.jetbrains.kotlinx:kotlinx-serialization-*:1.9.0` | Apache-2.0 | Compatible. |
| `org.jetbrains:annotations:23.0.0` | Apache-2.0 | Compatible. |

### 1.3 Database and cryptography

| Component | Licence | Verdict |
|---|---|---|
| `net.zetetic:sqlcipher-android:4.11.0` | BSD-3-Clause (ZETETIC LLC) | Compatible. |
| — bundled SQLite | Public domain | Compatible. |
| — bundled OpenSSL, statically linked into `libsqlcipher.so` | Apache-2.0 | Compatible. **Verified, not assumed** — see below. |
| `androidx.security:security-crypto:1.1.0` | Apache-2.0 | Compatible. |
| `com.google.crypto.tink:tink-android:1.8.0` | Apache-2.0 | Compatible. |
| `com.google.code.gson:gson:2.8.9` | Apache-2.0 | Compatible. |

**The OpenSSL question.** This is the one genuine GPL-compatibility risk in the whole graph and
it is worth stating why. OpenSSL 1.x was distributed under the OpenSSL/SSLeay licence, whose
advertising clause is **incompatible with the GPL**; linking it into a GPL work requires a
system-library exception that does not apply to a library shipped inside your own APK. OpenSSL
relicensed to Apache-2.0 with version 3.0, which removed the problem.

SQLCipher links OpenSSL statically, so the version is a property of the prebuilt `.so`, not of
anything Gradle can resolve. Checked directly:

```
$ unzip -p app-core-release.apk lib/arm64-v8a/libsqlcipher.so > libsqlcipher.so
$ strings -a libsqlcipher.so | grep -E '^OpenSSL [0-9]'
OpenSSL 3.5.4 30 Sep 2025
```

3.5.4 is Apache-2.0. **Re-run this check whenever the SQLCipher version is bumped.** A downgrade
to a 1.x-based build would make the APK legally undistributable, and nothing in the build would
say so.

### 1.4 Transitive utilities

| Component | Licence | Verdict | Arrives via |
|---|---|---|---|
| `com.squareup.okio:okio:3.9.1` | Apache-2.0 | Compatible. | `androidx.datastore:datastore-core-okio` |
| `com.google.guava:listenablefuture:1.0` | Apache-2.0 | Compatible. | `androidx.concurrent:concurrent-futures` |
| `org.jspecify:jspecify:1.0.0` | Apache-2.0 | Compatible. | `androidx.room`, `androidx.security` |

### 1.5 Vendored source (compiled into the `plus` APK)

| Component | Licence | Verdict |
|---|---|---|
| `ggml-org/llama.cpp` v0.3.0, submodule at `assist/src/main/cpp/third_party/llama.cpp` | MIT | Compatible. MIT is one-way compatible with GPLv3; the combined work is distributed under GPLv3. Built from source as a CMake target, never as a prebuilt artefact, so the binary is reproducible from this tree plus the recorded submodule commit. |

Only the `llama` and `ggml` targets are built. Tests, tools, examples and the server are off,
and that last one matters beyond build time: **the server target links `cpp-httplib`**. Verified
before vendoring that with those options the httplib sources compile no objects at all — an HTTP
client inside the process holding the user's selected text would contradict this project's
central claim even if nothing ever called it.

Result: `libborderkeysassist.so` is 3.2 MB stripped for arm64-v8a and 2.4 MB for armeabi-v7a.

### 1.6 Build-time only (not in the APK)

| Component | Licence | Verdict |
|---|---|---|
| Android Gradle Plugin 9.3.1 | Apache-2.0 | Build tool; not distributed. |
| Kotlin Gradle plugins (compose, serialization) 2.4.10 | Apache-2.0 | Build tool. |
| KSP 2.3.11 | Apache-2.0 | Build tool. |
| `androidx.room:room-compiler` 2.8.4 | Apache-2.0 | Annotation processor; generated code is ours. |
| Gradle 9.5, Gradle wrapper | Apache-2.0 | Redistributed verbatim; annotated in `REUSE.toml`. |
| JUnit 4.13.2 | EPL-1.0 | Test-only, never linked into a distributed artifact. EPL-1.0 is GPL-incompatible, which is exactly why it must stay out of `implementation`. |
| MockK 1.14.6 | Apache-2.0 | Test-only. |
| `androidx.test:core` 1.7.0 | Apache-2.0 | Test-only. |
| NDK 27.1.12297006 toolchain, libc++ | Apache-2.0 with LLVM exception | Runtime pieces of libc++ are statically linked (`ANDROID_STL=c++_static`); the LLVM exception permits this without additional obligations. |

> **JUnit is the reason `testImplementation` and `implementation` are not interchangeable.**
> EPL-1.0 cannot be combined with GPLv3 in a distributed binary. It never reaches one here, and
> it must not start to.

### 1.7 Deliberately absent

Enforced by `verifyNoForbiddenDependencies`, which walks the transitive graph and throws:
Firebase, Google Play Services, MediaPipe, OkHttp, Retrofit, Ktor, Volley, Dagger/Hilt, Koin,
RxJava, Moshi, Timber, Glide, Coil, Picasso.

**One documented exception: `com.google.code.gson`.** It is on the project's written blacklist
but reaches the classpath transitively and unavoidably:

```
androidx.security:security-crypto → com.google.crypto.tink:tink-android → com.google.code.gson:gson
```

Tink serialises its keyset as JSON, and `EncryptedSharedPreferences` — which holds the SQLCipher
passphrase — stores its keyset through Tink. Listing gson in the enforcement set would fail the
build on a dependency the design asks for, so it is excluded there, with the reason written next
to the list in `build.gradle.kts` rather than left as a silent hole.

The way to actually remove it is to drop `androidx.security:security-crypto` and wrap the
passphrase with an AES-256-GCM key taken straight from the Android Keystore — roughly sixty
lines in `:data`, removing Tink and gson (three artifacts) from the APK. Jetpack Security is
deprecated upstream anyway. **Open decision for step 4.**

---

## 2. Assets

Nothing in this section can be derived from a build file. Every row is entered by hand at
import time, and `LanguagePack.licenseNote` in the database is a required field for exactly
this reason.

### 2.1 Currently shipped

| Asset | Source | Licence | Notes |
|---|---|---|---|
| *(none)* | — | — | No dictionaries, no models and no fonts are in the repository yet. `keyboard/src/main/assets/dict/` and `.../layouts/` are empty; the launcher icon is deliberately absent rather than inherited from an Android Studio template. |

### 2.2 Planned, with the licence question already open

| Asset | Step | Licence situation |
|---|---|---|
| `ro_RO.bkd`, `en_US.bkd` word lists and n-grams | 3 | **Unresolved.** Many lexical corpora are not free. The source corpus must be chosen for its licence first and its size second, and recorded here before the blob is committed. |
| Keyboard layout descriptions (`assets/layouts/*.json`) | 5 | Authored here, GPL-3.0-or-later. |
| FUTO Swipe neural weights | 6.3 (option B1) | **FUTO Model Weights License 1.0 — not OSI, not free.** Verified by reading the licence at `huggingface.co/futo-org/futo-swipe`: commercial use, redistribution and derivative models are all permitted, but a **visible "powered by FUTO Swipe" notice to end users is mandatory** and its absence is "a material breach"; sublicensing is forbidden; the licence terminates immediately on a patent claim against the weights. Weights are an aggregated asset rather than linked code, so they coexist with GPL legally — but `plus` would then carry a non-free artifact and must declare `NonFreeAssets`. |
| FUTO `swipe-library` C++ inference code | 6.3 | **GPL-3.0-or-later — verified via the GitLab API, `license.key = gpl-3.0+`.** That is not merely compatible with this project, it is the same licence. Not to be confused with the FUTO Keyboard *application*, which is under the FUTO Source First License 1.0 — not free, and it must never enter this repository. |
| ExecuTorch v1.2.0 | 6.3 (option B1) | **BSD-3-Clause.** Compatible. Not previously accounted for: `swipe-library` vendors ExecuTorch as a git submodule and its models are `.pte` files, so option B1 means building PyTorch's runtime into `libborderkeys.so`, not the self-contained C++ library the plan assumed. It also requires CMake 3.29+, against the 3.22.1 this project pins. See section 2.4. |
| FUTO gesture corpus, `futo-org/swipe.futo.org` | 6.3 (option B2) | **MIT — verified from the dataset's own licence tag.** Free. There is also `futo-org/swipe-negatives` under Apache-2.0. Together these are what make option B2 legally possible without asking anyone's permission. |
| GGUF language model for the text assistant | 7 | **Never bundled, in either flavor.** A model arrives only because the user chose a file, and only if its SHA-256 matches an entry in `KnownAssistModels`. All three entries are **Apache-2.0**, verified from the publishing repositories' own file metadata: Qwen3-0.6B-Q8_0 (610 MB, `9465e63a…`), Qwen3-1.7B-Q8_0 (1.75 GB, `061b54da…`) and SmolLM3-Q4_K_M (1.83 GB, `8334b850…`). So `plus` carries no non-free asset on this account and declares no anti-feature for it. |

### 2.3 What the licence check actually found

Checked before writing any tier-B code, because the plan depended on facts about other people's
repositories rather than on anything in this one. Three of the four assumptions held. The fourth
did not.

**Held.** The corpus is MIT. `swipe-library` is GPL-3.0-or-later, identical to ours rather than
merely compatible. The weights are under a non-OSI licence whose real obligation is a visible
attribution notice, exactly as expected.

**Did not hold.** `swipe-library` is not a self-contained C++ library. Its `.gitmodules` vendors
`github.com/pytorch/executorch`, its README states "It depends on ExecuTorch for model
inference", and the published models are `.pte` files — the ExecuTorch format. So integrating
tier B through it means compiling PyTorch's runtime into this keyboard's native library.

That matters for three reasons:

1. `docs/state-of-the-art.md` argues *against* ExecuTorch for the text assistant, on the grounds
   that its static graph fixes the context length at compile time. That argument does not apply
   to a swipe encoder, whose input is always 64 points — but it does mean the project would be
   depending on a runtime it had reasoned itself out of using elsewhere.
2. `swipe-library` requires **CMake 3.29+**, and `gradle/libs.versions.toml` pins 3.22.1. That
   pin would have to move, for every native target, to add one optional feature to one flavor.
3. It is a large third-party build inside the module with the tightest latency budget in the
   application. Not an AAR, which the rules forbid — but not the small vendored library the plan
   described either.

None of this makes B1 impossible. It makes it a different decision than the one written down,
and it is the maintainer's to make rather than one to take quietly while implementing.

### 2.4 The text assistant's runtime and models, checked before writing code

Same discipline as the swipe decoder, and this time every assumption held.

**llama.cpp is MIT** and builds for both Android ABIs with CMake 3.22.1 — the version this
project pins, unlike `swipe-library`, which needs 3.29+. It configures and links without
`cpp-httplib` once the server target is off, which was the one thing worth checking before
putting an inference runtime inside a keyboard that holds no network permission.

**Three of the candidate models are free software.** Qwen3 0.6B and 1.7B are Apache-2.0, as is
SmolLM3. Phi-4-mini is MIT. That matters more than it might look: it means the `plus` flavor can
offer a text assistant **without a single non-free asset**, which is not true of the swipe
weights in section 2.2. The two remaining candidates are not free and are not in the registry:
LFM2 is under a bespoke "LFM Open License", and Gemma 3 is under Google's Gemma Terms of Use.
Gemma is the best of them on a weak phone — it fits in 4 GB — and that is the trade being
declined, on the grounds that a keyboard whose free build has no anti-features should not need
one in its paid-for-in-storage build either.

Nothing is bundled either way. A 610 MB model in an APK is not a distribution mechanism, and
F-Droid would be right to refuse it.

### 2.5 Option B2 — what a free-weights swipe decoder would cost

Recorded here so the option stays real rather than aspirational.

- **Data:** the MIT gesture corpus, already free. No collection needed.
- **Model:** the published architecture is ~635K parameters, a TCN encoder with a spatial head
  taking the active layout at inference time. Small enough that inference can be hand-written in
  C++ with a plain GEMM and no third-party runtime.
- **Training:** a single mid-range GPU, on the order of days rather than weeks, at this
  parameter count. The pipeline itself is the real cost — feature extraction (64-point
  resampling, Savitzky-Golay, the 8-D vector), CTC training loop, evaluation harness.
- **Payoff:** `plus` becomes fully free, `NonFreeAssets` disappears, and the extension point in
  the decoder means swapping the weights touches no other code.

The code must therefore keep the weights behind a clean extension point — which is what the
`GestureDecoder` interface in step 6.4 is for.

---

## 3. Source correspondence (GPL section 6)

The binary points at its own source:

- `BuildConfig.GIT_COMMIT` — from `git rev-parse HEAD` at configuration time, in `:settings`.
- `BuildConfig.SOURCE_URL` — from `borderkeys.sourceUrl` in `gradle.properties`.

The About screen shows both and offers a button that hands the URL to `Intent.ACTION_VIEW`.
The app has no `INTERNET` permission and needs none: the browser has it, we only pass the
intent.

## 4. Reproducible builds

F-Droid rebuilds the APK and compares it byte for byte, so this is a licensing concern as much
as a build one — an unreproducible build cannot be verified to correspond to the published
source.

- `isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` on every archive task
  (root `build.gradle.kts`). Verified: APK entries carry the fixed 1981-01-01 stamp.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }` — the blob AGP otherwise
  writes into the signing block is encrypted with a Google key and is not reproducible.
- `packaging { jniLibs { useLegacyPackaging = false } }` — uncompressed, page-aligned, stable.
- Every dependency version is pinned in `gradle/libs.versions.toml`; no dynamic versions and no
  version ranges anywhere in the build.

## 5. F-Droid anti-features

| Flavor | AntiFeatures | Why |
|---|---|---|
| `core` | *(none)* | Contains no model, no non-free asset, no tracking, no network path. This flavor existing with an empty anti-feature list is the entire point of the flavor split. |
| `plus` | `NonFreeAssets` **if** it ships FUTO weights (option B1) | Declared honestly, together with the required visible attribution in-app. Removed if option B2 lands. |
