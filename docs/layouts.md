<!--
SPDX-License-Identifier: GPL-3.0-or-later
SPDX-FileCopyrightText: 2026 BorderKeys contributors
-->

# Layouts, keys and what a long press holds

How a keyboard layout is described, how it becomes pixels, and where the characters behind a long
press come from — which is two separate mechanisms that meet on the same key.

- [The asset format](#the-asset-format)
- [Key codes and flags](#key-codes-and-flags)
- [From JSON to pixels](#from-json-to-pixels)
- [Long press: two sources](#long-press-two-sources)
- [Layers](#layers)
- [Adding a layout](#adding-a-layout)
- [What is deliberately not here](#what-is-deliberately-not-here)

---

## The asset format

A layout is one JSON file in `keyboard/src/main/assets/layouts/`. Thirty-three ship today:

```
qwerty  qwertz  azerty  dvorak  colemak  colemak_dh  workman  bepo  clearflow  kasroz  toki_pona
turkish_f  turkish_q  spanish  portuguese  nordic  danish  german  czech  hungarian
russian  ukrainian  bulgarian  serbian  macedonian  greek  armenian  georgian
numpad  symbols  symbols_shift  symbols_numpad_left  symbols_numpad_right
```

The eight scripts of their own are one layout each, in the arrangement the language types on,
with what a row has no room for on the long press its overlay provides: ё and ъ for Russian,
ґ for Ukrainian, the accented vowels for Greek, five letters for Armenian, seven for Georgian.
Their subtypes are named by the locale's own display name, the way the two QWERTY subtypes
are, since each is one language.

The national variants (`spanish` with ñ, `portuguese` with ç, `nordic` with å ä ö, `danish`
with å æ ø, `german` with ü ö ä, `czech` with ú ů, `hungarian` with ő ú é á ö ü, the two
Turkish arrangements with ğ ü ş ı ö ç) carry their extra letters as keys; every other accented
letter stays on the long press the language's overlay provides. Polish has no variant on
purpose: its letters all live on the long press of their base letter.

**No pixels anywhere.** Widths are in key-width units and row heights are multiples of the
theme's row height, so one description serves every screen size and every theme.

```json
{
  "id": "qwerty",
  "label": "QWERTY",
  "languageTag": "und",
  "rows": [
    { "keys": [ { "c": "q" }, { "c": "w" }, { "c": "e" } ] },
    {
      "indent": 0.5,
      "keys": [ { "c": "a", "alt": "@" }, { "c": "s", "alt": "#" } ]
    },
    { "keys": [ { "code": "shift", "w": 1.5 } ] }
  ]
}
```

### Row fields

| Field | Meaning |
|---|---|
| `indent` | Leading empty space in key-width units. `0.5` is the classic QWERTY stagger. |
| `heightScale` | Row height as a multiple of the theme's row height. |
| `keys` | The keys, left to right. |

A row's total width is `indent + Σ widthUnits`, and is never zero.

### Key fields

| Field | Meaning |
|---|---|
| `c` | A character key — the overwhelming majority. The code point of the first character. |
| `code` | A named action instead: `shift`, `delete`, `enter`, `symbols`, `language`, `emoji`, `settings`. |
| *(top-level `label`)* | **Not read.** See [below](#adding-a-layout). |
| `label` | What is drawn, when it differs from what is typed (`⌫` for delete). Defaults sensibly. |
| `alt` | Characters reachable by long press, in order. |
| `w` | Width in key-width units. Defaults to 1. |
| `absorb` | This key, not the space bar, takes the width of an optional key the layout drops. |
| `secondary` | Drawn in the modifier fill without being a modifier, so a digit block reads as one against the symbols around it. |

`c` and `code` are two spellings of the same field: `c` wins when present, otherwise `code` is
resolved through `KeyCodes.named()`.

---

## Key codes and flags

Codes are ints. A character key's code **is** its code point; actions are negative:

| Code | Value |
|---|---|
| `SHIFT` | −1 |
| `DELETE` | −2 |
| `ENTER` | −3 |
| `SYMBOLS` | −4 |
| `LANGUAGE` | −5 |
| `EMOJI` | −6 |
| `SETTINGS` | −7 |
| `SYMBOLS_SHIFT` | −8 |
| `NONE` | −100 |

Flags are a bitmask, derived by the loader rather than written in the asset — so a layout author
cannot forget one:

| Flag | Set when | Used for |
|---|---|---|
| `LETTER` | a character key that is not space | swipe decoding |
| `PREVIEW` | as above | the pop-up bubble |
| `MODIFIER` | not a character | styling, and exclusion from swipe |
| `HAS_ALTERNATIVES` | `alt` is non-empty | the long-press hint |
| `REPEATABLE` | code is `DELETE` | held-backspace repeat |
| `SECONDARY_ROW` | — | the optional digit row |
| `ABSORBS_FREED_WIDTH` | `absorb` is true | width redistribution |
| `SECONDARY_ROW` | `secondary` is true, or the row `withNumberRow()` adds | the key fill |

**Space is a character but gets neither `LETTER` nor `PREVIEW`** — it is not a swipe letter and a
preview bubble over the space bar is noise.

`REPEATABLE` exists for one specific interaction and the comment in `LayoutLoader` records why:
the long-press timer (380 ms) always elapses before the character-repeat delay (400 ms) would, so
a held backspace goes straight to deleting a whole *word* rather than a character. Once that
first word is gone, `KeyboardCanvasView`'s repeat runnable keeps it going one word at a time for
as long as the finger stays down — and that mechanism **only arms itself when this flag is set**.
Without it a held backspace stops after exactly one word.

---

## From JSON to pixels

Three objects, and the split is the point.

```
assets/layouts/<id>.json
  └─ LayoutLoader          parses, allocates freely, runs once at service start off the UI thread
      └─ KeyboardLayout    rows and keys in relative units; a parse result, not a draw structure
          └─ KeyboardGeometry   parallel arrays of primitives, compiled when the view knows its size
              └─ KeyboardCanvasView   draws and hit-tests
```

**`KeyboardLayout` is deliberately not what the view holds.** The compile step exists so that the
rendering and hit-testing paths never dereference an object per key. `KeyboardGeometry` is nine
parallel arrays — `keyLeft`, `keyTop`, `keyRight`, `keyBottom`, `keyCode`, `keyFlags`,
`rowOfKey`, `centerX`, `centerY` — and there is no `Key` object and no `List<Key>` anywhere near a
finger. The draw path reads floats; the touch path reads ints.

`KeyboardGeometry` is also free of Android, and that is not incidental. Hit-testing and the
arrangement of key rectangles are the two things in a keyboard that are pure arithmetic and easy
to get subtly wrong — **a one-pixel gap between two keys is a touch that does nothing, and neither
a device nor a screenshot will show it.** Split out, it is tested as arithmetic.

`centerX`/`centerY` are also what the engine gets through `setKeyGeometry`: without them
`KeyGeometry::isSet()` is false and the fuzzy walk never runs at all.

### Failure is a fallback, never an exception

`LayoutLoader.load` returns `KeyboardLayout.fallbackQwerty()` on **any** failure — missing asset,
malformed JSON, empty rows. A malformed asset should mean a plain QWERTY, not an input method
that cannot draw, because a keyboard that crashes on start is one the user cannot replace without
already having another one installed.

`org.json` is used because it is in the framework: no dependency, no annotation processor,
nothing on the classpath.

---

## Long press: two sources

A key's long-press characters come from **two independent places**, and keeping them separate is
what makes accents follow the user's languages rather than their layout.

### 1. The layout's own `alt`

Punctuation and symbols that belong to the *layout*, written in the asset:

```json
{ "c": "a", "alt": "@" }
```

These are the same whatever languages are enabled.

### 2. Accent overlays, per enabled language

**The base layout carries no language-specific accents at all.** Each bundled language has a small
`assets/accents/<tag>.json` mapping a plain letter to its accented forms, and the keyboard merges
the overlays for the languages the user has turned **on**.

Enable Romanian and `a` holds `ă â`; turn it off and it does not.

> Accents follow the **Languages** screen, not the layout.

That is the whole reason the split exists. The alternative — writing accents into each layout —
means a Romanian speaker on a QWERTY layout gets nothing, and an English-only user on the same
layout carries diacritics they never type.

`AccentOverlays.load` returns an **empty map** on any failure, never an exception: a missing or
malformed overlay means a key without that accent, never a keyboard that will not draw.

### Timing

`longPressMillis` is a user setting, clamped to `[MIN_LONG_PRESS_MILLIS, MAX_LONG_PRESS_MILLIS]`.
`longPressHints` (default on) controls whether the alternatives are hinted on the key face.

---

## Layers

`SYMBOLS` (−4) and `SYMBOLS_SHIFT` (−8) switch to the symbol layouts, which are ordinary layout
assets — there is no separate mechanism for a "layer".

`symbols_numpad_right`, the default symbols page, and `symbols_numpad_left` put the digits in a
3×3 block with the symbols beside it -- nine keys a row, so each is close to square; the digits
`secondary`, so they read as a block; backspace and enter down the right edge -- on whichever
side a thumb prefers. `absorb` exists for a page that drops an optional key and wants something
other than the space bar to take its width.

The digit row is not a separate layout either: `KeyboardLayout.withTopRowDigits()` returns the
same layout with ten digits added above it. Two details are deliberate:

- **Nothing on their long press.** A physical keyboard's number row is digits, and every symbol
  worth shifting to is already on a letter's long press or a `?123` page.
- **`PREVIEW or SECONDARY_ROW`, and *not* `LETTER`.** A swipe must not pass through a digit, and
  a digit is never a substitution target when correcting a typo. `SECONDARY_ROW` also sets the
  row apart visually, the way it is set apart on a hardware keyboard.

The modifier row is the same kind of transform. `KeyboardLayout.withModifierRow()` puts escape,
tab, control, alt and the four arrows above the letters -- above the number row when both are
shown -- or, with `atBottom`, under the space row (`modifierRowPosition`). Which keys, and in what
order, is `modifierRowKeys`: any of escape, tab, control, alt, the four arrows, home, end, page
up, page down, forward delete and insert, up to twelve, sharing the row's ten units; each is
`MODIFIER`, and the arrows and forward delete are `REPEATABLE` like backspace. Every key
on it reaches the application as a hardware key event through the input connection: control and
alt are armed by a tap and go out with the next key (a letter under them is sent as the key that
carries it, so control with `a` is the application's select-all), and a shift the user holds puts
the shift bits on the arrows, which is how they select. Off by default (`modifierRow`), for
terminals and editors. Layout assets can place the same keys themselves with the codes `escape`,
`tab`, `control`, `alt`, `left`, `right`, `up` and `down`.

`LANGUAGE` (−5) cycles enabled layouts — the same thing the globe key and the `SWITCH_LAYOUT`
[quick action](architecture.md#quick-actions) do.

---

## Adding a layout

1. Write `keyboard/src/main/assets/layouts/<id>.json`. Copy the nearest existing one; widths are
   relative, so nothing needs measuring.
2. Set `languageTag` to a BCP-47 tag, or `und` if the layout is not language-specific (QWERTY is
   `und` — it is used by many).
3. If the language needs diacritics, add `assets/accents/<tag>.json` rather than putting them in
   the layout's `alt` fields.
4. Declare a subtype for it in `keyboard/src/main/res/xml/method.xml`, with the next free
   `subtypeId`, `layout=<id>` as its extra value and a `subtype_label_<id>` string beside the
   others in `res/values/strings.xml`, so the keyboard picker can offer it.
   `python3 tools/check_layouts.py` checks that every asset, subtype and label agree, and CI
   runs it.

**Do not rely on the asset's top-level `label`.** The loader does not read it, and the comment
says why: the value the files carry is an English word, not a catalogue key, so the one place it
could have been shown would have shown it untranslated. Any user-visible layout name belongs in
the `:i18n` catalogues like every other string.

The loader tolerates unknown fields and skips empty rows. A layout with no rows at all throws —
and is then caught by `load`, which returns the QWERTY fallback, so even that reaches the user as
a working keyboard.

---

## What is deliberately not here

- **No per-key user customisation.** A user cannot reassign what a given key's long press holds;
  `alt` is layout data and accents follow enabled languages. Some keyboards offer a grid of
  user-assigned subkeys per key. This does not, and that is a real feature difference rather than
  an oversight.
- **No pixel positions, ever.** If you find yourself wanting one in an asset, the answer is a
  width unit or a height scale.
- **No per-key `View`s.** `KeyboardCanvasView` draws dozens of keys from parallel arrays;
  `InlineSuggestionsHostView` is the one place in the project that hosts framework `View`s, and
  it has no alternative.
