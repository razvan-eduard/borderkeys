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

**525 test functions across 53 files.** No device, no emulator, no Robolectric.

That is possible because the logic is deliberately kept out of the Android classes. The policy
objects in `ime/` hold no `InputConnection` and make no native calls — they take strings and
return decisions:

| Class | What its tests pin down |
|---|---|
| `AutoCorrection` | Every reason a correction is refused |
| `AutoShift` | Shift state from a field's request and the text before the cursor |
| `PrivateMode` | Every input type the platform defines |
| `LanguageSwitchCorrector` | Which flags resolve to which replacements, and where the caret lands |
| `RunningText`, `Contractions`, `SentenceCase`, `TextShortcuts`, `HabitSpace` | Pure text transforms |
| `LearningBuffer` | Debounce and eviction, with a clock passed in |
| `PredictionRequestQueue` | The one-deep queue and generation discipline |
| `KeyboardGeometry` | Hit-testing arithmetic |
| `KeyboardPreferences` | Every setting's clamp and default |

`KeyboardGeometry` is the clearest case for why the split is worth it: a one-pixel gap between
two keys is a touch that does nothing, and **neither a device nor a screenshot will show it**.
Tested as arithmetic, it cannot hide.

### The join — `PipelineTest`, `LanguageSwitchPipelineTest`

The section above and the one below each test half of a decision. The native suite stops at the
engine; the policy classes start after it, on values handed to them. Between the two sits the
join, and every correction bug reported from a device lived exactly there — a word the engine
offered and the guards were meant to refuse, or the reverse.

`Pipeline` closes it. It drives the shipping engine through the shipping JNI bridge and then the
shipping Kotlin, against the packs the application ships. Nothing in it is a model of the
pipeline; it *is* the pipeline, with the editor and the touch surface left out. What still needs
a device: the composing region, delimiter handling, field state, and what the service decides
around all of it.

| Payload | What it pins |
|---|---|
| `pipeline_cases.tsv` | 32 cases, `typed <TAB> committed <TAB> situation` |
| `pipeline_cases_ro.tsv` | 5 Romanian cases, against the pack most reports come from |
| `LanguageSwitchPipelineTest` | The backward correction, end to end over two packs |

The **situation** is asserted beside the outcome because an outcome on its own can be right by
accident: a guard can stop working while another covers for it, which is exactly what a chain of
early returns used to hide. Adding a case costs a line, not a method, and every failure in a run
is reported together rather than stopping at the first.

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

### Native — `ctest --test-dir native-tests/build`

Two registered tests:

- **`engine`** (`borderkeys_tests`) — the engine, folding, the pack format, the gesture decoders.
  Source: `test_engine.cpp`, `test_fold.cpp`, `test_format.cpp`, `test_gesture.cpp`, `test_tcn.cpp`.
- **`pack_corpus`** — the pack loader against a committed corpus of deliberately damaged files.

A note on running them: **build every target before `ctest`.** `cmake --build … --target
suggest_eval` leaves `borderkeys_tests` stale, and `ctest` will then report a pass against an
engine from before your change. A `kDeleteCost` sweep was committed that way, with two engine
assertions failing and nothing saying so.

A note on writing engine tests: **`setKeyGeometry` is not optional.** Without it
`KeyGeometry::isSet()` is false and the walk never leaves exact-match mode — no substitution,
deletion, transposition or insertion at all. A test that forgets it measures prefix completion
and believes it has measured the engine.

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
a capacity that is not a power of two, an unterminated language tag. Eighteen named cases.

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
| A — `Shark2Decoder`, every build | **77.0%** | 90.2% |
| B — `TcnDecoder`, `plus`, on by default | **90.6%** | 94.8% |

Tier A reads 77.0% rather than the 60.4% it held while only the five geometry constants had been
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
77.0% there. Both are QWERTY and both drop taps and out-of-pack words; `swipe-5` is simply a
harder collection than the held-out test split. Only the movement within a column means anything.

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

### Suggestion quality — the one that does not

```
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_en.tsv en-US
```

Prints rank-1 accuracy, top-3 and mean rank over a corpus of `typed<TAB>expected` cases. It
**does not assert and is not a test**, deliberately — same contract as `gesture_replay`: a corpus
in, a measurement out, no verdict.

A case whose right answer is *"leave the word alone"* is written with the typed word as its own
expectation (`snobul` → `snobul`), because "offers nothing better than what I wrote" is a result
worth measuring, and it is the result the guards in `AutoCorrection` exist to produce.

Current baseline: **71.9% first place, 81.2% top three, mean rank 1.39**, 32 cases.

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

**Measured through `Pipeline`, not `suggest_eval`.** The two disagree here by a wide margin,
because `suggest_eval --autocorrect` models the known-word guard and not `AutoCorrection`'s edit
ceiling — it reports 71.5% where a user gets 22.0%. Where they differ, the harness that runs the
real Kotlin is the one to believe.

| | corrected | wrong word | left alone | of which ≥ 8 letters |
|---|---|---|---|---|
| before | 6 | 59 | 135 | 1 / 50 |
| after | **44** | **42** | 114 | **39 / 50** |

The gain sits at eight letters and above because that is where
`KeyboardPreferences.CORRECTION_DISTANCE_NORMAL` allows two edits, and this shape needs two. A
shorter word mistyped and unfinished is still refused by the ceiling, which `beleiv` in
`pipeline_cases.tsv` pins.

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

Current baseline: **89.3% restored**, 300 cases. It was **3.3%** before any of this: the
respelling tier in the engine took it to 64.0%, normalising the dictionary's diacritic
encodings to 86.3%, and repairing its mojibake to 89.3%.

That second half was the larger surprise. `ro_RO.tsv` spelled **8,822 words two or more ways** —
cedilla `ş`/`ţ` against comma-below `ș`/`ț`, and `ã` (a Portuguese letter) standing in for `ă` —
so a word could be ranked on a fraction of its real count, and the spelling that survived
`build_dict.py`'s one-per-folded-key rule could be the wrong one. `și` alone carried 1,044,916
under one spelling and 441,688 under another. `tools/normalise_diacritics.py` folds them,
returning **2,241,446 occurrences** to the right spelling.

The remainder is mostly not a defect. **57 of the 300 cases have a typed form that is itself a
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

- **No instrumented (on-device) test suite.** Everything above runs on a JVM or a host toolchain.
  Views, touch dispatch and `InputConnection` behaviour are verified by hand on a device and an
  emulator.
- **No screenshot or UI-regression tests.** The keyboard draws itself on a `Canvas`, so a visual
  regression is invisible to everything here.
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

# Measurements
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_en.tsv en-US
native-tests/build/suggest_eval <dict dir> --explain teh the en-US
native-tests/build/suggest_eval <dict dir> \
    --autocorrect native-tests/data/autocorrect_accents_ro.tsv ro-RO
python3 tools/gesture_replay.py --binary native-tests/build/gesture_replay \
    --pack native-tests/build/test_pack.bkd --check-regression
```

CI runs on push and pull request to `main`; release only on a `v*` tag. See
[`docs/licensing.md`](licensing.md) for the reproducible-build settings.
