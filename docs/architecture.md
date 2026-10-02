<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# How BorderKeys works

What each module is, what happens between a finger touching glass and a word appearing, and why
the numbers that decide it are the numbers they are.

This is the map. Where a rule has a measurement behind it the measurement is named here, but the
authority is always the comment beside the code — those are written at the point of decision and
this document goes stale first. File and symbol names are given so you can jump.

- [Module map](#module-map)
- [The typing path](#the-typing-path)
- [The typing orchestrator](#the-typing-orchestrator)
- [The two axes](#the-two-axes)
- [Scoring: every term](#scoring-every-term)
- [The gates](#the-gates)
- [The learning path](#the-learning-path)
- [Language detection and revert](#language-detection-and-revert)
- [Swipe](#swipe)
- [Quick actions](#quick-actions)
- [The dictionary pipeline](#the-dictionary-pipeline)
- [Invariants](#invariants)

---

## Module map

Seven Gradle modules. The split is not cosmetic: it is what keeps the typing path free of things
that allocate, and what lets most of the logic be tested on a JVM with no device attached.

| Module | What lives there |
|---|---|
| `:keyboard` | The IME itself. Kotlin for the input method, the typing flow, views and policy; C++ for the engine, the trie, the n-gram model, the personal model, the touch model and both swipe decoders. |
| `:data` | Room + SQLCipher. The durable copy of everything learned (words, phrases, the heatmap's totals), the clipboard, language packs, themes, drafts. |
| `:effects` | The particle effects, drawn on a `Canvas` by the keyboard and by the settings preview alike. |
| `:settings` | The settings application, Jetpack Compose. Never on the typing path. |
| `:i18n` | Every string the user can read, as JSON catalogues in six languages. No hardcoded text anywhere else. |
| `:assist` | The optional on-device assistant, vendoring `llama.cpp`. Entirely separate from prediction. |
| `:app` | The two flavours, `core` and `plus`, and nothing else. |

`core` and `plus` are compile-time flavours, not runtime switches. `BORDERKEYS_NEURAL_SWIPE` is
the clearest case: in a `core` build the neural decoder, its weights and every branch that tests
for it are absent from the binary rather than disabled in it.

### Inside `:keyboard`

```
ime/          the input method, the views, the policy objects, and the dictionary loads
typing/       the typing flow: TypingOrchestrator and the flows it drives
predict/      the Kotlin side of the engine: threading, queueing, the learning buffer
cpp/          the engine
cpp/gesture/  both swipe decoders
```

The policy objects in `ime/` — `AutoCorrection`, `AutoShift`, `LanguageSwitchCorrector`,
`RunningText`, `Contractions`, `SentenceCase`, `TextShortcuts`, `HabitSpace`, `PrivateMode` —
hold no `InputConnection` and make no native calls. That is deliberate and it is what makes them
testable: they take strings and return decisions. The typing package goes one step further: it
takes nothing from Android but the constants of `KeyEvent` and `EditorInfo`, and reaches the
field, the engine, the views and the database through interfaces the service implements
([the typing orchestrator](#the-typing-orchestrator)).

---

## The typing path

A keystroke reaches `InputConnection` in about two milliseconds. Everything below is arranged
around that.

```
touch
  └─ KeyboardCanvasView          resolves the key arithmetically (no child views), and the point
                                 it was chosen at
      └─ BorderKeysService       Android glue: the field, the views, the panels
          └─ TypingOrchestrator  the word: its keys, the commit, the correction, spacing, shift,
                                 learning
              └─ PredictionEngine    the EnginePort: posts a request to its own HandlerThread
                  └─ PredictionRequestQueue   keeps exactly one pending request
                      └─ NativePredictor      JNI, one call per answer
                          └─ Engine::suggest  the search
          ◀── the answer posted back to the main looper, tagged with the query it answers
```

Three rules hold this together, all in `predict/PredictionEngine.kt`:

- **The UI thread never blocks on JNI.** There is no path from a touch event into the engine.
- **The handle cannot outlive the engine.** Every native call goes through `withHandle`, under
  one lock, zeroed on release.
- **Only the newest request matters.** `PredictionRequestQueue` is exactly one deep, and
  `NewestWins` numbers every request — the typed ones, and the swipe and pause-preview decodes
  with one each — so a slow answer that arrives after a newer one is dropped rather than shown.
  This is what stops the strip flickering back to a previous word.

Because the answer arrives late, every result carries the `query` it is about.
`AutoCorrection.correctionFor` refuses outright when `typed != suggestionQuery` — otherwise a
delimiter typed before the answer landed would apply a correction computed for a different word.
That is the real cause behind reports like *"tinde" became "idependent"*: no edit budget reaches
one from the other, and it never was the answer to that question.

### Inside `Engine::suggest`

`engine.cpp`, in order:

1. Fold the input (`foldUtf8`) — case and diacritics wash out, because that is how the trie is
   keyed. This is why typing `totusi` reaches `totuși` at **zero** cost rather than as a
   correction. The fold covers Latin, Greek (case, tonos, dialytika and the final sigma),
   Cyrillic (case, and ё and ѐ onto е, ѝ onto и), Armenian and Georgian (case), in
   `proximity.cpp` and, character for character, in `tools/build_dict.py`; the native tests
   diff the two tables. The fold also records which typed code point each folded one came
   from, so the word's taps line up with the query the search walks.
2. `refreshWeights()`, `resolveContext()` — per-request, per-pack, once rather than per candidate.
3. Run the search plan (`search_plan.hpp`): the passes as a table, each with the condition it
   runs on, what it searches and the edit ceiling it walks under.

   | Pass | Runs when | Searches | Ceiling | Commits |
   |---|---|---|---|---|
   | `Primary` | always | the packs the request is [restricted to](#which-pack-a-request-is-restricted-to), or every pack | by length | yes |
   | `OtherPacks` | a restriction applied, the lock is not strict, and another pack could read the letters more closely than `Primary`'s closest reading | every other pack, keeping only [closer readings](#closer-readings) | by length, lowered to what outmatches `Primary` | yes |
   | `UserModel` | something is typed | the personal trie | — | yes |
   | `Wide` | something is typed and nothing has been found | every pack | `kFallbackEditCost` | no, the strip only |
   | `NextWord` | nothing is typed | the personal successors and phrases | — | yes |

   A pack is walked by `collectEndpoints()` over the fuzzy neighbourhood, `collectWords()`
   descending from each endpoint, and `searchFrequentWithPrefix()` for short prefixes the budget
   cannot cross. With nothing typed, `searchNextWord()` instead walks the pack's successor index
   for the context word -- or the sentence-start list -- keeping the `kSuccessorWalk` strongest
   pairs and scoring those in full, then the 512-word frequent shortlist.
4. The answer tiers, each overriding the one before: the corrections heap, then the respelling,
   then the exact spelling. Then the main heap drains and the completion cap applies.

One native call, `nativeAnswer`, returns the whole answer — the ranking, the correction's index,
text and name flag, the known spelling and the possessive — and `PredictionAnswer` assembles it on
the Kotlin side, for the prediction thread and the tests alike. Nothing is left in the engine for
a later call to read.

---

## The typing orchestrator

`TypingOrchestrator` (`typing/`) owns each word from its first key to what is learned from it:
the keys, the composing word, the two words before it, the engine's answer, the commit decision,
the pending correction, spacing, shift and the field's undo history. The service keeps the
Android glue — the views, the panels, the swipe ring's window, the quick-action bar, the
clipboard — and the dictionary loads (`DictionaryLoader`: the packs, the bundled-pack repair,
accents, offensive words, emoji keywords, contractions, blocked words and the personal model).

The word travels as records rather than loose fields:

| Record | What it is |
|---|---|
| `ComposingWord` | The word being written: its text, a `TapTrail` kept in step with it (where each code point was typed, or no point), and how it began |
| `WordContext` | The two words before it, nearest first |
| `SearchAnswer` | The engine's last answer as the commit decision reads it: the query it answers, the known spelling, the correction and whether it is a name, the possessive, and whether the query inflects a known stem |
| `PendingCorrection` | A correction just written, until the next key settles it; it carries the typed word's taps |
| `FieldSession` | One field: its generation (an answer about an older field is dropped), its `FieldPolicy`, and whether it is a terminal or an address field |

`FieldPolicy` is the one gate, decided at the field's start from `PrivateMode` and the switches,
and again on every settings change:

| Answer | True when |
|---|---|
| `suggestionsAllowed` | not a password field |
| `privateField` | a password field, one that asked for no personalised learning, or one that describes nothing about itself |
| `personalAllowed` | Learning is on and the field is not private |
| `verbatim` | a password field: the keys go in exactly as typed, no rewrite, no space added or removed |
| `heatmapAllowed` | `personalAllowed`, and the Heatmap switch is on |

Everything outside the word is an interface, which is what lets the JVM tests type through the
real flow:

| Interface | In the app | In the JVM tests |
|---|---|---|
| `FieldEditor` | `ConnectionFieldEditor`, over the `InputConnection` | `FakeFieldEditor` |
| `EnginePort` | `PredictionEngine`, on its worker thread | `QueuedEngine`: the host JNI bridge, drained by the test |
| `TypingHost` | the service: the views, the strip, effects and stats | `FakeTypingHost` |
| `RingUi` | the service: the swipe ring's view, its steering and its timeout | `FakeRingUi` |
| `LearningStore` | Room, through the service | an in-memory store |
| `TypingClock` | the system clocks | a clock the test moves |

### The flows

The orchestrator drives seven flows, each a `TypingFlow`. The lifecycle is `final` in the base
class — `startField`, `finishField`, `applySettings`, `shutdown` — so the field generation, the
policy and flushing exactly once per field are written once. A flow supplies only what it does
when a field starts (abstract: every flow states what it resets), ends, or the settings change,
and when the keyboard goes.

| Flow | Owns |
|---|---|
| `LearningFlow` | What is learned: the words, pairs and triples, and where the taps land; the buffer, its flush and its gate |
| `ShiftFlow` | Shift, caps lock and auto-shift, and the casing of the strip's words and a swipe's candidates |
| `SpacingFlow` | The spaces the keyboard adds or takes back on its own: after a mark, before one, the full stop two spaces make, the space typed out of habit; none in a verbatim field |
| `TerminalWriter` | A terminal: each key out at once as the key event that carries it |
| `CommitFlow` | The commit decision (`WordCommit`, its rules a first-claim-wins chain of `CommitRule`s), the pending correction and its revert, and the corrections a change of language leaves wrong |
| `SuggestionFlow` | The requests, the word last asked about, the answer a delimiter applies, the strip's row |
| `SwipeFlow` | A swipe between its decode and its word, and the words its ring offers |

Flows never call each other: each talks only to the orchestrator, which hands a field, a
settings change and each answer to them in a fixed order.

---

## The two axes

This is the single most important thing to understand about the engine, and the thing most
likely to be re-broken by someone tidying up.

**The suggestion strip and autocorrect are asking different questions, and they cannot share a
ranking.**

- The strip asks *"what are you writing"*. A longer word carrying on from what has been typed is
  a fine answer.
- Autocorrect asks *"what did you mean"*. The word is finished; a continuation of it is not a
  candidate at all.

Ranking them together means pricing *"a longer word starting with this"* against *"a different
word one slip away"*, and there is no honest exchange rate between those. The attempt to set one
is why `kEditPenalty` is 40 and why a `static_assert` has to defend it.

Concretely: typing `teh`, the strip held `tehran`, `tehran's`, `Tehan`, `Tehrani` and six more
before `the`. Autocorrect read the strip's first entry, so it applied none of them. That was not
two faults — the strip was reporting its ranking honestly, and the ranking was answering the
wrong question.

So the engine keeps **two heaps**, filled during the same walk, at the same moment, with no
second pass over any dictionary:

| | `heap` | `correctionHeap_` |
|---|---|---|
| Holds | 16 candidates | 4 (`kMaxCorrections`) |
| Admits | everything reached | `reachesCorrectionHeap()` **and** `plausibleCorrectionTarget()` |
| Read by | the strip | `bestCorrection()` → `AutoCorrection` |

Only the best correction is ever read; the other three exist so that the best is the best of
several rather than the first one reached.

### What decides which heap — `Reading`

A candidate is classified from two numbers: what the walk paid in edits to reach the endpoint,
and how many characters the word runs past the letters typed. `reading.hpp` names the five
results, and the routing between them *is* the correction hierarchy:

| reading | cost | depth | goes to | bound |
|---|---|---|---|---|
| `Exact` | 0 | 0 | strip | — |
| `Respelling` | 0 | 0, carries a diacritic | its own tier, above the heap | no frequency floor |
| `ShortCompletion` | 0 | 1 | strip + corrections | `kMaxCorrectionCompletion` |
| `LongCompletion` | 0 | ≥ 2 | strip | `kMaxFreeCompletion` 12 |
| `Correction` | > 0 | 0, or 1 when the edit landed on no word | strip + corrections | `kMaxCompletionAfterEdit` |

Three consequences worth stating plainly, because each was a device report before it was a rule:

- A one-character completion costs `kCompletionPenalty` 0.5 and an edit costs roughly 43, so
  **a completion beats a correction by about 86 to 1**. Typing `believ` commits `believe`, not
  `belief`. Correction-first was measured at 12.0% on mid-word typing against 98.5% for this.
- A `LongCompletion` never reaches the corrections heap, which is why `teh` commits `the`
  although `tehran` outranks it in the strip by 25.7 points.
- A `Respelling` outranks the heap outright rather than competing in it, because the two are not
  on one scale: a proposal must clear `kCorrectionFrequencyFloor` and a respelling is exempt.
  `cană` (170 occurrences) beats `canal` (1,369).

`Correction` may carry on one character past the endpoint **only when the edit landed on no
word**. `sevrice` already spells `service` once the transposition is undone, so it stops there
rather than gaining a letter and becoming `services`; `sevric` spells nothing and carries on.
Without that condition the completion is free on top of an uncertain edit, and a frequent
inflected form wins — the failure that kept this barred entirely until it was measured.

`bestCorrection()` decides nothing. Whether the word is applied remains
`AutoCorrection.correctionFor`'s to say, and it still applies every guard below.

### The third axis: language

Orthogonal to both. `observeContextLanguage()` accumulates per-pack evidence from committed
words and sets `dominantPack_` once the evidence is one-sided enough. It only ever runs forward
— which is why [the revert module](#language-detection-and-revert) exists.

---

## Scoring: every term

One number per candidate:

```
score = packWeightLog + contextLogProb + editComponent − lengthPenalty (+ personal boost)
```

Packs store **natural logs**; `kLogProbScale = 10.0` is the quantisation. Zipf values quoted in
comments are `log10(count/total × 1e9)`.

`packWeightLog` is the pack's configured weight, normalised over the active packs
(`refreshWeights`). It is a setting and nothing else moves it: which language is being written
is answered by the evidence and dominance mechanism below, never by drifting a weight.

### Edit costs — `engine.cpp`

| Constant | Value | Why |
|---|---|---|
| `kEditPenalty` | **40.0** | The multiplier on every edit. Must clear `ln(worst frequency ratio)` so that one transposition outweighs the gap between the commonest and rarest word in a pack. Floored at 15 by a `static_assert`. |
| `kInsertCost` | 0.85 | A dropped letter is a commoner slip than a wrong key, so just under a full neighbour substitution. |
| `kDeleteCost` | **1.6** | Deliberately not the mirror of `kInsertCost`. Supplying a letter someone did not type is the ordinary lossiness of typing; discarding one they *did* type throws away the only direct evidence of intent. At 0.85, 79% of the correct words the pack lacked were overwritten by something *shorter* — `bisection` → `section`, `crewel` → `crew`. Swept 0.85 to 2.0: unknown words left alone 66.0% → 90.0%, the typo and mid-word corpora never move, and the strip holds at 71.9% up to 1.6 and drops from 1.7. |
| `kRepeatDeleteCost` | **0.75** | Discarding a letter typed right after the same letter: a key struck twice. Priced as `kDeleteCost` it lost to any closer word, and with Romanian also on, `nationaal` became `națională` (a swap, 0.8) instead of `national`. Just under a swap because at 0.6 `aagin`, a swap of `again`, read as a doubled `a` plus a letter and became `aging`. Doubled corpus 187 → 191, unknown words left alone 188 → 191, every other corpus and the strip unchanged. |
| `kTransposeCost` | 0.80 | One gesture out of order, not two errors. Deliberately only *slightly* cheaper: at the old 0.65 this priced two equally common slips as though one were a thousand times likelier, which let `acm` → `cam` crowd out `acum`. |
| `kMarkInsertCost` | **0.02** | A mark — an apostrophe or a hyphen — left out is a convention dropped, not a key missed. This is what makes `cant` → `can't` and `wellknown` → `well-known` reachable. At `kEditPenalty` 40 it costs 0.8 points: enough that an exactly-spelled word still wins, little enough that a commoner contraction wins on frequency. |
| `kMarkDeleteCost` | **3.0** | The same mark in the other direction. Nobody's finger lands on an apostrophe by accident, so discarding one is a contradiction rather than a correction — `the workers' rights` was becoming `the workers rights`. Above `maxEditCostFor`'s largest ceiling (2.5), so no ordinary search reaches a word by dropping a mark; below `kFallbackEditCost` (4.2), so the wide pass may still *show* the stripped word without ever committing it. |
| `kCompletionPenalty` | **0.5** | Per character a completion adds. Was 0.12, which let longer commoner words push the typed word out of the 16 entirely — typing `car` offered `care`, `cartea`, `carol`, `carmen`, with `car` nowhere. 0.5 is measured: first-place accuracy 61.5% → 68.8%. Past ~1.0 it starts costing the half-typed words completion exists for. |
| `kCorrectionSurcharge` | **3.0** | A flat charge for having needed a correction *at all*, on top of per-edit cost. Charged once to any candidate with cost > 0. Completions are untouched. Without it, Romanian `si` (≈80× commoner) displaced correctly-typed `stiu`. |
| `kGrammarWeight` | 0.75 | How much the part-of-speech transition counts where the n-gram model has nothing. From a sweep on held-out text: below it the term barely moves the ranking; above it, grammatically plausible but rare words start displacing frequent ones and the fifth slot suffers for no gain in the first. The first chip is what people tap, and nobody reads the fifth. |
| `kBackoffLogFactor` | ln(0.4) | Stupid backoff, as in the literature. Deterministic and needing no runtime normalisation, which is the whole reason it is used instead of a smoothed model. |

### The safety margin

```cpp
static_assert(
    kMinCorrectionStrictness *
        (kEditPenalty * KeyGeometry::kMinSubstitutionCost + kCorrectionSurcharge)
    > kMaxUserBoost, ...);
```

`kMaxUserBoost` (3.0) happens to equal `kCorrectionSurcharge` (3.0). That is safe *only* because
no edit the engine prices ever gets cheap enough for the coincidence to matter — and this checks
it at compile time against the cheapest possible substitution and the most lenient strictness a
user can dial in. Change any of those five numbers and the build fails rather than a live report
arriving. It implies `kEditPenalty > 15`.

### Where the tap landed — `touch_model.cpp`

A substitution — a tapped letter read as another key — is priced from where the tap landed.
Every key has a default pattern, the same for everyone and stored nowhere: taps centred on the
key, spread `kReferenceSpread` (0.3 key units) both ways. Under it, reading a tap on one key as
another costs √(d_intended² − d_typed²), the tap's distances from the two centres in key units
(x in key widths, y in key heights). A tap on a key's centre costs exactly the key geometry's
centre distance, so centre taps rank as they always did; a tap near an edge makes that neighbour
cheaper and the others dearer. Nothing goes below `KeyGeometry::kMinSubstitutionCost` (0.2), so
the safety margin above holds.

A letter without a point is priced by the key geometry alone: a long-press alternative, a key
reached by sliding onto it (its entry point sits on the edge of the key before), a swiped or
adopted word, a key the screen reader typed. The taps travel with every request the strip makes
(`nativeAnswer`'s x and y arrays), in every field, private ones included: they price that one
answer and are kept nowhere. A terminal's requests carry none.

The heatmap's learned patterns ([the heatmap](#the-heatmap)) take a key's place while Learning
and the Heatmap are on, the field allows them, and the key has the minimum of taps. The cost then
moves by the weight towards `kReferenceSpread · √(2 · LLR)`, LLR being the log-likelihood ratio
of the tap under the typed key's pattern against the intended key's, and the same floor applies.

| Setting | Default | Range | What it is |
|---|---|---|---|
| `heatmapWeight` | 1.0 | 0.5–2.0 | How far a learned pattern moves the cost from the default pattern's |
| `heatmapMinTaps` | 30 | 10–100, by 10 | Taps before a key's own pattern counts; below it the key keeps the default |
| `heatmapHalfLifeDays` | 30 | 7–180 | After this many days a tap counts half |

Measured on synthetic taps (`tools/make_tap_corpus.py`, six profiles of how taps miss;
`native-tests/touch_eval`), autocorrect's right word on 4,000 slips into non-words, after 20,000
taps learned:

| Profile | Geometry only | Default pattern | Learned |
|---|---|---|---|
| centred | 83.1% | 91.2% | 91.4% |
| low | 76.6% | 90.4% | 90.3% |
| two thumbs | 77.4% | 89.3% | 89.9% |
| right thumb | 73.4% | 89.3% | 90.4% |
| precise | 85.4% | 92.7% | 92.7% |
| sloppy | 70.8% | 88.7% | 88.9% |

Almost all of the gain is reading where the tap landed at all, which is why the default pattern
prices every tapped letter for everyone; learning a person's pattern adds up to a point on these
taps, whose Gaussian spread suits the default. A pattern younger than about 20 taps does worse
than the default, which is what the minimum of 30 guards; the weight is best or within 0.1 of it
at 1.0 on every profile, and the half-life barely moves the score (7 to 180 days stay within 0.6
points after a change of grip) — 30 days keeps the rarest letters above the minimum for someone
typing 500 taps a day.

### Personal model terms

| Constant | Value | Meaning |
|---|---|---|
| `kUserOnlyLogProb` | −8.0 | The log-probability for a word that exists *only* in the personal dictionary. Deliberately pessimistic and fixed: the model's own totals cannot be used, because a word confirmed 40 times out of 50 would be three quarters of that distribution — likelier than `the`. |
| `kMaxUserBoost` | 3.0 | The ceiling on what personal evidence adds. |
| `kMinPersonalEvidence` | 3.0 | Effective counts (raw × learning speed) at which repetition alone *establishes* a word no pack holds — offered from the personal model, treated as a known word by autocorrect, predicted as a successor. At the cautious 0.35 multiplier that is ~9 repetitions; at the immediate 3.0 the first use clears it. One assertion (a tap on the strip, a reverted correction) establishes a word at any count. The word is learned and listed the whole time. |
| `kUserBigramPrior` | 4.0 | Smoothing, in observations. One `vreau să` out of one `vreau` is not evidence that `să` always follows. Four, not one, because this competes with a corpus. |
| `kMaxUserBigramBoost` | 2.5 | Ceiling on what a personal pair adds. |
| `kUserChainPreference` | 1.5 | How far a phrase *this* person writes may outrank what the corpus says follows. Scaled by confidence: +0.4 once, +0.8 at three, +1.3 at twenty. |
| `kUserChainHalfLife` | 3.0 | Observations to reach half that ceiling. |
| `kPhraseSecondLinkFactor` | 1.5 | The second word of a two-word suggestion needs 1.5× the evidence. Twice the guess, twice the evidence — single-word leads after two repetitions, two-word appears after four. |
| `kPhraseMinShare` | 0.34 | Share of its context a link must hold. Not a count: followed by one thing nine times in ten is a habit; followed by nine different things is not. |

### Search bounds

| Constant | Value | Notes |
|---|---|---|
| `nodeVisitBudgetFor(len)` | 3000 / 10000 / 20000 | By prefix length (≤2, ≤4, else). Scaled because a visit is not worth the same at every length: under one character no budget crosses the subtree, so extra visits buy an arbitrary sample — the frequent-word shortlist answers that case properly. Measured: a flat 20000 spent 900 µs on `mas` for *worse* suggestions. |
| `maxEditCostFor(len)` | 0.0 / 1.7 / 2.5 | One or two characters carry almost no information, so fuzzy matching there returns noise. |
| `kFallbackEditCost` | 4.2 | Second pass, only when the first found **nothing**. An empty strip tells the writer nothing about why. |
| `kMaxRunAhead` | 2 | An insertion advances the trie without consuming input, so it would otherwise recurse forever. |
| `kMaxEndpoints` | 96 | |
| `kArenaBytes` | 512 KiB | Bump allocator, reset per request. |
| `kMaxCandidates` | 16 | The strip shows three; the rest feed the gesture decoder and reranking. |
| `kMaxShownCompletions` | **4** | See below. |

`visitBudget_` is reset **per pack**, inside `searchPacks`, not once per request. A shared
counter let one pack's fuzzy walk exhaust it before a later pack ran, silently starving that
pack's corrections for that keystroke. The budget is a node count and not a timer on purpose: a
wall-clock check would make the answer depend on how busy the device was, so two identical
requests could return different suggestions.

The frame stack is LIFO, so branches pushed **first** are explored **last** and can be starved.
This is why the apostrophe insertion is pushed last in its loop — so it is explored first.

### The completion cap

Nothing is mis-scored: every continuation earns its place. The trouble is that a short stem has
a great many of them and they arrive *as a block*, pushing corrections past the three or four
slots anyone looks at. Measured through `explainScore`: for `teh`, `the` is 7.8 points **better**
on the language model and loses by 35 points of edit penalty — a constant floored at 15 and so
not adjustable.

So `kMaxShownCompletions = 4` is applied **after** draining, never during the search. The heap
still ranks them all first, so the four kept are the best of them rather than whichever the walk
reached first. A continuation is recognised from its text (`continuesTyped`) rather than recorded
on the candidate, because `Candidate` is twelve bytes of plain data crossing JNI on a path that
may not allocate.

---

## The gates

Everything that can stop a correction, in the order it applies.

### In the engine

1. **`fuzzy = maxCost > 0.0f && geometry_.isSet()`** — without a key layout the walk is
   exact-match only. A harness that forgets `setKeyGeometry` measures prefix completion and
   reports it as the whole engine.
2. **`plausibleCorrectionTarget()`** — `kCorrectionFrequencyFloor = 9.0` nats below the pack's own
   commonest word. Expressed relatively so a smaller corpus does not raise the bar on itself.
   Nine nats is 3.9 Zipf, which separates real targets (`occurred` 4.84, `receive` 5.09, `the`
   7.81) from junk (`cr` 3.78, `eh` 3.32) cleanly. A spelling another active pack holds counts
   when it clears that pack's floor: `great` is rare in the Romanian list and common in the
   English one, and it is the same word in both.
3. **`Engine::personalWordEstablished`** — a learned word no pack holds is offered, counts as
   a known word, and is predicted after its context only once *established*: chosen on purpose
   at least once, or written `kMinPersonalEvidence` effective times. Until then it is a count.

### In `AutoCorrection.correctionFor`

Returns null — commit what was typed — in every case where applying a correction would be an
argument rather than a correction:

- the word is shorter than `minimumLength`, **unless** the only difference is a diacritic
  (`in` → `în` is two real words that differ by an accent, not a coin toss);
- **the dictionaries spell the word** — it is a word, and a keyboard does not correct words. The
  engine ranks by likelihood, so a real but uncommon word loses to a longer common one and was
  being replaced by it. Once a language leads the evidence, its spelling answers first; when it
  reads the letters another way (`daca` as `dacă`, `Havard` as the name `Håvard`) that reading
  is taken, and only when it reads them not at all does another language's spelling count, so
  `aceasta` stays `aceasta` after an English sentence. A name reads the letters only in the case
  they were typed in: the English name `Duca` is no reading of `duca`, the Romanian `ducă` is;
- the suggestion is what was typed, or what was typed in a different case;
- `typed != suggestionQuery` — a stale answer, see [the typing path](#the-typing-path);
- `editDistance(stripDiacritics(typed), stripDiacritics(suggestion)) > maxEdits` — a ceiling on
  top of the engine's ranking, which only ever decides *which* candidate is first, never whether
  it is close enough to be a correction at all. `snobul` was being replaced by `noul`;
- **the proper-noun rule** — a name corrects only its own letters. `maria` may become `Maria` and
  `laurentiu` → `Laurențiu`, but `everyone` must never become `Everton` nor `thanks` `Hanks`.
- **a regular inflection of a known stem** — `smooths`, `testings`, `spatting`: the stem is in
  the dictionaries as an ordinary word within `kStemFrequencyFloor` (10.5 nats) of the pack's
  commonest, the ending is one the language forms (`WordStems`, English and Romanian tables),
  and the answer is neither that stem nor another inflection of it nor the typed letters carried
  on. A stem counts only in the dictionary of the language whose ending made it: `orices` is
  not the Romanian `orice` with an English plural. The prediction worker asks the engine which
  stems it vouches for, one language at a time, beside the other per-request answers, so the
  delimiter blocks on nothing.

### Cross-pack agreement

`packsAgreeProperNoun()` — with several packs active, **every** pack that knows a word must agree
it is a name before it is capitalised. `Si` is a family name to the English list and `și` (typed
without its accent) is "and" to the Romanian one.

### Privacy gates

`PrivateMode`, a pure function of the field's `EditorInfo`, decides at the field's start whether
it must be forgotten entirely, and the field's `FieldPolicy` carries the verdict. It is enforced
twice, which is the right number for a rule whose failure mode is a password in the personal
dictionary:

- `LearningFlow` records nothing the policy does not allow, and drops what was buffered when a
  private field starts;
- `LearningBuffer` refuses every record while its gate is off, and the flow sets that gate from
  the policy at every field start and every settings change.

`setPersonalModelEnabled(false)` additionally stops the model being *consulted* — what this
device learned from its owner must not be offered back into a field that asked to be forgotten.
The model stays loaded and untouched. The learned touch patterns follow the same gate
(`setTouchModel`): outside `heatmapAllowed`, only the default pattern prices a tap. A password
field is also verbatim: its keys go in exactly as typed, with no rewrite and no space added or
taken away, and its text never reaches the engine.

`OffensiveWords` keeps its list out of suggestions, corrections and learning while the switch is
on; entries are folded through `WordFold`, so `Shit` at a sentence start is the same refusal as
`shit`.

A word the user blocked is matched by its exact spelling, case aside: blocking `maine` blocks
`Maine` and leaves `mâine` alone. The engine treats a blocked spelling as absent from every
dictionary (`Engine::setBlockedWords`). It is never offered, on the strip, as a next word or
from a swipe, and the next candidate takes its slot. It is never a correction, and it is not a
known word, so autocorrect may replace it when typed: `maine` becomes `mâine`. `RefusedWords`
keeps both lists out of learning.

---

## The learning path

**There is no model being fine-tuned and no gradient anywhere.** A count goes up every time
the user commits a word — types or swipes it and moves on, or picks it from the strip. That is
the entire learning rule.

**Typed and chosen are two different facts.** Every commit raises `count`. Only a choice raises
`asserted`: a tap on the strip (the typed word's own chip included), or a correction put back
with backspace. A word no pack holds is *established* — offered as a completion, treated as a
known word by autocorrect, predicted after its context — once it has been asserted at all, or
written `kMinPersonalEvidence` effective times (three at the balanced setting). Until then it is
recorded and listed. Words the packs already hold need none of this — `knownSpelling` answers
from the packs first.

**Sentence openers are learned too.** A word committed with nothing before it — an empty
field, or a sentence end or line break before it — is recorded as a pair under a sentence-start
marker the personal model reserves (`\x02start`, a word no key can type), and at a sentence
start the engine asks the personal model for that marker's successors before the packs' own
openers fill the remaining slots. The Learned phrases page lists such a pair as the word "at the
start of a sentence".

**The learning switch gates both halves.** Off, nothing typed is recorded and nothing personal
is offered: the dictionaries alone answer, exactly as in a private field. Switching it off asks
first, then forgets everything learned, the heatmap included.

```
user commits a word  (types a delimiter after it, swipes it, or picks it from the strip)
  └─ LearningFlow              the gate; the word, its pair and triple, and its taps
      └─ LearningBuffer        in-memory, debounced; carries count, deliberate capital, assertion
          └─ Room (:data)      the one durable copy, SQLCipher
          └─ UserModel (C++)   rebuilt from Room at every start
```

**Why the buffer exists.** A key press has two milliseconds to reach `InputConnection`. An
INSERT is a transaction, a disk write and an encryption pass. So confirmations accumulate in
memory and flush when the buffer is old enough, full enough, or the field ends
(`LearningFlow.onFieldFinished`). `LearningBuffer` is free of Android and of coroutines — a
counter with a clock passed in — so the debounce and the eviction are testable on the JVM.

**Structure.** `UserModel` is a node-per-character trie with a sorted child list. Insertion is
off the hot path by construction; prefix lookup is on it, and is a binary search per character
over a list almost always one or two entries long. A mutable double-array trie would have to be
rebuilt on nearly every insertion.

**Caps and eviction.** `kMaxBigrams = 4096`, `kMaxTrigrams = 2048` — half, because a triple is
both rarer and narrower, firing only when the last two words match. When full, the least-used
entry is dropped, so a phrase typed once years ago does not hold a slot against one typed daily.
`PersonalWordDecay` (`:data`) is how the dictionary forgets without being told to.

**Deliberate capitals.** A word the user capitalised *themselves* — shift physically pressed for
that letter, never auto-capitalise's doing — is treated as a name from then on, regardless of how
it is typed next time (`UserModel::deliberateCapitals`).

**Learning speed** (`setLearningSpeed`) multiplies raw counts into effective counts. It is the
same number `kMinPersonalEvidence` is measured against, not a separate knob.

### The heatmap

Where the taps land on each key is learned beside the words, only where the field's policy
allows it (`heatmapAllowed`: Learning and the Heatmap switch on, the field not private), and
priced as [where the tap landed](#where-the-tap-landed--touch_modelcpp) describes.

```
a word learned from typing, with its taps  (TypedTaps, from the word's TapTrail)
  └─ TapAlignment          a sample per letter: its offset from the kept letter's key centre
      └─ TouchLearning     the current bucket's stored totals, plus the samples not yet written
          ├─ the engine          per key: taps, mean offset, covariance (nativeSetTouchPatterns)
          └─ Room (key_touches)  totals by bucket and letter, merged at the learning flush
```

**When.** At the points a word is learned from typing: a word kept as typed (space, Enter,
before a swipe), a correction confirmed by the next key (the taps travel on
`PendingCorrection`), a correction taken back (the letters as typed), and a pick from the strip
(the typed letters against the picked word's first ones).

**Which taps count.** A tap on the key of the letter kept, or on a ring neighbour of it (within
1.45 key units, `KeyGeometry::kNeighbourRadius`), gives that letter one sample: the tap's offset
from that key's centre, in key units, dropped beyond one. A word with any letter untapped gives
none, so a swiped word, an adopted one or a slide onto a key never teaches the heatmap.

**Buckets.** A tap's position depends on more than its key: the orientation, the keyboard's
placement and the layout each make a bucket (`portrait/0/qwerty`), with its own totals. The
keyboard loads the current bucket's totals when the layout or the placement changes it.

**What is stored.** Per bucket and letter: the tap weight and the weighted sums of x, y, x², y²
and xy, the key's size and the display's density at the last tap, and when that was — enough for
the mean and the covariance, and nothing that says what was typed. Raw taps never leave memory.
Every total halves each half-life since its last tap (`KeyTouches.decayed`, `merged`), so a new
way of holding the phone takes over.

**Forgetting.** Switching Learning or the Heatmap off drops the taps not yet written at once;
switching the Heatmap off asks, then deletes the table, and Forget everything deletes it too.
An edit made on the settings screen fires `DictionaryRepository.edits`, and the keyboard reloads
the current bucket's totals. The table is left out of backups.

**The preview.** The settings screen draws a bucket's stored totals on the keyboard preview
(`KeyboardCanvasView.touchGlows`, which the keyboard itself never sets): each key's glow centred
on its mean offset, an ellipse two standard deviations of its covariance wide (`GlowEllipse`),
its strength from 0.35 at the minimum of taps to full at four times it (`HeatmapGlows`). A key
below the minimum is a faint circle at its centre, the default pattern.

---

## Language detection and revert

Two halves, forward and backward.

### Forward — `Engine::observeContextLanguage`

Every committed word is looked up in each active pack. Rather than counting which packs know the
word, it scores the **frequency ratio**, because dictionaries overlap heavily and a shared word
is weak evidence:

```cpp
if (knowers == 1)  award = 1.0;
else if (knowers > 1) award = clamp((ownerLogProb − rivalLogProb) / kLanguageEvidenceFullGap, 0, 1);
```

`kLanguageEvidenceFullGap = 2.302585` (= ln 10, one order of magnitude): a word ten times
commoner in one language than in every other is as clear a signal as a word only that language
has at all, and a word both know equally contributes **nothing**.

Each completed word multiplies every language's evidence by `kLanguageEvidenceDecay = 0.85` and
adds the award, so the score is a weighted sum over roughly the **last seven words** — long
enough not to swing on one borrowed noun, short enough that switching language mid-conversation
is followed within a sentence. A pack becomes dominant at `kLanguageDominanceShare = 0.7`.

That share is **not a setting**: it states how one-sided a measurement must be before acting on
it, which is not something anyone can answer by trying values. *How much* evidence to wait for
is the question a person can have an opinion about, and that one is `languageLockMinimum_`, from
the settings.

`setLanguageLock(minimum, strict)` controls how one-sided the evidence must be before a language
is decided. Once it is, the other languages are still searched, but offer only [closer
readings](#closer-readings) of the letters typed — the detector is a guess about the sentence,
not a verdict on the next word. Strict offers nothing from them at all. Setting it decides the
language again from the evidence already gathered, so evidence put back before the first field
sets the lock is judged by that lock, not by the engine's default.

`languageEvidence(tag)` and `setLanguageEvidence(tag, value)` read and set one language's
evidence, the setter deciding the language again as a committed word does. The service uses them
to carry the verdict through a restart of the keyboard; the tests, to type each case right after
a decided sentence without writing the sentence again.

### Which pack a request is restricted to

Three questions, in order, and only the first two are about this request:

```cpp
const int restrictTo = (dominantPack_ >= 0)   ? dominantPack_
                     : (preferredPack_ >= 0)  ? preferredPack_
                     : (strictLanguage_ ? heaviestPack() : -1);   // -1 = every pack
```

**The preferred pack sits below the detected one, never above it**, and that is the whole meaning
of the word. `setPreferredLanguage(tag)` says where detection *starts*; it is outranked the moment
the evidence decides otherwise, and the other packs still offer their closer readings. Set
Romanian and write four English words and you get English, because by then it is no longer a
guess.

### Closer readings

A restricted request searches its pack first (`Primary`), recording the closest reading of the
typed letters it reaches: the edit cost, then the fit — spelling the letters with every mark
typed, spelling them without a mark typed (`carti` for `carți`), or running on past them. Which
of its words count as readings:

- an ordinary word always, however rare;
- a name reached by an edit, or running on, only when it clears the correction floor (`American`
  counts, `Weathers` does not);
- a name reached without an edit only in the case the letters were typed in.

`OtherPacks` then searches every other pack and keeps a word only when it reads the letters more
closely: cheaper by `kLanguageMargin` (0.5), or as cheap and a closer fit. Where `Primary` read
nothing at all, the decided language says nothing about the word and the others answer as if
none were decided. Measured cases, the corpora typed right after a sentence in the other
language:

| Typed after | Decided language reads | Another reads | Result |
|---|---|---|---|
| English | `mibtea` as `Mineta`, 1.8 | `mintea`, 1.0 | `mintea`: cheaper by more than the margin |
| English | `cand` as `candy`, running on | `când`, spelled | `când`: as cheap, closer fit |
| Romanian | `speeaker` as `speakeri`, 0.75, running on | `speaker`, 0.75, spelled | `speaker` |
| English | `car` as `car`, spelled | `ar`, `cu`, by edits | `car`, and nothing Romanian beside it |
| English | `zpart` as `apart`, 1.41 (a diagonal key) | `spart`, 1.0 (a straight one) | `apart`: 0.41 is a near tie, and ties go to the decided language |

The margin is more than a diagonal neighbour costs over a straight one (0.41) and less than a
deletion costs over a swap (0.8). `TwoLanguageCorpusTest` holds the autocorrect corpora to
floors with Romanian and English both on, each word typed after a sentence in the other language
and after one in its own. Before this rule the other language's words were searched only when the
decided one found nothing at all: Romanian plain words after English went 135 → 200 of 200,
English transpositions after Romanian 63 → 188 of 200, and no run in its own language lost a
word.

It is **not** a term in the score, and nothing in the scoring path reads it. That distinction is
the reason it exists: a pack's `weight` *is* a scoring term (`packWeightLog`), so using weight to
say "start here" also biased every one of that language's words for ever, including after another
language had become dominant. Weight is now only what it says it is.

The preference is stored as a **tag** rather than a slot index, because `setActiveLanguages` opens
and closes packs and an index does not survive that; `resolvePreferredPack()` re-resolves it there
and requires the pack to be *active*, not merely open. An empty tag, or one naming a pack that is
absent or switched off, resolves to −1 and behaves as no preference — which is the default, and
restores exactly the behaviour that shipped before the setting existed.

`resetLanguageEvidence()` forgets the verdict so a new field decides for itself. The service calls
it on field start unless `rememberDetectedLanguage` is on and no language is preferred. The
setting is on by default, which keeps what the keyboard has always done within one run: a field
inherits the previous field's verdict. It also carries that verdict through a restart: whenever
the keyboard hides, the service writes each language's evidence to the `language_evidence`
preferences file, and puts it back once the packs have loaded in a new process, so a new session
starts in the language the last one ended in rather than from equal weights. Off, the file is
emptied and every field starts undecided. A preferred language takes precedence: every field
starts from it, and the Languages screen greys the switch.

### Backward — `LanguageSwitchCorrector`

The forward pass only ever runs forward: a run of Romanian evidence at the start of a message is
never revisited once the next several words turn out to be exclusively English. This is that
revisit, and only that.

It holds no `InputConnection` and makes no native calls, so it tests on plain data. Reading live
text and asking the engine for a pack-specific candidate (`Engine::candidateForPack` — the one
place a caller names a pack explicitly rather than accepting `dominantPack`'s verdict) both
happen in `CommitFlow`, through `FieldEditor` and `EnginePort.candidatesForPack`.

- `recordCorrection(Flag)` tracks each correction applied while the language was believed to be
  something it may not have been, bounded at `MAX_TRACKED`.
- `observeDominantPack(int)` is the cheap per-word check: did the dominant pack change? An int
  comparison, so the expensive steps only run when it did.
- `resolve()` produces `Replacement`s, carrying **both** spellings so `Ask` mode has something to
  show, not just something to do. It applies `matchCase`, so a correction that replaces a capital
  keeps it.
- `caretAfter()` keeps the cursor where the user left it, whatever the offsets of the edit — a
  correction several words back must not drag the caret with it.
- `reset()` on a new field: an offset from the last one means nothing in this one.

**It only ever revisits words the keyboard itself changed.** Text the user typed manually, and
text pasted in, are never touched — which is what stops a copied passage in another language from
being butchered.

---

## Swipe

Two tiers behind one interface (`GestureDecoder`), merged across packs through one scorer
interface (`GestureScorer`). Passing the scorer as an interface rather than a pointer to `Engine`
is what removes the header cycle and lets a decoder be tested against a stub with no engine.

### Tier A — `Shark2Decoder`

Geometric, in the manner of SHARK² (Kristensson and Zhai, 2004). Ships in **every** build and is
always what `decodeGesture` falls back to.

A word has an ideal trajectory — the polyline through the centres of its letters — and a swipe is
a noisy instance of one. Decoding is a nearest-template search, made tractable by discarding
almost every word before measuring anything. Two channels, both needed:

- **Shape**, with translation and scale normalised away. Recognises the same word swiped larger,
  smaller or off to one side.
- **Location**, absolute pixels, no normalisation. Stops shape being fooled: `were` and `tie`
  trace nearly the same figure in nearly the same proportions, in different places.

**77.0% top-1, 90.2% top-3** on 500 recorded traces from FUTO's held-out split, against the
shipped English pack (`native-tests/data/gestures_futo.csv`, gated in CI). The SHARK² paper
reports about 80% on English QWERTY; this is our implementation on real swipes, and it is the
number tier B has to beat to justify its weights.

The descent reaches a letter when the path passes within `kTouchRadius` of its key, not only
when that key is the nearest one to some sample. Marking only the nearest key left 159 of the
500 words unreachable — never scored at any heap depth, which is why no weighting changed them —
and widening it is worth 16.6 points of top-1 and 22.4 of top-3. It costs about 0.1 ms a
gesture and does not approach `kVisitBudget`, which is inert over a 33-fold range either way.
The radius is fitted on English QWERTY but is not particular to it: it gains between 2.4 and
24.4 points on azerty, qwertz, dvorak, german and spanish as well (`docs/testing.md`).

`kShapeWeight`, `kLocationWeight`, `kEndpointRadius` and `kTouchRadius` are fitted against that
corpus. The two distance channels matter far less individually than their size relative to the
language model: `kShapeWeight` is flat from 6 to 30, while `kLocationWeight` traces a clean peak
at 8. Weighting the language model itself against them is a clean peak at 1.0, so it carries no
constant. `kMinLengthRatio` and `kMaxLengthRatio` are inert over any range worth trying and keep
their original values.

### Tier B — `TcnDecoder`

`plus`-only, behind `BORDERKEYS_NEURAL_SWIPE`, and **on by default** since it was measured:
**90.6% top-1, 94.8% top-3** on the same 500 traces, 13.6 points above tier A and ahead at every
word length. A `core` build compiles no tier B at all, so tier A is what that flavour swipes
with. Three stages:

```
resampleUniformTime + buildTcnFeatures   raw touch samples → encoder input
TcnEncoder                               → per-timestep intention/spectral output
TcnCtcDecoder                            + the active lexicon → ranked words
```

**Scoring.** A hypothesis' final score is
`ctc / letters^kLengthNormalisation + kLengthBonus × letters + kFrequencyWeight × contextLogProb`,
fitted against the replay corpus at γ 0, β 3.0, λ 0.5, beam 100. The beam and the scoring are
not independent: at the unfitted scoring a wider beam bought nothing at top-1, and at the fitted
scoring it is worth nine points, because a beam only helps once the ranking can tell its extra
candidates apart.

Smoothing and resampling belong to the decoder, not the caller, because both tiers want the same
features and must not disagree about how they were produced.

**Memory.** `setSwipeModelEnabled(false)` **frees the decoder outright** — it holds ~2.5 MB of
weights by value, and a decoder that has been switched off has no claim on the memory. The next
`loadSwipeWeights` rebuilds it.

**Warm-up.** `warmSwipeModel()` decodes one synthetic gesture and throws the answer away, so the
first real swipe is not also the first pass through the network. It bypasses the tier guard
deliberately: load, warm, *then* tell anyone the model is ready. Warming is an optimisation and
never a precondition.

**Score normalisation.** `normaliseGestureScores` rescales to a fixed-temperature softmax over
[0, 1000]. The raw score is a log-probability sum with no fixed scale — two decodes are not
comparable on it, and a confidence, a threshold or a blend all need one. Only `decodeGesture`
calls it; tap-typing's candidates are never rescaled.

**The radial menu.** `SwipeRadialController` holds what a paused swipe's ring is doing, as pure
geometry — a ring of alternatives around the finger plus a separate centre Cancel target.
`RadialSuggestionMenuView` draws it.

---

## Quick actions

`QuickAction` (`:data`) is an enum of 21 actions, each with a stable `id` so that a bar
configured by a newer build **opens** rather than fails on an older one (`fromIds` drops unknown
ids). `DEFAULT` is the five that answer *"I want that text somewhere"*:
`COPY_PREVIOUS_WORD`, `COPY_ALL`, `PASTE`, `CLIPBOARD_HISTORY`, `SELECT_ALL`.

The rest cover cursor movement (`CURSOR_START/END/LEFT/RIGHT`), selection (`SELECT_WORD`,
`SELECT_ALL`), editing (`CUT`, `DELETE_WORD`, `NEWLINE`, and `TIMESTAMP`, the date and time in the pattern the
`timestampPattern` preference gives it), case (`CAPITAL` flips the current
word's first letter and leaves the cursor alone; `NORMALISE` capitalises every sentence in the
field and changes nothing else), history (`UNDO`/`REDO` step through what *this keyboard* did to
the field this session), and `COMPOSE` — a draft box the application cannot see, seeded from the
selection.

`NEWLINE` is worth a button because in a messaging app the return key sends the message, and the
gesture for "new line without sending" is different in every one of them.

### Macros

`macroEligible` decides whether an action may be one step of a `CustomQuickAction`. A macro runs
its steps back to back with nothing shown in between, so a step must be a plain edit that
finishes within the tap that started it. Four are excluded:

- `CLIPBOARD_HISTORY` opens a panel and only acts once something is picked, which a macro cannot
  wait for;
- `SWITCH_LAYOUT`, `SETTINGS` and `COMPOSE` leave the field for another IME subtype or another
  Activity.

Everything else reads or writes through `InputConnection` alone.

### The view

`QuickActionsView` is drawn, not composed, like everything else in the keyboard process: icons
are vector drawables loaded once and drawn into bounds computed at layout, so the draw path sets
no state and allocates nothing. Two shapes — open (the whole row) and collapsed (one button that
opens it and closes again as soon as an action is chosen), because the alternative is a row
costing height on every screen for a button pressed twice a day.

---

## The dictionary pipeline

Nothing here is learned or trained. `.bkd` packs are **build output and are not committed** — a
Gradle task (`BuildDictionaries` in `keyboard/build.gradle.kts`) runs the same `build_dict.py`
the tests use, so a pack in an APK is always exactly what the committed list compiles to.

```
corpus ──make_pack.py──▶ dictionaries/<tag>.tsv  ──build_dict.py──▶ assets/dict/<tag>.bkd
                         dictionaries/<tag>.ngrams
```

`dictionaries/<tag>.tsv` is `word<TAB>frequency[<TAB>name]`. The third column is the proper-noun
flag. The corpora themselves are not in the repository, so tools that refine a built list edit
the `.tsv` **in place** and `build_dict.py` recompiles.

A pack (format version 6, `bkd_format.hpp`) holds the double-array trie over folded keys, one
row per spelling, the pairs as a **successor index** -- for every word and for the sentence
start, the words that followed it, sorted by index, each with its quantised conditional
log-probability, five bytes a pair -- and the triples as a **continuation index** hung off the
pairs the same way: for every pair, by its position in the successor index, the words that
followed it, five bytes a triple. A pair lookup is a binary search in one list, a triple lookup
is that search and one more inside the pair's own list; the next-word search walks the list.

### The tools

| Tool | What it does |
|---|---|
| `build_dict.py` | Compiles a word list + n-grams into `.bkd`. `--selftest` proves it still agrees with `bkd_format.hpp`, which is the authority on the layout. |
| `make_pack.py` | Counts a corpus into a word list; merges names; holds the name guards (`name_allowed`, `NAME_EVIDENCE_TIERS`, `NAME_ADD_MIN_USES`, `UNTAGGED_FREQUENT_RANK`). |
| `make_names.py` | Asks Wikidata for names. `--kind persons` (given/family, counted by people) and `--kind entities` (companies, countries, islands, counted by Wikipedia sitelinks). |
| `merge_names.py` | Merges a name list into a built `.tsv` in place, reusing `make_pack.py`'s guards **by import**. |
| `flag_names.py` | Flags proper nouns already in a pack's ordinary rows, by case asymmetry. |
| `make_ordinary.py` | Which corpus words are lower-case headwords of the spelling dictionary. |
| `build_pos.py` | Treebank tags and transition matrices → `dictionaries/<tag>.pos`. |
| `classify_wordlist.py` | Keeps a bundled word list to the rows its language's own evidence supports: spelling dictionaries in three case forms, the stock keyboard's word lists, subtitle frequencies, how a news corpus writes the word, the treebank, the name flag, corpus pairs; against twins missing their diacritics, typos, other languages' words and short noise, in two tiers by rank. Applied to all six lists (`docs/dictionaries.md`, "Which rows a list keeps"). |
| `make_twin_corpus.py` | Writes the twin and plain autocorrect corpora from `classify_wordlist.py`'s review files. |
| `make_doubled_corpus.py`, `make_firstletter_corpus.py`, `make_midtypo_corpus.py`, `make_unknown_corpus.py`, `make_accent_corpus.py` | The generated autocorrect corpora under `native-tests/data`, each from a seed. |
| `drop_foreign.py` | Removes another language's vocabulary that a crawled corpus quoted. |
| `drop_misspellings.py` | Review-only, multi-oracle. Review-only because a rare surname and a misspelling are the same shape in this data. |
| `make_contractions.py` | The apostrophe maps. |
| `merge_apostrophes.py` | One row per word whatever apostrophe the corpus wrote: every apostrophe becomes the plain one, and the rows that then agree add up, in the word list, the n-grams, the grammar and the hand lists. |
| `fold_diacritic_noise.py` | Romanian has three ways to write the same accented letter. |
| `build_emoji.py`, `gen_keys.py`, `gen_settings_index.py`, `extract_strings.py`, `inject_strings.py` | Emoji palette, key codes, the settings search index, i18n catalogue round-trip. |
| `gesture_replay.py`, `tcn_replay.py`, `swipe_model/` | Swipe measurement and training. |

Every tool is **standard library only**, deliberately: they run in CI, on a laptop, and one day
inside the settings process through the same native code. A dependency here would be one the
project cannot actually check.

### Naming words

Three questions, three different oracles, and the rule is that each is used only where it carries
information:

- **The treebank** (`.pos`) tags a word NN or NE. Reliable where it has met the word. It tags
  `president`, `states`, `united` and `court` as proper nouns, because they occur inside
  multi-word names — so flagging on that tag alone would be worse than the gap it fills.
- **The spell checker** answers "is this an ordinary lower-case word". `flag_names.py` uses the
  *asymmetry* (refused lower, accepted capitalised) as a name signal; `merge_names.py` uses plain
  acceptance as a veto.
- **Case conventions differ**, and both rules inherit that for free. English flags `friday` and
  `april`, Romanian does not, because Romanian does not capitalise them — nothing encodes a rule
  about weekdays.

German is the language where the spell-checker rule breaks: it capitalises every noun, so
hunspell refuses `haus`, `jahr` and `panik` in lower case exactly as it refuses a name.
`flag_names.py` refuses German outright (`CAPITALISES_EVERY_NOUN`); `merge_names.py` instead asks
**every** shipped language's dictionary, which is what still works there.

`merge_names.py` asks a deliberately different question of each channel:

- **Flagging** an existing corpus word changes how something the user already types behaves, so
  it takes the strictest test: ordinary in *any* shipped language ⇒ never flagged. Measured, 977
  words across the six languages are ordinary somewhere (9% of English, 27% of German), so a
  per-language rule would leave a thousand-word hand list to curate.
- **Adding** a word the corpus never wrote down asks only that language's own dictionary.
  Every-language would be wrong here — `chile` is a pepper in English, `argentina` is "silvery"
  in Italian, `ecuador` is the equator in Spanish — costing 16 of 25 country names against none.

The cost is named rather than hidden: a word that is both ordinary somewhere and a real name here
is refused, so `amazon`, `intel`, `shell`, `orange`, `sky` and German `island` (Iceland) do not
gain the flag. They stay as they were. The trade is a name not gained against an ordinary word
wrongly capitalised mid-sentence, and the second is the worse keyboard.

Two things the entity pipeline deliberately does **not** do, both measured first:

- **Multi-word labels are refused, not split.** Splitting reaches `paribas` inside "BNP Paribas",
  but it makes a candidate of every ordinary word inside an organisation's name: the packs grew
  **140%** and it flagged `united`, `congress`, `museum` and Romanian `tău` ("your").
- **All-upper-case labels are skipped.** The flag is one bit and the spelling beside it is
  lower-cased, so the most it can produce for `bbc` is "Bbc" — a more visible wrong than the
  uncapitalised word.

### Measurement, not opinion

`native-tests/suggest_eval.cpp` runs a corpus (`native-tests/data/suggest_en.tsv`) and prints
rank-1 accuracy, top-3 and mean rank. It **does not assert and is not a test**: `borderkeys_tests`
says whether the engine is correct, this says how good its answers are — a number that moves
rather than a line that passes. `--explain <typed> <candidate>` decomposes one score into its
terms, which is the question every scoring change starts with and the one a ranked list cannot
answer.

A harness that forgets `setKeyGeometry` measures prefix completion and reports it as the whole
engine. Current baseline: **71.9% first, mean rank 1.39.**

---

## Invariants

Things that will silently break if not respected.

- **No hardcoded user-visible strings.** Everything lives in the `:i18n` JSON catalogues, six
  languages. `extract_strings.py` / `inject_strings.py` round-trip them.
- **The draw path allocates nothing.** `KeyboardCanvasView` resolves touches arithmetically;
  there are no child views per key. `InlineSuggestionsHostView` is the one place framework
  `View`s are hosted, and it has no alternative.
- **`Candidate` is twelve bytes of plain data** crossing JNI on a path that may not allocate. Do
  not add fields to it; `continuesTyped` recognising a completion from text rather than a flag is
  this rule in action.
- **`bkd_format.hpp` is the authority** on the pack layout. `build_dict.py --selftest` is what
  proves the Python still agrees with it.
- **`BundledDictionaries.kt`'s word counts and byte sizes are load-bearing.** `repairBundledPacks`
  treats a pack whose recorded numbers differ as stale and re-copies it on every start. Read them
  from the compiled headers; never estimate.
- **Packs are build output**, never committed.
- **The engine is single-writer by construction** — a bump allocator, no locks — so it needs one
  thread that is always the same thread. That is why `PredictionEngine` owns a dedicated
  `HandlerThread` rather than using `Dispatchers.Default`.
- **Two privacy checks**, not one. See [the gates](#privacy-gates).
- **The typing package holds no Android class.** `com.borderkeys.typing` takes only the
  constants of `KeyEvent` and `EditorInfo`; the field, the engine, the views and the database
  are interfaces. That is what lets the JVM rig type through the real flow.
- **Flows never call each other.** Each talks only to `TypingOrchestrator`.
- **Raw taps never leave memory.** Only per-key totals are written, and only where
  `heatmapAllowed` holds.
