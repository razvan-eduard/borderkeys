// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_RESAMPLE_HPP
#define BORDERKEYS_GESTURE_RESAMPLE_HPP

namespace borderkeys {

// How many equidistant points every trajectory the decoder compares is resampled to.
inline constexpr int kResampleCount = 64;

/**
 * Savitzky-Golay smoothing, seven-sample window, quadratic fit. `in` and `out` may not overlap;
 * fewer than seven samples are copied through.
 */
void savitzkyGolaySmooth(const float* in, int count, float* out);

/** Total length of the polyline. */
float pathLength(const float* xs, const float* ys, int count);

/**
 * Resamples to `outCount` points spaced equally along the path by arc length; false when the
 * path has no length.
 */
bool resamplePath(const float* xs, const float* ys, int count,
                  float* outX, float* outY, int outCount);

/**
 * Translates to the centroid and scales so the longer side of the bounding box is 1: SHARK²'s
 * shape channel.
 */
void normaliseShape(const float* xs, const float* ys, int count, float* outX, float* outY);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_RESAMPLE_HPP
