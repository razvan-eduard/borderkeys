#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes keyboard/src/main/assets/compose/compose.json, the compose key's table.

The sequences are read from tools/compose in file-name order: the extra, Arabic and Cyrillic
JSON trees, then X11's en_US.UTF-8 Compose list (its <Multi_key> sequences only, key names
resolved through keysymdef.h and the names the list defines). The first sequence wins; one that
repeats an earlier one, or starts or extends it, is dropped. A result naming a key is written as
the character that key types. With --check, fails when the asset differs.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = ROOT / "tools/compose"
OUT = ROOT / "keyboard/src/main/assets/compose/compose.json"

# Key names a JSON tree may give as a result, to the text the key types.
NAMED_RESULTS = {
    "nbsp": " ",
    "\\n": "\n",
    "\\t": "\t",
    "combining_aigu": "́",
    "combining_alef_above": "ٰ",
    "combining_alef_below": "ٖ",
    "combining_arabic_inverted_v": "ٛ",
    "combining_arabic_v": "ٚ",
    "combining_breve": "̆",
    "combining_dammah": "ُ",
    "combining_dammatan": "ٌ",
    "combining_fatha": "َ",
    "combining_fathatan": "ً",
    "combining_grave": "̀",
    "combining_hamza_above": "ٔ",
    "combining_hamza_below": "ٕ",
    "combining_inverted_breve": "̑",
    "combining_kasra": "ِ",
    "combining_kasratan": "ٍ",
    "combining_payerok": "꙽",
    "combining_pokrytie": "҇",
    "combining_shaddah": "ّ",
    "combining_slavonic_dasia": "҅",
    "combining_slavonic_psili": "҆",
    "combining_sukun": "ْ",
    "combining_titlo": "҃",
    "combining_trema": "̈",
    "combining_vertical_tilde": "̾",
    "combining_vzmet": "꙯",
}

KEYSYM = re.compile(r"^#define XK_(\S+)\s+\S+\s*/\*.U\+([0-9a-fA-F]+)\s")
LINE = re.compile(r'^((?:\s*<[^>]+>)+)\s*:\s*"((?:[^"\\]+|\\.)+)"\s*(\S+)?\s*(?:#.+)?$')
KEY = re.compile(r"\s*<(?:U([a-fA-F0-9]{4,6})|([^>]+))>")


def keysyms(path: pathlib.Path) -> dict:
    names = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        m = KEYSYM.match(line)
        if m:
            names[m.group(1)] = chr(int(m.group(2), 16))
    return names


def result_text(raw: str) -> str:
    return raw[1] if len(raw) == 2 and raw[0] == "\\" else raw


def x11_sequences(path: pathlib.Path, names: dict) -> list:
    lines = path.read_text(encoding="utf-8").splitlines()
    names = dict(names)
    for line in lines:
        m = LINE.match(line)
        if m and m.group(3):
            names[m.group(3)] = result_text(m.group(2))
    out = []
    prefix = "<Multi_key>"
    for line in lines:
        if not line.startswith(prefix):
            continue
        m = LINE.match(line[len(prefix):])
        if not m:
            continue
        keys = []
        for code, name in KEY.findall(m.group(1)):
            if code:
                character = chr(int(code, 16))
            elif len(name) == 1:
                character = name
            else:
                character = names.get(name)
            if character is None or len(character) != 1 or ord(character) > 0xFFFF:
                keys = None
                break
            keys.append(character)
        if keys:
            out.append(("".join(keys), result_text(m.group(2))))
    return out


def json_sequences(path: pathlib.Path) -> list:
    text = "".join(
        line[: line.find("//")] + "\n" if "//" in line else line
        for line in path.read_text(encoding="utf-8").splitlines(True)
    )
    out = []

    def walk(tree, prefix):
        for key, value in tree.items():
            if isinstance(value, str):
                if re.fullmatch(r"[a-z]+_[a-z_]+", value) and value not in NAMED_RESULTS:
                    raise SystemExit(f"make_compose: {path.name} names an unknown key {value!r}")
                out.append((prefix + key, NAMED_RESULTS.get(value, value)))
            else:
                walk(value, prefix + key)

    walk(json.loads(text), "")
    return out


def build() -> dict:
    names = keysyms(SOURCES / "keysymdef.h")
    sequences = []
    for path in sorted(SOURCES.iterdir()):
        if path.suffix == ".json":
            sequences += json_sequences(path)
        elif path.suffix == ".pre":
            sequences += x11_sequences(path, names)
    table = {}
    prefixes = set()
    for sequence, text in sequences:
        starts_one = any(sequence[:end] in table for end in range(1, len(sequence) + 1))
        if starts_one or sequence in prefixes:
            continue
        table[sequence] = text
        for end in range(1, len(sequence)):
            prefixes.add(sequence[:end])
    return dict(sorted(table.items()))


def main() -> int:
    table = build()
    text = json.dumps({"sequences": table}, ensure_ascii=False, indent=1) + "\n"
    if "--check" in sys.argv:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            raise SystemExit("make_compose: the asset is stale; run python3 tools/make_compose.py")
        print(f"make_compose: {len(table)} sequences, up to date")
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"make_compose: {len(table)} sequences -> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
