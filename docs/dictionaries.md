<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Building a real dictionary

A language pack is a word list with frequencies, plus the pairs those words appear in, compiled
into the `.bkd` format. Nothing about it is learned or trained. The whole quality of the
keyboard's suggestions is the quality of those counts.

The six dictionaries that ship with the application (`en_US`, `ro_RO`, `de_DE`, `es_ES`, `fr_FR`,
`it_IT`) are built from combined Wortschatz Leipzig corpora (wikipedia, news, and newscrawl
together, not one genre alone -- a single-genre cut was found to be missing everyday words) via
`tools/make_pack.py --corpus`, 90,000 to 120,000 words each. What each one actually used, and its
licence, is recorded in `docs/licensing.md` section 2 rather than here, so there is one place to
check rather than two that can disagree. This document is about replacing or adding to them, not
about what currently ships.

## The one command

```
python3 tools/make_pack.py --corpus text.txt --tag ro-RO --out ro_RO.bkd
```

Point it at plain text in the language and it counts the words, the pairs and the triples in one
pass, applies cutoffs, and compiles a pack. Nothing is downloaded by the tool: what goes into a
dictionary is a decision made by whoever runs it.

## Why a corpus beats a frequency list

The engine backs off the usual way: if it knows a bigram it uses `P(w2|w1)`, otherwise
`0.4 × P(w2)`. Those two numbers are only comparable when they were counted from the same body
of text.

This is not theoretical. Building a pack with word frequencies in the hundreds of thousands and
hand-written bigram counts in the hundreds made every bigram look *less* likely than backing off
to the unigram, so the context model produced nothing at all — the strip offered the language's
most frequent words no matter what had just been typed. Counting both from one corpus is what
makes `--corpus` the recommended path rather than merely the convenient one.

## Where the data is

None of these is bundled and none is fetched automatically. Check the terms yourself before
committing a pack built from any of them, and record what you used in `docs/licensing.md`
section 2 — that file exists so a licence question has an answer written down rather than
remembered.

| Source | What it gives | Licence as published |
|---|---|---|
| **Wortschatz Leipzig Corpora** | Sentence collections and word lists for 250+ languages, in per-language downloads sized from 10K to 1M sentences | CC BY |
| **OpenSubtitles frequency lists** (`hermitdave/FrequencyWords`) | `word count` lists for ~60 languages, from subtitle corpora — conversational, which is closer to what people type on a phone than prose is | CC BY-SA |
| **Wikipedia database dumps** | Everything, in every language; needs extracting from wikitext first | CC BY-SA |
| **OSCAR / CC-100** | Web-crawled plain text per language, large | Per-corpus terms, check each |
| **Hunspell dictionaries** (`ro_RO`, `en_US`, …) | Spelling stems plus affix rules. Coverage, no frequencies; expand with `unmunch` and use `--wordlist` | GPL/LGPL/MPL, varies by language |
| **Tatoeba** | Sentences with translations, many languages | CC BY 2.0 FR |

A conversational corpus is usually the better choice for a keyboard. Prose corpora over-weight
words people read and under-weight the ones they write.

## Recipes

Plain text, the best case:

```
python3 tools/make_pack.py --corpus ro_sentences.txt --tag ro-RO --out ro_RO.bkd
```

A frequency list with no context — good corrections, poor prediction:

```
python3 tools/make_pack.py --frequencies ro_50k.txt --tag ro-RO --out ro_RO.bkd
```

A frequency list and n-gram counts from the *same* corpus:

```
python3 tools/make_pack.py --frequencies ro_words.txt --ngram-counts ro_bigrams.txt \
    --tag ro-RO --out ro_RO.bkd
```

A Hunspell dictionary, for coverage when nothing better exists:

```
unmunch ro_RO.dic ro_RO.aff > ro_words.txt
python3 tools/make_pack.py --wordlist ro_words.txt --tag ro-RO --out ro_RO.bkd
```

## Size

`--max-words` defaults to 120,000 and `--min-count` to 3. A 120,000-word pack with 200,000
n-grams is a few megabytes -- what the six bundled dictionaries actually are, one to a few
megabytes each. Larger than that, built from a bigger or less aggressively cut corpus, belongs on
the device as an imported pack rather than bundled into every install of the application.

## Names

A word list built the way above never contains a proper name unless its lower-case spelling
happens to coincide with an ordinary word (`make_pack.py`'s tokeniser lower-cases everything on
the way in) -- and even then, nothing marks it as a name, so the keyboard has no way to offer it
capitalised outside of sentence-start or shift-state, which is not how a name should behave
mid-sentence.

`tools/make_names.py` builds a separate, per-language name list from Wikidata (CC0 -- see
`docs/licensing.md` section 2 for why this one source gets its own entry rather than folding into
the corpus table above: it is a *classification* source, not a *frequency* one, and its entries
carry a flat synthetic frequency rather than a real corpus count):

```
python3 tools/make_names.py --language ro --out names_ro.tsv
python3 tools/make_pack.py --corpus ro_sentences.txt --names names_ro.tsv \
    --tag ro-RO --out ro_RO.bkd
```

`--names` merges the list in *after* `--max-words`/`--min-count` have already cut the corpus down
-- a name is not competing for one of the corpus's own ranked slots, and its absence from a
frequency-based cut is not evidence it is rare, only that it is a name. Every merged entry is
written into the compiled `.tsv` with a third column, `name`, which `build_dict.py` reads as
`WORD_FLAG_PROPER_NOUN` and the running keyboard reads back as "always capitalise this,
regardless of typed case or shift state" (`AutoCorrection.matchCase` in
`keyboard/src/main/java/com/borderkeys/ime/AutoCorrection.kt`).

Pass the language's grammar too, when there is one (`--grammar dictionaries/ro_RO.pos`): Wikidata
knows enough real people called "In", "To", "Said" and "Will" for those to pass the family-name
threshold, and flagged they were capitalised every time anyone typed them. The treebank behind
the `.pos` file has already decided what each ordinary word is, so a name it tags as a
preposition, a verb or an adjective is not flagged; a word it has never seen keeps the flag,
because absence from a treebank is not evidence of anything. Family names in particular need a
lower threshold than the default 50 to reach past the handful every language shares
(`--min-family-uses 5` is what the Romanian list was built with -- "Sadoveanu" has nowhere near
fifty Wikidata people, and neither does most of the country's surnames). A list fetched that low
is passed as `--names-flag-only`: a name the corpus already has gains the flag, one it does not
have is left out, because a hundred thousand surnames at a flat frequency would outrank the
corpus's own tail. What the treebank does not know it cannot refuse -- "asa", "cat", "tu", "cui"
are all somebody's name -- so `dictionaries/<tag>.names-exclude` lists those by hand, one per
line, and `--names-exclude` reads it.

Coverage is genuinely uneven across languages -- Wikidata's own editor base skews toward
English/German/French/Spanish, and Romanian will come back with fewer names than those do. That
is `make_names.py` reporting the real state of a free source, not a bug to chase; its own printed
count is the number to look at before deciding a language's list is worth shipping.

All six bundled lists have been through this: the names merged in, the treebank guard and the
exclusion lists applied, and the Romanian family names refetched at the lower threshold. The
committed `dictionaries/*.tsv` are the result, and the keyboard's own rule for using the flag
is one more check: with several packs active, every pack that knows a word has to agree it is a
name before it is capitalised, so a name in one language's list cannot capitalise an ordinary
word of another's.

## Shipping one with the application

Drop the word list in `dictionaries/` as `<tag>.tsv` — with `_` where the tag has `-` — and the
Gradle task in `keyboard/build.gradle.kts` compiles it into the APK's assets on every build. Add
an entry to `BundledDictionaries.ALL` so the Languages screen offers it. No binary is committed:
the pack in an APK is always what the committed list compiles to.
