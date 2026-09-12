#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Trains TcnEncoder (model.py) from scratch on the free swipe.futo.org corpus.

CTC + emission-regularizer loss, AdamW with cosine decay, the coordinated trajectory+layout
augmentation (augment.py) applied fresh every sample every epoch -- see docs/licensing.md section
2.5 for why this exists (a free-weights alternative to FUTO's own non-free released weights) and
model.py for the architecture this trains.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset

from augment import augment_trajectory_and_layout
from features_np import TIMESTEPS, build_features, resample_uniform_time
from futo_layout import letters_in_order, load_futo_layout
from model import TcnEncoder, dct_basis, key_log_probs

# Training uses only "qwerty", matching the paper's own choice (see futo_layout.py's module doc)
# -- QWERTY_LETTERS' order is the fixed index->letter mapping the CTC target alphabet and the
# exported .bkw file both commit to, so it must stay whatever letters_in_order deterministically
# produces (alphabetical), not swipe-5/layouts/qwerty.json's own key order.
_QWERTY_CENTERS_BY_LETTER = load_futo_layout("qwerty")
QWERTY_LETTERS = letters_in_order(_QWERTY_CENTERS_BY_LETTER)
QWERTY_CENTERS = _QWERTY_CENTERS_BY_LETTER


class SwipeDataset(Dataset):
    def __init__(self, jsonl_path: Path, augment: bool, seed: int = 0):
        self.records = []
        with jsonl_path.open(encoding="utf-8") as f:
            for line in f:
                self.records.append(json.loads(line))
        self.letter_index = {c: i for i, c in enumerate(QWERTY_LETTERS)}
        self.key_centers = np.array([QWERTY_CENTERS[c] for c in QWERTY_LETTERS], dtype=np.float32)
        self.augment = augment
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int):
        record = self.records[index]
        xs = np.array(record["xs"], dtype=np.float32)
        ys = np.array(record["ys"], dtype=np.float32)
        ts = np.array(record["ts"], dtype=np.float64)
        width = float(record.get("canvas_width") or xs.max() or 1.0)
        height = float(record.get("canvas_height") or ys.max() or 1.0)
        xs = xs / width
        ys = ys / height
        key_centers = self.key_centers

        word = record["word"].lower()
        reversed_word = False
        if self.augment:
            xs, ys, key_centers, reversed_word = augment_trajectory_and_layout(
                xs, ys, key_centers, self.rng)

        if reversed_word:
            word = word[::-1]
        # Letters this layout doesn't have (punctuation, digits, an apostrophe) are dropped
        # rather than failing the sample -- the same "untypeable letters are skipped, never
        # guessed" rule the geometric engine already follows for the same reason.
        target = [self.letter_index[c] + 1 for c in word if c in self.letter_index]  # +1: 0 = blank

        resampled = resample_uniform_time(xs, ys, ts)
        if resampled is None or len(target) == 0:
            return None
        features = build_features(*resampled)
        return features, np.array(target, dtype=np.int64), key_centers


def collate(batch):
    batch = [b for b in batch if b is not None]
    features = torch.from_numpy(np.stack([b[0] for b in batch]))
    targets = [torch.from_numpy(b[1]) for b in batch]
    target_lengths = torch.tensor([len(t) for t in targets], dtype=torch.int64)
    targets_flat = torch.cat(targets)
    # Every sample in a batch was built from the SAME QWERTY_CENTERS, only perturbed by
    # augmentation independently per sample -- so key centres are per-sample, not shared, exactly
    # like the trajectory they were perturbed alongside.
    key_centers = torch.from_numpy(np.stack([b[2] for b in batch]))
    return features, targets_flat, target_lengths, key_centers


def ctc_and_emission_loss(model: TcnEncoder, features: torch.Tensor, targets: torch.Tensor,
                          target_lengths: torch.Tensor, key_centers: torch.Tensor) -> torch.Tensor:
    intention, spectral = model(features)  # (B,T), (B,T,64)
    batch_size = features.shape[0]

    # One basis matrix per sample, since augmentation perturbs each sample's key centres
    # independently -- unlike inference, where one basis serves every gesture on one layout.
    # Batched, not a Python loop over the batch: dct_basis and key_log_probs both broadcast over
    # leading dimensions precisely so this scales to a thousand-sample batch without a thousand
    # separate small matmuls -- the difference between an epoch and an afternoon at this size.
    basis = dct_basis(key_centers)  # (B, K, 64)
    blank_lp, char_lp = key_log_probs(spectral, intention, basis)  # (B,T), (B,T,K)
    # blank at index 0 to match PackedTrie's own kTerminalSymbol=0 convention (a coincidence of
    # index, not of meaning -- see tcn_ctc_decoder.hpp's own note on this).
    log_probs = torch.cat([blank_lp.unsqueeze(-1), char_lp], dim=-1)  # (B, T, K+1)
    log_probs = log_probs.transpose(0, 1)  # (T, B, K+1), what ctc_loss expects

    input_lengths = torch.full((batch_size,), log_probs.shape[0], dtype=torch.int64)
    ctc_loss = torch.nn.functional.ctc_loss(
        log_probs, targets, input_lengths, target_lengths, blank=0, zero_infinity=True,
    )

    # Emission-count regularizer: the SUM of the intention gate across the gesture should land
    # near the number of letters actually being typed -- without this, sigmoid(0)=0.5 at every
    # timestep is a cheap local optimum for the gate that the CTC loss alone does not clearly
    # forbid early in training.
    expected_counts = target_lengths.to(intention.dtype)
    actual_counts = intention.sum(dim=1)
    emission_loss = torch.mean((actual_counts - expected_counts) ** 2)

    return ctc_loss + 0.05 * emission_loss


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, default=Path(__file__).parent / "data")
    parser.add_argument("--epochs", type=int, default=120)
    parser.add_argument("--batch-size", type=int, default=1024)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--min-lr", type=float, default=2e-5)
    parser.add_argument("--weight-decay", type=float, default=1e-4)
    parser.add_argument("--warmup-fraction", type=float, default=0.05)
    parser.add_argument("--grad-clip", type=float, default=1.0)
    parser.add_argument("--checkpoint", type=Path, default=Path(__file__).parent / "checkpoint.pt")
    parser.add_argument("--resume", action="store_true",
                        help="Continue from --checkpoint's model, optimizer and step count instead "
                             "of starting a fresh model at epoch 0 -- for after a crash, a forced "
                             "restart or a `kill`, none of which this process can protect itself "
                             "from the way an idle-sleep (a laptop lid staying closed while "
                             "caffeinate holds off system sleep) does not even need protecting "
                             "against in the first place. Requires the SAME --epochs, --batch-size "
                             "and --data as the interrupted run, since the cosine schedule and the "
                             "per-epoch step count are both derived from those, not stored "
                             "independently of them.")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else
                        ("mps" if torch.backends.mps.is_available() else "cpu"))
    arguments = parser.parse_args()

    device = torch.device(arguments.device)
    train_set = SwipeDataset(arguments.data / "train.jsonl", augment=True)
    train_loader = DataLoader(train_set, batch_size=arguments.batch_size, shuffle=True,
                              collate_fn=collate, drop_last=True)

    model = TcnEncoder().to(device)
    print(f"TcnEncoder: {model.parameter_count():,} parameters")

    optimizer = torch.optim.AdamW(model.parameters(), lr=arguments.lr,
                                  weight_decay=arguments.weight_decay, betas=(0.9, 0.999))
    total_steps = arguments.epochs * max(1, len(train_loader))
    warmup_steps = max(1, int(total_steps * arguments.warmup_fraction))

    def lr_at(step: int) -> float:
        if step < warmup_steps:
            return arguments.lr * step / warmup_steps
        progress = (step - warmup_steps) / max(1, total_steps - warmup_steps)
        cosine = 0.5 * (1 + math.cos(math.pi * progress))
        return arguments.min_lr + (arguments.lr - arguments.min_lr) * cosine

    start_epoch = 0
    step = 0
    if arguments.resume:
        state = torch.load(arguments.checkpoint, map_location=device)
        model.load_state_dict(state["model"])
        optimizer.load_state_dict(state["optimizer"])
        start_epoch = state["epoch"] + 1
        step = state["step"]
        print(f"resumed from {arguments.checkpoint}: epoch {state['epoch']}, step {step}")

    for epoch in range(start_epoch, arguments.epochs):
        model.train()
        epoch_loss = 0.0
        batches = 0
        for features, targets, target_lengths, key_centers in train_loader:
            for group in optimizer.param_groups:
                group["lr"] = lr_at(step)

            features = features.to(device)
            targets = targets.to(device)
            key_centers = key_centers.to(device)
            target_lengths = target_lengths.to(device)

            optimizer.zero_grad()
            loss = ctc_and_emission_loss(model, features, targets, target_lengths, key_centers)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), arguments.grad_clip)
            optimizer.step()

            epoch_loss += loss.item()
            batches += 1
            step += 1

        print(f"epoch {epoch + 1}/{arguments.epochs}: loss {epoch_loss / max(1, batches):.4f}")
        torch.save({
            "model": model.state_dict(),
            "optimizer": optimizer.state_dict(),
            "epoch": epoch,
            "step": step,
        }, arguments.checkpoint)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
