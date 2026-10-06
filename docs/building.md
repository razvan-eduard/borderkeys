<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Building BorderKeys

Everything that turns this repository into the two APKs, the language packs and the test
readings: the toolchain, the Gradle tasks and what each one generates, the native library and
its host build, the checks that fail a build, the release path, and every tool and script under
`tools/` and `scripts/`, grouped by what it is for.

- [The toolchain](#the-toolchain)
- [The modules](#the-modules)
- [Everyday commands](#everyday-commands)
- [What a build generates](#what-a-build-generates)
- [The native library](#the-native-library)
- [The checks that fail a build](#the-checks-that-fail-a-build)
- [Tests](#tests)
- [Signing, versions and releases](#signing-versions-and-releases)
- [Continuous integration](#continuous-integration)
- [The word lists and language packs](#the-word-lists-and-language-packs)
- [Tools and scripts, by purpose](#tools-and-scripts-by-purpose)

## The toolchain

| What | Version | Where it is pinned |
|---|---|---|
| JDK | 21 | `gradle/gradle-daemon-jvm.properties`; the foojay resolver in `settings.gradle.kts` provisions it when it is missing |
| Android Gradle Plugin | 9.3.3 | `gradle/libs.versions.toml` (`agp`) |
| Kotlin | 2.4.10 | `gradle/libs.versions.toml` (`kotlin`) |
| Android SDK | compile 37, target 36, minimum 30 | `gradle/libs.versions.toml`; 30 is a hard floor, inline autofill suggestions start there |
| NDK | 27.1.12297006 | `gradle/libs.versions.toml` (`ndk`) |
| CMake | 3.22.1 | `gradle/libs.versions.toml` (`cmake`) |
| Python | 3, standard library only | every tool under `tools/` except `tools/swipe_model/`, which has its own `requirements.txt` |

Python is needed by the build itself: the dictionary packs are compiled by `tools/build_dict.py`
at every build (see [What a build generates](#what-a-build-generates)). `hunspell` on `PATH` is
needed only to rebuild word lists, never to build the app.

## The modules

| Module | What it is |
|---|---|
| `:app` | The application shell: manifest, flavours, R8, signing. No logic. |
| `:keyboard` | The input method service, the `Canvas` keyboard view, the JNI bridge and the C++ engine. No Compose, in any variant. |
| `:data` | Room over SQLCipher and typed DataStore: the only place state lives. |
| `:settings` | Every line of Compose: the settings application. |
| `:effects` | The particle effects, drawn by the keyboard and by the settings preview alike. |
| `:i18n` | Every sentence the app shows, as JSON catalogues in 22 languages, and the generated `Keys.kt`. |
| `:assist` | The local text assistant in its own process: `plus` only. |

Two flavours share one dimension, `engine`: `core` (no model, no neural code) and `plus`, which
adds the assistant and compiles the neural swipe decoder into the native library
(`-DBORDERKEYS_NEURAL_SWIPE=ON`, `keyboard/build.gradle.kts`). They are two applications,
`com.borderkeys` and `com.borderkeys.plus`.

## Everyday commands

```bash
./gradlew :app:assembleCoreRelease          # the free build
./gradlew :app:assemblePlusRelease          # with the assistant and the neural swipe decoder
./gradlew :app:assembleCoreDebug            # a debug build, for an emulator only
./gradlew test                              # every module's JVM tests
./gradlew :keyboard:buildDictionaries       # the bundled .bkd packs alone
```

APKs land in `app/build/outputs/apk/<flavour>/<buildType>/`. Install a release build with
`adb install -r <apk>` rather than a Gradle install task: a debug build has R8 off and JNI
debugging on, so its latency numbers mean nothing.

Gradle properties that change a build:

| Property | Effect |
|---|---|
| `-Pborderkeys.extraAbis=x86_64` | Adds ABIs to the default `arm64-v8a` and `armeabi-v7a`, for an x86 emulator. |
| `-Pborderkeys.keystore.path=…`, `-Pborderkeys.keystore.alias=…` | The release keystore, when not in `~/.borderkeys/` or the environment. |
| `-Pborderkeys.taps=<dir>` | The tap corpora `TapCorpusTest` types; without it that test skips itself. |
| `-Pborderkeys.readings=<dir>` | Makes the pipeline tests write one line per corpus case into `<dir>`. |

## What a build generates

Nothing below is committed; each is rebuilt by Gradle when its inputs change.

| Output | Task | From | Notes |
|---|---|---|---|
| `keyboard/build/generated/dictionaries/dict/<tag>.bkd` | `:keyboard:buildDictionaries` | `dictionaries/<tag>.tsv` with its `.ngrams`, `.pos`, `.known` and `.case` beside it | Runs `tools/build_dict.py` per list and adds the folder to the app's assets. A list whose `.case` lacks a flagged name fails the build. `dictionaries/extra/` is not compiled here. |
| `settings/build/generated/source/settingsIndex/…/SettingsIndex.kt` | `:settings:generateSettingsIndex` | the settings screen sources | Every titled row, switch, heading and picker title, with its card and its note, for the search box. A new setting is searchable at the next build. |
| `libborderkeys.so` per ABI | the CMake build behind `:keyboard` | `keyboard/src/main/cpp/` | See [The native library](#the-native-library). |

Generated once by a tool and committed, because they change rarely and are checked in CI:

| File | Tool | Check |
|---|---|---|
| `i18n/src/main/java/com/borderkeys/i18n/Keys.kt` | `tools/gen_keys.py` | the catalogue tests |
| `keyboard/src/main/assets/compose/compose.json` | `tools/make_compose.py` | "The compose table matches its generator" |
| `keyboard/src/main/assets/extra_keys.json` | `tools/make_extra_keys.py` | "The extra keys match their generator" |
| `keyboard/src/main/assets/emoji/*` | `tools/build_emoji.py` | — |
| `dictionaries/<tag>.case` | `tools/make_case_evidence.py` | the dictionary build itself |

Run `python3 tools/gen_keys.py` after adding a string to `en.json`.

## The native library

`keyboard/src/main/cpp/CMakeLists.txt` builds `libborderkeys.so`: the prediction engine, the
pack reader, the touch model, the tap decoder, the geometric swipe decoder and, in `plus`, the
neural one. It is compiled with the NDK's `c++_static` STL, C++17, for each ABI in
`abiFilters`.

The same sources build on the host from `native-tests/CMakeLists.txt`:

```bash
cmake -S native-tests -B native-tests/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native-tests/build --parallel        # everything
ctest --test-dir native-tests/build
```

| Target | What it is |
|---|---|
| `borderkeys` | The host library, `native-tests/build/libborderkeys.{so,dylib}`, which the Kotlin pipeline tests load through JNI. Build it before `./gradlew test`, or those tests skip themselves. |
| `borderkeys_tests` | The engine's own tests (ctest `engine`). |
| `pack_corpus_test` | Malformed and mutated packs through the loader (ctest `pack_corpus`). |
| `suggest_eval` | The strip, autocorrect, reachability and context readings: `--autocorrect`, `--reachable`, `--context`, `--explain`. |
| `touch_eval` | The touch model on the tap corpora. |
| `gesture_replay`, `tcn_replay` | The two swipe decoders on recorded swipes. |

The engine's tuning constants can be overridden per build for a sweep, as CMake cache variables
(`-DBORDERKEYS_SLIP_SCALE=…`, `-DBORDERKEYS_EDIT_PENALTY=…` and the others listed in
`native-tests/CMakeLists.txt`); the shipped values are the ones in the source.

After changing a JNI signature, `touch` the bridge sources so the host library rebuilds, or the
Kotlin pipeline tests run against the old one and skip.

## The checks that fail a build

Wired into `assemble`, in the root `build.gradle.kts`:

- `verifyNoInternetPermission` reads the merged manifest of each variant, so a library that
  injects `INTERNET` is caught; on `core` it also refuses the assistant's text-selection entries.
- `verifyNoForbiddenDependencies` walks the release runtime classpath for telemetry, HTTP
  clients and dependency-injection containers.
- `verifyKeyboardHasNoCompose` walks `:keyboard`'s classpath for any Compose artifact.

And in the compilers:

- `tools/build_dict.py` refuses a list with a name its `.case` file does not hold.
- The settings index task refuses a screen file named after a screen the `Screen` enum lacks.

## Tests

`docs/testing.md` describes every test and corpus and records their readings. To run them:

| Suite | Command | Needs |
|---|---|---|
| JVM, every module | `./gradlew test` | the host library and the packs, for the pipeline tests |
| JVM, one class | `./gradlew :keyboard:testCoreDebugUnitTest --tests 'com.borderkeys.predict.PipelineCorpusTest'` | as above |
| Native | `ctest --test-dir native-tests/build` | the host build |
| Every corpus reading, for a diff | `scripts/corpus_readings.sh <out dir>` | both of the above |
| Instrumented smoke suite | build `assemblePlusDebug` and `assemblePlusDebugAndroidTest`, `adb install` both, then `adb shell am instrument -w -e class com.borderkeys.ImeSmokeTest com.borderkeys.plus.test/androidx.test.runner.AndroidJUnitRunner` | an emulator |

The tap corpora for `TapCorpusTest` come from `tools/make_tap_corpus.py`, one file per
profile, as CI writes them:

```bash
mkdir -p native-tests/build/taps
for profile in centred low thumbs right-thumb precise sloppy; do
  python3 tools/make_tap_corpus.py --profile "$profile" --taps 60000 --seed 1 \
    > "native-tests/build/taps/$profile.tsv"
done
./gradlew test -Pborderkeys.taps="$PWD/native-tests/build/taps"
```

## Signing, versions and releases

The release keystore is looked up in this order: `RELEASE_KEYSTORE_PATH` and
`RELEASE_KEYSTORE_PASSWORD` in the environment (how CI supplies it), then the Gradle properties,
then `~/.borderkeys/borderkeys-release.jks` with its password in
`~/.borderkeys/keystore_password.txt`. The alias is `borderkeys` unless `RELEASE_KEY_ALIAS` says
otherwise. With none of them, the release APK is built unsigned, so a contributor without the
key is never blocked.

A release:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`, and the same version in
   `CITATION.cff` and both `metadata/com.borderkeys*.yml`.
2. Write the version's section in `CHANGELOG.md`.
3. Push to `main` and wait for CI to pass on that commit.
4. Push a `v<version>` tag on it. The Release workflow builds both flavours signed, checks them,
   attests their provenance and publishes the GitHub Release; the F-Droid workflow then
   publishes to the custom repository.

The Release workflow refuses to move a tag whose release is already published.

## Continuous integration

| Workflow | When | What |
|---|---|---|
| `ci.yml` | push and pull request to `main` | `licensing`: `reuse lint`. `verify`: the host library, the packs and the tap corpora, then every JVM test, both flavours assembled with the build checks, the APK checks (no permission, no model in `core`, no assistant code in `core`), every tool self-test and generator check, the native tests, the reachability, context, first-place and touch-model gates on every pack, the pack loader under sanitisers and libFuzzer, and the swipe accuracy gates. `smoke`: after `verify`, the instrumented suite on two emulators (Android 11 and 15). |
| `release.yml` | a `v*` tag, or by hand | The host library, the packs, the tap corpora and every JVM test again, then both flavours signed and checked, the release notes from `CHANGELOG.md`, provenance attestation and the GitHub Release. |
| `deploy-fdroid.yml` | after a successful Release, or by hand | `scripts/sync_fdroid_metadata.sh`, then merges into the custom F-Droid repository and pushes. |
| `packs.yml` | by hand | Compiles every list in `dictionaries/extra/` with `tools/build_dict.py` and publishes the `.bkd` files on the packs release. |

## The word lists and language packs

The six bundled languages are compiled at every build from `dictionaries/<tag>.tsv`; how a list
is made, which rows it keeps and how names are flagged is in `docs/dictionaries.md`. Each list
has siblings, all optional to the compiler:

| File | What | Made by |
|---|---|---|
| `<tag>.tsv` | `word<TAB>count[<TAB>name]` | `tools/make_pack.py`, then the cleaning tools below |
| `<tag>.ngrams` | word pairs and triples | `tools/make_pack.py` |
| `<tag>.pos` | a tag per word and the tag transition matrix | `tools/build_pos.py` |
| `<tag>.known` | words known but never offered (Rare words) | `tools/make_known_words.py` |
| `<tag>.case` | how each name is written inside sentences | `tools/make_case_evidence.py` |
| `<tag>.names-ordinary`, `.names-exclude`, `.names-include` | the names guards | `tools/make_ordinary.py`; the other two by hand |
| `<tag>.misspellings`, `.nonwords`, `.words-include` | the cleaning lists | the `drop_*` tools; by hand |

The 21 downloadable languages live in `dictionaries/extra/`, are built end to end by
`tools/new_language.py` from a manifest in `tools/languages/<tag>.json` (fetch, overlay, names,
grammar, corpus, ordinary, pack, clean, compile, check), are not compiled into the app, and are
published by `packs.yml`. Fetching needs network access and several gigabytes; the Leipzig and
Hunspell downloads are cached under `~/.cache/borderkeys/`.

## Tools and scripts, by purpose

Every tool prints its usage with `--help`, and its header says what it does.

### Building packs and assets

| Tool | What it does |
|---|---|
| `build_dict.py` | Compiles a word list and an n-gram list into a `.bkd` pack; `--case` drops the name flag of words written mostly in lower case; `--selftest` round-trips a synthetic pack. |
| `make_pack.py` | Turns a real corpus or frequency list into a word list and pack, names merged under its guards. |
| `new_language.py` | Builds a downloadable language pack from its manifest, end to end. |
| `build_pos.py` | A part-of-speech tag per word and the transition matrix, from a Universal Dependencies treebank. |
| `build_emoji.py` | The emoji panel's assets, from Unicode's `emoji-test.txt`. |
| `make_compose.py` | The compose key's table, from the sources in `tools/compose/`. |
| `make_extra_keys.py` | Each language's extra keys, from `tools/extra_keys/method.xml`. |
| `gen_keys.py` | `Keys.kt` from the English catalogue. |
| `check_layouts.py` | That the layout assets, their subtypes and their labels agree. |

### Keeping the word lists clean

| Tool | What it does |
|---|---|
| `classify_wordlist.py` | Keeps a list to the rows its language's own evidence supports. |
| `drop_unreachable.py` | Removes rows that are not words of the list's language. |
| `drop_foreign.py` | Removes another language's vocabulary. |
| `drop_misspellings.py` | Removes a corpus's own misspellings. |
| `drop_mojibake.py` | Repairs or removes decoding accidents. |
| `fold_diacritic_noise.py` | Folds diacritic-dropped noise into its accented spelling. |
| `normalise_diacritics.py` | Folds Romanian spellings that differ only in how a diacritic is encoded. |
| `merge_apostrophes.py` | Writes every apostrophe as the plain one and merges the rows that then agree. |
| `make_contractions.py` | Each language's apostrophe-dropped spellings, from its own counts. |
| `make_known_words.py` | The words a pack knows but never offers, for Rare words. |
| `corpus_words.py` | The word list as the corpus generators read it (a library for the `make_*_corpus` tools). |

### Names

| Tool | What it does |
|---|---|
| `make_names.py` | Person and entity names from Wikidata (the QLever mirror). |
| `merge_names.py` | Merges a name list into a built list under `make_pack.py`'s guards. |
| `flag_names.py` | Flags the names already in a list's ordinary vocabulary. |
| `make_ordinary.py` | The corpus words a spelling dictionary lists in lower case. |
| `make_case_evidence.py` | How each name is written inside sentences, into `<tag>.case`. |

### Test corpora

| Tool | What it writes |
|---|---|
| `make_slip_corpus.py`, `make_omitted_corpus.py`, `make_extra_corpus.py`, `make_doubled_corpus.py`, `make_firstletter_corpus.py`, `make_midtypo_corpus.py`, `make_rareprefix_corpus.py` | One kind of typo each, on frequent words. |
| `make_known_corpus.py`, `make_unknown_corpus.py`, `make_unlisted_corpus.py` | Correct words to be left alone: in the pack, missing from it, and missing from every dictionary. |
| `make_real_corpus.py` | Real phone typos from the ITE Typing dataset. |
| `make_accent_corpus.py`, `make_twin_corpus.py` | Accent restoration, and spellings that differ only by diacritics. |
| `make_case_corpus.py` | Capitalisation inside sentences, from held-out text. |
| `make_context_corpus.py` | Held-out text for `suggest_eval --context`. |
| `make_tap_corpus.py` | Synthetic taps for the touch model. |
| `make_corrupt_packs.py` | The malformed packs under `native-tests/data/corpus`. |
| `estimate_typing_channel.py` | How often phone typing adds, drops or swaps a letter, for the tap decoder. |

### Swipe typing

| Tool | What it does |
|---|---|
| `gesture_replay.py`, `tcn_replay.py` | The geometric and neural decoders' accuracy on recorded swipes, against `docs/gesture-accuracy*.json`. |
| `layout_to_replay.py` | A layout asset as a replay layout. |
| `script_swipe_eval.py` | Synthesised swipes on an own-script layout, both decoders measured. |
| `swipe_model/` | Training the neural decoder: `prepare_corpus.py`, `train.py`, `export_weights.py`, the synthesiser in `synthesise.py` and `flow.py`, and the per-script queues. Its `README.md` explains the whole process, and `train_combined.log` is the training log of the weights the `plus` build ships. |

### Translations

| Tool | What it does |
|---|---|
| `extract_strings.py` | Lifts user-facing text out of Kotlin sources into the catalogue. |
| `inject_strings.py` | Gives every composable that looks up text a `strings` to look it up in. |

### Scripts

| Script | What it does |
|---|---|
| `scripts/corpus_readings.sh <out dir>` | Every corpus reading, native and through the whole Kotlin path, one line per case, for `diff -r` between two runs. |
| `scripts/sync_fdroid_metadata.sh` | The F-Droid metadata tree for both flavours, from `fastlane/` and `metadata/`. |
