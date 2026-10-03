<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Translations

Every word a person reads — in the settings app and on the keyboard itself — comes from
`i18n/src/main/assets/translations/{language}.json`. No source file contains a sentence, and
`NoHardcodedTextTest`, in the settings and in the keyboard module, fails the build if one appears.

## Adding a language

1. Copy `en.json` to `{code}.json`, where `{code}` is a language subtag (`de`) or a subtag with a
   region (`pt-br`, lowercase). Both forms are matched: a phone set to `pt-BR` takes `pt-br` if it
   exists and `pt` otherwise.
2. Translate the values. Leave the keys exactly as they are.
3. Run `./gradlew :i18n:test`. It checks that your file carries the same keys as English, with
   the plural forms your language uses; that no entry is blank; that every `%s` in an English
   string is still there in yours; and the gates below.

That is the whole procedure. Nothing else needs editing: the picker on the Languages screen lists
whatever is in the directory, and `LanguageResolution` will hand your file to any phone that asks
for it.

Give the language its own name while you are there — add `language_name_{code}` (`"language_name_de":
"Deutsch"`) so the picker offers it as its speakers would write it. Without one the picker falls
back to the bare code, which works but reads like a bug.

## Placeholders

`%s` is substituted left to right by `LanguageManager.format`, which is not `String.format`: a
stray `%` in a translation shows as a `%`, it does not throw. You may reorder the sentence around
the placeholders, but the count has to match English — the test enforces it, because a dropped
`%s` leaves a hole where a number should be.

## Counts

A string that carries a count has one key per plural category the language uses, as CLDR defines
them: `key` is the "other" form and `key_one`, `key_few`, `key_many`, `key_zero` are the rest.
`PluralRules` holds the rules, and `LanguageManager.counted(key, n, …)` picks the key for `n` in
the loaded language. English, German, Spanish, French and Italian use `one` and `other`; Romanian
uses `one`, `few` (0, 2–19, and 101–119 and so on) and `other` ("20 de cuvinte"); Czech, Polish,
Russian and Ukrainian use `one`, `few`, `many` and `other`; Latvian uses `zero`, `one` and
`other`; Japanese, Korean, Chinese, Vietnamese and Indonesian use `other` alone.

A number shown with decimals is passed as the text shown, `strings.counted(key, text)`, and its
form follows the digits shown.

A key is counted when English carries a `_one` form of it. The parity test then expects each
language to carry exactly the forms its rules use, no more and no fewer.

## Gates

`./gradlew :i18n:test` holds every catalogue to these, beside the parity and placeholder checks:

| Test | Rule | Data |
|---|---|---|
| `GlossaryTest` | Where English uses a concept's term, each language uses one of its allowed stems and none of its forbidden ones. | `i18n/src/test/resources/glossary.json` |
| `RegisterTest` | Each language addresses the reader in the register listed under Languages; a form of the other register fails. | `register.json` |
| `LengthTest` | A translation is at most 2.0× English for a title, 1.75× for the rest, for English over 40 code points, a `%s` counting as 4. | `length.json` for exceptions |
| `UntranslatedShareTest` | No string is identical to English in more than 9 of every 21 languages, brands, units and listed keys aside. | `untranslated.json` |
| `StoreListingTest` | Every store listing locale has a title of at most 50, a short description of at most 80 and a full description. | `fastlane/` |
| `CatalogueTest` | Every shipped language is named in every catalogue. | |

Where a concept already has a term in the catalogues, that term is kept and the others are brought
to it.

## Languages

| Language | Code | Register | Language | Code | Register |
|---|---|---|---|---|---|
| English | `en` | — | Latvian | `lv` | formal |
| Czech | `cs` | formal | Dutch | `nl` | informal |
| German | `de` | informal | Polish | `pl` | informal |
| Spanish | `es` | informal | Portuguese (Brazil) | `pt` | formal |
| Persian | `fa` | formal | Romanian | `ro` | informal |
| Filipino | `fil` | informal | Russian | `ru` | formal |
| French | `fr` | formal | Turkish | `tr` | formal |
| Hungarian | `hu` | formal | Ukrainian | `uk` | formal |
| Indonesian | `id` | formal | Vietnamese | `vi` | neutral |
| Italian | `it` | informal | Chinese (Simplified) | `zh-cn` | informal |
| Japanese | `ja` | formal | Korean | `ko` | formal |

A phone set to `in`, `iw`, `ji` or `tl` gets `id`, `he`, `yi` or `fil`; Simplified Chinese
(`zh-Hans`, `zh-CN`, `zh-SG` or a bare `zh`) gets `zh-cn`, and Traditional Chinese falls through
to the phone's next language, or to English.

## Direction and search

The settings lay out right to left when the loaded language is written that way (Persian, Arabic,
Hebrew), whatever the phone's own direction. Icons that follow the text's direction are drawn
mirrored with it (`android:autoMirrored`): back, forward, delete, undo, redo, return, tab, line
start and end, a list, a speech bubble; arrows that name a side of the screen are not.
`IconMirroringTest` pins the set. The keyboard's quick-actions bar mirrors the same icons while
the letters on the keys read right to left. The settings search compares folded text: case, accents, width, ß and
katakana against hiragana do not matter, so "stergere" finds "Ștergere".

## Adding a string

1. Add it to `en.json` with a key of the form `{screen}_{a few words of the text}`.
2. Run `python3 tools/gen_keys.py`, which rewrites `Keys.kt`.
3. Use it as `strings[Keys.YOUR_KEY]`, or `strings.getString(Keys.YOUR_KEY, value)` when it
   carries a `%s`, or `strings.counted(Keys.YOUR_KEY, n, …)` when it carries a count.
4. Add it to every other language file. `:i18n:test` will tell you which ones you missed.

In a composable, `strings` comes from `LocalStrings.current`. In the keyboard, the views are given
a `LanguageManager` by `BorderKeysService`, which builds one when the service starts.

## What is not in the catalogue

`ime_name` and `subtype_language_label` stay in `keyboard/src/main/res/values/strings.xml`. The
framework reads those out of the manifest to build the input-method list, before any of our code
runs, so they cannot come from a file we parse ourselves.

The six entries Android adds to every application's text-selection menu are plain resources in
`settings/src/main/res/values-*/strings.xml`, one folder per language; Android names Indonesian
`in` and Simplified Chinese `zh-rCN` there.

The store listings live in `fastlane/{application}/metadata/android/{locale}/`, three files per
locale for each application: `title.txt`, `short_description.txt` (80 characters at most) and
`full_description.txt`. `scripts/sync_fdroid_metadata.sh` copies every locale's summary and
description to F-Droid; the icon, the screenshots and the changelog stay English.

Log messages and exception text are not translated either. They are read by whoever is debugging,
not by whoever is typing.
