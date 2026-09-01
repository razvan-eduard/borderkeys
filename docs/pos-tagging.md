<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Grammar in the prediction score, measured

A part-of-speech tag per word plus a tag transition matrix, so the engine can prefer a word that
fits grammatically where it has no statistical evidence. This records what it is worth, because
the number is smaller than it looks and the reason is interesting.

## Where it can help at all

Nowhere the n-gram model has evidence. Measured on the Romanian pack, after "la" the engine
already predicts `un, o, ora, fel, începutul` — determiners and nouns. The bigram encodes the
same grammar a tag class would and encodes it more precisely: it knows what follows *"la"*, not
merely what follows *a preposition*.

What it does not cover is a previous word with no stored continuation, where the engine falls
back to raw unigram frequency and offers `de, în, a, la, și` whatever came before. After raising
the packs to 80,000 n-grams that is **7.7% of the words someone writes**. Grammar is the only
thing that can help there, because no quantity of n-grams reaches a pair the corpus never saw.

## What it is worth

Held out on `ro_rrt-ud-test`, restricted to those blind positions, ranking the 2,000 most
frequent words:

| grammar weight | top 1 | top 2 | top 3 | top 5 |
|---|---|---|---|---|
| 0 (today) | 8.6% | 16.1% | 22.6% | 33.3% |
| 0.5 | 10.1% | 18.4% | 23.1% | 31.8% |
| **0.75** | **11.0%** | **19.3%** | 23.1% | 31.2% |
| 1.5 | 11.1% | 19.3% | 22.8% | 31.4% |

The win is concentrated in the first two slots — +28% relative on the first suggestion — and it
is paid for out of the fifth. That is the right trade for a keyboard: the first chip is what
people tap and what "apply on space" would commit, and nobody reads the fifth.

In absolute terms it is 2.4 points on 7.7% of positions, so about **0.2 points of overall
next-word accuracy**. Small, and worth stating plainly.

## Which tagset

| tagset | top 1 on blind positions |
|---|---|
| none | 8.6% |
| UPOS, 16 tags | 8.6% |
| MULTEXT-East, 476 tags | 11.0% |

Coarse tags are worthless here. "Noun follows preposition" is already implied by frequency;
what pays is `Ncfsry` — common noun, feminine, singular, definite — because agreement is what
Romanian grammar actually constrains.

255 tags cover 99.4% of tokens, so one byte per word is enough with the tail sharing a slot.

## The ambiguity that a single byte discards

6.9% of word types carry more than one tag, but those types are common: **30.6% of the words
someone writes are ambiguous**. "la" is a preposition and a noun, "mare" an adjective and a
noun. One tag per word type means taking the most frequent reading and being confidently wrong
on the rest.

Storing a distribution rather than a tag was tried in the evaluation and changed nothing:
marginalising over a word's tags scored within 0.1 points of taking its most frequent one. The
reason is that the score takes a maximum over candidate tags anyway, so the dominant tag wins
the maximum either way. A single byte is therefore the right size — not because ambiguity does
not matter, but because this particular use of the tag is insensitive to it.

## Cost

Per language: 18 KB of tags plus a 255×255 matrix at one byte each, 65 KB. **81 KB**, against a
2.1 MB pack. The lookup is two array reads and an add.

## Licensing

The tags come from UD Romanian RRT, which is CC BY-SA 4.0. Creative Commons declares a one-way
compatibility from CC BY-SA 4.0 to GPL-3.0, so a GPL-3.0-or-later application may incorporate
it under the GPL, with attribution recorded in `docs/licensing.md`.

No tagger is involved. A treebank carries a tag for every token *in context*, which is what
makes the ambiguity above measurable at all; a tagger would give one answer per word type and
hide it. It is also one less dependency: no runtime, no model file, no version to pin.

## What is not yet known

39% of the pack's vocabulary — 6,978 of 18,000 words — gets no tag, because the treebank never
contains them. Those are the rare words, and the blind positions are exactly where the previous
word is rare, so grammar may be unavailable where it is most wanted. The measurement above
already includes this dilution: a position whose previous word has no tag falls back to
frequency and is counted as such.

## How it is built

`tools/build_pos.py` reads one or more `.conllu` files and writes a JSON of tags and a matrix,
which `tools/build_dict.py --grammar` folds into the pack. The Gradle task passes it whenever a
`dictionaries/{lang}.pos` exists, so a language can be added long before anyone finds a treebank
for it — a pack without grammar declares zero tags, carries two empty sections, and is scored
exactly as packs were before the sections existed.

Which tagset is used is decided per treebank. Where a treebank fills its XPOS column that is a
tagset a linguist designed for that language and it measured better — 11.0% against 10.1% on
Romanian. Where it does not, and French GSD and Spanish GSD do not, the tag is composed from
UPOS plus the features agreement is expressed through: gender, number, case, person, verb form,
mood, definiteness, pronoun type. Picking one scheme for all six would have meant either
French getting a single tag or Romanian giving up its own.

| language | tags | source |
|---|---|---|
| Romanian | 255 | RRT's MULTEXT-East |
| English | 49 | EWT's Penn tags |
| German | 52 | GSD's STTS |
| Italian | 38 | ISDT |
| Spanish | 255 | composed |
| French | 212 | composed |

## Where it applies

Only in the branch of `Engine::contextLogProb` that has already fallen through both the trigram
and the bigram. Everywhere above it a stored n-gram exists, and that is better evidence about
this pair than a tag class is; adding the term there would be a worse signal arguing with a
better one. The weight is 0.75, from the sweep above.

## The format change

Version 2 of `.bkd`. Two sections were added and the header grew from 256 to 320 bytes to hold
their descriptors, so a version 1 pack is refused rather than read with the new fields zeroed —
every section offset in an old file means something different now. Bundled dictionaries are
compiled at build time and are unaffected; a pack someone imported by hand has to be imported
again, and the refusal says so.
