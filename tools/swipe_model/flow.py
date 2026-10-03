#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""The learned swipe synthesiser: a conditional rectified-flow model of how a real swipe departs
from its ideal path.

A swipe and the polyline through its word's key centres are both resampled to POINTS points by
arc length and measured in key units (a key's width across, its height down). The model learns
the residual, x and y, plus how far each point's time runs ahead of or behind an even pace,
conditioned on the ideal path alone, so it transfers to any layout. The network is a 1-D dilated
convolution over the points with a sinusoidal time embedding; sampling integrates its velocity
field from noise in EULER_STEPS Euler steps. A capture pass then gives the stroke a duration,
from a fit of the corpus's durations to the ideal path's length, and the corpus's own intervals
between samples.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn

POINTS = 64
CHANNELS = 3
CONDITION = 4
EULER_STEPS = 32
WIDTH = 200
DILATIONS = (1, 2, 4, 8, 16, 1, 2, 4, 8, 16)
TIME_FEATURES = 64

# The corpus's QWERTY, as futo_layout gives it: a key 0.1 across and a row a third high.
CORPUS_KEY_WIDTH = 0.1
CORPUS_KEY_HEIGHT = 1.0 / 3.0


def resample(points: np.ndarray, count: int) -> np.ndarray | None:
    """[points] at [count] positions spaced evenly along their length; None for a stroke with none."""
    steps = np.hypot(np.diff(points[:, 0]), np.diff(points[:, 1]))
    along = np.concatenate([[0.0], np.cumsum(steps)])
    if along[-1] <= 1e-9:
        return None
    targets = np.linspace(0.0, along[-1], count)
    return np.stack([np.interp(targets, along, points[:, 0]), np.interp(targets, along, points[:, 1])], axis=1)


def resample_with_time(points: np.ndarray, times: np.ndarray, count: int) -> tuple[np.ndarray, np.ndarray] | None:
    """[resample], with each new point's time interpolated the same way."""
    steps = np.hypot(np.diff(points[:, 0]), np.diff(points[:, 1]))
    along = np.concatenate([[0.0], np.cumsum(steps)])
    if along[-1] <= 1e-9:
        return None
    targets = np.linspace(0.0, along[-1], count)
    xy = np.stack([np.interp(targets, along, points[:, 0]), np.interp(targets, along, points[:, 1])], axis=1)
    return xy, np.interp(targets, along, times)


def condition_of(ideal: np.ndarray) -> np.ndarray:
    """The model's view of an ideal path in key units: its points from its start, its progress, its turning."""
    origin = ideal - ideal[0]
    progress = np.linspace(0.0, 1.0, len(ideal))
    heading = np.arctan2(np.gradient(ideal[:, 1]), np.gradient(ideal[:, 0]))
    turning = np.abs(np.angle(np.exp(1j * np.gradient(heading))))
    return np.stack([origin[:, 0], origin[:, 1], progress, turning], axis=1).astype(np.float32)


def anchors_of(word: str, centres: dict[str, tuple[float, float]]) -> np.ndarray | None:
    points: list[tuple[float, float]] = []
    for character in word:
        position = centres.get(character)
        if position is None:
            return None
        if points and points[-1] == position:
            continue
        points.append(position)
    return np.array(points, dtype=np.float64) if len(points) >= 2 else None


class Block(nn.Module):
    def __init__(self, width: int, dilation: int):
        super().__init__()
        self.norm1 = nn.GroupNorm(8, width)
        self.conv1 = nn.Conv1d(width, width, 3, padding=dilation, dilation=dilation)
        self.film = nn.Linear(TIME_FEATURES, 2 * width)
        self.norm2 = nn.GroupNorm(8, width)
        self.conv2 = nn.Conv1d(width, width, 1)

    def forward(self, x: torch.Tensor, embedding: torch.Tensor) -> torch.Tensor:
        h = self.conv1(torch.nn.functional.silu(self.norm1(x)))
        scale, shift = self.film(embedding).unsqueeze(-1).chunk(2, dim=1)
        h = self.norm2(h) * (1 + scale) + shift
        return x + self.conv2(torch.nn.functional.silu(h))


class FlowNet(nn.Module):
    """The velocity field: (state, condition, flow time) to d state / d flow time."""

    def __init__(self):
        super().__init__()
        self.inp = nn.Conv1d(CHANNELS + CONDITION, WIDTH, 1)
        self.time = nn.Sequential(nn.Linear(TIME_FEATURES, TIME_FEATURES), nn.SiLU(), nn.Linear(TIME_FEATURES, TIME_FEATURES))
        self.blocks = nn.ModuleList(Block(WIDTH, d) for d in DILATIONS)
        self.out = nn.Conv1d(WIDTH, CHANNELS, 1)

    @staticmethod
    def features(t: torch.Tensor) -> torch.Tensor:
        half = TIME_FEATURES // 2
        frequencies = torch.exp(-math.log(1000.0) * torch.arange(half, device=t.device) / half)
        angles = t[:, None] * 1000.0 * frequencies[None, :]
        return torch.cat([torch.sin(angles), torch.cos(angles)], dim=1)

    def forward(self, state: torch.Tensor, condition: torch.Tensor, t: torch.Tensor) -> torch.Tensor:
        embedding = self.time(self.features(t))
        x = self.inp(torch.cat([state, condition], dim=1))
        for block in self.blocks:
            x = block(x, embedding)
        return self.out(x)


def prepare(data: Path, cache: Path) -> dict:
    """The corpus as (condition, residual) pairs, and the duration and interval statistics."""
    if cache.is_file():
        loaded = np.load(cache)
        return {key: loaded[key] for key in loaded.files}
    from futo_layout import load_futo_layout

    centres = {c: (x / CORPUS_KEY_WIDTH, y / CORPUS_KEY_HEIGHT) for c, (x, y) in load_futo_layout("qwerty").items()}
    conditions, residuals, lengths, durations, intervals = [], [], [], [], []
    with data.open(encoding="utf-8") as lines:
        for line in lines:
            record = json.loads(line)
            anchors = anchors_of(record["word"].lower(), centres)
            if anchors is None or len(record["xs"]) < 4:
                continue
            trace = np.stack([np.array(record["xs"]) / CORPUS_KEY_WIDTH, np.array(record["ys"]) / CORPUS_KEY_HEIGHT], axis=1)
            times = np.array(record["ts"], dtype=np.float64)
            times = times - times[0]
            if times[-1] <= 0:
                continue
            ideal = resample(anchors, POINTS)
            real = resample_with_time(trace, times, POINTS)
            if ideal is None or real is None:
                continue
            xy, at = real
            pace = at / at[-1] - np.linspace(0.0, 1.0, POINTS)
            conditions.append(condition_of(ideal))
            residuals.append(np.concatenate([xy - ideal, pace[:, None]], axis=1).astype(np.float32))
            length = float(np.hypot(*np.diff(anchors, axis=0).T).sum())
            lengths.append(length)
            durations.append(float(times[-1]))
            steps = np.diff(times)
            intervals.extend(steps[(steps > 0) & (steps < 200)].tolist()[:8])
    lengths_a = np.array(lengths)
    durations_a = np.array(durations)
    fit = np.polyfit(lengths_a, np.log(durations_a), 1)
    spread = float(np.std(np.log(durations_a) - np.polyval(fit, lengths_a)))
    out = {
        "conditions": np.stack(conditions),
        "residuals": np.stack(residuals),
        "duration_fit": np.array([fit[0], fit[1], spread]),
        "intervals": np.percentile(np.array(intervals), np.linspace(0, 100, 101)),
    }
    np.savez(cache, **out)
    return out


def train(args: argparse.Namespace) -> None:
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
    data = prepare(args.data, args.cache)
    conditions = torch.from_numpy(data["conditions"]).permute(0, 2, 1).contiguous()
    residuals = torch.from_numpy(data["residuals"])
    mean = residuals.mean(dim=(0, 1))
    std = residuals.std(dim=(0, 1))
    targets = ((residuals - mean) / std).permute(0, 2, 1).contiguous()
    model = FlowNet().to(device)
    print(f"flow: {sum(p.numel() for p in model.parameters()):,} parameters, {len(targets):,} swipes", flush=True)
    optimiser = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    steps_per_epoch = math.ceil(len(targets) / args.batch)
    schedule = torch.optim.lr_scheduler.CosineAnnealingLR(optimiser, T_max=args.epochs * steps_per_epoch)
    start = 0
    if args.resume and args.checkpoint.is_file():
        state = torch.load(args.checkpoint, map_location=device)
        model.load_state_dict(state["model"])
        optimiser.load_state_dict(state["optimiser"])
        schedule.load_state_dict(state["schedule"])
        start = state["epoch"]
        print(f"flow: resumed after epoch {start}", flush=True)
    generator = torch.Generator().manual_seed(args.seed)
    for epoch in range(start, args.epochs):
        began = time.time()
        order = torch.randperm(len(targets), generator=generator)
        total = 0.0
        model.train()
        for first in range(0, len(order), args.batch):
            index = order[first:first + args.batch]
            x1 = targets[index].to(device)
            condition = conditions[index].to(device)
            noise = torch.randn_like(x1)
            t = torch.rand(len(index), device=device)
            xt = t[:, None, None] * x1 + (1 - t[:, None, None]) * noise
            loss = torch.nn.functional.mse_loss(model(xt, condition, t), x1 - noise)
            optimiser.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimiser.step()
            schedule.step()
            total += loss.item() * len(index)
        print(f"epoch {epoch + 1}/{args.epochs}: loss {total / len(targets):.4f} ({time.time() - began:.0f} s)", flush=True)
        torch.save({
            "model": model.state_dict(), "optimiser": optimiser.state_dict(), "schedule": schedule.state_dict(),
            "epoch": epoch + 1, "mean": mean, "std": std,
            "duration_fit": torch.from_numpy(data["duration_fit"]), "intervals": torch.from_numpy(data["intervals"]),
        }, args.checkpoint)


class FlowSampler:
    """Synthetic swipes on [layout] from a trained checkpoint, in the layout's pixels and milliseconds."""

    def __init__(self, checkpoint: Path, layout, seed: int = 1):
        state = torch.load(checkpoint, map_location="cpu")
        self.model = FlowNet()
        self.model.load_state_dict(state["model"])
        self.model.eval()
        self.mean = state["mean"]
        self.std = state["std"]
        self.slope, self.intercept, self.spread = (float(v) for v in state["duration_fit"])
        self.intervals = state["intervals"].numpy()
        self.layout = layout
        self.rng = np.random.default_rng(seed)
        self.generator = torch.Generator().manual_seed(seed)

    @torch.no_grad()
    def sample(self, anchors: np.ndarray) -> tuple[np.ndarray, list[int]]:
        unit = np.array([self.layout.key_width, self.layout.key_height])
        keys = anchors / unit
        ideal = resample(keys, POINTS)
        condition = torch.from_numpy(condition_of(ideal)).T.unsqueeze(0)
        state = torch.randn((1, CHANNELS, POINTS), generator=self.generator)
        for step in range(EULER_STEPS):
            t = torch.full((1,), step / EULER_STEPS)
            state = state + self.model(state, condition, t) / EULER_STEPS
        residual = (state[0].T * self.std + self.mean).numpy()
        stroke = (ideal + residual[:, :2]) * unit
        pace = np.maximum.accumulate(np.clip(np.linspace(0.0, 1.0, POINTS) + residual[:, 2], 0.0, 1.0))
        length = float(np.hypot(*np.diff(keys, axis=0).T).sum())
        duration = math.exp(self.slope * length + self.intercept + self.rng.normal() * self.spread)
        at = pace / max(pace[-1], 1e-6) * duration
        times = [0.0]
        while times[-1] < duration:
            times.append(times[-1] + float(np.interp(self.rng.random(), np.linspace(0, 1, 101), self.intervals)))
        times[-1] = duration
        sampled = np.array(times)
        x = np.interp(sampled, at, stroke[:, 0])
        y = np.interp(sampled, at, stroke[:, 1])
        return np.stack([x, y], axis=1), [int(round(v)) for v in sampled]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    t = sub.add_parser("train")
    t.add_argument("--data", type=Path, default=Path("data/train.jsonl"))
    t.add_argument("--cache", type=Path, default=Path("data/flow_pairs.npz"))
    t.add_argument("--checkpoint", type=Path, default=Path("flow.pt"))
    t.add_argument("--epochs", type=int, default=12)
    t.add_argument("--batch", type=int, default=512)
    t.add_argument("--lr", type=float, default=1e-3)
    t.add_argument("--seed", type=int, default=1)
    t.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.command == "train":
        train(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
