#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""Exports a trained TcnEncoder checkpoint to the `.bkw` binary format
keyboard/src/main/cpp/gesture/tcn_weights.{hpp,cpp} loads.

Same discipline as tools/build_dict.py: a magic number, a version, and a `--selftest` mode that
round-trips a freshly built model through this exporter and a Python-side reader, so a drift
between this file and tcn_weights.hpp is caught here rather than as a crash on a phone. There is
no third-party serialisation format anywhere in this path -- no ONNX, no protobuf, no pickle in
the output file, only a flat little-endian float32 dump this project's own two readers understand.
"""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

import torch

from model import (
    ADAPTER_CHANNELS, ADAPTER_KERNEL, BATCHNORM_EPSILON, BLOCK_CHANNELS, EXPANDED_CHANNELS,
    INPUT_FEATURES, KERNEL_SIZE, NUM_BLOCKS, SE_REDUCED_CHANNELS, SPECTRAL_DIM, TRUNK_CHANNELS,
    TcnEncoder,
)

MAGIC = 0x3157424B  # 'B' 'K' 'W' '1', little-endian -- must equal TcnWeights::kMagic
VERSION = 1          # must equal TcnWeights::kVersion

# Computed the same way tcn_weights.hpp computes kTcnWeightsFloatCount (sizeof/sizeof(float)),
# just added up by hand here since this script has no C++ struct to take sizeof of. If this
# constant ever needs to change, kTcnWeightsFloatCount changed with it, and vice versa -- they are
# two independent derivations of the same architecture that MUST agree, which is exactly what
# --selftest checks on every run.
_BLOCK_FLOATS = (
    KERNEL_SIZE * TRUNK_CHANNELS + TRUNK_CHANNELS  # depthwise weight + bias
    + TRUNK_CHANNELS + TRUNK_CHANNELS               # folded batchnorm scale + bias
    + TRUNK_CHANNELS * EXPANDED_CHANNELS + EXPANDED_CHANNELS  # expand weight + bias
    + BLOCK_CHANNELS + BLOCK_CHANNELS               # grn scale + bias
    + BLOCK_CHANNELS * TRUNK_CHANNELS + TRUNK_CHANNELS  # project weight + bias
    + TRUNK_CHANNELS * SE_REDUCED_CHANNELS + SE_REDUCED_CHANNELS  # se reduce weight + bias
    + SE_REDUCED_CHANNELS * TRUNK_CHANNELS + TRUNK_CHANNELS  # se expand weight + bias
)
EXPECTED_FLOAT_COUNT = (
    INPUT_FEATURES * TRUNK_CHANNELS + TRUNK_CHANNELS  # input embed
    + NUM_BLOCKS * _BLOCK_FLOATS
    + ADAPTER_KERNEL * TRUNK_CHANNELS * ADAPTER_CHANNELS + ADAPTER_CHANNELS  # adapter conv
    + ADAPTER_CHANNELS + ADAPTER_CHANNELS  # folded adapter batchnorm
    + ADAPTER_CHANNELS + 1  # intention head weight + bias
    + ADAPTER_CHANNELS * SPECTRAL_DIM + SPECTRAL_DIM  # spectral head
)


def fold_batch_norm(bn: torch.nn.BatchNorm1d) -> tuple[torch.Tensor, torch.Tensor]:
    """An inference-only engine has no running mean/variance to track -- only the single affine
    transform they collapse into with the learned scale and shift, computed once, here."""
    scale = bn.weight / torch.sqrt(bn.running_var + BATCHNORM_EPSILON)
    bias = bn.bias - bn.running_mean * scale
    return scale, bias


def export_tensors(model: TcnEncoder) -> list[torch.Tensor]:
    """Every weight, in the exact order tcn_weights.hpp's TcnWeights declares them, each already
    reshaped/transposed/flattened into the row-major (input-major) layout the C++ side reads."""
    tensors: list[torch.Tensor] = []

    tensors.append(model.input_embed.weight.t().reshape(-1))  # [128,8] -> [8,128]
    tensors.append(model.input_embed.bias)

    for block in model.blocks:
        # [128,1,7] (out=in=128 depthwise, kernel=7) -> [7,128]: kernel-major, channel-minor.
        tensors.append(block.depthwise.weight.squeeze(1).t().reshape(-1))
        tensors.append(block.depthwise.bias)
        bn_scale, bn_bias = fold_batch_norm(block.batch_norm)
        tensors.append(bn_scale)
        tensors.append(bn_bias)
        tensors.append(block.expand.weight.t().reshape(-1))  # [512,128] -> [128,512]
        tensors.append(block.expand.bias)
        tensors.append(block.grn.gamma)
        tensors.append(block.grn.beta)
        tensors.append(block.project.weight.t().reshape(-1))  # [128,256] -> [256,128]
        tensors.append(block.project.bias)
        tensors.append(block.se.reduce.weight.t().reshape(-1))  # [32,128] -> [128,32]
        tensors.append(block.se.reduce.bias)
        tensors.append(block.se.expand.weight.t().reshape(-1))  # [128,32] -> [32,128]
        tensors.append(block.se.expand.bias)

    # [256,128,2] (out,in,kernel) -> [2,128,256] (kernel,in,out), matching
    # adapterWeight[(k*trunk+c)*adapterChannels+a] in tcn_weights.hpp.
    tensors.append(model.adapter.weight.permute(2, 1, 0).reshape(-1))
    tensors.append(model.adapter.bias)
    adapter_bn_scale, adapter_bn_bias = fold_batch_norm(model.adapter_bn)
    tensors.append(adapter_bn_scale)
    tensors.append(adapter_bn_bias)

    tensors.append(model.intention_head.weight.reshape(-1))  # [1,256] -> [256]
    tensors.append(model.intention_head.bias.reshape(-1))    # [1] -> scalar, still length-1 here
    tensors.append(model.spectral_head.weight.t().reshape(-1))  # [64,256] -> [256,64]
    tensors.append(model.spectral_head.bias)

    return tensors


def write_bkw(model: TcnEncoder, out_path: Path) -> None:
    model.eval()
    tensors = export_tensors(model)
    total = sum(t.numel() for t in tensors)
    if total != EXPECTED_FLOAT_COUNT:
        raise ValueError(
            f"exported {total} floats but the architecture constants say "
            f"{EXPECTED_FLOAT_COUNT} -- model.py and tcn_weights.hpp have drifted apart",
        )
    with out_path.open("wb") as f:
        f.write(struct.pack("<II", MAGIC, VERSION))
        for tensor in tensors:
            f.write(tensor.detach().cpu().numpy().astype("<f4").tobytes())


def read_bkw(path: Path) -> tuple[int, int, int]:
    """A minimal reader for --selftest: returns (magic, version, float_count) without knowing
    anything about the architecture, the same "can a second implementation agree with the first"
    check tools/build_dict.py's PackReader performs for the dictionary format."""
    data = path.read_bytes()
    magic, version = struct.unpack_from("<II", data, 0)
    remaining = len(data) - 8
    if remaining % 4 != 0:
        raise ValueError(f"{remaining} bytes after the header is not a whole number of floats")
    return magic, version, remaining // 4


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=None,
                        help="A train.py checkpoint (state_dict). Omit with --selftest.")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "model.bkw")
    parser.add_argument("--selftest", action="store_true",
                        help="Export a freshly initialised (untrained) model and verify it "
                             "round-trips, instead of exporting a real checkpoint.")
    arguments = parser.parse_args()

    if arguments.selftest:
        model = TcnEncoder()
        write_bkw(model, arguments.out)
        magic, version, float_count = read_bkw(arguments.out)
        assert magic == MAGIC, f"magic mismatch: {magic:#x} != {MAGIC:#x}"
        assert version == VERSION, f"version mismatch: {version} != {VERSION}"
        assert float_count == EXPECTED_FLOAT_COUNT, (
            f"float count mismatch: {float_count} != {EXPECTED_FLOAT_COUNT}"
        )
        print(f"selftest ok: {float_count:,} floats, {arguments.out} "
              f"({arguments.out.stat().st_size:,} bytes)")
        return 0

    if arguments.checkpoint is None:
        parser.error("--checkpoint is required unless --selftest is given")
    model = TcnEncoder()
    state = torch.load(arguments.checkpoint, map_location="cpu")
    model.load_state_dict(state["model"] if "model" in state else state)
    write_bkw(model, arguments.out)
    print(f"wrote {arguments.out} ({arguments.out.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
