// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_weights.hpp"

#include <cstring>

namespace borderkeys {

bool TcnWeights::loadFromBytes(const uint8_t* data, size_t length) {
    if (data == nullptr || length != kHeaderBytes + kTcnWeightsFloatCount * sizeof(float)) {
        return false;
    }
    uint32_t magic = 0;
    uint32_t version = 0;
    std::memcpy(&magic, data, sizeof(magic));
    std::memcpy(&version, data + sizeof(magic), sizeof(version));
    if (magic != kMagic || version != kVersion) {
        return false;
    }
    std::memcpy(this, data + kHeaderBytes, sizeof(TcnWeights));
    return true;
}

}  // namespace borderkeys
