#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""The coordinated trajectory+layout augmentation from "FUTO Swipe: Layout-Agnostic Neural Swipe
Decoding" -- the same random scale/shear/flip/rotation/translation applied to the swipe AND to the
layout's key centres together, every training step.

Step 6 (translation) is a rescale-and-refit rather than the paper's rejection sampling; either
keeps the gesture and the keys inside the visible keyboard.
"""

from __future__ import annotations

import numpy as np


def augment_trajectory_and_layout(
    xs: np.ndarray, ys: np.ndarray, key_centers: np.ndarray, rng: np.random.Generator,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, bool]:
    """`xs`/`ys` and `key_centers` (K, 2) already normalised to [0,1]^2. Returns the transformed
    trajectory, the transformed key centres, and whether time was reversed (the caller must then
    also reverse the target word, to match)."""
    points = np.stack([xs, ys], axis=-1)
    all_points = np.concatenate([points, key_centers], axis=0)
    centroid = all_points.mean(axis=0)

    # 1-2: independent x/y scale.
    scale = np.array([rng.uniform(0.85, 1.0), rng.uniform(0.75, 1.0)])
    # 3: shear.
    sxy, syx = rng.uniform(-0.05, 0.05), rng.uniform(-0.05, 0.05)
    shear = np.array([[1.0, sxy], [syx, 1.0]])
    # 4: independent flips.
    flip = np.array([-1.0 if rng.random() < 0.5 else 1.0,
                     -1.0 if rng.random() < 0.5 else 1.0])
    # 5: rotation, full circle, around the shared centroid.
    theta = rng.uniform(0.0, 2 * np.pi)
    cos_t, sin_t = np.cos(theta), np.sin(theta)
    rotation = np.array([[cos_t, -sin_t], [sin_t, cos_t]])

    centered = (all_points - centroid) * scale * flip
    centered = centered @ shear.T
    centered = centered @ rotation.T
    transformed = centered + centroid

    # 6: translation, by rescale-and-refit rather than rejection sampling -- see module doc.
    min_xy = transformed.min(axis=0)
    max_xy = transformed.max(axis=0)
    span = np.maximum(max_xy - min_xy, 1e-3)
    fitted_scale = np.minimum(1.0 / span, 1.0)  # only shrink, never grow past the transform's own scale
    fitted = (transformed - min_xy) * fitted_scale
    slack = np.maximum(1.0 - fitted.max(axis=0), 0.0)
    origin = np.array([rng.uniform(0.0, s) if s > 0 else 0.0 for s in slack])
    fitted = fitted + origin

    n = len(points)
    new_points = fitted[:n]
    new_keys = fitted[n:]

    # 7: time reversal.
    reversed_time = rng.random() < 0.1
    if reversed_time:
        new_points = new_points[::-1]

    # Computed in float64; cast once to the float32 the model and the C++ side use.
    return (new_points[:, 0].astype(np.float32).copy(),
           new_points[:, 1].astype(np.float32).copy(),
           new_keys.astype(np.float32), reversed_time)
