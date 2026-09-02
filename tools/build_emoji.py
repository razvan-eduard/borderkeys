#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Turns Unicode's emoji-test.txt into the panel's asset.

The groups in that file are the ones every emoji keyboard uses, in the order the Unicode
consortium recommends for exactly this purpose, so the panel gets its categories from the
source rather than from someone's idea of which face belongs where.

Skin-tone and hair variants are dropped. They multiply the list by six for a choice the panel
has no room to offer, and the base emoji is what the modifier modifies.
"""

import argparse
import pathlib
import re

# The keyboard shows eight tabs. Unicode's "Component" group is modifiers on their own, which
# are not emoji anyone inserts, and People & Body is folded into Smileys as every keyboard does.
GROUPS = {
    "Smileys & Emotion": "smileys",
    "People & Body": "smileys",
    "Animals & Nature": "nature",
    "Food & Drink": "food",
    "Travel & Places": "travel",
    "Activities": "activities",
    "Objects": "objects",
    "Symbols": "symbols",
    "Flags": "flags",
}

SKIN_TONES = {0x1F3FB, 0x1F3FC, 0x1F3FD, 0x1F3FE, 0x1F3FF}
HAIR = {0x1F9B0, 0x1F9B1, 0x1F9B2, 0x1F9B3}

LINE = re.compile(r"^([0-9A-F ]+);\s*(\S+)\s*#")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, required=True)
    parser.add_argument("--out", type=pathlib.Path, required=True)
    arguments = parser.parse_args()

    order = []
    for name in GROUPS.values():
        if name not in order:
            order.append(name)
    buckets = {name: [] for name in order}

    group = None
    for line in arguments.source.read_text(encoding="utf-8").splitlines():
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        match = LINE.match(line)
        if not match or GROUPS.get(group) is None:
            continue
        if match.group(2) != "fully-qualified":
            continue
        points = [int(p, 16) for p in match.group(1).split()]
        if any(p in SKIN_TONES or p in HAIR for p in points):
            continue
        buckets[GROUPS[group]].append("".join(chr(p) for p in points))

    # One line per category: the name, a tab, then the emoji separated by spaces. No emoji
    # contains a space or a tab, so this needs no parser -- which keeps a JSON library out of
    # the keyboard process for a file that is a list of lists of strings.
    lines = [name + "\t" + " ".join(buckets[name]) for name in order]
    arguments.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    total = sum(len(v) for v in buckets.values())
    for name in order:
        print(f"{name:12s} {len(buckets[name]):5d}")
    print(f"{total} emoji, {arguments.out.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
