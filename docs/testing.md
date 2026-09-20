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

**502 test functions across 49 files.** No device, no emulator, no Robolectric.

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

### Native — `ctest --test-dir native-tests/build`

Two registered tests:

- **`engine`** (`borderkeys_tests`) — the engine, folding, the pack format, the gesture decoders.
  Source: `test_engine.cpp`, `test_fold.cpp`, `test_format.cpp`, `test_gesture.cpp`, `test_tcn.cpp`.
- **`pack_corpus`** — the pack loader against a committed corpus of deliberately damaged files.

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
tools/gesture_replay.py --binary native-tests/build/gesture_replay \
    --pack native-tests/build/test_pack.bkd --check-regression
```

Compared against the number recorded in `docs/gesture-accuracy.json`, so a decoder regression
fails **in CI** rather than being noticed on a device weeks later. Accuracy is the only thing
that says whether a decoder change helped.

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

Current baseline: **71.9% first place, mean rank 1.39**, 32 cases.

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

# Native tests
cmake -S native-tests -B native-tests/build -DCMAKE_BUILD_TYPE=Debug
cmake --build native-tests/build --parallel
ctest --test-dir native-tests/build --output-on-failure

# Gates
./gradlew :app:verifyNoInternetPermission :app:verifyNoForbiddenDependencies \
          :keyboard:verifyKeyboardHasNoCompose
python3 tools/build_dict.py --selftest
reuse lint

# Measurements
native-tests/build/suggest_eval <dict dir> native-tests/data/suggest_en.tsv en-US
native-tests/build/suggest_eval <dict dir> --explain teh the en-US
python3 tools/gesture_replay.py --binary native-tests/build/gesture_replay \
    --pack native-tests/build/test_pack.bkd --check-regression
```

CI runs on push and pull request to `main`; release only on a `v*` tag. See
[`docs/licensing.md`](licensing.md) for the reproducible-build settings.
