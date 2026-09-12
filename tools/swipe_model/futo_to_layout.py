#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Converts one of FUTO's published key layouts into a BorderKeys layout asset
(keyboard/src/main/assets/layouts/<name>.json) -- one JSON, read by both the app (rows/indent,
via LayoutLoader.kt) and by this ML tooling (the added `cx`/`cy` per letter key), rather than two
separate representations of the same physical keyboard that could drift apart.

`cx`/`cy` are FUTO's own verbatim normalised centres (swipe-5/layouts/<name>.json, MIT), added as
extra, OPTIONAL fields on the existing `{"c": "<letter>"}` key objects. LayoutLoader.kt's JSON
parsing is field-by-field with `optString`/`optDouble` (confirmed by reading it before writing
this) -- unknown fields are silently ignored, so this is additive: the app's own rendering is
completely unaffected by their presence, and `futo_layout.py`'s HTTP fetch becomes unnecessary
for any layout this script has already generated -- read the same file the app ships instead.

The row/indent/width fields (what the app actually renders from) are a best-effort geometric
reconstruction, not a byte-for-byte reproduction of swipe.futo.org's own renderer: keys are
clustered into rows by y-position, each row is centred by the same half-key-width-indent
convention the hand-authored qwerty.json already uses for ITS OWN staggered rows (accepting the
same minor cross-row key-size variation that convention already implies -- this is not a defect
introduced here). The bottom action row (symbols/language/comma/emoji/space/period/enter) and the
shift/delete keys flanking the last letter row are reused verbatim from qwerty.json's own
template, since FUTO's data has no equivalent -- it only ever recorded letter positions.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from futo_layout import LAYOUT_NAMES, load_futo_layout

# Every layout whose alphabet is a-z-only or a subset of it -- see eval_layouts.py's own note on
# why german/spanish/lithuanian_qwerty/shavian are excluded: they need CTC vocabulary this
# project's model does not have, and converting their KEY LAYOUT is a separate question from
# whether the model can usefully swipe on them, but shipping a layout nobody can usefully swipe on
# yet is not this script's job to decide -- it only converts what is unambiguously ready.
CONVERTIBLE_LAYOUTS = ("azerty", "dvorak", "qwertz", "clearflow", "kasroz", "toki_pona")

ROW_CLUSTER_TOLERANCE = 0.03  # in normalised cy units; FUTO's own rows are >0.1 apart

# Reused verbatim from qwerty.json: the fourth row is layout-independent (it types no letters),
# and the shift/delete pair flanking the last letter row is this project's own established
# convention for where those two keys go, not something FUTO's data has an opinion on.
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
        # Centred by the same convention qwerty.json's own staggered rows already use: half the
        # slack on the left. This does not perfectly size-match every row to the widest one (see
        # module doc) -- neither does qwerty.json's existing 0.5 indent on its own middle row.
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
