#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors

"""The layout-agnostic swipe encoder, in PyTorch -- the single source of truth
`keyboard/src/main/cpp/gesture/tcn_encoder.{hpp,cpp}` and `tcn_weights.hpp` are hand-written to
match. Reimplements the architecture of "FUTO Swipe: Layout-Agnostic Neural Swipe Decoding"
(arXiv:2606.25247), trained here from scratch on the free, MIT-licensed swipe.futo.org corpus --
see docs/licensing.md section 2.5. Nothing in this file loads or was derived from FUTO's own
released weights, which are under a non-free licence.

Every width and count below has a same-named counterpart in TcnEncoder/TcnWeights; if one changes,
the other two must change with it, and `export_weights.py --selftest` is what catches the case
where they didn't.
"""

from __future__ import annotations

import math

import torch
from torch import nn

INPUT_FEATURES = 8       # kTcnFeatureDim
TIMESTEPS_IN = 64        # kTcnTimesteps
TIMESTEPS_OUT = 32       # TcnEncoder::kOutputTimesteps
TRUNK_CHANNELS = 128      # TcnEncoder::kTrunkChannels
EXPANDED_CHANNELS = 512   # TcnEncoder::kExpandedChannels (pre-GLU, "expansion factor 4x")
BLOCK_CHANNELS = 256      # TcnEncoder::kBlockChannels (post-GLU working width)
KERNEL_SIZE = 7           # TcnEncoder::kKernelSize
SE_REDUCED_CHANNELS = 32  # TcnEncoder::kSeReducedChannels
NUM_BLOCKS = 5            # TcnEncoder::kNumBlocks
DILATIONS = (1, 2, 3, 5, 8)
ADAPTER_CHANNELS = 256    # TcnEncoder::kAdapterChannels
ADAPTER_KERNEL = 2
SPECTRAL_DIM = 64         # TcnEncoder::kSpectralDim, 8x8 2D DCT coefficients
DCT_RESOLUTION = 8

GRN_EPSILON = 1e-6
BATCHNORM_EPSILON = 1e-5  # PyTorch's own default; export_weights.py folds it in at this value


class GlobalResponseNorm(nn.Module):
    """ConvNeXt V2's GRN: each channel is rescaled by how large its own response is (an L2 norm
    over the whole gesture) relative to the average response across every channel -- see
    TcnEncoder::runBlock's own comment for the same description in the inference code this must
    match."""

    def __init__(self, channels: int):
        super().__init__()
        self.gamma = nn.Parameter(torch.zeros(channels))
        self.beta = nn.Parameter(torch.zeros(channels))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, T, C)
        gx = torch.norm(x, p=2, dim=1, keepdim=True)  # (B, 1, C), L2 over time
        nx = gx / (gx.mean(dim=-1, keepdim=True) + GRN_EPSILON)
        return self.gamma * (x * nx) + self.beta + x


class SqueezeExcite(nn.Module):
    def __init__(self, channels: int, reduced: int):
        super().__init__()
        self.reduce = nn.Linear(channels, reduced)
        self.expand = nn.Linear(reduced, channels)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        # x: (B, T, C) -> pooled (B, C) -> gate (B, C)
        pooled = x.mean(dim=1)
        gate = torch.sigmoid(self.expand(torch.relu(self.reduce(pooled))))
        return gate.unsqueeze(1)  # (B, 1, C), broadcasts over time


class TcnBlock(nn.Module):
    """One dilated ConvNeXt-style block. Pipeline, matching TcnEncoder::runBlock exactly:
    dilated depthwise conv -> batchnorm -> 1x1 expand -> GLU -> GRN -> 1x1 project ->
    squeeze-excite -> residual sum."""

    def __init__(self, dilation: int):
        super().__init__()
        padding = (KERNEL_SIZE - 1) // 2 * dilation
        self.depthwise = nn.Conv1d(
            TRUNK_CHANNELS, TRUNK_CHANNELS, KERNEL_SIZE,
            dilation=dilation, padding=padding, groups=TRUNK_CHANNELS,
        )
        self.batch_norm = nn.BatchNorm1d(TRUNK_CHANNELS)
        self.expand = nn.Linear(TRUNK_CHANNELS, EXPANDED_CHANNELS)
        self.grn = GlobalResponseNorm(BLOCK_CHANNELS)
        self.project = nn.Linear(BLOCK_CHANNELS, TRUNK_CHANNELS)
        self.se = SqueezeExcite(TRUNK_CHANNELS, SE_REDUCED_CHANNELS)

    def forward(self, trunk: torch.Tensor) -> torch.Tensor:
        # trunk: (B, T, C) throughout -- transposed to (B, C, T) only for the conv itself, which
        # is the one op here that wants channels-first.
        x = trunk.transpose(1, 2)
        x = self.depthwise(x)
        x = self.batch_norm(x)
        x = x.transpose(1, 2)  # back to (B, T, C)

        x = self.expand(x)
        a, b = x.chunk(2, dim=-1)
        gated = a * torch.sigmoid(b)

        gated = self.grn(gated)
        projected = self.project(gated)
        gate = self.se(projected)
        return trunk + projected * gate


class TcnEncoder(nn.Module):
    """The full encoder: input embedding, five dilated blocks, the stride-2 adapter, and the two
    output heads (intention gate, spectral coefficients)."""

    def __init__(self):
        super().__init__()
        self.input_embed = nn.Linear(INPUT_FEATURES, TRUNK_CHANNELS)
        self.blocks = nn.ModuleList(TcnBlock(d) for d in DILATIONS)
        self.adapter = nn.Conv1d(
            TRUNK_CHANNELS, ADAPTER_CHANNELS, ADAPTER_KERNEL, stride=ADAPTER_KERNEL,
        )
        self.adapter_bn = nn.BatchNorm1d(ADAPTER_CHANNELS)
        self.intention_head = nn.Linear(ADAPTER_CHANNELS, 1)
        self.spectral_head = nn.Linear(ADAPTER_CHANNELS, SPECTRAL_DIM)

    def forward(self, features: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """`features`: (B, TIMESTEPS_IN, INPUT_FEATURES). Returns (intention, spectral):
        intention (B, TIMESTEPS_OUT), spectral (B, TIMESTEPS_OUT, SPECTRAL_DIM)."""
        trunk = self.input_embed(features)
        for block in self.blocks:
            trunk = block(trunk)

        adapted = self.adapter(trunk.transpose(1, 2))  # (B, ADAPTER_CHANNELS, TIMESTEPS_OUT)
        adapted = self.adapter_bn(adapted).transpose(1, 2)  # (B, TIMESTEPS_OUT, ADAPTER_CHANNELS)

        intention = torch.sigmoid(self.intention_head(adapted)).squeeze(-1)
        spectral = self.spectral_head(adapted)
        return intention, spectral

    def parameter_count(self) -> int:
        return sum(p.numel() for p in self.parameters())


def dct_basis(key_centers_uv: torch.Tensor) -> torch.Tensor:
    """Phi[k, (u,v)] = cos(pi*u*u_k) * cos(pi*v*v_k) -- the same basis
    TcnCtcDecoder::setLayout builds from KeyGeometry, built here from normalised [0,1]^2 key
    centres for training-time loss computation (the model never sees a layout directly; only the
    trainer, which needs per-key logits to compute the CTC loss, does).

    Batched over any number of leading dimensions, so a per-sample augmented layout (B, K, 2) and
    a single shared one (K, 2) both work without the caller branching on which it has -- the
    batched case is what makes training tractable at all: augmentation perturbs every sample's
    key centres independently, and doing that one sample at a time in a Python loop over a
    thousand-sample batch is the difference between an epoch and an afternoon.

    `key_centers_uv`: (..., K, 2) in [0,1]^2. Returns (..., K, SPECTRAL_DIM).
    """
    u = key_centers_uv[..., 0]  # (..., K)
    v = key_centers_uv[..., 1]
    du = torch.arange(DCT_RESOLUTION, dtype=key_centers_uv.dtype, device=key_centers_uv.device)
    cos_u = torch.cos(math.pi * du * u.unsqueeze(-1))  # (..., K, 8)
    cos_v = torch.cos(math.pi * du * v.unsqueeze(-1))  # (..., K, 8)
    # Outer product per key, flattened to match Phi[k, du*8+dv] in tcn_ctc_decoder.cpp.
    basis = cos_u.unsqueeze(-1) * cos_v.unsqueeze(-2)  # (..., K, 8, 8)
    return basis.reshape(*basis.shape[:-2], SPECTRAL_DIM)


def key_log_probs(spectral: torch.Tensor, intention: torch.Tensor,
                  basis: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
    """The factorized-softmax CTC emission distribution: blank = 1 - intention, character
    emissions = sigmoid(spectral . Phi^T) * intention -- matches
    TcnCtcDecoder::keyLogProbsFor exactly, in log space.

    `spectral`: (B, T, SPECTRAL_DIM). `intention`: (B, T). `basis`: (K, SPECTRAL_DIM) shared
    across the batch, or (B, K, SPECTRAL_DIM) one basis per sample (the training-time case, since
    augmentation perturbs each sample's layout independently).
    Returns (blankLogProb (B,T), charLogProbs (B,T,K)).
    """
    basis_t = basis.transpose(-2, -1)  # (K, D) -> (D, K), or (B, K, D) -> (B, D, K)
    z = spectral @ basis_t  # (B,T,D)@(D,K) or batched (B,T,D)@(B,D,K) -> (B, T, K) either way
    char_log_probs = torch.nn.functional.logsigmoid(z) + torch.log(intention.clamp_min(1e-6)).unsqueeze(-1)
    blank_log_prob = torch.log1p(-intention.clamp(max=0.999999))
    return blank_log_prob, char_log_probs
