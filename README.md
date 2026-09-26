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
  per-keystroke telemetry to anyone — the engine is a compiled library, not a service. Where the
  n-grams have no evidence at all, a part-of-speech tag per word and a tag transition matrix
  break the tie; [`docs/pos-tagging.md`](docs/pos-tagging.md) records exactly how much that is
  worth, because the number is smaller than it looks.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/13_completions.jpg" alt="The suggestion strip carrying a half-typed down on to downtown and downs" width="30%">
</p>

- **Several languages active at once.** Every dictionary you have switched on — up to four at a
  time — is consulted on every word; the one that actually recognises what you typed wins,
  without a manual switch. How quickly the
  keyboard commits to one language once it has seen enough — off, patient, balanced, quick or
  strict — is a setting. You can also mark one language **preferred**, and it answers first
  until the words you type say otherwise: it decides where guessing starts, never what wins, so
  a couple of words in another language still switch to it.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/4_languages.jpg" alt="The Languages screen: choosing which language the app and the keyboard's own labels are written in" width="30%">
</p>

- **Retroactive correction when the conversation's language turns out to differ.** A correction
  applied while typing can be wrong not because the guess was bad, but because the keyboard read
  the wrong language at the time — the sentence itself proves it a few words later. BorderKeys
  notices the flip and offers the affected word back (or fixes it automatically, your choice),
  landing on the field's own undo history like any other edit.

- **Autocorrect you can bound.** Off by default, and when on: the shortest word it may touch,
  how strict the engine's own ranking has to be, and how different a correction may be from what
  you typed — one letter, two only in long words, or two — are each a setting. A correct word the
  dictionaries simply do not know is left alone rather than replaced by something far away, and
  the backspace straight after a correction puts back exactly what you typed.
- **Offensive words, blocked on request.** One switch keeps a per-language list of profanity
  and slurs out of suggestions, corrections and learning. What you type yourself is never
  touched, and the lists are plain text in the repository, one per bundled language.
- **Capitals and spaces the way every keyboard does them.** Shift is spent by the next letter
  only; two quick taps or a hold lock it. Capitals are off, on when the field asks for them, or
  on everywhere — one choice, since some apps forget to ask. Two spaces make a full stop, a
  space is added after punctuation and a picked suggestion (never in an e-mail or web-address
  field), and the space you type out of habit right after can be ignored once, always, or
  kept. The capital after a full stop is decided
  from what the keyboard just wrote, so it comes even in apps whose editor answers late.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/10_typing_settings.jpg" alt="Capitals: off, when the app asks, or always" width="30%">
</p>

- **Swipe typing.** Geometric (SHARK²) and deterministic in `core` — no model, no training
  data, no accuracy number that depends on what you happened to type it on. A small loop at a
  doubled letter tells "hello" from "helo". Swiping works wherever suggestions do, and switches
  itself off in exactly one place: a password field, whose text never reaches the engine at all.
  `plus` adds a **neural decoder** — a small TCN hand-written in C++ with no ML runtime, its
  weights trained in this repository on a free corpus — and uses it by default, because on the
  same 500 recorded swipes it reads 90.6% first-try against the geometric tier's 77.0%; see
  [Flavors](#flavors). A switch turns it off, and its weights are freed the moment it goes off,
  so `plus` with the switch off costs no more than `core`.
- **An optional radial menu for swipe typing.** Pause mid-swipe, without lifting, to open a ring
  of alternatives around your finger — slide onto one to pick it, or onto the centre Cancel to
  discard the swipe, all in one continuous motion. Lifting elsewhere applies the top guess or
  cancels, whichever you've set as the default; a "keep it open" option turns the same ring into
  an unhurried, tap-only menu instead. A tap anywhere outside the ring — on the keys, in the text
  field, or elsewhere on the screen — closes it and keeps the swiped word, and can hide the
  keyboard too if you prefer. Position, size and the blur behind it are adjustable. Off by
  default — the ordinary suggestion strip is unchanged either way.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/5_radial_menu.jpg" alt="The ring of alternatives around a paused swipe, with Cancel at its centre" width="30%">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/6_radial_settings.jpg" alt="The ring's settings, the detection dials folded away" width="30%">
</p>

- **A names dictionary** built from Wikidata (CC0), so a name capitalises correctly mid-sentence
  instead of only at the start of one or after a shift — "Stevens" and "McConnell" as much as
  "Steve". Everyday words that happen to be somebody's name ("in", "will", "cloud", "mark") stay
  lower-case: a word only earns the flag when enough real people carry it for how common the
  word is, never when the language's own treebank calls it an ordinary word, and never when any
  of the shipped spelling dictionaries lists it in lower case unless the treebank knows better.
  That last rule asks every language rather than only the one being built, because words travel
  and a borrowed one rarely becomes a headword where it landed: "google" keeps its small g
  because British English has it as a verb, even though American English lists only the company.
  Which words earn the flag is judgement rather than fact, so the capital is a switch: turn
  "Capitalise names" off and a flagged word is cased like any other, while still never being
  allowed to correct an ordinary word.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/1_typing.jpg" alt="Steve and Steven offered capitalised from a lower-case stev, in the middle of a sentence" width="30%">
</p>

- **Alternate physical layouts** — AZERTY, Dvorak, QWERTZ, ClearFlow, KasRoz and Toki Pona — for
  the 26-letter alphabets every bundled dictionary already knows, plus a number row, a symbols
  page with a proper number pad — a 3×3 block at the right or the left with the symbols beside
  it, or a plain digit row — a numeric keypad in numeric fields, and an optional modifier
  row — Esc, Tab, Ctrl, Alt and the arrows, with Home, End, Page up, Page down, forward Delete
  and Insert on offer, in the order you choose, sent as the hardware keys they name, above the
  letters or below the keyboard — for terminals and editors. Ctrl and Alt apply to the next key,
  and with shift held the caret keys select.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/16_symbols_pad.jpg" alt="The symbols page: the digit block at the right, the symbols beside it, backspace and enter down the right edge" width="30%">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/14_modifier_row.jpg" alt="The modifier row below the keyboard: esc, tab, ctrl, alt and the arrows" width="30%">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/15_modifier_row_keys.jpg" alt="Layout &amp; keys: the modifier row's position and the keys on it, reorderable" width="30%">
</p>
- **A personal dictionary you can see and edit.** Every learned word is listed with how often
  you used it, and a `Forget` (remove it) and a `Block` (never suggest it again) right beside
  it — not a black box. The word pairs it learned are counted on the same screen and go with a
  word when it is forgotten. Holding a suggestion offers the same from the keyboard, and a
  "Why?": the engine's own account of the word's score, term by term, in plain words.
- **Text shortcuts.** A word that stands for a longer text — "omw" for "on my way", an address, a
  sign-off — expands when a space or a punctuation mark follows it, takes the capital you gave the
  shortcut, and comes back with the backspace straight after, like any other correction.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/12_text_shortcuts.jpg" alt="The Personal dictionary screen, with the text shortcuts card" width="30%">
</p>

- **Keys that answer back.** The pressed key shows itself enlarged above your finger while it is
  held, the held key's alternates pop up in the same place, and the keypress vibration follows
  the phone's own setting or one of three strengths — its own feedback classes, so still without
  a vibration permission. Long-press hints, the hold duration and what the enter key does are
  settings too.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/9_layout_keys.jpg" alt="Layout & keys: the number row, the accents and the key popup" width="30%">
</p>

- **Settings that stay out of the way.** Every card leads with what changes how typing feels;
  the calibration values, the workarounds for particular apps and the choices made once fold
  under an "Advanced settings" line, closed until you open it.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/2_home.jpg" alt="The settings home" width="30%">
</p>

- **Panels that search by the word under the caret.** Open the emoji panel over "cake" and the
  cakes come first, and over "pumpkin" the lantern, because each emoji is also found by the
  keywords Unicode gives it in the languages switched on; open the clipboard over "invoice" and
  the clips containing it come first, the
  header saying how many. A long press on a clip pins, edits or deletes it, and the Clipboard
  settings screen has a search box and an editor of its own.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/18_emoji_search.jpg" alt="The emoji panel opened over the word cake: the cakes come first" width="30%">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/17_clipboard_search.jpg" alt="The clipboard panel opened over the word invoice: two clips with it, first" width="30%">
</p>

- **A tour after setup.** Once the keyboard is selected, a screen lists what it can do, each
  feature with a small preview, its default and the screen it is switched on from, which a tap
  on the card opens; it can be dismissed for good and reopened from the About card on the
  settings home.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/19_feature_tour.jpg" alt="The feature tour: autocorrect, swipe typing and the strip, each with a preview, its default and where it is switched on" width="30%">
</p>

- **Private mode is automatic.** In a password field, or wherever an app asks for no personalised
  learning, there is no learning, no clipboard history, no assistant, and nothing from your
  personal dictionary in the suggestions — and a password's text never reaches the prediction
  engine at all. The strip offers a Show button there instead, so what the field holds can be
  checked and hidden again without leaving the keyboard.

<p align="center">
  <img src="fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/20_private_field.jpg" alt="A password field: the strip shows the field's text after Show, with Hide beside it" width="30%">
</p>

### Quick actions

A row of buttons for what otherwise takes several gestures — copying a word, a line or
everything, pasting, cutting, selecting a word or all of it, deleting a word, moving the cursor to
either end or one character left or right, undo and redo, a new line — plus two edits on the text
itself: **Capital** flips the first letter of the word at the cursor, **Normalise** capitalises
the start of every sentence in the field. Built in, and extendable: a **custom quick action** is
a macro of steps you define yourself, pinned onto the bar exactly like a built-in one. The bar
can sit above the suggestions, below the keys, or down either side; it comes in four sizes, with
optional labels under the buttons and an outline around each whenever the theme outlines the
keys — and the settings screen shows the real bar live while you change it.

<p align="center">
  <img src="docs/images/quick_actions.png" alt="The quick actions screen: the bar drawn live above its settings, with labels under the buttons" width="40%">
</p>

### Themes

Built-in themes across several colour families, full manual control over every colour and
corner radius, and a **custom theme library**: save what you built, import a theme someone
shared, export and rename your own — kept separately from whichever single theme is active
right now. Colours picked through the wheel are kept per field, the background can reach the
whole width or stop at the keys, leave the navigation bar's strip bare or paint it, and the key
outline follows every key-shaped control — the keys, the held-key popup, the panel chips — and
frames the whole keyboard.

| The theme screen, the keyboard drawn live | Presets, then the colours |
|---|---|
| ![](docs/images/custom_theme.png) | ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/3_theme.jpg) |

### Particle effects

Five surfaces — the keys, the suggestion ring, the suggestion strip, the language-correction
panel and the quick-action bar — each with two independent layers: a **fill** that bursts inside
the element (Fire, Glow, Waves, Rainbow, Neon pulse) and an **outline** that traces the element's
own edge and radiates outward (Comet, Pulse, Sparkle, Fire, Wind), with their own colours, speed,
density and width. Every element hands the particle system its exact geometry, so an outline
follows a key, a wedge or a chip precisely and survives any resize. Six built-in looks — Off is
one of them — and your own saved presets set all five surfaces at once; whatever you change
afterwards shows as unsaved changes against the preset you started from, and each layer's colours
and dials sit under its own Advanced fold. Off by default.

| Presets, then each surface's layers | A layer unfolded: colours and dials |
|---|---|
| ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/7_particle_effects.jpg) | ![](fastlane/com.borderkeys/metadata/android/en-US/images/phoneScreenshots/11_particle_layers.jpg) |

### The assistant, `plus` only

A **draft box** — write somewhere the app itself cannot see, then ask an on-device model to
correct, shorten, summarise, re-tone or translate it, with a version kept for every step so
nothing is lost to a bad answer. The box edits rich text: bold, italic and lists from its own
formatting bar, kept as Markdown throughout, and long drafts scroll inside it. Reachable from a
text selection in *any* app through four more entries in the system's own text-selection menu
(Correct, Shorten, Summarise, and any of your own saved instructions), each running immediately
instead of opening an idle box first.

| Draft box, with its formatting bar | Working, on-device | A version kept for every step | The models this build will run |
|---|---|---|---|
| ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/5_draft_box.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/6_translating.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/7_translated.jpg) | ![](fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/8_assistant_models.jpg) |

### Backup, restore, and control

Everything this keyboard has learned and configured — dictionaries, settings, theme, size and
position, particle effects, quick actions, text shortcuts, saved instructions — writes to and
reads from a
single file, under your control, on your schedule, with a checklist of exactly what a file
contains shown before any of it is applied. The two builds can hand their settings and
dictionaries to each other directly, without a file. Nothing syncs anywhere on its own.

## Two builds, one repository

| | `core` | `plus` |
|---|---|---|
| Deterministic n-gram engine, geometric swipe decoding | ✓ | ✓ |
| Multiple languages active at once, no manual switching | ✓ | ✓ |
| Zero permissions, no `INTERNET` in the merged manifest | ✓ | ✓ |
| Neural swipe decoder, on by default, switchable | | ✓ |
| On-device text assistant (draft box) | | ✓ |
| Correct, Shorten, Summarise and your own instructions in every app's text-selection menu | | ✓ |

Both stay free software end to end — `plus` only *adds* to `core`, it never trades privacy
for the extra features. The separation is at compile time, not behind a runtime flag: unpack
`app-core-release.apk` and the assistant's code simply is not in it.

Both are published as **separate applications** (`com.borderkeys` and `com.borderkeys.plus`) so
they can be installed side by side — choosing the assistant never costs you the settings or the
learned dictionary of the other build.

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
| `:i18n`     | Every sentence the app shows, in six languages, as JSON — see [`docs/translations.md`](docs/translations.md). |

## Flavors

| flavor | contains                                                                 |
|--------|--------------------------------------------------------------------------|
| `core` | Deterministic n-gram engine, geometric (SHARK²) swipe decoding. No model files, no neural code, no non-free assets. |
| `plus` | Adds the local text assistant, and the experimental neural swipe decoder with its weights. |

`core` is the default and is the one that stays entirely free software. The separation is at
compile time, not behind a runtime flag: unpack `app-core-release.apk` and the assistant's code
is not in it. The prediction engine is the same code in both builds; `plus` compiles one extra
gesture decoder into it — a small TCN hand-written in C++ with no ML runtime, its weights
trained by [`tools/swipe_model`](tools/swipe_model) on the MIT-licensed `futo-org/swipe.futo.org`
corpus — behind a "Neural swipe model" switch that is on by default. That makes `plus`
free software too, weights included; see [`docs/licensing.md`](docs/licensing.md) §2.1 and
§2.5 for the provenance and what the other options would have cost.

## Building

Requires JDK 21 (the Gradle daemon provisions it), the Android SDK with platform 37, and
NDK 27.1.12297006. The keyboard runs on Android 11 (API 30) and later: inline autofill
suggestions, which are how password managers reach the strip, need that level, and the floor is
recorded in `gradle/libs.versions.toml`.

```bash
./gradlew :app:assembleCoreRelease      # the free build
./gradlew :app:assemblePlusRelease      # with the optional model-backed features
./gradlew test                          # JVM tests, every module
```

Three checks run as part of `assemble` and fail the build rather than warn:

- `verifyNoInternetPermission` — reads the *merged* manifest, so a library that injects
  `INTERNET` during the merge is caught rather than inherited. On `core` it also refuses the
  assistant's four text-selection entries, so the free build never advertises a Correct,
  Shorten, Summarise or custom action it cannot run.
- `verifyNoForbiddenDependencies` — walks the release runtime classpath for telemetry, HTTP
  clients and DI containers, transitively.
- `verifyKeyboardHasNoCompose` — walks `:keyboard`'s classpath for any Compose artifact.

The bundled dictionaries are compiled from the word lists in [`dictionaries/`](dictionaries) on
every build; no binary pack is committed. The native engine also builds and tests on the host
(`native-tests/`, a CMake project) in CI, against packs produced by the same compiler.

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

The settings application measures the keyboard as it runs. The **Debug stats** line above its
"Try it here" field opens a panel with the keystroke-to-strip and native search latency, the
swipe decode and lift-to-text figures labelled with the decoder that ran, the swipe's duration,
path and samples, the pack load time, typing speed and process memory — each as last, mean and
maximum, rated against a baseline with a line on what changes it — and a Share button that hands
the panel to any app as text. The figures in [`docs/testing.md`](docs/testing.md) come from it.

<p align="center">
  <img src="fastlane/com.borderkeys.plus/metadata/android/en-US/images/phoneScreenshots/25_debug_stats.jpg" alt="Debug stats: keystroke to strip, native search, swipe decode and lift to text, each rated green" width="30%">
</p>

```bash
# Zero permissions. Not "only harmless ones" -- zero.
aapt2 dump permissions app/build/outputs/apk/core/release/app-core-release.apk

# No model files, no assistant classes in the free build.
unzip -l app/build/outputs/apk/core/release/app-core-release.apk
```

Release APKs published from CI carry a build provenance attestation:

```bash
gh attestation verify BorderKeys-v0.6.2-core.apk --repo razvan-eduard/borderkeys
```

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — what each module is and what happens between a
  finger and a word: the suggestion and learning paths, why autocorrect and the suggestion strip
  rank separately, every scoring constant with the measurement behind it, the gates that stop a
  correction, language detection and revert, both swipe tiers, and the dictionary pipeline.
- [`docs/privacy.md`](docs/privacy.md) — what the keyboard can and cannot know: the three things
  that enforce zero permissions, how a private field is detected and what stops learning from it,
  what encryption at rest does *not* buy, and a threat model with both columns filled in.
- [`docs/layouts.md`](docs/layouts.md) — the layout asset format, key codes and flags, how JSON
  becomes hit-testable pixels, and why accents follow your languages rather than your layout.
- [`docs/testing.md`](docs/testing.md) — tests, build gates, sanitisers and fuzzing, the
  measurements that are deliberately not tests, and what is not covered.
- [`docs/licensing.md`](docs/licensing.md) — every dependency and asset, with its licence and
  a compatibility verdict, plus the reproducible-build settings and the F-Droid anti-feature
  checklist.
- [`docs/dictionaries.md`](docs/dictionaries.md) — how a language pack is built from a corpus,
  how the names list is merged into it, and what the offensive-word lists behind the "Block
  offensive words" switch do and do not cover.
- [`docs/pos-tagging.md`](docs/pos-tagging.md) — what the grammar tags inside a pack are worth,
  measured.
- [`docs/translations.md`](docs/translations.md) — adding a language or a string to the
  interface.
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — DCO, no CLA.
- [`metadata/`](metadata) — F-Droid submission metadata for both packages;
  [`fastlane/`](fastlane) — the store listings and screenshots the custom repository publishes.
