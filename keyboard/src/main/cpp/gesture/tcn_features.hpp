// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_FEATURES_HPP
#define BORDERKEYS_GESTURE_TCN_FEATURES_HPP

#include <cstdint>

namespace borderkeys {

// Touch points in, features for [TcnEncoder] out, resampled by time.

/** Timesteps into the encoder. */
inline constexpr int kTcnTimesteps = 64;

/** Position, velocity, acceleration, speed, curvature -- interleaved per timestep. */
inline constexpr int kTcnFeatureDim = 8;

/**
 * Resamples a raw touch trace to [kTcnTimesteps] points evenly spaced in time, interpolating
 * linearly, then normalises the coordinates to [0,1]^2 by the key-area extents. False for fewer
 * than 2 points or a duration of zero.
 */
bool resampleUniformTime(const float* xs, const float* ys, const int64_t* ts, int count,
                         float keyAreaWidth, float keyAreaHeight, float* outX, float* outY);

/**
 * Builds the per-timestep features of a time-resampled, normalised trajectory: position,
 * velocity and acceleration by central differences (one-sided at the ends), speed, and curvature
 * as the central difference of the heading `atan2(vy, vx)`, clamped to [-2, 2] radians per
 * sample. `outFeatures` is `count * kTcnFeatureDim` floats, interleaved
 * (x,y,vx,vy,ax,ay,speed,curvature) per timestep.
 */
void buildTcnFeatures(const float* xs, const float* ys, int count, float* outFeatures);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_FEATURES_HPP
