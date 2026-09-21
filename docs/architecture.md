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

Six Gradle modules. The split is not cosmetic: it is what keeps the typing path free of things
that allocate, and what lets most of the logic be tested on a JVM with no device attached.

| Module | What lives there |
|---|---|
| `:keyboard` | The IME itself. Kotlin for the input method, views and policy; C++ for the engine, the trie, the n-gram model, the personal model and both swipe decoders. |
| `:data` | Room + SQLCipher. The durable copy of everything learned, the clipboard, language packs, themes, drafts. |
| `:settings` | The settings application, Jetpack Compose. Never on the typing path. |
| `:i18n` | Every string the user can read, as JSON catalogues in six languages. No hardcoded text anywhere else. |
| `:assist` | The optional on-device assistant, vendoring `llama.cpp`. Entirely separate from prediction. |
| `:app` | The two flavours, `core` and `plus`, and nothing else. |

`core` and `plus` are compile-time flavours, not runtime switches. `BORDERKEYS_NEURAL_SWIPE` is
the clearest case: in a `core` build the neural decoder, its weights and every branch that tests
for it are absent from the binary rather than disabled in it.

### Inside `:keyboard`

```
ime/          the input method, the views, and the policy objects
predict/      the Kotlin side of the engine: threading, queueing, the learning buffer
cpp/          the engine
cpp/gesture/  both swipe decoders
```

The policy objects in `ime/` — `AutoCorrection`, `AutoShift`, `LanguageSwitchCorrector`,
`RunningText`, `Contractions`, `SentenceCase`, `TextShortcuts`, `HabitSpace`, `PrivateMode` —
hold no `InputConnection` and make no native calls. That is deliberate and it is what makes them
testable: they take strings and return decisions.

---

## The typing path

A keystroke reaches `InputConnection` in about two milliseconds. Everything below is arranged
around that.

```
touch
  └─ KeyboardCanvasView          resolves the key arithmetically (no child views)
      └─ BorderKeysService       updates the composing text, commits to the field
          └─ PredictionEngine    posts a request to its own HandlerThread
              └─ PredictionRequestQueue   keeps exactly one pending request
                  └─ NativePredictor      JNI
                      └─ Engine::suggest  the search
          ◀── result posted back to the main looper, tagged with the query it answers
```

Three rules hold this together, all in `predict/PredictionEngine.kt`:

- **The UI thread never blocks on JNI.** There is no path from a touch event into the engine.
- **The handle cannot outlive the engine.** Every native call goes through `withHandle`, under
  one lock, zeroed on release.
- **Only the newest request matters.** `PredictionRequestQueue` is exactly one deep, with a
  generation number; a slow answer that arrives after a newer one is dropped rather than shown.
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
   correction.
2. `refreshWeights()`, `resolveContext()` — per-request, per-pack, once rather than per candidate.
3. Choose which packs to search: the dominant pack when one is decided, otherwise the heaviest
   when language lock is strict, otherwise all of them.
4. `searchPacks()` → per pack: `collectEndpoints()` walks the fuzzy neighbourhood, `collectWords()`
   descends from each endpoint, `searchFrequentWithPrefix()` covers short prefixes the budget
   cannot cross.
5. `searchUserModel()` — the personal trie.
6. Empty-prefix case only: `searchUserSuccessors()`, `searchUserPhrases()`.
7. Fallback: if nothing at all was found, one wider pass at `kFallbackEditCost`.
8. Drain the corrections heap, then the main heap, then apply the completion cap.

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

### Edit costs — `engine.cpp`

| Constant | Value | Why |
|---|---|---|
| `kEditPenalty` | **40.0** | The multiplier on every edit. Must clear `ln(worst frequency ratio)` so that one transposition outweighs the gap between the commonest and rarest word in a pack. Floored at 15 by a `static_assert`. |
| `kInsertCost` | 0.85 | A dropped letter is a commoner slip than a wrong key, so just under a full neighbour substitution. |
| `kDeleteCost` | 0.85 | As above. |
| `kTransposeCost` | 0.80 | One gesture out of order, not two errors. Deliberately only *slightly* cheaper: at the old 0.65 this priced two equally common slips as though one were a thousand times likelier, which let `acm` → `cam` crowd out `acum`. |
| `kApostropheInsertCost` | **0.02** | A convention dropped, not a key missed. This is what makes `cant` → `can't` reachable. At `kEditPenalty` 40 it costs 0.8 points — enough that an exactly-spelled word still wins, little enough that a commoner contraction wins on frequency. |
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

### Personal model terms

| Constant | Value | Meaning |
|---|---|---|
| `kUserOnlyLogProb` | −8.0 | The log-probability for a word that exists *only* in the personal dictionary. Deliberately pessimistic and fixed: the model's own totals cannot be used, because a word confirmed 40 times out of 50 would be three quarters of that distribution — likelier than `the`. |
| `kMaxUserBoost` | 3.0 | The ceiling on what personal evidence adds. |
| `kMinPersonalEvidence` | 2.0 | Effective counts (raw × learning speed) before a learned word is *offered*. At the cautious 0.35 multiplier that is ~6 repetitions; at the immediate 3.0 the first use clears it. The word is learned and listed the whole time — this governs only the strip. |
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
   7.81) from junk (`cr` 3.78, `eh` 3.32) cleanly.
3. **`kMinPersonalEvidence`** — a learned word needs a second use before it is offered.

### In `AutoCorrection.correctionFor`

Returns null — commit what was typed — in every case where applying a correction would be an
argument rather than a correction:

- the word is shorter than `minimumLength`, **unless** the only difference is a diacritic
  (`in` → `în` is two real words that differ by an accent, not a coin toss);
- **the dictionaries spell the word** — it is a word, and a keyboard does not correct words. The
  engine ranks by likelihood, so a real but uncommon word loses to a longer common one and was
  being replaced by it;
- the suggestion is what was typed, or what was typed in a different case;
- `typed != suggestionQuery` — a stale answer, see [the typing path](#the-typing-path);
- `editDistance(stripDiacritics(typed), stripDiacritics(suggestion)) > maxEdits` — a ceiling on
  top of the engine's ranking, which only ever decides *which* candidate is first, never whether
  it is close enough to be a correction at all. `snobul` was being replaced by `noul`;
- **the proper-noun rule** — a name corrects only its own letters. `maria` may become `Maria` and
  `laurentiu` → `Laurențiu`, but `everyone` must never become `Everton` nor `thanks` `Hanks`.

### Cross-pack agreement

`packsAgreeProperNoun()` — with several packs active, **every** pack that knows a word must agree
it is a name before it is capitalised. `Si` is a family name to the English list and `și` (typed
without its accent) is "and" to the Romanian one.

### Privacy gates

Two independent checks, which is the right number for a rule whose failure mode is a password in
the personal dictionary:

- `PrivateMode` decides whether the field must be forgotten entirely; the service refuses to call
  `LearningBuffer.record`;
- `LearningBuffer.enabled` is false regardless.

`setPersonalModelEnabled(false)` additionally stops the model being *consulted* — what this
device learned from its owner must not be offered back into a field that asked to be forgotten.
The model stays loaded and untouched.

`OffensiveWords` keeps its list out of suggestions, corrections and learning while the switch is
on. `LearningBuffer.setBlockedWords` also holds every word the user has refused; entries are
folded through `WordFold`, so `Shit` at a sentence start is the same refusal as `shit`.

---

## The learning path

**There is no model being fine-tuned and no gradient anywhere.** A count goes up when the user
picks a word that was not the top suggestion. That is the entire learning rule — and it is why
the keyboard does not degrade over time the way a model trained on its own output does: a count
cannot learn a typo unless the user deliberately chose the typo.

```
user confirms a word  (picks it from the strip, or types a delimiter after it)
  └─ LearningBuffer            in-memory, debounced
      └─ Room (:data)          the one durable copy, SQLCipher
      └─ UserModel (C++)       rebuilt from Room at every start
```

**Why the buffer exists.** A key press has two milliseconds to reach `InputConnection`. An
INSERT is a transaction, a disk write and an encryption pass. So confirmations accumulate in
memory and flush when the buffer is old enough, full enough, or the input session ends
(`onFinishInput`). `LearningBuffer` is free of Android and of coroutines — a counter with a clock
passed in — so the debounce and the eviction are testable on the JVM.

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

`setLanguageLock(minimum, strict)` controls how one-sided the evidence must be before other
languages stop being searched. When a dominant pack turns out to have nothing for the current
word, the search **re-runs unrestricted** — the detector is a guess about the sentence, not a
verdict on the next word.

### Which pack a request is restricted to

Three questions, in order, and only the first two are about this request:

```cpp
const int restrictTo = (dominantPack_ >= 0)   ? dominantPack_
                     : (preferredPack_ >= 0)  ? preferredPack_
                     : (strictLanguage_ ? heaviestPack() : -1);   // -1 = every pack
```

**The preferred pack sits below the detected one, never above it**, and that is the whole meaning
of the word. `setPreferredLanguage(tag)` says where detection *starts*; it is outranked the moment
the evidence decides otherwise, and a word it does not hold still falls through to every other
pack via the empty-heap retry. Set Romanian and write four English words and you get English,
because by then it is no longer a guess.

It is **not** a term in the score, and nothing in the scoring path reads it. That distinction is
the reason it exists: a pack's `weight` *is* a scoring term (`packWeightLog`), so using weight to
say "start here" also biased every one of that language's words for ever, including after another
language had become dominant. Weight is now only what it says it is.

The preference is stored as a **tag** rather than a slot index, because `setActiveLanguages` opens
and closes packs and an index does not survive that; `resolvePreferredPack()` re-resolves it there
and requires the pack to be *active*, not merely open. An empty tag, or one naming a pack that is
absent or switched off, resolves to −1 and behaves as no preference — which is the default, and
restores exactly the behaviour that shipped before the setting existed.

`resetLanguageEvidence()` forgets the verdict so a new field decides for itself; the service calls
it on field start **only when a preferred language is set**, since without one, inheriting the
previous field's verdict is what the keyboard has always done.

### Backward — `LanguageSwitchCorrector`

The forward pass only ever runs forward: a run of Romanian evidence at the start of a message is
never revisited once the next several words turn out to be exclusively English. This is that
revisit, and only that.

It holds no `InputConnection` and makes no native calls, so it tests on plain data. Reading live
text and asking the engine for a pack-specific candidate (`Engine::candidateForPack` — the one
place a caller names a pack explicitly rather than accepting `dominantPack`'s verdict) both
happen in `BorderKeysService`.

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
weights by value, and the preference is off by default, so keeping it resident for a feature
nobody asked for is the wrong trade. The next `loadSwipeWeights` rebuilds it.

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
`SELECT_ALL`), editing (`CUT`, `DELETE_WORD`, `NEWLINE`), case (`CAPITAL` flips the current
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
| `drop_foreign.py` | Removes another language's vocabulary that a crawled corpus quoted. |
| `drop_misspellings.py` | Review-only, multi-oracle. Review-only because a rare surname and a misspelling are the same shape in this data. |
| `make_contractions.py` | The apostrophe maps. |
| `fold_diacritic_noise.py` | Romanian has three ways to write the same accented letter. |
| `build_emoji.py`, `gen_keys.py`, `extract_strings.py`, `inject_strings.py` | Emoji palette, key codes, i18n catalogue round-trip. |
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
- **Two independent privacy checks**, not one. See [the gates](#the-gates).
