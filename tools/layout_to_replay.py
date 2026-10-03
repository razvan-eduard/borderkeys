#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""Writes a layout asset's letter keys as a replay layout for gesture_replay and tcn_replay.

The geometry is KeyboardGeometry's: each row divides the full width by its own units, its height
is its share of the whole, and a key's centre is the middle of its cell. Only letters are written,
as only letters reach the engine. One layout by name, or every own-script layout with --all,
written to native-tests/data/<layout>_1080.layout.
"""

import argparse
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYOUTS = ROOT / "keyboard/src/main/assets/layouts"
OUT = ROOT / "native-tests/data"

WIDTH = 1080.0
ROW_HEIGHT = 160.0

OWN_SCRIPT = ["russian", "ukrainian", "bulgarian", "serbian", "macedonian", "greek", "armenian",
              "georgian", "hebrew", "arabic"]


def letter_keys(layout: dict) -> tuple[float, float, list[tuple[str, float, float]]]:
    rows = layout["rows"]
    total = sum(float(row.get("height", 1.0)) for row in rows)
    height = total * ROW_HEIGHT
    keys: list[tuple[str, float, float]] = []
    widths: list[float] = []
    y = 0.0
    for row in rows:
        row_height = float(row.get("height", 1.0)) / total * height
        units = float(row.get("indent", 0.0)) + sum(float(key.get("w", 1.0)) for key in row["keys"])
        unit_width = WIDTH / units
        x = float(row.get("indent", 0.0)) * unit_width
        for key in row["keys"]:
            width = float(key.get("w", 1.0)) * unit_width
            character = key.get("c", "")
            if len(character) == 1 and character.isalpha():
                keys.append((character, x + width / 2, y + row_height / 2))
                widths.append(width)
            x += width
        y += row_height
    return sum(widths) / len(widths), ROW_HEIGHT, keys


def convert(name: str) -> pathlib.Path:
    layout = json.loads((LAYOUTS / f"{name}.json").read_text(encoding="utf-8"))
    key_width, key_height, keys = letter_keys(layout)
    if len(keys) > 64:
        raise SystemExit(f"layout_to_replay: {name} has {len(keys)} letter keys, more than 64")
    lines = [
        f"# BorderKeys replay layout: 1080 px phone {name}, from layouts/{name}.json.",
        "# SPDX-" "License-Identifier: GPL-3.0-or-later",
        "# SPDX-" "FileCopyrightText: 2026 BorderKeys contributors",
        "# keyWidth keyHeight",
        f"{key_width:.1f} {key_height:.1f}",
    ]
    lines += [f"{character} {x:.1f} {y:.1f}" for character, x, y in keys]
    out = OUT / f"{name}_1080.layout"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("names", nargs="*")
    parser.add_argument("--all", action="store_true")
    args = parser.parse_args()
    names = OWN_SCRIPT if args.all else args.names
    if not names:
        parser.error("name a layout, or --all")
    for name in names:
        out = convert(name)
        print(f"layout_to_replay: {out.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
