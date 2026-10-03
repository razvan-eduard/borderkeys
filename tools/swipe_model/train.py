#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Trains TcnEncoder (model.py) from scratch on the free swipe.futo.org corpus.

CTC + emission-regularizer loss, AdamW with cosine decay, the coordinated trajectory+layout
augmentation (augment.py) applied fresh every sample every epoch. See docs/licensing.md section
2.5, and model.py for the architecture.
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
from model import TIMESTEPS_OUT, KeyEmbedding, TcnEncoder, key_log_probs

# Training uses only "qwerty", as the paper does (futo_layout.py). QWERTY_LETTERS' order, the
# alphabetical one letters_in_order produces, is the index-to-letter mapping the CTC target
# alphabet and the exported .bkw file both use.
_QWERTY_CENTERS_BY_LETTER = load_futo_layout("qwerty")
QWERTY_LETTERS = letters_in_order(_QWERTY_CENTERS_BY_LETTER)
QWERTY_CENTERS = _QWERTY_CENTERS_BY_LETTER


def load_layout(path: Path | None) -> tuple[tuple[str, ...], dict[str, tuple[float, float]]]:
    """A replay layout's letters and centres, normalised as the runtime does
    (synthesise.ReplayLayout); FUTO's QWERTY when [path] is None."""
    if path is None:
        return QWERTY_LETTERS, QWERTY_CENTERS
    from synthesise import ReplayLayout

    centres = ReplayLayout(path).normalised()
    return letters_in_order(centres), centres


class SwipeDataset(Dataset):
    def __init__(self, jsonl_path: Path, augment: bool, seed: int = 0, layout: Path | None = None):
        self.records = []
        with jsonl_path.open(encoding="utf-8") as f:
            for line in f:
                self.records.append(json.loads(line))
        letters, centres = load_layout(layout)
        self.letter_index = {c: i for i, c in enumerate(letters)}
        self.key_centers = np.array([centres[c] for c in letters], dtype=np.float32)
        # The synthesised words are spelled as the dictionary has them; the letters the layout
        # lacks are written as synthesise.py swiped them.
        from synthesise import long_press, project

        aliases = long_press(layout) if layout is not None else {}
        self.project = (lambda word: project(word, set(letters), aliases) or word) if layout is not None else (lambda word: word)
        self.augment = augment
        self.rng = np.random.default_rng(seed)

    def __len__(self) -> int:
        return len(self.records)

    def __getitem__(self, index: int):
        record = self.records[index]
        # swipe.futo.org stores x/y as a canvas fraction already, in [0,1].
        xs = np.array(record["xs"], dtype=np.float32)
        ys = np.array(record["ys"], dtype=np.float32)
        ts = np.array(record["ts"], dtype=np.float64)
        key_centers = self.key_centers

        word = self.project(record["word"].lower())
        reversed_word = False
        if self.augment:
            xs, ys, key_centers, reversed_word = augment_trajectory_and_layout(
                xs, ys, key_centers, self.rng)

        if reversed_word:
            word = word[::-1]
        # Letters this layout does not have (punctuation, digits, an apostrophe) are dropped.
        target = [self.letter_index[c] + 1 for c in word if c in self.letter_index]  # +1: 0 = blank

        # CTC needs a blank between two adjacent occurrences of the same symbol, so a target
        # with R adjacent-repeated letters needs len(target)+R timesteps at minimum. An example
        # that does not fit is filtered out here; eval_ctc.py evaluates the same set.
        adjacent_repeats = sum(1 for a, b in zip(target, target[1:]) if a == b)
        if len(target) + adjacent_repeats > TIMESTEPS_OUT:
            return None

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
    # Key centres per sample: augmentation perturbs each sample's independently.
    key_centers = torch.from_numpy(np.stack([b[2] for b in batch]))
    return features, targets_flat, target_lengths, key_centers


def ctc_and_emission_loss(model: TcnEncoder, key_embedding: KeyEmbedding, features: torch.Tensor,
                          targets: torch.Tensor, target_lengths: torch.Tensor,
                          key_centers: torch.Tensor) -> torch.Tensor:
    intention, spectral = model(features)  # (B,T), (B,T,64)
    batch_size = features.shape[0]

    # One basis matrix per sample, batched: key_embedding and key_log_probs broadcast over
    # leading dimensions.
    basis = key_embedding(key_centers)  # (B, K, 64)
    blank_lp, char_lp = key_log_probs(spectral, intention, basis)  # (B,T), (B,T,K)
    # blank at index 0, the index of PackedTrie's kTerminalSymbol (tcn_ctc_decoder.hpp).
    log_probs = torch.cat([blank_lp.unsqueeze(-1), char_lp], dim=-1)  # (B, T, K+1)
    log_probs = log_probs.transpose(0, 1)  # (T, B, K+1), what ctc_loss expects

    input_lengths = torch.full((batch_size,), log_probs.shape[0], dtype=torch.int64)
    ctc_loss = torch.nn.functional.ctc_loss(
        log_probs, targets, input_lengths, target_lengths, blank=0, zero_infinity=True,
    )

    # Emission-count regularizer: the sum of the intention gate across the gesture should land
    # near the number of letters being typed.
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
    parser.add_argument("--layout", type=Path, default=None,
                        help="a replay layout (native-tests/data/*.layout) the data was swiped on; "
                             "FUTO's QWERTY when omitted")
    parser.add_argument("--init", type=Path, default=None,
                        help="a checkpoint whose model and key embedding the run starts from")
    parser.add_argument("--device", default="cuda" if torch.cuda.is_available() else
                        ("mps" if torch.backends.mps.is_available() else "cpu"))
    parser.add_argument("--seed", type=int, default=None,
                        help="seeds the weights, the augmentation and the batch order")
    arguments = parser.parse_args()

    generator = None
    if arguments.seed is not None:
        torch.manual_seed(arguments.seed)
        generator = torch.Generator().manual_seed(arguments.seed)
    device = torch.device(arguments.device)
    train_set = SwipeDataset(arguments.data / "train.jsonl", augment=True, layout=arguments.layout,
                             seed=arguments.seed or 0)
    train_loader = DataLoader(train_set, batch_size=arguments.batch_size, shuffle=True,
                              collate_fn=collate, drop_last=True, generator=generator)

    model = TcnEncoder().to(device)
    key_embedding = KeyEmbedding().to(device)
    if arguments.init is not None and not arguments.resume:
        initial = torch.load(arguments.init, map_location=device)
        model.load_state_dict(initial["model"])
        key_embedding.load_state_dict(initial["key_embedding"])
        print(f"started from {arguments.init}")
    print(f"TcnEncoder: {model.parameter_count():,} parameters, "
          f"KeyEmbedding: {key_embedding.parameter_count():,} parameters")

    optimizer = torch.optim.AdamW(
        list(model.parameters()) + list(key_embedding.parameters()), lr=arguments.lr,
        weight_decay=arguments.weight_decay, betas=(0.9, 0.999),
    )
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
        key_embedding.load_state_dict(state["key_embedding"])
        optimizer.load_state_dict(state["optimizer"])
        start_epoch = state["epoch"] + 1
        step = state["step"]
        print(f"resumed from {arguments.checkpoint}: epoch {state['epoch']}, step {step}")

    for epoch in range(start_epoch, arguments.epochs):
        model.train()
        key_embedding.train()
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
            loss = ctc_and_emission_loss(
                model, key_embedding, features, targets, target_lengths, key_centers,
            )
            loss.backward()
            torch.nn.utils.clip_grad_norm_(
                list(model.parameters()) + list(key_embedding.parameters()), arguments.grad_clip,
            )
            optimizer.step()

            epoch_loss += loss.item()
            batches += 1
            step += 1

        print(f"epoch {epoch + 1}/{arguments.epochs}: loss {epoch_loss / max(1, batches):.4f}")
        torch.save({
            "model": model.state_dict(),
            "key_embedding": key_embedding.state_dict(),
            "optimizer": optimizer.state_dict(),
            "epoch": epoch,
            "step": step,
        }, arguments.checkpoint)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
