#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes keyboard/src/main/assets/extra_keys.json, each subtype language's extra keys.

The entries are the `extra_keys` of tools/extra_keys/method.xml, Unexpected Keyboard's subtype
table, for each language tag keyboard/src/main/res/xml/method.xml declares: the subtype of the
same tag, else of the same language alone. An entry is `key[:alternative...][@next_to]`, where a
key is a character or an `accent_<name>` accent. A tag with no entries is left out. With --check,
fails when the asset differs.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE = ROOT / "tools/extra_keys/method.xml"
OURS = ROOT / "keyboard/src/main/res/xml/method.xml"
OUT = ROOT / "keyboard/src/main/assets/extra_keys.json"

TAG = re.compile(r'android:languageTag="([^"]+)"')
EXTRA = re.compile(r'android:imeSubtypeExtraValue="([^"]*)"')


def upstream() -> dict:
    table = {}
    for line in SOURCE.read_text(encoding="utf-8").splitlines():
        tag = TAG.search(line)
        extra = EXTRA.search(line)
        if not tag or not extra:
            continue
        for part in extra.group(1).split(","):
            if part.startswith("extra_keys="):
                table.setdefault(tag.group(1), part[len("extra_keys="):].split("|"))
    return table


def build() -> dict:
    table = upstream()
    out = {}
    for tag in sorted(set(TAG.findall(OURS.read_text(encoding="utf-8")))):
        entries = table.get(tag) or table.get(tag.split("-")[0])
        if entries:
            out[tag] = entries
    return out


def main() -> int:
    text = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if "--check" in sys.argv:
        if not OUT.is_file() or OUT.read_text(encoding="utf-8") != text:
            raise SystemExit("make_extra_keys: the asset is stale; run python3 tools/make_extra_keys.py")
        print("make_extra_keys: up to date")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print(f"make_extra_keys: {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
