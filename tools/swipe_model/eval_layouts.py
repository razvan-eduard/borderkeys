#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Measures the trained encoder on REAL swipes from layouts it never trained on -- the actual
claim being tested, not an assumption backing it.

`swipe-5` is FUTO's own held-out multi-layout collection run, per its README: "This collection
expands to different languages and layouts to validate the encoder model's generalization
capabilities." Training (train.py) uses only "qwerty" -- everything else here is zero-shot.

Restricted to the layouts whose alphabet is a SUBSET of training's 26 a-z letters: qwerty (in-
domain, a sanity check), azerty, dvorak, qwertz, clearflow, kasroz, toki_pona. german/spanish/
lithuanian_qwerty add accented letters and shavian is a different script entirely -- none of
those are representable by this model's fixed 26-class CTC output without extending its
vocabulary and retraining, so evaluating on them would only measure that gap, not
layout-agnosticism.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from eval_ctc import greedy_decode
from futo_layout import load_futo_layout
from features_np import build_features, resample_uniform_time
from model import TcnEncoder, dct_basis

# Every layout whose alphabet is a-z-only or a subset of it, per a live check of each layout's
# own "letters" field in swipe-5/layouts/*.json.
COMPATIBLE_LAYOUTS = ("qwerty", "azerty", "dvorak", "qwertz", "clearflow", "kasroz", "toki_pona")


def evaluate_layout(model: TcnEncoder, layout_name: str, records, limit: int | None) -> tuple[int, int]:
    centers = load_futo_layout(layout_name)
    letters = tuple(sorted(centers.keys()))
    key_centers = torch.tensor([centers[c] for c in letters], dtype=torch.float32)
    basis = dct_basis(key_centers)

    correct = 0
    total = 0
    with torch.no_grad():
        for record in records:
            if limit is not None and total >= limit:
                break
            points = record.get("data")
            if not isinstance(points, list) or len(points) < 2:
                continue  # dual-finger records carry a dict here, not a list -- skipped
            word = record["word"].lower()
            if any(c not in letters for c in word):
                continue
            width = float(record.get("canvas_width") or 1.0)
            height = float(record.get("canvas_height") or 1.0)
            xs = torch.tensor([p["x"] for p in points], dtype=torch.float32) / width
            ys = torch.tensor([p["y"] for p in points], dtype=torch.float32) / height
            ts = torch.tensor([p["t"] for p in points], dtype=torch.float64)
            resampled = resample_uniform_time(xs.numpy(), ys.numpy(), ts.numpy())
            if resampled is None:
                continue
            features = torch.from_numpy(build_features(*resampled)).unsqueeze(0)

            intention, spectral = model(features)
            decoded = greedy_decode(intention[0], spectral[0], basis, letters)
            correct += int(decoded == word)
            total += 1

    return correct, total


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=Path(__file__).parent / "checkpoint.pt")
    parser.add_argument("--layouts", nargs="+", default=list(COMPATIBLE_LAYOUTS))
    parser.add_argument("--limit", type=int, default=2000,
                        help="Cap examples per layout -- swipe-5 mixes every layout and language "
                             "in one file, so this bounds how much of it gets scanned per layout.")
    arguments = parser.parse_args()

    from datasets import load_dataset
    dataset = load_dataset("futo-org/swipe.futo.org", "swipe-5", split="train")

    model = TcnEncoder()
    state = torch.load(arguments.checkpoint, map_location="cpu")
    model.load_state_dict(state["model"] if "model" in state else state)
    model.eval()

    for layout_name in arguments.layouts:
        filtered = dataset.filter(lambda r, name=layout_name: r["layout"] == name and r["dual_finger"] == 0)
        correct, total = evaluate_layout(model, layout_name, filtered, arguments.limit)
        accuracy = correct / total if total else 0.0
        zero_shot = "" if layout_name == "qwerty" else " (zero-shot -- never trained on)"
        print(f"{layout_name}{zero_shot}: {correct}/{total} ({accuracy:.2%})")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
