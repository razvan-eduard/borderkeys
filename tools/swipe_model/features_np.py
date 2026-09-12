#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""The numpy mirror of keyboard/src/main/cpp/gesture/tcn_features.{hpp,cpp} -- same resampling
rule (evenly spaced in TIME, not arc length), same 8-D feature vector, so training and inference
extract identical features from identical input. See that file's own doc for why arc-length
resampling (the OTHER decoder's resample.cpp) is the wrong choice here."""

from __future__ import annotations

import numpy as np

TIMESTEPS = 64      # kTcnTimesteps
FEATURE_DIM = 8      # kTcnFeatureDim


def resample_uniform_time(xs: np.ndarray, ys: np.ndarray, ts: np.ndarray) -> tuple[np.ndarray, np.ndarray] | None:
    """`xs`/`ys` already normalised to [0,1]^2. Returns None for a zero-duration trace -- a tap,
    not a gesture, the same refusal tcn_features.cpp's resampleUniformTime makes."""
    duration = ts[-1] - ts[0]
    if duration <= 0:
        return None
    target_times = ts[0] + (np.arange(TIMESTEPS, dtype=np.float64) / (TIMESTEPS - 1) * duration)
    out_x = np.interp(target_times, ts, xs).astype(np.float32)
    out_y = np.interp(target_times, ts, ys).astype(np.float32)
    return out_x, out_y


def build_features(xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
    """Position, velocity, acceleration, speed, curvature -- central differences, matching
    buildTcnFeatures's forward/backward difference at the two ends. Returns (N, FEATURE_DIM)."""
    n = len(xs)
    idx = np.arange(n)
    prev = np.maximum(idx - 1, 0)
    nxt = np.minimum(idx + 1, n - 1)
    denom = (nxt - prev).astype(np.float32)

    vx = (xs[nxt] - xs[prev]) / denom
    vy = (ys[nxt] - ys[prev]) / denom
    ax = (vx[nxt] - vx[prev]) / denom
    ay = (vy[nxt] - vy[prev]) / denom
    speed = np.sqrt(vx ** 2 + vy ** 2)

    angle = np.arctan2(vy, vx)
    delta = np.diff(angle, prepend=angle[0])
    # Wrapped to (-pi, pi] before being read as a turn rate -- same reason tcn_features.cpp wraps
    # it: a heading crossing from just under +pi to just under -pi is a small turn, not almost a
    # full circle.
    delta = (delta + np.pi) % (2 * np.pi) - np.pi
    curvature = np.clip(delta, -2.0, 2.0)

    return np.stack([xs, ys, vx, vy, ax, ay, speed, curvature], axis=-1).astype(np.float32)
