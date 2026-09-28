#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Loads any of FUTO's published key layouts, by name, from swipe.futo.org's dataset repository.

`swipe-5/layouts/` (MIT) holds eleven: azerty, clearflow, dvorak, german, kasroz,
lithuanian_qwerty, qwerty, qwertz, shavian, spanish, toki_pona. Training (train.py) uses only
"qwerty", as the paper does; eval_layouts.py measures the others.
"""

from __future__ import annotations

import json

DATASET_ID = "futo-org/swipe.futo.org"
LAYOUT_NAMES = (
    "azerty", "clearflow", "dvorak", "german", "kasroz", "lithuanian_qwerty",
    "qwerty", "qwertz", "shavian", "spanish", "toki_pona",
)


def load_futo_layout(name: str) -> dict[str, tuple[float, float]]:
    """`name` is one of [LAYOUT_NAMES]. Returns letter -> (cx, cy), normalised to [0,1]^2, the
    space resampleUniformTime and TcnCtcDecoder's basis matrix work in. Cached by
    `huggingface_hub` after the first call.
    """
    if name not in LAYOUT_NAMES:
        raise ValueError(f"{name!r} is not one of FUTO's published layouts: {LAYOUT_NAMES}")

    from huggingface_hub import hf_hub_download

    path = hf_hub_download(
        repo_id=DATASET_ID, filename=f"swipe-5/layouts/{name}.json", repo_type="dataset",
    )
    with open(path, encoding="utf-8") as f:
        layout = json.load(f)
    return {key["letter"]: (key["cx"], key["cy"]) for key in layout["keys"]}


def letters_in_order(centers: dict[str, tuple[float, float]]) -> tuple[str, ...]:
    """A fixed order for a layout's own letters, the key index order everywhere a per-key tensor
    position matters (the DCT basis, the CTC target alphabet): alphabetical, so two layouts
    sharing an alphabet give the same index to the same letter."""
    return tuple(sorted(centers.keys()))
