#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes keyboard/src/main/assets/compose/latin.json, the compose key's table.

Accented letters come from Unicode itself: for each accent's ASCII mark and each letter, the
pair composes under NFC to one character, mark then letter, and letter then mark where the mark
is not a letter itself. Symbols follow the X11 compose sequences most people know. No
sequence may be the start of another, since the keyboard writes a full match at once; the
script fails rather than write such a table.

    python3 tools/make_compose.py            # writes the asset
    python3 tools/make_compose.py --check    # fails when the asset differs
"""

import json
import pathlib
import sys
import unicodedata

OUT = pathlib.Path(__file__).resolve().parent.parent / "keyboard/src/main/assets/compose/latin.json"

# ASCII mark -> combining mark
MARKS = {
    "'": "́",
    "`": "̀",
    "^": "̂",
    '"': "̈",
    "~": "̃",
    "v": "̌",
    "u": "̆",
    ",": "̧",
    ";": "̨",
    "o": "̊",
    "_": "̄",
    ".": "̇",
}

LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

SYMBOLS = {
    "oc": "©", "or": "®", "tm": "™", "sm": "℠",
    "---": "—", "--.": "–", "..": "…", "<<": "«", ">>": "»", "<'": "‘", ">'": "’",
    "<\"": "“", ">\"": "”", ",'": "‚", ",\"": "„",
    "=e": "€", "l-": "£", "y=": "¥", "c/": "¢", "c=": "€", "=c": "€",
    "ss": "ß", "ae": "æ", "AE": "Æ", "oe": "œ", "OE": "Œ", "o/": "ø", "O/": "Ø", "d-": "đ", "D-": "Đ",
    "th": "þ", "TH": "Þ", "dh": "ð", "DH": "Ð", "l/": "ł", "L/": "Ł", "ng": "ŋ", "NG": "Ŋ",
    "12": "½", "14": "¼", "34": "¾", "13": "⅓", "23": "⅔", "18": "⅛",
    "^1": "¹", "^2": "²", "^3": "³", "_0": "₀", "_1": "₁", "_2": "₂", "_3": "₃",
    "+-": "±", "-:": "÷", "xx": "×", "=/": "≠", "<=": "≤", ">=": "≥", "~=": "≈",
    "!!": "¡", "??": "¿", "so": "§", "p!": "¶", "*0": "°", "mu": "µ", "^.": "·",
    "->": "→", "<-": "←", "-^": "↑", "-v": "↓",
}


def build() -> dict:
    table = dict(SYMBOLS)
    for mark, combining in MARKS.items():
        for letter in LETTERS:
            composed = unicodedata.normalize("NFC", letter + combining)
            if len(composed) != 1:
                continue
            # Letter then mark only where the mark is no letter itself.
            orders = (mark + letter, letter + mark) if not mark.isalpha() else (mark + letter,)
            for sequence in orders:
                table.setdefault(sequence, composed)
    # The space after a mark writes the mark itself.
    for mark in MARKS:
        if mark not in LETTERS:
            table.setdefault(mark + " ", mark)
    prefix_clashes = sorted(
        (a, b) for a in table for b in table if a != b and b.startswith(a)
    )
    if prefix_clashes:
        # Drop the longer entry: the shorter one is the common one, written at once.
        for _, longer in prefix_clashes:
            table.pop(longer, None)
    return dict(sorted(table.items()))


def main() -> int:
    table = build()
    for a in table:
        for b in table:
            if a != b and b.startswith(a):
                raise SystemExit(f"make_compose: {a!r} starts {b!r}")
    text = json.dumps({"sequences": table}, ensure_ascii=False, indent=1) + "\n"
    if "--check" in sys.argv:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            raise SystemExit("make_compose: the asset is stale; run python3 tools/make_compose.py")
        print(f"make_compose: {len(table)} sequences, up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"make_compose: {len(table)} sequences -> {OUT.relative_to(OUT.parents[4])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
