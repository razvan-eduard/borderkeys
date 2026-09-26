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
`tools/make_pack.py --corpus`, some 100,000 to 128,000 words each once the names are merged in.
What each one actually used, and its
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

## The alphabet a language is allowed

A word is kept only when every character **folds into that language's own alphabet**: `a`–`z`,
the letters its long-press overlay offers (`keyboard/src/main/assets/accents/<tag>.json`), and
the apostrophes and hyphen that live inside words. The keyboard is the thing that has to be able
to type the word, so the keyboard's own overlay is where the alphabet comes from; there is no
second list to drift.

Folded, not literal. `naïve` is an English word and no English overlay carries `ï`, but it folds
to `i` and is typed that way — as are `Bjørn` (`ø`→`o`) and `François` (`ç`→`c`). What is refused
is a character that folds to *itself* and is on no overlay: Greek `α`, Devanagari `ख`, the unit
superscript in `km²`, the ordinals `º` and `ª`, mojibake `ðÿ`, and Romanian written with the
wrong mark — `ȋn`, `sǎ` — which look right and are not.

A **bare letter** is kept only when it is a word of that language. One letter is a complete match
for the tokeniser, so every initial, list marker and table cell in the corpus became an entry.
The real ones are named in `tools/drop_unreachable.py`; nothing decides it automatically, since
Hunspell lists letters as headwords for spell-checking and the frequencies overlap.

Both rules run in `make_pack.py` as the corpus is counted, and
`tools/drop_unreachable.py <tsv>` applies them to a list that already exists. Removing 331 bare
letters and ~730 unreachable rows took **4.75 MB** off the six bundled packs — almost all of it
German, which had 73 folded code points in its alphabet and now has 30. A pack is indexed by its
own alphabet, so the words are not what cost the space.

`tools/drop_unreachable.py --selftest` fails if an overlay edit changes which letters a language
admits. Only `ß`, `æ` and `œ` can: every other accented letter folds to an unaccented twin, so
taking it off an overlay changes nothing.

## Size

`--max-words` defaults to 120,000 and `--min-count` to 3. The six bundled packs are 8.1 to 10.2 MB
each, the trie and its text a few megabytes of that and the pairs and triples the rest, five
bytes apiece in the successor index and the continuation index. Larger than that, built from a
bigger or less aggressively cut corpus, belongs on the device as an imported pack rather than
bundled into every install of the application.

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

A name list is only half of it, because a name and an ordinary word can be the same string:
"Dan" is a Romanian word and a first name, "Mai" is May and a Vietnamese name, "President" is
a real surname. Wikidata is right about all of them, and a keyboard that capitalised every
"mai" would be worse than one that missed "Sadoveanu". So `make_pack.py` decides, per word,
whether the flag may be applied, from four things it knows:

- **What the treebank says.** Pass the language's grammar (`--grammar dictionaries/ro_RO.pos`):
  a word the treebank behind it tags as a preposition, a verb or an adjective is never flagged,
  however many people are called that. The accent-stripped match is weighed, not applied
  blindly: "și" outranks "si" in the corpus and refuses it, but "măria" (41 uses) must not
  refuse "Maria" (7,149), so the ordinary word has to be at least as frequent as the name. A
  word among the 300 most frequent that the treebank has no tag for at all is one it splits
  before tagging ("del", "au", "zur" are multiword tokens in Universal Dependencies), and is
  refused too.
- **What the spelling dictionary says**, for the words the treebank never met. A treebank is
  small -- a few hundred thousand tokens -- and past its vocabulary the tiers below ask a
  single person of a rare word, which Wikidata has for "thunder", "needle" and "wage" alike:
  several thousand ordinary English words came out flagged that way. `tools/make_ordinary.py`
  runs the corpus words the name list touches through Hunspell and writes the ones that are a
  lower-case *headword* of the language's dictionary to `dictionaries/<tag>.names-ordinary`,
  read by `--names-ordinary`; a word on it is never flagged unless the treebank itself tags it
  as a name. The English dictionary is given beside every other language's, because a corpus
  of Romanian or German web text is full of English ("hot", "life", "service", "happy") that
  the language's own dictionary has never heard of and Wikidata has a family name for.
  Headwords only, on purpose: Hunspell also accepts what its affix rules can build,
  and half of Romania's surnames are an ordinary word with the article on the end ("Lupu",
  "Ciobanu", "Moraru"), which a rule that read "wages" as a form of "wage" refused wholesale.
  And the treebank's verdict outranks the dictionary's on purpose too: one that met "Dan" and
  "Ion" mostly as names knows more than a dictionary that also lists a martial-arts rank and a
  charged particle.
- **How many people carry the name.** `make_names.py` writes that count as a fourth column, and
  `make_pack.py` demands more of it the more common the word is in the corpus
  (`NAME_EVIDENCE_TIERS`): thousands of people for a word among the 300 most frequent, a few
  hundred up to rank 1,000, single digits past rank 3,000. Read off the Romanian data: real
  first names at ranks 300-1000 carry 400-800 people ("Mihai", "Vasile", "Iulia"), the words that
  must not be flagged carry under 200 ("satu", "tine"); past rank 1,000 a surname is a small
  family ("Trump" 82, "Năstase" 28), so the bar drops with the rank.
- **A hand-kept list.** What none of the above catches goes in
  `dictionaries/<tag>.names-exclude`, one word per line, read by `--names-exclude`. Two kinds
  of word land there: ones no source knows at all ("asa", "cat", "tu", "cui" are all somebody's
  name), and the treebank's own blind spot -- it keeps one tag per word, the one it used most,
  and a treebank of web text meets "Apple", "Orange" and "Hidden" (the company, the county, the
  valley) more often than the fruit, the colour and the adjective, so it calls them names and
  the dictionary's objection is overruled. "kingdom", "cloud", "tower", "saint", "parent",
  "truc", "borsa", "wild" and a couple of hundred more across the six languages are listed by
  hand for that reason, each after reading the full list of words both sources claim.

A name the corpus never wrote down is added at the flat frequency only with fifty people behind
it (`NAME_ADD_MIN_USES`), which keeps the additions to roughly a tenth of the corpus's own size;
the given-name tail alone is over a hundred thousand labels a language, most of them a handful
of people each. `--names-flag-only` is the same rule without the additions.

Two things the fetch itself has to get right. Wikidata types most given names as a *male*,
*female* or *unisex* given name rather than as the bare class, so a query for "given name"
alone finds the rarer items and misses "Maria" and "Laurențiu"; `make_names.py` asks for all
four classes. And family names need a lower floor than the 50 the first lists used
(`--min-family-uses 5`): "Sadoveanu" has nowhere near fifty Wikidata people, and neither does
most of the country's surnames -- the tiers above are what keeps that low floor safe.

A third thing, found on 2026-09-20 and worth knowing if you have an older fetch lying around.
Wikidata has moved language-neutral labels -- which is what a name usually is -- to the `mul`
language code, so a query asking only for `LANG(?label) = "ro"` now misses most of the answer:
given names went from 89,263 to 145,741 for English and from 36,077 to 92,555 for Romanian once
`mul` was accepted alongside the language's own code. `make_names.py` asks for both, and the
bundled lists have been re-fetched with it: between 3,000 and 6,200 rows a language that were
already in the corpus turned out to be somebody's name and had never been flagged.

That merge is `--flag-only`, the same distinction `make_pack.py --names-flag-only` draws. A
person-name list may flag a word the corpus already has but never add one it does not, because
a hundred thousand surnames at one flat frequency would outrank the corpus's own tail. Entity
lists are three orders of magnitude smaller and are added from.

Coverage is genuinely uneven across languages -- Wikidata's own editor base skews toward
English/German/French/Spanish, and Romanian will come back with fewer names than those do. That
is `make_names.py` reporting the real state of a free source, not a bug to chase; its own printed
count is the number to look at before deciding a language's list is worth shipping.

All six bundled lists have been through this, from a fetch of all four given-name classes with
the family floor at 5, the six `.names-ordinary` lists from the LibreOffice Hunspell
dictionaries (`docs/licensing.md` 2.1.1 names them), and the hand lists. Regenerating one
language, with `hunspell` on PATH and the `.dic`/`.aff` pair beside each other:

```
python3 tools/make_names.py --language ro --min-family-uses 5 --out names_ro.tsv
python3 tools/make_ordinary.py --words dictionaries/ro_RO.tsv --names names_ro.tsv \
    --dictionary /path/to/ro_RO /path/to/en_US --out dictionaries/ro_RO.names-ordinary
python3 tools/make_pack.py --frequencies ro_words.txt --names-flag-only names_ro.tsv \
    --grammar dictionaries/ro_RO.pos --names-ordinary dictionaries/ro_RO.names-ordinary \
    --names-exclude dictionaries/ro_RO.names-exclude --tag ro-RO --out ro_RO.bkd --keep-intermediate
```

### Names that are not people's names

Everything above asks Wikidata about persons, so companies, countries, islands and
organisations were never candidates and `paribas`, `ubisoft` and `bytedance` sat in the ordinary
vocabulary rows unflagged. `--kind entities` asks for those instead, counting how many
Wikipedias carry an article rather than how many people share a name:

```
python3 tools/make_names.py --language en --kind entities \
    --endpoint https://qlever.dev/api/wikidata --out entities_en.tsv
python3 tools/merge_names.py --tag en_US --names entities_en.tsv \
    --hunspell en_US=/path/en_US --hunspell en_US=/path/en_GB \
    --hunspell de_DE=/path/de_DE_frami --hunspell es_ES=/path/es_ES \
    --hunspell fr_FR=/path/fr --hunspell it_IT=/path/it_IT \
    --hunspell ro_RO=/path/ro_RO --report review_en.txt --apply
```

`merge_names.py` edits `dictionaries/<tag>.tsv` in place rather than rebuilding a pack, because
the corpora the lists were counted from are not in this repository and feeding a built list back
through `make_pack.py --frequencies` would drop every proper-noun flag it already carries. It
uses `make_pack.py`'s own guards, plus one of its own: **a word that is an ordinary lower-case
word in any language the project ships is never flagged**, whatever Wikidata says. That rule is
doing real work, because organisations are routinely named after ordinary words -- without it
English flags `zero`, `joy`, `guard` and `blues`, and Romanian flags `carantină` and `taur`.

Every language has to be asked, not just the list's own, and that is why the command above
passes all seven dictionaries. Languages borrow, and a borrowed word arrives in lower case
without becoming a headword in the borrower's dictionary: Romanian writes `live`, `punk` and
`rap`, Italian writes `blogger` and `ceo`, French writes `arena`. Measured across the six
bundled languages, 977 flagged words are ordinary *somewhere* -- 9% of English, 27% of German.
Asking one language leaves those as a thousand-word list to curate by hand; asking all of them
leaves two. German is the case that makes it necessary rather than merely tidy: it capitalises
every noun, so its own checker refuses `panik` and `pilot` in lower case exactly as it refuses a
name, and the other five are the only witnesses that still work there.

The cost is named rather than hidden: a word that is both an ordinary word somewhere and a real
name here is refused, so `amazon`, `intel`, `shell`, `orange`, `sky` and German `island`
(Iceland) do not gain the flag. They keep their corpus row and stay uncapitalised, which is what
they already did.

**Adding asks a narrower question.** Only the list's own language vetoes a word the corpus never
wrote down, because every-language would be wrong here: `chile` is a pepper in English,
`argentina` is "silvery" in Italian, `ecuador` is the equator in Spanish. The strict rule costs
16 of 25 country names, the narrow one costs none, and it costs nothing in safety -- a word the
corpus does not contain is not one this language's users are currently having capitalised out
from under them.

Two things this deliberately does not do. Multi-word labels are refused rather than split into
words: splitting reaches `paribas` in "BNP Paribas", but it also makes a candidate of every
ordinary word inside an organisation's name, and measured that grew the packs by 140% while
flagging `united`, `congress`, `museum` and Romanian `tău` ("your"). And an all-upper-case label
is skipped, because the flag is one bit and the spelling beside it is lower-cased, so the most
it can produce for `bbc` is "Bbc" -- a more visible kind of wrong than the uncapitalised word.

The committed `dictionaries/*.tsv` are the result, and the keyboard's own rules for using the
flag are two more checks: with several packs active, every pack that knows a word has to agree
it is a name before it is capitalised, so a name in one language's list cannot capitalise an
ordinary word of another's; and autocorrect lets a name correct only its own letters --
"maria" to "Maria", "laurentiu" to "Laurențiu" -- never an ordinary word an edit or two away,
so "everyone" cannot become "Everton" just because a football club is in the corpus.

## Offensive words

A corpus counted the way above contains whatever people wrote, profanity included, and a
frequent swear word ranks exactly as high as any other frequent word. "Block offensive words",
on the Typing and Suggestions screen and off by default, is the switch that keeps a curated set
of them out of the suggestion strip, out of autocorrect and out of the personal dictionary.

The lists are plain text, one per bundled language, in `keyboard/src/main/assets/offensive/`
as `<tag>.txt` -- with the hyphen the tag actually has (`en-US.txt`), unlike `dictionaries/`,
which uses `_` because the compiled pack is named after the file. One word per line, `#` for a
comment. They are loaded for the languages that are turned on and merged, the same way the
accent overlays in `assets/accents/` are, so turning Italian off takes its words out of the set
with it.

What the switch does *not* do is as much the point as what it does:

- **It never touches what you type.** The words are removed from what the keyboard *offers*.
  Typed letter by letter, a word on the list is committed exactly as typed: the typed word has
  its own chip on the strip, always first, whether or not any candidate survived the filter, and
  the "do the dictionaries spell this?" lookup that stops autocorrect from touching a real word
  does not consult the list at all. So the failure everyone knows -- typing a swear word and
  having the keyboard replace it with something else -- cannot happen here.
- **It is whole words, never substrings.** The comparison is against a dictionary candidate, not
  a search through the letters, so "assassin", "Scunthorpe" and "cockpit" are untouched. This is
  the one design decision that makes a list like this safe at all.
- **A swipe that decodes to one offers the next candidate instead.** The gesture results go
  through the same filter as the typed ones. That is the same behaviour every other keyboard
  with this switch has, and it is the price of the swipe path having no "but I meant it" signal
  the way deliberate typing does.
- **Nothing is deleted.** A word already in the personal dictionary is *hidden* while the switch
  is on: the personal model is loaded without it, and without any pair or triple that names it,
  which is also what keeps it out of the two-word phrase suggestions the engine builds from the
  personal model alone -- a whole-word filter cannot see inside "holy shit". Turn the switch off
  and every row is back, with its count intact.

Both halves of the comparison are folded first -- lower case, accents stripped -- so a list needs
one spelling of a word rather than every casing of it, and a Romanian entry written with its
diacritics also matches the same word typed without them. `ñ` and `ß` are deliberately *not*
folded away: `ñ` is a letter of its own and "cono" must not go with "coño". Inflections are
different words to the engine and each one needs its own line; that is why the lists carry
"fuck", "fucked", "fucking" and "fucker" separately rather than a stem.

### Editing a list, or adding a language

Add the line, keep the file sorted, and that is the whole procedure -- the lists are read at run
time from the assets, so nothing is recompiled and no pack is rebuilt. A new language needs
`<tag>.txt` next to the others and nothing else; a tag with no file simply contributes no words.
`OffensiveWordsTest` reads all six out of the repository and fails on a multi-word entry (it
could never match a single candidate), an upper-case one, or a word listed twice once folded.

The selection is this project's own, and the policy behind it is narrower than it looks:
**profanity and slurs, nothing else.** Anatomy, medicine and mild words stay suggestible on
purpose -- a keyboard that will not complete "penis" or "breast" is broken for anyone writing
about their own body, and that is a far more common need than the switch itself. The public
lists that exist for this (the LDNOOBW set, and a Romanian fork of it) were read for coverage
and not copied: they are content-moderation lists, built to answer "what should this platform
not *show* people", which is a different question. They mark ordinary vocabulary -- "martillo"
is a hammer, "pesce" is a fish, "negru" is the colour black -- and they carry multi-word phrases
that can never match a candidate. Their licence (CC BY 4.0) and this difference are recorded in
`docs/licensing.md` section 2.1.

## Shipping one with the application

Drop the word list in `dictionaries/` as `<tag>.tsv` — with `_` where the tag has `-` — and the
Gradle task in `keyboard/build.gradle.kts` compiles it into the APK's assets on every build. Add
an entry to `BundledDictionaries.ALL`, with the word count and the compiled size the build
prints, so the Languages screen offers it -- and keep those two numbers current whenever the
list changes, because they label the entry and the start-up repair uses them to tell an
installed copy from the pack the build ships. No binary is committed: the pack in an APK is
always what the committed list compiles to.

## Publishing one as a downloadable pack

A language the application does not bundle ships as a `.bkd` on the rolling `packs` release,
which the Languages screen's "More languages" card points at; the person downloads it in a
browser and imports it through "Import your own". Its word list lives in `dictionaries/extra/`,
which the Gradle task above does not compile, and a manifest in `tools/languages/<tag>.json`
names everything the pipeline needs: the Leipzig corpora, the Hunspell dictionary (a path in
LibreOffice's repository, or the two files' own URLs), the Universal Dependencies treebank, the
Wikidata language code, the long-press letters, the single-letter words, and the reachability
budget once it has been measured.

```
python3 tools/new_language.py tools/languages/nl_NL.json
```

runs the whole of it, each step skipped when its output is already there: fetch, the accent
overlay, the two name lists, the grammar, a first count for the ordinary-word list, the count
again with the names merged, the cleaning tools over a working copy that holds the six bundled
lists beside the new one, the compiled pack with its size, and the reachability check. The
manifests also feed `drop_unreachable.py` and `drop_foreign.py`, so a downloadable language is
judged by the same rules as a bundled one. `.github/workflows/packs.yml`, started by hand,
compiles every list in `dictionaries/extra/` and replaces the `packs` release with the result.
