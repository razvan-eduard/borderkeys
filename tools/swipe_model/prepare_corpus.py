#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Downloads the free, MIT-licensed swipe.futo.org gesture corpus and caches it in a fast-loading
shape for train.py.

Raw (x, y, t) per swipe, the target word, and the canvas size it was captured at, one file per
split. Resampling, the 8-D feature vector and the augmentation happen in train.py.
futo-org/swipe-negatives is not used.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

DATASET_ID = "futo-org/swipe.futo.org"
# The dataset's five collection runs, published as separate configs; together they are the
# paper's training split. --configs picks a subset.
ALL_CONFIGS = ("swipe-1", "swipe-2", "swipe-3", "swipe-4", "swipe-5")
SPLITS = ("train", "validation", "test")


def prepare_split(dataset, split: str, output_dir: Path) -> int:
    records = []
    for row in dataset:
        points = row.get("data") or []
        if len(points) < 2:
            continue
        xs = [float(p["x"]) for p in points]
        ys = [float(p["y"]) for p in points]
        ts = [int(p["t"]) for p in points]
        records.append({
            "word": row["word"],
            "xs": xs,
            "ys": ys,
            "ts": ts,
            "canvas_width": row.get("canvas_width"),
            "canvas_height": row.get("canvas_height"),
        })

    out_path = output_dir / f"{split}.jsonl"
    with out_path.open("w", encoding="utf-8") as f:
        for record in records:
            f.write(json.dumps(record, separators=(",", ":")))
            f.write("\n")
    return len(records)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "data",
                        help="Directory to write {train,validation,test}.jsonl into.")
    parser.add_argument("--configs", nargs="+", default=list(ALL_CONFIGS),
                        help=f"Which of {ALL_CONFIGS} to pull, combined. Default: all five, the "
                             "full corpus. A smoke test wants just one.")
    parser.add_argument("--limit", type=int, default=None,
                        help="Cap rows per split (after combining configs), for a quick smoke "
                             "test rather than the real ~1.04M-row pull.")
    arguments = parser.parse_args()

    # Imported here, not at module scope.
    from datasets import concatenate_datasets, load_dataset

    arguments.out.mkdir(parents=True, exist_ok=True)
    for split in SPLITS:
        print(f"Loading {DATASET_ID} {arguments.configs} [{split}]...")
        parts = [load_dataset(DATASET_ID, config, split=split) for config in arguments.configs]
        dataset = parts[0] if len(parts) == 1 else concatenate_datasets(parts)
        if arguments.limit is not None:
            dataset = dataset.select(range(min(arguments.limit, len(dataset))))
        count = prepare_split(dataset, split, arguments.out)
        print(f"  wrote {count} swipes -> {arguments.out / (split + '.jsonl')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
