#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Loads any of FUTO's published key layouts from swipe.futo.org's own dataset repository --
generic over the layout name, not a QWERTY-specific transcription.

`swipe-5/layouts/` (MIT, the dataset's own licence) holds eleven of these: azerty, clearflow,
dvorak, german, kasroz, lithuanian_qwerty, qwerty, qwertz, shavian, spanish, toki_pona -- the same
ones the `swipe-5` collection run's `layout` column names, per its own README: "This collection
expands to different languages and layouts to validate the encoder model's generalization
capabilities." Training (train.py) uses only "qwerty", matching the paper's own choice ("English
swipe.futo.org training split... No other layouts in training set") -- everything else here exists
so eval_layouts.py can measure the model on layouts it never saw a single real example of, which
is the actual claim being tested, not an assumption backing it.

BorderKeys itself ships only a QWERTY layout today (keyboard/src/main/assets/layouts/qwerty.json)
-- these are not a second source for THAT file, or for anything the app loads; they exist only to
train and evaluate this model against real key geometries.
"""

from __future__ import annotations

import json

DATASET_ID = "futo-org/swipe.futo.org"
LAYOUT_NAMES = (
    "azerty", "clearflow", "dvorak", "german", "kasroz", "lithuanian_qwerty",
    "qwerty", "qwertz", "shavian", "spanish", "toki_pona",
)


def load_futo_layout(name: str) -> dict[str, tuple[float, float]]:
    """`name` is one of [LAYOUT_NAMES]. Returns letter -> (cx, cy), already normalised to
    [0,1]^2 -- exactly the space resampleUniformTime and TcnCtcDecoder's basis matrix both work
    in, so nothing downstream of this function rescales anything.

    Cached by `huggingface_hub` itself after the first call, the same as every corpus shard
    prepare_corpus.py pulls -- there is no separate local copy for this script to keep in sync.
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
    """A fixed order for a layout's own letters -- key index order everywhere a per-key tensor
    position matters (the DCT basis, the CTC target alphabet). Alphabetical, since that is at
    least a documented, reproducible choice rather than the source JSON's own list order, and
    because two layouts sharing the same alphabet (QWERTY and Dvorak both use a-z) must produce
    the SAME index for the SAME letter, or a checkpoint trained against one could not even be
    asked a question about the other."""
    return tuple(sorted(centers.keys()))
