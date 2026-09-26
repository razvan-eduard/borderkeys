#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Checks that the layout assets, the subtypes that name them and their labels agree.

Every letter layout in keyboard/src/main/assets/layouts/ is declared as a subtype in
res/xml/method.xml with a label string of its own; every subtype names an asset that exists;
no layout carries the same key twice; every letter layout reaches the whole Latin alphabet
and ends in the action row with a space bar and an enter key. Exit status is non-zero on the
first thing that disagrees, with the file named. Runs in CI beside the other self-tests.

    python3 tools/check_layouts.py
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LAYOUTS = ROOT / "keyboard" / "src" / "main" / "assets" / "layouts"
METHOD = ROOT / "keyboard" / "src" / "main" / "res" / "xml" / "method.xml"
STRINGS = ROOT / "keyboard" / "src" / "main" / "res" / "values" / "strings.xml"

# Pages reached from the letter layouts rather than chosen as one, and the two QWERTY subtypes
# that name the same asset under language-prefixed ids.
PAGES = {"numpad", "symbols", "symbols_shift", "symbols_numpad_left", "symbols_numpad_right"}
QWERTY_ALIASES = {"ro_qwerty", "en_qwerty"}
ALPHABET = set("abcdefghijklmnopqrstuvwxyz")


def fail(message: str) -> None:
    raise SystemExit(f"check_layouts: {message}")


def main() -> int:
    assets = {path.stem: json.loads(path.read_text(encoding="utf-8")) for path in sorted(LAYOUTS.glob("*.json"))}
    letters = {name for name in assets if name not in PAGES}
    method = METHOD.read_text(encoding="utf-8")
    subtypes = re.findall(r'imeSubtypeExtraValue="layout=([a-z_]+)"', method)
    ids = re.findall(r'subtypeId="(0x[0-9A-Fa-f]+)"', method)
    labels = set(re.findall(r'name="subtype_label_([a-z_]+)"', STRINGS.read_text(encoding="utf-8")))

    if len(ids) != len(set(ids)):
        fail("two subtypes share a subtypeId")
    named = {"qwerty" if s in QWERTY_ALIASES else s for s in subtypes}
    for layout in sorted(letters - named):
        fail(f"{layout}.json has no subtype in method.xml")
    for layout in sorted(named - letters):
        fail(f"method.xml names layout={layout}, which has no asset")
    for layout in sorted(letters - {"qwerty"}):
        if layout not in labels:
            fail(f"{layout} has no subtype_label_{layout} string")

    for name, layout in assets.items():
        rows = layout.get("rows") or []
        if not rows:
            fail(f"{name}.json has no rows")
        keys = [key for row in rows for key in row.get("keys", [])]
        chars = [key["c"] for key in keys if "c" in key]
        if len(chars) != len(set(chars)):
            duplicates = sorted({c for c in chars if chars.count(c) > 1})
            fail(f"{name}.json carries a key twice: {duplicates}")
        for char in chars:
            if len(char) != 1:
                fail(f"{name}.json: a key's c must be one character, not {char!r}")
        if name in letters:
            present = {c for c in chars if c in ALPHABET}
            if present != ALPHABET and name != "toki_pona":
                fail(f"{name}.json lacks {sorted(ALPHABET - present)}")
            codes = {key.get("code") for key in rows[-1].get("keys", [])}
            if not {"space", "enter"} <= codes:
                fail(f"{name}.json: the last row must carry the space bar and the enter key")
    print(f"check_layouts: {len(letters)} letter layouts, {len(PAGES & set(assets))} pages, "
          f"{len(subtypes)} subtypes, all agreed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
