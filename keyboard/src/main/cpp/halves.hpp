// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_HALVES_HPP
#define BORDERKEYS_HALVES_HPP

#include <cstdint>

namespace borderkeys {

/** A span's two halves: what its middle splits it into. */
inline constexpr uint32_t kHalves = 2;

/** The index halfway from [low] to [high], rounded down, without overflowing their sum. */
inline constexpr uint32_t midpoint(uint32_t low, uint32_t high) { return low + (high - low) / kHalves; }

}  // namespace borderkeys

#endif  // BORDERKEYS_HALVES_HPP
