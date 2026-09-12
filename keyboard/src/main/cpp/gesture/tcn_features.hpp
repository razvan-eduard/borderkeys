// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_TCN_FEATURES_HPP
#define BORDERKEYS_GESTURE_TCN_FEATURES_HPP

#include <cstdint>

namespace borderkeys {

// Points in, features for [TcnEncoder] out.
//
// Separate from [resamplePath] (resample.hpp) on purpose, not a variant of it: that one resamples
// by ARC LENGTH, so that a word swiped fast and the same word swiped slow produce the same shape
// -- exactly right for SHARK2's template matching, and exactly wrong here. This encoder's feature
// vector includes velocity and acceleration, which arc-length resampling would erase by
// construction (every resampled step covers the same distance, so a naive derivative of it is
// pure noise). This file resamples by TIME instead, so speed is still speed.

/** Timesteps into the encoder -- matches [kResampleCount] in resample.hpp only by coincidence of
 *  both settling on the literature's usual figure; the two are resampled by different rules. */
inline constexpr int kTcnTimesteps = 64;

/** Position, velocity, acceleration, speed, curvature -- interleaved per timestep. */
inline constexpr int kTcnFeatureDim = 8;

/**
 * Resamples a raw touch trace to [kTcnTimesteps] points evenly spaced in TIME (linear
 * interpolation between the two raw samples straddling each target time), then normalises the
 * coordinates to [0,1]^2 by the key-area extents the caller measured the trace in.
 *
 * Returns false for a trace with fewer than 2 points or a total duration of zero -- a tap, not a
 * gesture, and the caller's job to have already filtered out before this is asked.
 */
bool resampleUniformTime(const float* xs, const float* ys, const int64_t* ts, int count,
                         float keyAreaWidth, float keyAreaHeight, float* outX, float* outY);

/**
 * Builds the [kTcnFeatureDim]-wide per-timestep feature vector from a time-uniformly resampled,
 * already-normalised trajectory: position as given, velocity and acceleration by central
 * differences of the trace (forward/backward difference at the two ends), speed as their
 * magnitude, and curvature as the central difference of the heading angle `atan2(vy, vx)`,
 * clamped to [-2, 2] radians per sample -- an unclamped angle derivative spikes without bound at
 * a near-stationary point, where the heading itself is undefined.
 *
 * `outFeatures` is `count * kTcnFeatureDim` floats, interleaved (x,y,vx,vy,ax,ay,speed,curvature)
 * per timestep.
 */
void buildTcnFeatures(const float* xs, const float* ys, int count, float* outFeatures);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_TCN_FEATURES_HPP
