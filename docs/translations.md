<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Translations

Every word a person reads — in the settings app and on the keyboard itself — comes from
`i18n/src/main/assets/translations/{language}.json`. No source file contains a sentence, and
`NoHardcodedTextTest` fails the build if one appears.

## Adding a language

1. Copy `en.json` to `{code}.json`, where `{code}` is a language subtag (`de`) or a subtag with a
   region (`pt-br`, lowercase). Both forms are matched: a phone set to `pt-BR` takes `pt-br` if it
   exists and `pt` otherwise.
2. Translate the values. Leave the keys exactly as they are.
3. Run `./gradlew :i18n:test`. It checks that your file carries the same keys as English, that no
   entry is blank, and that every `%s` in an English string is still there in yours.

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

`LanguageManager.counted(key, n)` picks between three forms:

| n | key looked up |
|---|---|
| 1 | `key_one` |
| 2–19 | `key` |
| 0, 20+ | `key_many`, in Romanian only |

Romanian is why the third column exists: it counts in three, and "20 cuvinte" is wrong where "20
de cuvinte" is right. A language that does not distinguish large numbers simply has no `_many`
entries, and the parity test knows that `_many` keys are expected in Romanian and nowhere else.

## Adding a string

1. Add it to `en.json` with a key of the form `{screen}_{a few words of the text}`.
2. Run `python3 tools/gen_keys.py`, which rewrites `Keys.kt`.
3. Use it as `strings[Keys.YOUR_KEY]`, or `strings.getString(Keys.YOUR_KEY, value)` when it
   carries a `%s`.
4. Add it to every other language file. `:i18n:test` will tell you which ones you missed.

In a composable, `strings` comes from `LocalStrings.current`. In the keyboard, the views are given
a `LanguageManager` by `BorderKeysService`, which builds one when the service starts.

## What is not in the catalogue

`ime_name` and `subtype_language_label` stay in `keyboard/src/main/res/values/strings.xml`. The
framework reads those out of the manifest to build the input-method list, before any of our code
runs, so they cannot come from a file we parse ourselves.

Log messages and exception text are not translated either. They are read by whoever is debugging,
not by whoever is typing.
