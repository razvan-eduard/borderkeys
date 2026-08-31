// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_GESTURE_RESAMPLE_HPP
#define BORDERKEYS_GESTURE_RESAMPLE_HPP

namespace borderkeys {

// Every trajectory the decoder compares -- what the finger did, and what each candidate word
// would look like -- is reduced to the same fixed number of equidistant points first.
//
// 64 is the figure the current literature settled on, and the reason is worth stating: below
// about 32 the curvature that distinguishes "than" from "thin" is gone, and above about 96
// nothing measurable improves while every distance computation costs proportionally more. It
// also matches what the neural tier expects, so tier A and tier B consume identical features
// and can be compared on the same recording.
inline constexpr int kResampleCount = 64;

/**
 * Savitzky-Golay smoothing, seven-sample window, quadratic fit.
 *
 * Applied before anything is measured. A touch driver reports at whatever rate it likes and the
 * finger itself shakes; both put high-frequency noise into the path that the curvature and
 * velocity features would otherwise read as signal. A moving average would smooth the noise and
 * flatten the corners with it -- and the corners are where a swipe changes letter. A
 * Savitzky-Golay filter fits a low-order polynomial instead, so it removes the jitter and
 * leaves the turns.
 *
 * `in` and `out` may not overlap. Fewer than seven samples are copied through: there is nothing
 * to fit.
 */
void savitzkyGolaySmooth(const float* in, int count, float* out);

/** Total length of the polyline. */
float pathLength(const float* xs, const float* ys, int count);

/**
 * Resamples to `outCount` points spaced equally along the path.
 *
 * By arc length, not by time. Two people writing the same word at different speeds produce very
 * different time series and nearly identical shapes, and the shape is the thing being matched.
 * Returns false when the path has no length -- a tap, or a gesture that did not move.
 */
bool resamplePath(const float* xs, const float* ys, int count,
                  float* outX, float* outY, int outCount);

/**
 * Translates to the centroid and scales so the longer side of the bounding box is 1.
 *
 * This is SHARK²'s shape channel: after it, where on the keyboard the gesture happened and how
 * large it was are gone, and only its form remains. That is deliberate -- and it is also why
 * the location channel exists separately, because a shape alone cannot tell "were" from "tie".
 */
void normaliseShape(const float* xs, const float* ys, int count, float* outX, float* outY);

}  // namespace borderkeys

#endif  // BORDERKEYS_GESTURE_RESAMPLE_HPP
