#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# SPDX-FileCopyrightText: 2026 BorderKeys contributors
"""The encoder's shape, as plain numbers.

Imported by `model.py`, which builds the network from them, and by `export_weights.py`, which
writes them into the `.bkw` header for `TcnWeights::loadFromBytes` to check against its own
constants. No torch here, so a `.bkw` file can be read and rewritten without a training
environment.
"""

INPUT_FEATURES = 8        # kTcnFeatureDim
TIMESTEPS_IN = 64         # kTcnTimesteps
TIMESTEPS_OUT = 32        # TcnEncoder::kOutputTimesteps
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
KEY_EMBED_HIDDEN = 96     # TcnWeights::kKeyEmbedHidden

GRN_EPSILON = 1e-6
BATCHNORM_EPSILON = 1e-5  # PyTorch's own default; export_weights.py folds it in at this value

# The descriptor written into the .bkw header, in the order TcnWeights::Descriptor declares.
# Every entry is checked against its C++ counterpart at load.
DESCRIPTOR_FIELDS = (
    ("inputFeatures", INPUT_FEATURES),
    ("timesteps", TIMESTEPS_IN),
    ("trunk", TRUNK_CHANNELS),
    ("expanded", EXPANDED_CHANNELS),
    ("blockWidth", BLOCK_CHANNELS),
    ("kernelSize", KERNEL_SIZE),
    ("seReduced", SE_REDUCED_CHANNELS),
    ("numBlocks", NUM_BLOCKS),
    ("adapterChannels", ADAPTER_CHANNELS),
    ("adapterKernel", ADAPTER_KERNEL),
    ("spectralDim", SPECTRAL_DIM),
    ("keyEmbedHidden", KEY_EMBED_HIDDEN),
)


def block_floats() -> int:
    """Floats in one residual block, matching TcnWeights::Block."""
    return (
        KERNEL_SIZE * TRUNK_CHANNELS + TRUNK_CHANNELS             # depthwise weight + bias
        + TRUNK_CHANNELS + TRUNK_CHANNELS                          # folded batchnorm
        + TRUNK_CHANNELS * EXPANDED_CHANNELS + EXPANDED_CHANNELS   # expand
        + BLOCK_CHANNELS + BLOCK_CHANNELS                          # grn
        + BLOCK_CHANNELS * TRUNK_CHANNELS + TRUNK_CHANNELS         # project
        + TRUNK_CHANNELS * SE_REDUCED_CHANNELS + SE_REDUCED_CHANNELS   # se reduce
        + SE_REDUCED_CHANNELS * TRUNK_CHANNELS + TRUNK_CHANNELS        # se expand
    )


def expected_float_count() -> int:
    """Every float in the payload, matching kTcnWeightsFloatCount."""
    return (
        INPUT_FEATURES * TRUNK_CHANNELS + TRUNK_CHANNELS
        + NUM_BLOCKS * block_floats()
        + ADAPTER_KERNEL * TRUNK_CHANNELS * ADAPTER_CHANNELS + ADAPTER_CHANNELS
        + ADAPTER_CHANNELS + ADAPTER_CHANNELS
        + ADAPTER_CHANNELS + 1
        + ADAPTER_CHANNELS * SPECTRAL_DIM + SPECTRAL_DIM
        + (2 + SPECTRAL_DIM) * KEY_EMBED_HIDDEN + KEY_EMBED_HIDDEN
        + KEY_EMBED_HIDDEN * SPECTRAL_DIM + SPECTRAL_DIM
    )
