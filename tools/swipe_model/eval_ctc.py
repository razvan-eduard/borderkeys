#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Raw (lexicon-unconstrained) greedy CTC decode accuracy on a held-out split -- a fast sanity
check during training, not the number that matters for shipping.

The real figure is the lexicon-constrained beam search TcnCtcDecoder actually runs on-device;
reproducing that exactly in Python here would mean maintaining a second copy of
tcn_ctc_decoder.cpp's trie-walk logic against a Python trie reader, for a number this script's
job is only to sanity-check DURING training, epoch to epoch, before anything is exported. Once a
checkpoint looks promising, export it (export_weights.py) and measure the real number the way
tools/gesture_replay.py measures Shark2's: against the app's own decoder.
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

from model import TcnEncoder, dct_basis
from train import QWERTY_LETTERS, SwipeDataset, collate


def greedy_decode(intention: torch.Tensor, spectral: torch.Tensor, basis: torch.Tensor,
                  letters: tuple[str, ...]) -> str:
    """Per-timestep argmax over (blank, key_1, ..., key_K), collapsed the standard CTC way:
    consecutive repeats of the same non-blank symbol merge into one, a blank never merges with
    anything. `intention`/`spectral` are for ONE sample: (T,), (T, 64)."""
    z = spectral @ basis.t()  # (T, K)
    char_probs = torch.sigmoid(z) * intention.unsqueeze(-1)  # (T, K)
    blank_prob = 1.0 - intention  # (T,)
    frame = torch.cat([blank_prob.unsqueeze(-1), char_probs], dim=-1)  # (T, K+1)
    best = frame.argmax(dim=-1)  # (T,)

    letters_out = []
    previous = -1
    for symbol in best.tolist():
        if symbol != 0 and symbol != previous:
            letters_out.append(letters[symbol - 1])
        previous = symbol
    return "".join(letters_out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=Path(__file__).parent / "data")
    parser.add_argument("--split", default="validation")
    parser.add_argument("--checkpoint", type=Path, default=Path(__file__).parent / "checkpoint.pt")
    parser.add_argument("--limit", type=int, default=None)
    arguments = parser.parse_args()

    model = TcnEncoder()
    state = torch.load(arguments.checkpoint, map_location="cpu")
    model.load_state_dict(state["model"] if "model" in state else state)
    model.eval()

    dataset = SwipeDataset(arguments.data / f"{arguments.split}.jsonl", augment=False)
    basis = dct_basis(torch.from_numpy(dataset.key_centers))

    correct = 0
    total = 0
    with torch.no_grad():
        for i in range(len(dataset) if arguments.limit is None else min(arguments.limit, len(dataset))):
            item = dataset[i]
            if item is None:
                continue
            features, target, _ = item
            expected = "".join(QWERTY_LETTERS[t - 1] for t in target)
            intention, spectral = model(torch.from_numpy(features).unsqueeze(0))
            decoded = greedy_decode(intention[0], spectral[0], basis, QWERTY_LETTERS)
            correct += int(decoded == expected)
            total += 1

    accuracy = correct / total if total else 0.0
    print(f"{arguments.split}: {correct}/{total} exact matches ({accuracy:.2%}), raw greedy CTC, "
          f"no lexicon")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
