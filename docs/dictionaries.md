<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Building a real dictionary

A language pack is a word list with frequencies, plus the pairs those words appear in, compiled
into the `.bkd` format. Nothing about it is learned or trained. The whole quality of the
keyboard's suggestions is the quality of those counts.

The two dictionaries that ship with the application are **starters**: about a thousand words
each, written in this repository so their licence is this project's licence. They are enough to
type with and they are not enough to be good. This is how to replace them.

## The one command

```
python3 tools/make_pack.py --corpus text.txt --tag ro-RO \
    --out keyboard/src/main/assets/dict/ro_RO.bkd
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
n-grams is a few megabytes, which is fine for a pack the user imports and too much for several
bundled in an APK. The bundled starters are small on purpose; anything larger belongs on the
device rather than in the download.

## Shipping one with the application

Drop the word list in `dictionaries/` as `<tag>.tsv` — with `_` where the tag has `-` — and the
Gradle task in `keyboard/build.gradle.kts` compiles it into the APK's assets on every build. Add
an entry to `BundledDictionaries.ALL` so the Languages screen offers it. No binary is committed:
the pack in an APK is always what the committed list compiles to.
