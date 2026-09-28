#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Converts one of FUTO's published key layouts into a BorderKeys layout asset
(keyboard/src/main/assets/layouts/<name>.json), read by the app (rows and indent, through
LayoutLoader.kt) and by this tooling (the added `cx`/`cy` per letter key).

`cx`/`cy` are FUTO's normalised centres (swipe-5/layouts/<name>.json, MIT), added as optional
fields on the `{"c": "<letter>"}` key objects; LayoutLoader.kt ignores them.

The rows are reconstructed geometrically: keys are clustered into rows by y-position and each
row is centred with half the slack on the left, as qwerty.json's staggered rows are. The bottom
action row and the shift and delete keys flanking the last letter row come from qwerty.json.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from futo_layout import LAYOUT_NAMES, load_futo_layout

# Every layout whose alphabet is a-z or a subset of it (eval_layouts.py).
CONVERTIBLE_LAYOUTS = ("azerty", "dvorak", "qwertz", "clearflow", "kasroz", "toki_pona")

ROW_CLUSTER_TOLERANCE = 0.03  # in normalised cy units; FUTO's own rows are >0.1 apart

# Reused verbatim from qwerty.json: the fourth row, and the shift/delete pair flanking the last
# letter row.
BOTTOM_ACTION_ROW = {
    "keys": [
        {"code": "symbols", "w": 1.5},
        {"code": "language"},
        {"c": ",", "alt": ";:"},
        {"code": "emoji"},
        {"code": "space", "w": 3},
        {"c": ".", "alt": "-_,!?"},
        {"code": "enter", "w": 1.5},
    ],
}


def cluster_rows(centers: dict[str, tuple[float, float]]) -> list[list[tuple[str, float, float]]]:
    """Groups (letter, cx, cy) into rows by cy proximity, each row sorted left to right."""
    items = sorted(centers.items(), key=lambda kv: kv[1][1])
    rows: list[list[tuple[str, float, float]]] = []
    for letter, (cx, cy) in items:
        if rows and abs(rows[-1][0][2] - cy) <= ROW_CLUSTER_TOLERANCE:
            rows[-1].append((letter, cx, cy))
        else:
            rows.append([(letter, cx, cy)])
    for row in rows:
        row.sort(key=lambda item: item[1])
    return rows


def build_layout_json(name: str) -> dict:
    centers = load_futo_layout(name)
    rows = cluster_rows(centers)
    widest = max(len(row) for row in rows)

    row_objects = []
    for row_index, row in enumerate(rows):
        # Half the slack on the left, as qwerty.json's staggered rows.
        indent = (widest - len(row)) / 2.0
        keys = [{"c": letter, "cx": cx, "cy": cy} for letter, cx, cy in row]
        if row_index == len(rows) - 1:
            keys = [{"code": "shift", "w": 1.5}] + keys + [{"code": "delete", "w": 1.5}]
            indent = max(0.0, indent - 1.5)  # the flanking keys already push the row outward
        row_objects.append({"indent": indent, "keys": keys} if indent > 0 else {"keys": keys})

    row_objects.append(BOTTOM_ACTION_ROW)
    return {
        "id": name,
        "label": name.replace("_", " ").title(),
        "languageTag": "und",
        "rows": row_objects,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layouts", nargs="+", default=list(CONVERTIBLE_LAYOUTS),
                        choices=LAYOUT_NAMES)
    parser.add_argument(
        "--out", type=Path,
        default=Path(__file__).parent.parent.parent / "keyboard/src/main/assets/layouts",
    )
    arguments = parser.parse_args()

    arguments.out.mkdir(parents=True, exist_ok=True)
    for name in arguments.layouts:
        layout = build_layout_json(name)
        out_path = arguments.out / f"{name}.json"
        with out_path.open("w", encoding="utf-8") as f:
            json.dump(layout, f, indent=2, ensure_ascii=False)
            f.write("\n")
        print(f"wrote {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
