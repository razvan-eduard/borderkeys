<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Contributing to BorderKeys

## Developer Certificate of Origin, not a CLA

Every commit must carry a `Signed-off-by` line:

```
Signed-off-by: Your Name <your.email@example.com>
```

`git commit -s` adds it. It is an assertion about the code you are submitting, quoted in full
at the bottom of this file: that you wrote it, or that you have the right to submit it under
this project's licence.

There is no contributor licence agreement, and there will not be one. A CLA assigns rights to
a single owner, which would mean the project could later be relicensed out from under the
people who built it. That contradicts the premise. The DCO records where a contribution came
from and transfers nothing.

## Licence header on every file

Every source file — Kotlin, C++, CMake, Gradle, Python, XML, shell — starts with:

```
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
```

in that file's comment syntax. Files that cannot carry a comment (binary blobs, generated
assets) are covered by an entry in `REUSE.toml`. `reuse lint` runs in CI and blocks the merge,
so this is checked, not requested.

## What the build will refuse

Three Gradle tasks run as part of `assemble` and throw rather than warn:

- **`verifyNoInternetPermission`** — the merged manifest must not declare `INTERNET` or
  `ACCESS_NETWORK_STATE`; and the `core` manifest must not carry any of the assistant's four
  text-selection entries (Correct, Shorten, Summarise, custom), which only `plus` can run.
- **`verifyNoForbiddenDependencies`** — no Firebase, Play Services, MediaPipe, OkHttp,
  Retrofit, Ktor, Volley, Dagger/Hilt, Koin, RxJava, Gson, Moshi, Timber, Glide, Coil or
  Picasso on a release runtime classpath, transitively.
- **`verifyKeyboardHasNoCompose`** — `:keyboard` must not resolve any `androidx.compose.*`
  artifact, in any variant.

If one of these fails, the fix is almost never to relax the check.

## Adding a dependency

Open an issue first. A pull request that adds one is reviewed on three numbers: added DEX
method count, added APK size, and what it drags in transitively. "It is convenient" is not one
of them. The current graph is small enough to read end to end, and that is a feature with a
maintenance cost, not an accident.

The parts of the project that are allowed to be heavy — the neural swipe decoder, the text
assistant — are compiled from source into our own native library, in the `plus` flavor only,
and never as a third-party AAR.

## Rules that are not up for negotiation

1. **No network.** No permission, no HTTP client, no telemetry, nothing that downloads at
   runtime. Language packs and models are bundled or imported by the user from a local file.
2. **The keyboard view draws on a `Canvas`.** No Compose, no XML inflation. Touches are
   resolved arithmetically against a precomputed hit-box array.
3. **No allocation on the hot path.** `onDraw`, `onTouchEvent` and the JNI bridge allocate
   nothing: no `Paint`, no `Rect`, no `String`, no boxing, no capturing lambda, no iterator.
   Primitive arrays, not collections.
4. **Prediction is deterministic.** The core is a compact trie plus n-grams, in C++. Neural
   models are permitted in exactly two optional places, both off the typing path and both
   entirely offline: the swipe decoder's tier B, and the text assistant in its own process.
5. **Private mode is a security requirement.** In a password field, or when
   `IME_FLAG_NO_PERSONALIZED_LEARNING` is set: no learning, no clipboard history, no personal
   dictionary suggestions, no assistant.

## Latency budget

Per keystroke, p95, mid-range device:

| step                                          | budget    |
|-----------------------------------------------|-----------|
| touch → commit through `InputConnection`      | ≤ 2 ms, UI thread |
| `onDraw` of the invalidated region             | ≤ 4 ms    |
| prediction request                             | ≤ 8 ms, off the UI thread, never blocking |
| full swipe decode, finger up → candidates      | ≤ 30 ms   |

A change that regresses these is a bug even if every test passes.

## Before opening a pull request

```bash
./gradlew test
./gradlew :app:assembleCoreRelease :app:assemblePlusRelease
reuse lint
```

If you touched the gesture decoder, also run `tools/gesture_replay.py`. Top-1 accuracy must
not fall below the value recorded in `docs/`; CI compares against it.

## What a debug build will tell you

A debuggable build — `assembleCoreDebug`, `assemblePlusDebug`; for the emulator, never for
measuring latency — logs every gate on the learning path under one tag: the field's private
flags, the correction decided at a delimiter, what the buffer accepted, when the flush is due,
the database write and the row count before and after it.

```bash
adb logcat -s BorderKeysDebug:D
```

It can also type for you. Arm it with a word and open any field:

```bash
adb shell settings put global borderkeys_debug_type <word>
adb shell settings delete global borderkeys_debug_type   # disarm
```

The keyboard then types that word — plus one fresh letter per field opened, so every open is a
word the dictionary has not seen — through the same `onKey` path a finger takes, the moment the
field opens. A release build carries none of this: the gate is the build's own debuggable flag,
and the messages are never built there.

## Commit messages

Present tense, and say why the change is correct rather than what it touched. The diff already
says what it touched.

## Developer Certificate of Origin 1.1

By making a contribution to this project, I certify that:

- (a) The contribution was created in whole or in part by me and I have the right to submit it
  under the open source licence indicated in the file; or
- (b) The contribution is based upon previous work that, to the best of my knowledge, is
  covered under an appropriate open source licence and I have the right under that licence to
  submit that work with modifications, whether created in whole or in part by me, under the
  same open source licence (unless I am permitted to submit under a different licence), as
  indicated in the file; or
- (c) The contribution was provided directly to me by some other person who certified (a), (b)
  or (c) and I have not modified it.
- (d) I understand and agree that this project and the contribution are public and that a
  record of the contribution (including all personal information I submit with it, including
  my sign-off) is maintained indefinitely and may be redistributed consistent with this
  project or the open source licence(s) involved.
