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

from architecture import BATCHNORM_EPSILON, DESCRIPTOR_FIELDS, expected_float_count

# torch and model are imported where they are used: --upgrade rewrites a file's header and needs
# neither, so a .bkw can be brought to the current version without a training environment.

MAGIC = 0x3157424B  # 'B' 'K' 'W' '1', little-endian -- must equal TcnWeights::kMagic
VERSION = 3          # must equal TcnWeights::kVersion -- bumped for the architecture descriptor
RESERVED = 0

EXPECTED_FLOAT_COUNT = expected_float_count()


def header_bytes() -> bytes:
    """Magic, version, the architecture descriptor, the payload's float count, one reserved word.

    TcnWeights::describeMismatch checks every field against its own constant and names the first
    that differs, so a model exported for another shape is refused by name rather than loading as
    a different network.
    """
    words = [MAGIC, VERSION]
    words += [value for _name, value in DESCRIPTOR_FIELDS]
    words += [EXPECTED_FLOAT_COUNT, RESERVED]
    return struct.pack(f"<{len(words)}I", *words)


def fold_batch_norm(bn):  # torch.nn.BatchNorm1d -> (scale, bias)
    """An inference-only engine has no running mean/variance to track -- only the single affine
    transform they collapse into with the learned scale and shift, computed once, here."""
    import torch  # noqa: PLC0415 -- see the import note at the top

    scale = bn.weight / torch.sqrt(bn.running_var + BATCHNORM_EPSILON)
    bias = bn.bias - bn.running_mean * scale
    return scale, bias


def export_tensors(model, key_embedding) -> list:
    """Every weight, in the exact order tcn_weights.hpp's TcnWeights declares them, each already
    reshaped/transposed/flattened into the row-major (input-major) layout the C++ side reads."""
    tensors = []

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

    tensors.append(key_embedding.hidden.weight.t().reshape(-1))  # [96,66] -> [66,96]
    tensors.append(key_embedding.hidden.bias)
    tensors.append(key_embedding.output.weight.t().reshape(-1))  # [64,96] -> [96,64]
    tensors.append(key_embedding.output.bias)

    return tensors


def write_bkw(model, key_embedding, out_path: Path) -> None:
    model.eval()
    key_embedding.eval()
    tensors = export_tensors(model, key_embedding)
    total = sum(t.numel() for t in tensors)
    if total != EXPECTED_FLOAT_COUNT:
        raise ValueError(
            f"exported {total} floats but the architecture constants say "
            f"{EXPECTED_FLOAT_COUNT} -- model.py and tcn_weights.hpp have drifted apart",
        )
    with out_path.open("wb") as f:
        f.write(header_bytes())
        for tensor in tensors:
            f.write(tensor.detach().cpu().numpy().astype("<f4").tobytes())


def read_bkw(path: Path) -> tuple[int, int, int]:
    """A minimal reader for --selftest: returns (magic, version, float_count) without knowing
    anything about the architecture, the same "can a second implementation agree with the first"
    check tools/build_dict.py's PackReader performs for the dictionary format."""
    data = path.read_bytes()
    magic, version = struct.unpack_from("<II", data, 0)
    remaining = len(data) - len(header_bytes())
    if remaining % 4 != 0:
        raise ValueError(f"{remaining} bytes after the header is not a whole number of floats")
    return magic, version, remaining // 4


def upgrade(path: Path, out_path: Path) -> int:
    """Rewrites an older file's header for the current version, keeping its payload byte for byte.

    The payload has not changed shape since version 2, so the weights carry across untouched --
    which is what makes this safe without the checkpoint that produced them, and why it needs
    neither torch nor a training environment. A file whose float count does not match this
    architecture is refused rather than relabelled.
    """
    data = path.read_bytes()
    magic, version = struct.unpack_from("<II", data, 0)
    if magic != MAGIC:
        raise ValueError(f"{path} is not a .bkw file (magic {magic:#x})")
    known_header = {2: 8, VERSION: len(header_bytes())}
    if version not in known_header:
        raise ValueError(f"{path} is version {version}; only {sorted(known_header)} are readable")
    payload = data[known_header[version]:]
    floats = len(payload) // 4
    if len(payload) % 4 != 0 or floats != EXPECTED_FLOAT_COUNT:
        raise ValueError(
            f"{path} carries {floats} floats, not this architecture's {EXPECTED_FLOAT_COUNT}",
        )
    out_path.write_bytes(header_bytes() + payload)
    print(f"upgraded {path} (v{version}) -> {out_path} (v{VERSION}), "
          f"{floats:,} floats unchanged")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=None,
                        help="A train.py checkpoint (state_dict). Omit with --selftest.")
    parser.add_argument("--out", type=Path, default=Path(__file__).parent / "model.bkw")
    parser.add_argument("--upgrade", type=Path, default=None,
                        help="Rewrite this .bkw file's header for the current version, keeping "
                             "its weights. Needs no checkpoint and no torch.")
    parser.add_argument("--selftest", action="store_true",
                        help="Export a freshly initialised (untrained) model and verify it "
                             "round-trips, instead of exporting a real checkpoint.")
    arguments = parser.parse_args()

    if arguments.upgrade is not None:
        return upgrade(arguments.upgrade, arguments.out)

    from model import KeyEmbedding, TcnEncoder  # noqa: PLC0415 -- see the import note above

    if arguments.selftest:
        model = TcnEncoder()
        key_embedding = KeyEmbedding()
        write_bkw(model, key_embedding, arguments.out)
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
    key_embedding = KeyEmbedding()
    state = torch.load(arguments.checkpoint, map_location="cpu")
    model.load_state_dict(state["model"] if "model" in state else state)
    key_embedding.load_state_dict(state["key_embedding"])
    write_bkw(model, key_embedding, arguments.out)
    print(f"wrote {arguments.out} ({arguments.out.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
