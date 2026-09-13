<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

<p align="center">
  <img src="docs/images/header.jpg" alt="BorderKeys — a keyboard with no permissions, and an optional on-device assistant" width="100%">
</p>

# BorderKeys

An Android keyboard that holds no permissions, opens no sockets, and predicts your next word
with a deterministic engine written in C++.

Nothing here downloads at runtime. Dictionaries and models are either inside the APK or
imported by you from a local file. There is no telemetry, no crash reporting, no account, no
sync. The `core` build contains no machine-learning model of any kind.

Licensed **GPL-3.0-or-later**.

[![Release](https://img.shields.io/github/v/release/razvan-eduard/borderkeys)](https://github.com/razvan-eduard/borderkeys/releases)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)

**F-Droid repo (custom, third-party):** https://razvan-eduard.github.io/vox-fdroid-repo/repo/

Not yet on the official F-Droid repository — the submission is prepared (see
[`metadata/`](metadata)) but not merged. Until then, add the URL above as a repository in your
F-Droid client to get both builds and their updates. It also carries the other apps from
[VoxApps](https://github.com/razvan-eduard/VoxApps), which is who hosts it. Or install a release
directly from [GitHub Releases](https://github.com/razvan-eduard/borderkeys/releases/latest).

## Features

### Typing

- **Deterministic n-gram prediction and autocorrect**, in C++, on-device. No cloud lookup, no
  per-keystroke telemetry to anyone — the engine is a compiled library, not a service.
- **Several languages active at once.** Every installed dictionary is consulted on every word;
  the one that actually recognises what you typed wins, without a manual switch.
- **Retroactive correction when the conversation's language turns out to differ.** A correction
  applied while typing can be wrong not because the guess was bad, but because the keyboard read
  the wrong language at the time — the sentence itself proves it a few words later. BorderKeys
  notices the flip and offers the affected word back (or fixes it automatically, your choice),
  landing on the field's own undo history like any other edit.
- **Swipe typing**, geometric (SHARK²) and deterministic in both flavors — no model, no training
  data, no accuracy number that depends on what you happened to type it on. A from-scratch neural
  decoder (a small TCN, hand-written in C++, no ML runtime) exists in `plus`'s own source and test
  suite, but is not the active one yet: see [Flavors](#flavors) below.
- **A names dictionary** built from Wikidata (CC0), so a name capitalises correctly mid-sentence
  instead of only at the start of one or after a shift.
- **Alternate physical layouts** — AZERTY, Dvorak, QWERTZ, ClearFlow, KasRoz and Toki Pona — for
  the 26-letter alphabets every bundled dictionary already knows.
- **A personal dictionary you can see and edit.** Every learned word or phrase is listed, with a
  `Forget` (remove it) and a `Block` (never suggest it again) right beside it — not a black box.

### Quick actions

A row of buttons for what otherwise takes several gestures — copying a word, pasting, selecting
everything — built in, and extendable: a **custom quick action** is a macro of steps you define
yourself, pinned onto the bar exactly like a built-in one.

<p align="center">
  <img src="docs/images/quick_actions.png" alt="The quick-action bar, expanded, with its built-in clipboard actions" width="40%">
</p>

### Themes

Built-in themes across several colour families, full manual control over every colour and
corner radius with a live preview, and a **custom theme library**: save what you built, import a
theme someone shared, export and rename your own — kept separately from whichever single theme
is active right now.

<p align="center">
  <img src="docs/images/custom_theme.png" alt="The theme screen: My Themes (save or import), and manual colour controls" width="40%">
</p>

### The assistant, `plus` only

A **draft box** — write somewhere the app itself cannot see, then ask an on-device model to
correct, shorten, summarise or translate it, with a version kept for every step so nothing is
lost to a bad answer. Reachable from a text selection in *any* app through four more entries in
the system's own text-selection menu (Correct, Shorten, Summarise, and any of your own saved
instructions), each running immediately instead of opening an idle box first.

### Backup, restore, and control

Everything this keyboard has learned and configured — dictionaries, themes, quick actions,
saved instructions — writes to and reads from a single file, under your control, on your
schedule. Nothing syncs anywhere on its own.

<p align="center">
  <img src="docs/images/language_switch.png" alt="The retroactive language-switch correction offer, in the suggestion strip's own slot" width="40%">
</p>

## Two builds, one repository

| | `core` | `plus` |
|---|---|---|
| Deterministic n-gram engine, geometric swipe decoding | ✓ | ✓ |
| Multiple languages active at once, no manual switching | ✓ | ✓ |
| Zero permissions, no `INTERNET` in the merged manifest | ✓ | ✓ |
| On-device text assistant (draft box) | | ✓ |

Both stay free software end to end — `plus` only *adds* to `core`, it never trades privacy
for the extra features. The separation is at compile time, not behind a runtime flag: unpack
`app-core-release.apk` and the assistant's code simply is not in it.

Both are published as **separate applications** (`com.borderkeys` and `com.borderkeys.plus`) so
they can be installed side by side — choosing the assistant never costs you the settings or the
learned dictionary of the other build.

## Screenshots

### Typing, in both builds

| Suggestions match the case you typed | Settings | Theme | Several languages at once |
|---|---|---|---|
| ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/1_typing.jpg) | ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/2_home.jpg) | ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/3_theme.jpg) | ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/4_languages.jpg) |

### The assistant, `plus` only

| Draft box, opened on a selection | Working, on-device | A version kept for every step | The models this build will run |
|---|---|---|---|
| ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/5_draft_box.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/6_translating.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/7_translated.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/8_assistant_models.jpg) |

## Why the modules are split the way they are

The rule the whole project is arranged around is that Compose must never enter the keyboard's
rendering process. Not "we agreed not to" -- it cannot be imported, because no path in the
dependency graph puts it on `:keyboard`'s classpath, and `verifyKeyboardHasNoCompose` fails
the build if that ever changes.

```
:app ──> :keyboard ──> :data
  │           (no Compose here, in any variant)
  ├──> :settings ──> :data
  │         └──> :keyboard      (theme preview embeds the real keyboard view)
  └──> :assist ──> :data        (plusImplementation: absent from the core APK)
```

| module      | what it is                                                      |
|-------------|-----------------------------------------------------------------|
| `:app`      | Thin application shell. Manifest, flavors, R8, signing. No logic. |
| `:keyboard` | `InputMethodService`, the `Canvas` keyboard view, JNI, the C++ prediction and gesture engines. |
| `:data`     | Room over SQLCipher, typed DataStore. The only place state lives. |
| `:settings` | Every line of Compose in the repository.                          |
| `:assist`   | Local text assistant, own process, `plus` flavor only.            |

## Flavors

| flavor | contains                                                                 |
|--------|--------------------------------------------------------------------------|
| `core` | Deterministic n-gram engine, geometric (SHARK²) swipe decoding. No model files, no neural code, no non-free assets. |
| `plus` | Adds the local text assistant.               |

`core` is the default and is the one that stays entirely free software. The separation is at
compile time, not behind a runtime flag: unpack `app-core-release.apk` and the assistant's code
is not in it. The swipe and prediction engine itself is byte-identical between the two builds --
a neural swipe tier is a documented, legally-cleared future option (see
[`docs/licensing.md`](docs/licensing.md) §2.2.1/§2.5), not something either flavor ships today.

## Building

Requires JDK 21 (the Gradle daemon provisions it), the Android SDK with platform 37, and
NDK 27.1.12297006.

```bash
./gradlew :app:assembleCoreRelease      # the free build
./gradlew :app:assemblePlusRelease      # with the optional model-backed features
./gradlew test                          # JVM tests, every module
```

Three checks run as part of `assemble` and fail the build rather than warn:

- `verifyNoInternetPermission` — reads the *merged* manifest, so a library that injects
  `INTERNET` during the merge is caught rather than inherited.
- `verifyNoForbiddenDependencies` — walks the release runtime classpath for telemetry, HTTP
  clients and DI containers, transitively.
- `verifyKeyboardHasNoCompose` — walks `:keyboard`'s classpath for any Compose artifact.

## Installing on a device

Install the **release** build, never a debug one. A debug build has R8 off and JNI debugging
on, so every latency number this project is built around is meaningless on it.

```bash
./gradlew :app:installCoreRelease
```

That needs a signing key at `~/.borderkeys/borderkeys-release.jks` with its password in
`~/.borderkeys/keystore_password.txt` (or `RELEASE_KEYSTORE_PATH` and
`RELEASE_KEYSTORE_PASSWORD` in the environment, which is how CI supplies it). Without one the
build still succeeds and produces an unsigned APK, so a contributor without the key is never
blocked.

## Verifying the claims yourself

```bash
# Zero permissions. Not "only harmless ones" -- zero.
aapt2 dump permissions app/build/outputs/apk/core/release/app-core-release.apk

# No model files, no assistant classes in the free build.
unzip -l app/build/outputs/apk/core/release/app-core-release.apk
```

Release APKs published from CI carry a build provenance attestation:

```bash
gh attestation verify BorderKeys-v0.3.0-core.apk --repo razvan-eduard/borderkeys
```

## Documentation

- [`docs/licensing.md`](docs/licensing.md) — every dependency and asset, with its licence and
  a compatibility verdict, plus the reproducible-build settings and the F-Droid anti-feature
  checklist.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — DCO, no CLA.
- [`metadata/`](metadata) — F-Droid submission metadata for both packages.
