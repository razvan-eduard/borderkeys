<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# How this is tested

Four kinds of check, and they answer different questions. Confusing them is how a project ends up
with a green build and a keyboard that suggests the wrong word.

| Kind | Question | Fails the build? |
|---|---|---|
| **Tests** | Is it correct? | Yes |
| **Gates** | Is the shipped artefact what we claim? | Yes |
| **Sanitisers and fuzzing** | Does it survive hostile input? | Yes |
| **Measurements** | How *good* are the answers? | Only on regression |

- [Tests](#tests)
- [Gates](#gates)
- [Sanitisers and fuzzing](#sanitisers-and-fuzzing)
- [Measurements](#measurements)
- [What is not covered](#what-is-not-covered)
- [Running it all locally](#running-it-all-locally)

---

## Tests

### JVM — `./gradlew test`

**813 test functions across 91 files.** No device, no emulator, no Robolectric.

That is possible because the logic is deliberately kept out of the Android classes. The policy
objects in `ime/` hold no `InputConnection` and make no native calls — they take strings and
return decisions — and the typing package reaches the field, the engine and the views only
through interfaces:

| Class | What its tests pin down |
|---|---|
| `AutoCorrection` | Every reason a correction is refused |
| `WordCommit` | Which claim on a committed word wins -- shortcut, apostrophe map, possessive, capital, autocorrect -- and under which switch |
| `WordStems` | The endings each language forms and the stems they leave |
| `AutoShift` | Shift state from a field's request and the text before the cursor |
| `PrivateMode` | Every input type the platform defines |
| `LanguageSwitchCorrector` | Which flags resolve to which replacements, and where the caret lands |
| `RunningText`, `Contractions`, `SentenceCase`, `TextShortcuts`, `HabitSpace` | Pure text transforms |
| `LearningBuffer` | Debounce and eviction, with a clock passed in |
| `PredictionRequestQueue`, `NewestWins` | The one-deep queue, and only the newest request's answer used |
| `KeyboardGeometry` | Hit-testing arithmetic |
| `KeyboardPreferences` | Every setting's clamp and default |
| `FieldPolicy`, `TypingFlow` | A field's five answers from its type and the two switches; the lifecycle every flow shares |
| `TapTrail`, `TapAlignment` | The taps kept in step with the word through every edit; which taps give the heatmap a sample, and where |
| `TouchLearning`, `KeyTouches` | The heatmap's stored and pending totals, their decay by half-life and their merge |
| `UserPhrase` | Pairs and triples in one list, most used first |
| `GlowEllipse`, `HeatmapGlows` | The heatmap's drawing: a covariance as an ellipse, a bucket's totals as glows |

`KeyboardGeometry` is the clearest case for why the split is worth it: a one-pixel gap between
two keys is a touch that does nothing, and **neither a device nor a screenshot will show it**.
Tested as arithmetic, it cannot hide.

### The join — `PipelineTest`, `PipelineCorpusTest`, `LanguageSwitchPipelineTest`

The section above and the one below each test half of a decision. The native suite stops at the
engine; the policy classes start after it, on values handed to them. Between the two sits the
join, and every correction bug reported from a device lived exactly there — a word the engine
offered and the guards were meant to refuse, or the reverse.

`Pipeline` closes it. It drives the shipping engine through the shipping JNI bridge and then the
shipping Kotlin -- `WordCommit` with the apostrophe maps of the languages opened, the productive
possessive, the capital a language always writes, and every guard in `AutoCorrection` --
against the packs the application ships, and it types each case through `TypingOrchestrator`,
so a case's word ends as it would on a phone. Nothing in it is a model of the pipeline; it *is*
the pipeline, with the touch surface left out.

| Payload | What it pins |
|---|---|
| `pipeline_cases.tsv` | 49 cases, `typed <TAB> committed <TAB> reason` |
| `pipeline_cases_ro.tsv` | 13 Romanian cases, against the pack most reports come from |
| `PipelineCorpusTest` | Every autocorrect corpus, with a floor per corpus -- see [the whole path](#the-whole-path--pipelinecorpustest) |
| `LanguageSwitchPipelineTest` | The backward correction, end to end over two packs |

The **reason** is asserted beside the outcome because an outcome on its own can be right by
accident: a guard can stop working while another covers for it. The reason is the rewrite that
claimed the word (Shortcut, Contraction, Possessive, Capital), one of autocorrect's situations,
or the gate that kept autocorrect from being asked (NotProse). Adding a case costs a line, not a
method, and every failure in a run is reported together rather than stopping at the first.

Every line is a **requirement** — what the keyboard must do, never what it currently does.

That is worth stating because it was briefly got wrong, and the failure is instructive. Three
Romanian lines described known defects and were written green so the suite would pass. It
inverts what a suite is for: a passing line is indistinguishable from a requirement, so CI went
green *enforcing* the bug, and no change to the keyboard was ever going to turn it red. A defect
belongs in a tracker or in a red test, never in a green one.

`LanguageSwitchPipelineTest` is the first test the language-switch revert has ever had; half of
what it needs is a native answer, so it was previously reachable only by typing two languages
into a phone. It pins the cost as well as the fact: **six English words to overturn a settled
Romanian verdict**, against the twenty corrections `LanguageSwitchCorrector` keeps. If that count
ever drifts past the window, the revert stops firing with nothing else failing.

**These skip on a machine that has not built the harness, and fail on one that has no excuse.**
Skipping is right for a checkout that simply has not run cmake; a suite that fails there teaches
people to ignore it. In CI it is the opposite, and that distinction was learned the hard way:
both prerequisites used to be built *later* in the same job, so every pipeline case skipped in
CI from the day it was written and the step reported green over three known defects.

So CI builds them first, and `Pipeline.require()` fails rather than skips when `CI` is set:

```bash
cmake --build native-tests/build --target borderkeys   # the host JNI bridge
./gradlew :keyboard:buildDictionaries                  # the packs
```

### The typing flow — `TypingScenarioTest`

`TypingRig` builds the real `TypingOrchestrator` with everything around it replaced by
something a test can hold: an in-memory field with a composing region, a selection and batch
edits (`FakeFieldEditor`); the engine on the host JNI bridge and the shipped packs, its answers
queued until the rig delivers them (`QueuedEngine`), so the order of events is the test's and
never a thread's; a recording host and ring (`FakeTypingHost`, `FakeRingUi`); an in-memory store;
and a clock the test moves. After each key the rig delivers what reaches the input method before
the next one — the field's selection reports, the engine's answers, the runnables that fell due —
and checks that the word's taps are still in step with its text.

**105 scenarios**, each a whole keystroke sequence asserting the field's text and caret and, where
it matters, what was learned: a word typed and ended by every kind of key, a correction and the
backspace that takes it back, typing after a caret move into committed text, undo and redo,
shift and caps lock, the spaces the keyboard adds and takes away, French spacing, a text
shortcut, a terminal, a password field typed verbatim, swipes — real paths decoded by the host
bridge's decoder (`SwipePath`) — with their ring lifted, tapped, timed out and cancelled, the
taps a request carries in every kind of field, and what the heatmap learns from each way a word
is kept.

### Native — `ctest --test-dir native-tests/build`

Two registered tests:

- **`engine`** (`borderkeys_tests`) — the engine, the touch model, folding, the pack format, the
  gesture decoders, and the assistant's answer cleanup. Source: `test_engine.cpp`,
  `test_fold.cpp`, `test_format.cpp`, `test_gesture.cpp`, `test_tcn.cpp`,
  `test_answer_cleanup.cpp`. The touch model's checks include a centre tap costing exactly the
  key geometry's distance, `thede` tapped towards the r becoming `there`, and a learned pattern
  making the same tap dearer to read as a neighbour.
- **`pack_corpus`** — the pack loader against a committed corpus of deliberately damaged files.

A note on running them: **build every target before `ctest`.** `cmake --build … --target
suggest_eval` leaves `borderkeys_tests` stale, and `ctest` will then report a pass against an
engine from before your change. A `kDeleteCost` sweep was committed that way, with two engine
assertions failing and nothing saying so.

A note on writing engine tests: **`setKeyGeometry` is not optional.** Without it
`KeyGeometry::isSet()` is false and the walk never leaves exact-match mode — no substitution,
deletion, transposition or insertion at all. A test that forgets it measures prefix completion
and believes it has measured the engine.

### Instrumented — `./gradlew :app:connectedCoreDebugAndroidTest`

`app/src/androidTest/java/com/borderkeys/ImeSmokeTest.kt` is the one suite that runs the keyboard
against the framework's own editor. It installs the bundled English pack, switches autocorrect
on, selects the keyboard through `ime set`, opens the settings application's "Try it here" field
and taps keys by the accessibility nodes the keyboard publishes for them, reading the field back
through its node. What it covers is exactly what the JVM cannot: a correction written on the
space bar, the backspace that puts the typed word back, typing after a caret move into committed
text, a chip picked with the caret at the start of the field followed by a new word, a slide
along the space bar with shift locked that selects what was typed, a swipe across three keys
that writes the word, a pause in a swipe that opens the ring and a lift on its top wedge that
picks the top word, Control on the modifier row followed by A selecting the field, a mistyped
word in a password field left as typed, a command typed into Termux reaching its prompt letter
by letter before the word ends and running on enter, a slide up the space bar in a field of several
lines that moves the caret a line, letters tapped on the Russian layout, chosen through the
subtype setting, reaching the field as themselves, on the Hebrew layout the first
suggestion picked from the right end of the strip, and with the Romanian pack installed beside
the English one, "in" spelled "în" under a settled Romanian verdict and put back once six
English words turn the verdict, with the language-switch correction set to apply itself — each
asserting the field's final text. Keys
are found by scanning the keyboard's own nodes for the name they carry, since a selector on the
description alone does not match a name outside the Latin script. The password field
and the field of several lines are the "Try it here" field in two of its three modes: a tap on
its label, which names the mode after a coloured bullet, moves to the next mode, and the field
carries its mode and its text in its content description so the suite can read it where the
accessibility tree would show a password masked.

One case reads the screen rather than the field: in a password field, Show on the strip puts
what was typed where the private notice was, and Hide puts the notice back. The strip draws both
on its canvas and exposes neither to the accessibility tree, so the case takes a screenshot and
measures how far across the middle of the strip the text reaches: less than half as far as the
notice after Show, and the notice's own pixels again after Hide.

The Termux case reads Termux's own screen, which Termux publishes as its terminal's description
when it starts under an accessibility connection, as it does during the suite. It needs Termux
on the emulator: CI installs Termux's own x86_64 release build, checked against the digest its
release publishes, and passes `termux=required`, which makes a missing Termux a failure.
Without that argument the case is skipped where Termux is not installed; locally, install it,
or add `-e termux required` to the command below to insist on it.

It runs in CI's `smoke` job on x86_64 emulators with a Pixel 5 profile at API 30 and API 35, the oldest and the newest
the keyboard is built for (`-Pborderkeys.extraAbis=x86_64` adds the ABI, which the shipped APKs
do not carry). Locally it runs on an emulator, never a phone,
because a debug build is what it needs, and the build is installed by hand rather than by
Gradle's connected task, which uninstalls the application and its data when the run ends:

```bash
./gradlew :app:assemblePlusDebug :app:assemblePlusDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/plus/debug/app-plus-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/plus/debug/app-plus-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r -e class com.borderkeys.ImeSmokeTest \
  com.borderkeys.plus.test/androidx.test.runner.AndroidJUnitRunner
```

The suite leaves the pack it installed and the preferences it set in place.

`DatabaseMigrationTest` checks each database migration against the schemas Room exports into
`data/schemas`, which the instrumented build carries as assets: a database built at version 7
with a learned word in it is migrated to 8, validated against the version-8 schema, and still
holds the word, beside an empty `key_touches` table for the heatmap. It runs the same way, with
`-e class com.borderkeys.DatabaseMigrationTest`.

---

## Gates

Checks that the artefact is what the project claims. Each is named explicitly in CI *as well as*
being wired into `assemble`: if someone unwires a gate while leaving the task in place, the
assemble step goes green and the named step does not.

### On the sources

- `:app:verifyNoInternetPermission`
- `:app:verifyNoForbiddenDependencies`
- `:keyboard:verifyKeyboardHasNoCompose`

### On the artefact

The Gradle tasks check the *sources* of the APK. These check the *APK* — not the same claim,
since a merged manifest and a packaged binary are separated by resource shrinking, R8 and the
packager.

- **Zero permissions.** `aapt2 dump permissions` over both release flavours; any line fails.
- **`core` contains no assistant.** Asserted against the **dex**, not trusted to a build file —
  DEX stores type descriptors as plain strings, so grep suffices and needs no extra tooling.
- **Neither flavour bundles a model.**
- **REUSE lint.** Every file has an SPDX header and a known licence.

### On the source tree

- **Headers are self-contained.** Every `.hpp` under `keyboard/src/main/cpp` and `native-tests`
  is compiled alone with `-fsyntax-only`. A header that only compiled because of what happened to
  be included before it fails here — which is exactly what happened once.
- **Dictionary compiler round trip.** `build_dict.py --selftest` proves the Python still agrees
  with `bkd_format.hpp`, which is the authority on the format.
- **Encoder parity, `test_tcn.cpp`.** `native-tests/data/tcn_golden.bin` carries `model.py`'s
  output for one fixed input, written by `export_weights.py --golden` from the checkpoint the
  shipped weights come from. The C++ encoder is run on the same input with the same `.bkw` and
  has to agree within 1e-3 on both tracks. The `.bkw` payload is read positionally, so two
  same-shaped arrays written in the wrong order load at the same byte length and pass magic,
  version and every descriptor field; the test exchanges `seReduceWeight` and `seExpandWeight`
  itself and asserts the comparison then fails, so it cannot silently stop measuring anything.

  The encoder's forward pass never calls the key-embedding MLP, so those four arrays — 12,640
  floats, about 2% of the payload — need their own reference or the parity test steps over them.
  The golden vector carries `KeyEmbedding`'s output for the key centres of `TestLayout`, and the
  test compares it against what `TcnCtcDecoder::setLayout` builds, then reverses one of the two
  weight matrices in place and requires that to fail too. Between the two halves every array in
  the file is read by something that would notice it moving.

---

## Sanitisers and fuzzing

Both point at the **pack loader**, because that is the one place this app parses a file it did
not write, and a language pack can come from outside.

### ASan + UBSan

`pack_corpus_test` is rebuilt with `-fsanitize=address,undefined` and run over the damaged-pack
corpus — truncated headers, wrong magic, sizes that lie, section offsets past the end of the file,
a triple count the sections cannot hold, an unterminated language tag. Eighteen named cases.

The corpus repairs each file's **checksum after mutating it**, so the mutation actually reaches
the section-bounds and traversal checks rather than being absorbed by the CRC. A crash here is a
build failure, not a report.

### libFuzzer

The same loader, seeded from the committed corpus, with a fixed 90-second budget so the job stays
bounded. `pack_corpus_test` covers that ground deterministically on every run; this is what finds
the inputs nobody thought to write down.

Two details that matter:

- The committed corpus is **copied out first**. libFuzzer writes the inputs it keeps back into
  the directory it is given, and `data/corpus` is a fixture with eighteen named cases in it, not
  a scratch pad.
- **A valid pack is seeded alongside the broken ones.** It is the only input from which the
  fuzzer can reach the code *behind* the header checks.

A crash fails the build, and the reproducer is uploaded as an artefact (30 days) so it can become
a corpus entry rather than having to be re-derived.

---

## Measurements

These say how **good** the answers are, which is a number that moves rather than a line that
passes. `borderkeys_tests` says whether the engine is correct; these say whether it is any use.

### Swipe accuracy — the one that gates

```
python3 tools/gesture_replay.py --binary native-tests/build/gesture_replay \
    --pack "$PWD/keyboard/build/generated/dictionaries/dict/en_US.bkd" \
    --corpus "$PWD/native-tests/data/gestures_futo.csv" --check-regression
python3 tools/tcn_replay.py --binary native-tests/build/tcn_replay \
    --pack "$PWD/keyboard/build/generated/dictionaries/dict/en_US.bkd" \
    --weights "$PWD/keyboard/src/plus/assets/model.bkw" \
    --corpus "$PWD/native-tests/data/gestures_futo.csv" --check-regression
```

Both tiers, against 500 recorded traces sampled with a fixed seed from FUTO's held-out split
(`tools/swipe_model/futo_to_corpus.py`) and the **shipped English pack**. Compared against
`docs/gesture-accuracy.json` and `docs/gesture-accuracy-tcn.json`, so a decoder regression fails
in CI rather than being noticed on a device weeks later.

| tier | top-1 | top-3 |
|---|---|---|
| A — `Shark2Decoder`, every build | **77.2%** | 90.2% |
| B — `TcnDecoder`, `plus`, on by default | **90.4%** | 94.6% |

Tier A reads 77.2% rather than the 60.4% it held while only the five geometry constants had been
fitted. The 159 words it missed then were never scored at all: widening the heap from 16 to 256
moved none of them, so the loss was in the descent rather than in the ranking. A letter was
reachable only when its key was the single nearest one to some resampled sample, so a key clipped
at a corner pruned the word outright. `kTouchRadius` accepts any key the path passes within, and
the scorer decides — worth 16.6 points of top-1. Weighting the language model against the two
geometry channels was swept at the same time and peaks cleanly at 1.0, which is what it already
was.

The corpus is filtered twice, and both filters are about measuring the decoder rather than
something else. Words the pack cannot produce are dropped, so the ceiling is 100% and a miss is
a decoding failure rather than a vocabulary gap. Traces shorter than a quarter of a key width
are dropped too: a touch that never leaves the touch slop is a tap on this keyboard and is typed
as one, so it never reaches the gesture decoder. In the held-out split those are all single
letters, and keeping them measured a case that cannot happen — tier A scored 0 of 7 on them
because a nearest-template search has no trajectory to match.

What this replaced is worth stating, because it looked like a passing gate for months: 30
gestures against the **59-word test pack**, scoring 96.67%. A tiny lexicon makes almost any
decode correct, and one gesture was 3.3 points. Neither the corpus nor the vocabulary resembled
what the keyboard does, and tier B had no gate at all — `tcn_replay.py` compared against a
baseline file that had never been created.

### Tier A on other keyboards — not gated

`kTouchRadius` is fitted on English QWERTY, and it decides what the scorer is allowed to see, so
it is the constant that would hurt most if it were overfitted. `futo_layout_corpus.py` builds a
replay layout and a matching corpus for each of FUTO's other keyboards from the same `swipe-5`
collection; on `qwerty` it reproduces the hand-written `qwerty_1080.layout` at 108x160 px, which
is what says the coordinate spaces agree. Radius 0.0 is the nearest-key-only rule that preceded
it.

| radius | azerty | qwertz | dvorak | german | spanish | qwerty |
|---|---|---|---|---|---|---|
| 0.0 | 31.6% | 39.8% | 65.8% | 48.4% | 56.6% | 49.4% |
| 0.9 | 36.6% | 48.6% | **68.2%** | 66.0% | 75.0% | 64.4% |
| 1.1 | 36.2% | **50.4%** | 66.2% | 71.4% | 78.2% | **67.0%** |
| 1.4 | 35.8% | 49.2% | 64.0% | **72.8%** | **79.2%** | 66.4% |

English on azerty, qwertz, dvorak and qwerty; German and Spanish on their own packs and their own
layouts. Every column improves, by between 2.4 and 24.4 points, so the rule is not a QWERTY
artefact. The plateau is broad enough that 1.1 is within about two points of each keyboard's own
best, except dvorak, which peaks at 0.9 and is nearly flat.

German and Spanish keep climbing past 1.4, and German's layout is the one with eleven columns and
therefore 97.9 px keys against everyone else's 108. A radius expressed in key widths shrinking as
keys narrow is a plausible reason, but these corpora differ in language and word statistics too,
and this data cannot separate the two.

The levels are not comparable to the gated table above — the same decoder reads 67.0% here and
77.2% there. Both are QWERTY and both drop taps and out-of-pack words; `swipe-5` is simply a
harder collection than the held-out test split. Only the movement within a column means anything.

### Both tiers on other keyboards, with the lexicon — not gated

The table above measures tier A alone, and the layout-generalisation table further down measures
the neural encoder alone, without a lexicon or a beam. This one measures what a person gets: both
shipped decoders, the shipped pack for the language actually being swiped, 500 gestures a layout
from FUTO's `swipe-5` collection, built with `futo_layout_corpus.py` as above. The language is
the collection's own: its AZERTY rows are French and its QWERTZ rows are English and German
mixed, so each is measured against the pack that holds its words. A corpus filtered by the wrong
pack keeps only the rows that happen to be words in both languages and reads 36%; that figure
is a mistake, not a result. The layouts added since -- Colemak, Colemak-DH, Workman, Bépo, the
two Turkish arrangements, the national variants, and the ten in other scripts -- have no
recorded gestures in the collection, so they carry no figure here. Both tiers run on every
layout: the geometric tier needs only the layout and the pack, and the neural tier takes the
layout's key centres (`TcnCtcDecoder::setLayout`) and is picked for any layout once its weights
are loaded. What exists is a measurement only for the Latin layouts the collection covers; on
the other scripts the neural tier runs with weights trained on Latin traces and its accuracy
there is unmeasured.

```
tools/swipe_model/.venv/bin/python3 tools/swipe_model/futo_layout_corpus.py azerty \
    --dataset azerty.jsonl --out-layout azerty.layout --out-corpus azerty.csv \
    --dictionary dictionaries/fr_FR.tsv
python3 tools/tcn_replay.py --binary native-tests/build/tcn_replay --layout azerty.layout \
    --corpus azerty.csv --pack keyboard/build/generated/dictionaries/dict/fr_FR.bkd \
    --weights keyboard/src/plus/assets/model.bkw
```

| layout | words | tier A top-1 / top-3 | tier B top-1 / top-3 |
|---|---|---|---|
| qwerty | English | 77.2% / 90.2% | 90.4% / 94.6% |
| dvorak | English | 66.2% / 78.8% | 81.4% / 91.2% |
| qwertz | English | 50.2% / 63.4% | 63.0% / 72.2% |
| qwertz | German | 70.2% / 83.6% | 79.8% / 88.0% |
| azerty | French | 77.4% / 85.6% | 80.8% / 88.6% |
| german | German | 71.6% / 80.4% | 81.2% / 88.2% |
| spanish | Spanish | 78.2% / 84.8% | 77.0% / 89.4% |

The neural tier was trained on English QWERTY alone and reads the key positions at inference,
so every other row is zero-shot for it. It still leads tier A by 10 to 15 points on Dvorak,
QWERTZ and the German keyboard, by three on AZERTY, and ties it on the Spanish keyboard, whose
rows are the ones with the most out-of-pack words dropped and the most taps. English on QWERTZ
is the weakest pair for both tiers, and the least clean: the collection's QWERTZ rows are
English and German mixed, and filtering them by the English pack keeps the English rows plus
every German word that is an English word too, swiped by someone writing German. Not gated,
because the corpora are built from a 312 MB download; the qwerty row is the gate above.

### Swipe latency and memory on a device — not gated

Two numbers per swipe, printed by debuggable builds as `swipe: decode … ms, lift to text … ms`:
the time inside the native decode, and the time from the finger lifting to the word standing in
the field, which adds the worker hand-off, the main-thread commit and whatever the device was
doing meanwhile. Thirty straight-line swipes of two-letter words on the emulator's QWERTY,
`adb shell input swipe` at 400 ms each, English pack, on an API 35 arm64 image running a debug
`plus` build on a host at load 10–20. Tier B is the neural decoder, tier A the geometric one.

| | swipes | decode median | decode p90 | decode max | lift to text median | p90 | max |
|---|---|---|---|---|---|---|---|
| tier B | 30 | 1.5 ms | 6.7 ms | 18.3 ms | 60 ms | 133 ms | 150 ms |
| tier A | 11 | 2.8 ms | 18.7 ms | 44.3 ms | 59 ms | 219 ms | 2,126 ms |

The emulator rows are of limited worth. The tier letter on that log line was the switch's
setting at the time, not what decoded, and the tier B row's decode times are what the geometric
decoder costs, so whether the neural weights had loaded on the emulator for that run is not
established; the phone rows below are the measurement. Tier A's count is lower because the
emulator, at that load, delivered most of the injected swipes as five samples or fewer, which
the keyboard rightly reads as taps; the eleven that arrived as gestures are pooled from three
runs. Its 2.1 s maximum was the first decode after
the process had been restarted, behind the packs still loading, and is the cost of a cold start
rather than of a decode. The lift-to-text figure is dominated by the main thread, and what it
measures is that nothing on the commit path waits on the worker: the search and the decode
write the worker's own buffers, and the lock around the shared result is held only for the copy.

The same figures from a phone, read off the debug stats panel on a HONOR DNP-NX9 running
Android 16 and the release `plus` build with one language, geometric decoder, a short session
(shared 2026-09-25):

| figure | mean |
|---|---|
| keystroke to strip | 34.5 ms |
| native search | 0.4 ms |
| swipe decode, tier A | 1.1 ms |
| lift to text, tier A | 17 ms |
| touch samples per swipe | 102 for a 636 ms swipe |
| packs loaded | 79 ms |
| memory in use | 156 MB, of which 20 MB is the process's own heap |

Against the emulator's rows above, the phone is about four times faster from lift to text and
delivers twenty times the touch samples; the emulator numbers are ceilings, not the product.

The same figures can be read on a phone without a cable: the settings application's "Debug
stats" line, just above its "Try it here" field, opens a panel with the keystroke-to-strip and
native search latencies, the swipe decode and lift-to-text figures labelled with the decoder in
use, the swipe path, duration and sample figures, the pack load time, the typing speed and the
process memory, each as last, mean and maximum with a count, re-read twice a second, with a
Reset for the next run. A figure with a baseline carries a green, amber or red dot judged on
its mean and a line saying what it is, whether any setting changes it and where the baseline
lies; a figure that is only a fact about the typing carries neither. Share hands the whole
panel to the system share sheet as plain text, headed by the build, the device and the moment. The keyboard
records them in every build; the panel is where they are shown.

The same phone, neural decoder, before the encoder and search changes below: decode 130.9 ms
mean over five swipes, lift to text 139.8 ms, which is what led to those changes.

### Where the neural decode's time goes

`tcn_replay` prints, per gesture, the milliseconds spent in the encoder and in the word search
over the tries, on this machine's release build (`cmake -S native-tests -B native-tests/build-release
-DCMAKE_BUILD_TYPE=Release`), over the 30 gestures of `native-tests/data/gestures.csv`:

| | encoder | search |
|---|---|---|
| before | 29.4 ms | 4.7 ms |
| encoder loops accumulated a row at a time, so the compiler vectorises them | 3.3 ms | 4.7 ms |
| the prune scores each hypothesis once | 3.3 ms | 4.0 ms |
| beam merges found through a hash table, key symbols resolved once per decode | 3.3 ms | 1.6 ms |

Every step keeps the arithmetic and its order, so the ranks over those 30 gestures are identical
at each row and the tier B gate above reads the same 90.4% / 94.6% throughout. On the same
phone as above, the neural decode went from 130.9 ms to 3.2 ms mean (6.0 ms max) over eleven
swipes, and lift to text from 139.8 ms to 11.9 ms; on the emulator, with the tier now reported
by the engine rather than read off the switch, thirty swipes decoded in 0.2 to 0.6 ms with one
at 5.6 ms.

The memory figure is mostly file-backed pages -- the packs and the libraries, mapped and
shareable -- and the panel's baseline (fine to 200 MB, worth a look past 300 MB) is set for a
phone with one or two languages on.

Memory, from `/proc/<pid>/status` on a phone running the release `plus` build after a session of
typing and swiping: 169 MB resident, of which 42 MB is the process's own (heap and stacks) and
125 MB is file-backed — the packs and the libraries, mapped and shareable, paged in on demand.
Peak 206 MB. On the emulator `dumpsys meminfo` reads 96–100 MB PSS with the neural decoder never
used and 174 MB once it has decoded, which is the decoder's weights and workspace and the reason
a decoder that has been switched off has no claim on the memory.

### Layout generalisation — not gated, and not the product number

```
tools/swipe_model/.venv/bin/python3 tools/swipe_model/eval_layouts.py \
    --checkpoint checkpoint_combined.pt --limit 2000
```

Whether the encoder holds up on layouts it never trained on. `swipe-5` is FUTO's own multi-layout
collection; training uses `qwerty` alone, so every other row is zero-shot.

| layout | top-1 | |
|---|---|---|
| qwerty | 36.25% | in-domain |
| azerty | 32.30% | zero-shot |
| qwertz | 37.30% | zero-shot |
| dvorak | 40.10% | zero-shot |
| clearflow | 49.00% | zero-shot |
| kasroz | 55.15% | zero-shot |
| toki_pona | 61.04% | zero-shot |

Two things this is not. It is not the shipped decoder: it greedy-decodes the encoder with no
lexicon and no beam, which is why qwerty reads 36% here and 90.6% in the table above — the number
is a floor on the encoder, not a product figure. And the rows are not comparable to each other,
because each layout's slice carries its own vocabulary; `toki_pona` scores highest on about a
hundred words. The comparison that holds is zero-shot against in-domain, and no layout falls
below it.

Requires torch and the FUTO dataset, which is why it is a manual run rather than a gate.

### Suggestion quality — a measurement in one mode, a gate in two others

```
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_en.tsv en-US
```

Prints rank-1 accuracy, top-3 and mean rank over a corpus of `typed<TAB>expected` cases. In this
mode it **does not assert and is not a test**, deliberately — same contract as `gesture_replay`: a
corpus in, a measurement out, no verdict. Its `--reachable` and `--context` modes are different:
both take floors and exit non-zero below them, and CI runs them on every bundled pack
(`.github/workflows/ci.yml`, the swipe-reachability and context steps).

A case whose right answer is *"leave the word alone"* is written with the typed word as its own
expectation (`snobul` → `snobul`), because "offers nothing better than what I wrote" is a result
worth measuring, and it is the result the guards in `AutoCorrection` exist to produce.

Current baseline: **75.0% first place, 84.4% top three, mean rank 1.29**, 32 cases.

With nothing typed, the pack's successor index is walked before the frequent shortlist, so a
strong successor that is itself a rare word reaches the strip: after `ice`, `cream` and
`hockey`; after `united`, `states`, `kingdom` and `nations`; after `human`, `rights` and
`beings`. `PipelineTest` pins four such pairs. What a prose corpus never wrote often enough
stays out -- `happy birthday` is not among the pairs the packs hold.

### The touch model — `touch_eval`, not gated

```
python3 tools/make_tap_corpus.py --profile thumbs --taps 60000 --seed 1 > thumbs.tsv
native-tests/build/touch_eval <dict dir> en-US native-tests/data/qwerty_1080.layout thumbs.tsv \
    --train-taps 20000 --test-from-taps 40000 --test-words 4000
```

`make_tap_corpus.py` types words drawn by frequency from `dictionaries/en_US.tsv` on a 1080-pixel
QWERTY, one tap per letter: the key's centre moved by a profile's offset for that key, plus
Gaussian noise, the letter typed being the key the tap falls on. Six profiles, in key units:
centred (spread 0.22), low (0.18 below the centre), two thumbs (towards the middle and low), one
right thumb (falling short towards the thumb, wider with the reach), precise (0.16) and sloppy
(0.28). They slip on 0.6% to 13.7% of letters.

`touch_eval` learns the patterns the way the keyboard does — a tap on the key meant or a ring
neighbour of it is a sample, dropped beyond one key unit, weighed down by the half-life — then
types the test words that hold a slip into a non-word of three letters or more, and reports what
autocorrect commits and where the strip ranks the word meant. Autocorrect's right word, after
20,000 taps, the weight at 1.0 and the minimum at 30:

| Profile | Geometry only | Default pattern | Learned |
|---|---|---|---|
| centred | 83.1% | 91.2% | 91.4% |
| low | 76.6% | 90.4% | 90.3% |
| two thumbs | 77.4% | 89.3% | 89.9% |
| right thumb | 73.4% | 89.3% | 90.4% |
| precise | 85.4% | 92.7% | 92.7% |
| sloppy | 70.8% | 88.7% | 88.9% |

The learned patterns' mean gain over the default pattern, in points, by taps learned — the
reason a key needs 30 taps before its own pattern counts:

| Minimum | 250 | 500 | 1,000 | 2,000 | 8,000 | 32,000 |
|---|---|---|---|---|---|---|
| 10 | −1.22 | −0.15 | +0.25 | +0.23 | +0.32 | +0.32 |
| 20 | −0.13 | +0.08 | +0.17 | +0.20 | +0.28 | +0.32 |
| 30 | 0.00 | 0.00 | +0.12 | +0.15 | +0.28 | +0.32 |

The weight is best or within 0.1 of the best at 1.0 on every profile, and 2.0 is worse on five of
six. The half-life barely matters: after a change of grip (two thumbs, then one right thumb),
half-lives of 7 to 180 days stay within 0.6 points of each other, and 30 days keeps the rarest
letters above the minimum for someone typing 500 taps a day. The taps are Gaussian, which suits
the default pattern; real taps may reward learning more.

### Readings — the check for a change meant to change nothing

```
scripts/corpus_readings.sh /tmp/readings-before      # on the commit before
scripts/corpus_readings.sh /tmp/readings-after       # on the change
diff -r -x SUMMARY.txt /tmp/readings-before /tmp/readings-after
```

Writes every corpus's reading, case by case: `native/`, `suggest_eval`'s output for every
corpus with a shipped pack, and `kotlin/`, one line per case from `PipelineCorpusTest` and
`PipelineTest` (`-Pborderkeys.readings`): typed, committed, reason, and the engine's ranking
with its scores. A change that restructures without meaning to change behaviour must read
identical, to the digit; every step of the typing orchestrator did. `--centre-taps` writes the
native readings with every letter tapped at its key's centre, which must read as they do with no
taps at all, since the default touch pattern prices a centre tap as the key geometry does.

### Correct words the pack has never heard of

```
native-tests/build/suggest_eval <dict dir> \
    --autocorrect native-tests/data/autocorrect_unknown_en.tsv en-US
```

Being correct is not the same as being in the dictionary. 6,891 ordinary English words of
length 5–9 are absent from the bundled pack, and a word the pack lacks gets no known-word
guard — the pack's own words compete for it unopposed. Generated by
`tools/make_unknown_corpus.py`.

Current baseline: **82.5% left alone**, 200 cases, up from 66.0%.

The number that moved it was `kDeleteCost`, raised from 0.85 to 1.6 so that it is no longer the
mirror of `kInsertCost`. Supplying a letter someone did not type is the ordinary lossiness of
typing; discarding a letter they *did* type throws away the only direct evidence of intent
there is. 79% of these failures committed something **shorter** than what was typed —
`bisection` as `section`, `crewel` as `crew`, `garble` as `able`.

Two things that sweep is worth reading for. The mid-word and typo corpora **never move at
all** across the whole range, because they are insertions and transpositions and this prices
neither — a prediction the reasoning made before the measurement confirmed it. And the corpus
this replaced took the *first* 200 words of the right length, which is alphabetical order, so
every one began with "a"; the headline barely changed (31.5% to 34.0% overwritten) but the
diagnosis was badly distorted, since a leading "a" makes a shorter word unusually easy to
reach.

### The whole path — `PipelineCorpusTest`

```bash
./gradlew :keyboard:testPlusDebugUnitTest --tests 'com.borderkeys.predict.PipelineCorpusTest' -i
```

Every corpus above and below, through the engine, the bridge and `WordCommit`, with a floor
per corpus that CI enforces. The engine's own numbers and the path's differ wherever a guard
refuses what the engine offered, or a rewrite claims the word first; where they differ, this
is the number a user gets.

| corpus | rows | engine alone | whole path |
|---|---|---|---|
| `autocorrect_typo_en` | 200 | 99.5% | 199 |
| `autocorrect_midword_en` | 200 | 98.0% | 192 |
| `autocorrect_midtypo_en` | 200 | 22.5% | 44 |
| `autocorrect_unknown_en` | 200 | 92.5% left alone | 190 |
| `autocorrect_doubled_en` | 200 | 95.5% | 191 |
| `autocorrect_firstletter_en` | 200 | 85.0% | 167 |
| `autocorrect_marks_en` | 268 | — | 268 |
| `autocorrect_slip_en` | 200 | 89.5% | 179 |
| `autocorrect_omitted_en` | 200 | 95.5% | 186 |
| `autocorrect_extra_en` | 200 | 96.5% | 192 |
| `autocorrect_rareprefix_en` | 193 | 73.1% | 131 |
| `autocorrect_known_en` | 200 | 98.5% left alone | 193 |
| `autocorrect_accents_ro` | 248 | 95.6% | 237 |
| `autocorrect_twins_ro` | 200 | 93.0% | 186 |
| `autocorrect_plain_ro` | 200 | 100.0% | 200 |
| `autocorrect_twins_fr` | 200 | 98.5% | 196 |
| `autocorrect_plain_fr` | 200 | 100.0% | 200 |
| `autocorrect_twins_es` | 190 | 100.0% | 188 |
| `autocorrect_plain_es` | 200 | 100.0% | 200 |
| `autocorrect_twins_it` | 57 | 100.0% | 57 |
| `autocorrect_plain_it` | 200 | 100.0% | 200 |

The twin corpora hold the spellings `tools/classify_wordlist.py` dropped as twins
(`docs/dictionaries.md`, "Which rows a list keeps"), typed without their diacritics, and the
plain corpora words it kept beside an accented ordinary word, each to be left alone; both are
written by `tools/make_twin_corpus.py` from the classifier's review files. Before the lists were
classified the twins restored 0.0%, 1.0%, 5.8% and 1.8% in the engine alone, each twin a word
the dictionary knew, and the plain corpora read as they do now. A twin that commits another
accented spelling of its own folded key -- `instanța` for `instanță`, `règle` for `réglé` --
counts as a miss.

The unknown corpus reads 184 without the inflection guard (`AutoCorrection.Situation.Inflection`,
`WordStems`); the guard moves no other corpus. The gap on the doubled-letter and first-letter
corpora is the proper-noun rule: a slip inside a name -- `ameriican`, `cecember` -- is offered
the name and refused as `NameMismatch`, since a name corrects only its own letters. Of the twelve correct words still overwritten,
none is a regular inflection of a stem above `kStemFrequencyFloor`: `pouter` and `headiness`
have stems the pack holds a dozen times, and the rest are not inflections at all. A typed
apostrophe carried on to the contraction -- `that'` on its way to `that's` -- is left alone as
`AutoCorrection.Situation.TrailingMark`, so every marks row answers as the corpus expects.

The downloadable packs are measured the same way, by hand, against the pack the pipeline
compiles into `build/languages/<tag>/packs/`: every list row must come back out of its pack,
with the count of those that do not recorded in the manifest as the budget a later run is
gated against, and the four diacritic-heavy Latin languages carry an accent-restoration corpus
of their own in `native-tests/data`, generated by `make_accent_corpus.py` from their lists.

| pack | rows | unreachable | accents restored |
|---|---|---|---|
| `pl-PL` | 118,800 | 248 (0.21%) | 83.3% of 300 |
| `cs-CZ` | 118,443 | 266 (0.22%) | 92.3% of 300 |
| `hu-HU` | 118,453 | 253 (0.21%) | 94.7% of 300 |
| `tr-TR` | 118,502 | 283 (0.24%) | 94.7% of 300 |
| the other seventeen | 112,789–120,592 | 50–348 (0.04%–0.29%) | — |

```bash
python3 tools/make_accent_corpus.py dictionaries/extra/pl_PL.tsv > native-tests/data/autocorrect_accents_pl.tsv
native-tests/build/suggest_eval build/languages/pl_PL/packs --autocorrect native-tests/data/autocorrect_accents_pl.tsv pl-PL
```

### One letter doubled, and the first letter one key over

```
native-tests/build/suggest_eval <dict dir> --autocorrect native-tests/data/autocorrect_doubled_en.tsv en-US
native-tests/build/suggest_eval <dict dir> --autocorrect native-tests/data/autocorrect_firstletter_en.tsv en-US
```

Two slips a keyboard sees constantly and no other corpus isolates: an inner letter repeated
(`takking`), and the first letter hit one key over (`raking` for `taking`). Generated by
`tools/make_doubled_corpus.py` and `tools/make_firstletter_corpus.py` from the 4,000 commonest
words, 200 cases each, seed in the file.

Current baselines: **98.0%** corrected for the doubled letter, **91.5%** for the first letter.
Neither needs a rule of its own: a deletion and a first-position substitution are already
priced by the walk, and the first-letter cases that fail are the ones where the slip lands on a
different real word.

### Mistyped and unfinished at once

```
native-tests/build/suggest_eval <dict dir> \
    --autocorrect native-tests/data/autocorrect_midtypo_en.tsv en-US
```

Two adjacent middle letters swapped *and* the last character dropped, so the intended word is
one edit plus one completed character away — `beleiv` for `believe`, `aavilabl` for `available`.
Generated by `tools/make_midtypo_corpus.py`.

Every other corpus tests one axis at a time: the mid-word set truncates clean words, the typo set
transposes complete ones. Nothing combined them, and the engine could not answer the combination
at all — `allowCompletion` refused to walk on from an endpoint reached by an edit, so `beleiv`
committed `belief` and `becuas` reached nothing.

**Measured through `Pipeline`, not `suggest_eval`.** `suggest_eval --autocorrect` walks
autocorrect's list under the edit ceiling, the name rule and the minimum length, so it reads
close to the whole path here (45 against 44 of 200; before it modelled the ceiling it reported
71.5% where a user got 22.0%). It still leaves out the inflection guard and the contraction and
possessive rewrites, so where the two differ, the harness that runs the real Kotlin is the one
to believe.

| | corrected | wrong word | left alone | of which ≥ 8 letters |
|---|---|---|---|---|
| before | 6 | 59 | 135 | 1 / 50 |
| after | **44** | **42** | 114 | **39 / 50** |

The gain sits at eight letters and above because that is where
`KeyboardPreferences.CORRECTION_DISTANCE_NORMAL` allows two edits, and this shape needs two. A
shorter word mistyped and unfinished is still refused by the ceiling, which `beleiv` in
`pipeline_cases.tsv` pins.

### One slip at a time, and the words to leave alone

```
native-tests/build/suggest_eval <dict dir> --autocorrect native-tests/data/autocorrect_slip_en.tsv en-US
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_slip_en.tsv en-US
```

Five corpora that each isolate one shape of correction, drawn from the 4,000 commonest words
with the seed in each file, 200 rows each (193 for the rare prefix, one row per typed form),
generated by `tools/make_slip_corpus.py`,
`make_omitted_corpus.py`, `make_extra_corpus.py`, `make_rareprefix_corpus.py` and
`make_known_corpus.py` on `tools/corpus_words.py`:

- **slip**: one letter hit one QWERTY neighbour over, at any position (`abyway` → `anyway`);
  `suggest_slip_en.tsv` holds the same rows for the bare strip-rank mode;
- **omitted**: one inner letter left out (`abve` → `above`);
- **extra**: one stray letter typed in, neither a neighbour nor a repeat of the letters beside
  it (`aballot` → `ballot`);
- **rare prefix**: a slip or omission on a frequent word that is also the start of a word ranked
  past 20,000 (`acros` → `across`, where `acrosome` begins the same way);
- **known**: 50 correctly spelled pack words from each of four frequency bands, to be left alone.

Readings before any pricing change, after the correction heap took its own key (a letter past
the last one typed priced as a letter left out, `kRunOnCost`), and after autocorrect began to
walk the heap's whole list for the first admissible entry; engine alone (`suggest_eval`, which
before these changes did not model the edit ceiling) and through the whole path
(`PipelineCorpusTest`):

| corpus | rows | before | heap key | list walk | `kEditPenalty` 25 |
|---|---|---|---|---|---|
| `autocorrect_slip_en` | 200 | 89.5% / 175 | 90.5% / 176 | 89.0% / 178 | 89.5% / **179** |
| `suggest_slip_en` (strip) | 200 | 72.0% first, 90.5% top three | same | same | 76.0% first, 93.0% top three |
| `autocorrect_omitted_en` | 200 | 96.0% / 184 | 94.5% / 184 | 94.5% / 184 | 95.5% / **186** |
| `autocorrect_extra_en` | 200 | 96.0% / 189 | 96.5% / 192 | 96.5% / 192 | same, **192** |
| `autocorrect_rareprefix_en` | 193 | 65.8% / 116 | 73.6% / 127 | 73.1% / 131 | same, **131** |
| `autocorrect_known_en` | 200 | 98.5% left alone / 193 | same | same | same, **193** |

Each cell is the engine-alone share, then the whole-path count; the bold counts are the floors.
The list walk also moved the unknown corpus 191 → 190: a rare word one deletion from a frequent
one is corrected once the name that led its list is passed over. `kEditPenalty` was swept at 40,
25, 15, 10 and 8 (`cmake -DBORDERKEYS_EDIT_PENALTY=… -DBORDERKEYS_CORRECTION_SURCHARGE=…` on
`native-tests`, the surcharge raised to keep the safety margin): the strip's `suggest_en` reads
71.9% at every value and the native tests pass at every value; the slip's word is first on the
strip 72.0, 76.0, 79.0, 84.0 and 84.0% of the time; through the whole path 25 gains the two rows
above and loses none, while 15 loses one unknown and two rare-prefix rows for four omitted, one
slip and one first-letter row.

`kSlipScale`, the factor on a neighbouring key's distance in the walk, was swept at 0.6, 0.7 and
0.8 with a cap of one edit up to four typed letters and two from five (`BORDERKEYS_SLIP_SCALE`),
and the reference's whole edit model was measured in the same units (slip 0.425, swap 0.51,
delete 0.85, budgets 0.51 / 0.85 / 1.275 / 1.7 by typed length, depth caps 0.85 / 1.275:
`BORDERKEYS_TRANSPOSE_COST`, `_DELETE_COST`, `_CEILING_3/4/5/8`, `_DEPTH_CAP_4/7`). Engine alone,
slip / first letter / omitted / mid-word / rare prefix / strip first place: 1.0 reads 179 / 170 /
191 / 196 / 141 / 71.9%; 0.8 reads 180 / 172 / 184 / 196 / 133 / 71.9%; 0.7 reads 180 / 172 /
175 / 196 / 124 / 71.9%; 0.6 reads 179 / 172 / 174 / 196 / 123 / 68.8%; the reference's model
147 / 149 / 162 / 177 / 110 / 65.6%, its budgets alone 140 / 149 / 174 / 194 / 123 / 65.6%, its
costs with our budgets 176 / 170 / 162 / 177 / 110 / 65.6%. The slip corpus and the first-letter
corpus gain at most two rows at any value below 1.0; the omitted and rare-prefix corpora lose
five to twenty-seven. The cap on its own moved one unknown row and nothing else, and is not kept.

The slip corpus loses most of its rows to the edit ceiling or the name rule, not to ranking,
and the rare-prefix corpus is where completions of a rare word outrank the frequent correction
(`cente` → `center`, `arge` → `large`). Of the known corpus's 7 misses, 5 are names the list
holds in lower case and the keyboard recases (`broadway` → `Broadway`).
`scripts/corpus_readings.sh` lists every corpus and summarises them in its `SUMMARY.txt`.

### Accent restoration — the one that was missing

```
native-tests/build/suggest_eval <dict dir> \
    --autocorrect native-tests/data/autocorrect_accents_ro.tsv ro-RO
```

Every corpus above is English, where the question does not arise. So the one behaviour Romanian
users depend on — typing without diacritics and having them put back — was the single feature
nothing measured, while being the source of nearly every report from a device.

Generated by `tools/make_accent_corpus.py`, **stratified across six frequency bands**, and the
stratification is the measurement rather than a detail: restoration never failed uniformly.
`totuși` (9,779) always worked and `cană` (170) never did, so a corpus drawn from the top of the
word list would have reported near-perfect and hidden the defect completely.

Current baseline: **95.2% restored**, 248 cases. It was **3.3%** before any of this: the
respelling tier in the engine took it to 64.0%, normalising the dictionary's diacritic
encodings to 86.3%, repairing its mojibake to 89.3%, and pack format 4 — a folded key carrying
every spelling rather than only the commonest — to 95.2%.

That second half was the larger surprise. `ro_RO.tsv` spelled **8,822 words two or more ways** —
cedilla `ş`/`ţ` against comma-below `ș`/`ț`, and `ã` (a Portuguese letter) standing in for `ă` —
so a word could be ranked on a fraction of its real count, and the spelling that survived
`build_dict.py`'s one-per-folded-key rule could be the wrong one. `și` alone carried 1,044,916
under one spelling and 441,688 under another. `tools/normalise_diacritics.py` folds them,
returning **2,241,446 occurrences** to the right spelling.

The remainder is mostly not a defect. **Most of the cases left have a typed form that is itself a
Romanian word** — `suporta` (infinitive) beside `suportă` (third person), `casa` beside `casă` —
where leaving it alone is the correct answer and the corpus, testing words in isolation, cannot
tell. Of the rest, a handful are foreign names carrying foreign diacritics (`León`, `Novák`),
and a few are drawn from the 136 remaining mojibake entries described below.

Two things this deliberately did not do, both recorded rather than fixed:

- **Mojibake is repaired by `tools/drop_mojibake.py`**, across all six dictionaries. Two
  accidents: a typographic apostrophe decoded as UTF-8-through-Latin-1 and then truncated, so
  "it's" was counted as `itâ`, French `l'` as `lâ`, Italian `dell'` as `dellâ` — 68 entries,
  4,664 occurrences, deleted, since `itâ` stands for `it'`, which is not a word either. And
  Romanian letters through a legacy codepage (`ºi`, `pånă`, `decåt`) — 74 entries, 2,857
  occurrences, repaired onto the spelling they meant.

  The repair lists every letter a glyph might stand for and lets the dictionary pick, rather
  than assuming a codepage. Assuming one got `å` wrong: taken for `ă` it turns `pånă` into
  `pănă` rather than `până`, and the dictionary held that misspelling too, so the wrong answer
  looked like confirmation. What is deliberately left alone is everything that reverses onto
  nothing — `Bjørn`, `Bård`, `Bø` are Norwegian, `måneskin` is a band, and `º`/`nº` is the
  masculine ordinal Spanish and Italian write "nº 5" with.

- **22 words keep a cedilla or tilde on purpose**, because they are genuinely foreign and the
  mark is correct: Turkish `Ayşegül`, `Şükrü`, `Barış`; Portuguese `Conceição`, `Estêvão`. The
  script excuses a word only when a letter Romanian never uses survives the fold, which is why
  `faţã` and `viaţã` — Romanian degraded twice over — are folded rather than mistaken for
  another language.

`--explain <typed> <candidate>` decomposes one candidate's score into its terms — the language
model, the pack weight, the personal boost, the edit and completion cost — and says what
autocorrect would do with it. That is the question every scoring change starts with, and the one
a ranked list cannot answer.

### Word-list classification

```
python3 tools/classify_wordlist.py --all --review <dir>
```

`tools/classify_wordlist.py` keeps each bundled list to the rows the language's own evidence
supports (`docs/dictionaries.md`, "Which rows a list keeps"), and was applied to all six on
2026-09-29. A run on a classified list drops nothing. What it prints last is coverage: how many
of the 10,000, 20,000 and 50,000 commonest words of the language's subtitle frequency list the
list reaches, by folded key, before and after.

| list | rows removed | 10k | 20k | 50k |
|---|---|---|---|---|
| `en_US` | 8,418 of 146,493 | 97.4% → 97.4% | 95.7% → 95.7% | 86.7% → 86.7% |
| `ro_RO` | 4,566 of 119,490 | 96.2% → 96.2% | 93.5% → 93.5% | 85.1% → 85.0% |
| `de_DE` | 1,114 of 110,342 | 92.9% → 92.8% | 87.5% → 87.5% | 71.0% → 71.0% |
| `es_ES` | 2,870 of 109,738 | 95.5% → 95.5% | 91.1% → 91.1% | 79.1% → 79.1% |
| `fr_FR` | 2,253 of 105,685 | 94.0% → 94.0% | 89.3% → 89.3% | 75.7% → 75.7% |
| `it_IT` | 2,348 of 107,484 | 94.8% → 94.8% | 90.3% → 90.2% | 76.3% → 76.3% |

Every corpus above read the same after as before, except the strip's mean rank, 1.39 to 1.36;
the swipe gates did not move, and every pack's count of unreachable rows fell, by 18 to 81. The
review directory holds `<tag>.dropped.tsv`, `<tag>.borderline.tsv` (the dropped rows at 2.5 zipf
or more) and `<tag>.kept.tsv`, each row with its reason; a real word dropped goes into
`dictionaries/<tag>.words-include`.

### Why these are not tests

Every scoring constant in `engine.cpp` was once found by typing something and looking. That works
until two of them pull against each other: raising the edit penalty to stop a frequent word
replacing a rare real one is the same change that buries `the` under `tehachapi` when someone
types `teh`, and no amount of looking at one example tells you the cost of the other.

Turning these into assertions would mean freezing a number that is *supposed* to move when the
dictionaries change. The swipe one is gated because a decoder either got better or it did not;
the suggestion one is read because a pack rebuild legitimately moves it.

---

## What is not covered

Named rather than left to be discovered:

- **On a device, only what the instrumented suite drives.** Everything else above runs on a JVM
  or a host toolchain, and the suite types into the settings application's own field and into
  Termux, on emulators: no phone, and no other app's editor. The rest of the views, touch
  dispatch and `InputConnection` behaviour is verified by hand on a device and an emulator.
- **No screenshot or UI-regression tests.** The keyboard draws itself on a `Canvas`, so a visual
  regression is invisible to everything here, apart from the one smoke case that measures how far
  the strip's text reaches in a screenshot.
- **The dictionary tools have no tests of their own**, apart from `build_dict.py --selftest`.
  `make_pack.py`'s name guards, `merge_names.py`'s vetoes and `flag_names.py`'s rule are exercised
  by running them and reading the output, not by assertions.
- **The assistant** (`:assist`) is covered only by the gates that keep it out of `core`. Its
  behaviour is not tested here; `llama.cpp` carries its own suite.
- **No performance regression gate.** The visit budgets and the 8 ms target are documented and
  enforced by construction (a node count rather than a timer, so the answer is deterministic),
  but nothing fails the build if a change makes the search slower.

---

## Running it all locally

```bash
# JVM tests, every module
./gradlew test

# ...including the pipeline harness, which skips without these two
cmake --build native-tests/build --target borderkeys
./gradlew :keyboard:buildDictionaries :keyboard:test

# Native tests
cmake -S native-tests -B native-tests/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native-tests/build --parallel
ctest --test-dir native-tests/build --output-on-failure

# Gates
./gradlew :app:verifyNoInternetPermission :app:verifyNoForbiddenDependencies \
          :keyboard:verifyKeyboardHasNoCompose
python3 tools/build_dict.py --selftest
reuse lint

# Dictionary hygiene: encoding variants, then decoding accidents
python3 tools/normalise_diacritics.py dictionaries/ro_RO.tsv --dry-run
for d in dictionaries/*.tsv; do python3 tools/drop_mojibake.py "$d" --dry-run; done

# Regenerate the generated corpora
python3 tools/make_accent_corpus.py dictionaries/ro_RO.tsv
python3 tools/make_unknown_corpus.py <hunspell en_US.dic> dictionaries/en_US.tsv
python3 tools/make_midtypo_corpus.py dictionaries/en_US.tsv
python3 tools/make_doubled_corpus.py dictionaries/en_US.tsv
python3 tools/make_firstletter_corpus.py dictionaries/en_US.tsv

# Classify the word lists: a report and the review files, then --apply to rewrite them
python3 tools/classify_wordlist.py --all --review /tmp/review
# The twin and plain corpora, from that review
python3 tools/make_twin_corpus.py --tag ro_RO --review /tmp/review --out native-tests/data

# Measurements
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_en.tsv en-US
native-tests/build/suggest_eval <dict dir> --explain teh the en-US
native-tests/build/suggest_eval <dict dir> \
    --autocorrect native-tests/data/autocorrect_accents_ro.tsv ro-RO
python3 tools/gesture_replay.py --binary native-tests/build/gesture_replay \
    --pack native-tests/build/test_pack.bkd --check-regression
python3 tools/make_tap_corpus.py --profile thumbs --taps 60000 --seed 1 > thumbs.tsv
native-tests/build/touch_eval <dict dir> en-US native-tests/data/qwerty_1080.layout thumbs.tsv \
    --train-taps 20000 --test-from-taps 40000 --test-words 4000

# Readings, before and after a change meant to change nothing
scripts/corpus_readings.sh /tmp/readings-after
diff -r -x SUMMARY.txt /tmp/readings-before /tmp/readings-after
```

CI runs on push and pull request to `main`; release only on a `v*` tag. See
[`docs/licensing.md`](licensing.md) for the reproducible-build settings.
