// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "tcn_features.hpp"

#include <cmath>

namespace borderkeys {
namespace {
constexpr float kPi = 3.14159265358979323846f;
}  // namespace

bool resampleUniformTime(const float* xs, const float* ys, const int64_t* ts, int count,
                         float keyAreaWidth, float keyAreaHeight, float* outX, float* outY) {
    if (xs == nullptr || ys == nullptr || ts == nullptr || count < 2 ||
        !(keyAreaWidth > 0.f) || !(keyAreaHeight > 0.f)) {
        return false;
    }
    const int64_t startTime = ts[0];
    const int64_t duration = ts[count - 1] - startTime;
    if (duration <= 0) {
        return false;
    }

    // Two-pointer walk: `segment` only ever advances, since both the raw samples and the target
    // times are non-decreasing. Bounded to count-2 so segment+1 is always a valid index.
    int segment = 0;
    for (int i = 0; i < kTcnTimesteps; ++i) {
        const double u = static_cast<double>(i) / static_cast<double>(kTcnTimesteps - 1);
        const int64_t targetTime =
            startTime + static_cast<int64_t>(u * static_cast<double>(duration) + 0.5);
        while (segment < count - 2 && ts[segment + 1] < targetTime) {
            ++segment;
        }
        const int64_t t0 = ts[segment];
        const int64_t t1 = ts[segment + 1];
        const float frac = (t1 > t0)
            ? static_cast<float>(targetTime - t0) / static_cast<float>(t1 - t0)
            : 0.f;
        outX[i] = (xs[segment] + (xs[segment + 1] - xs[segment]) * frac) / keyAreaWidth;
        outY[i] = (ys[segment] + (ys[segment + 1] - ys[segment]) * frac) / keyAreaHeight;
    }
    return true;
}

void buildTcnFeatures(const float* xs, const float* ys, int count, float* outFeatures) {
    if (count <= 0) {
        return;
    }
    // Generic over `count` rather than hard-wired to kTcnTimesteps, so a native test can exercise
    // this directly on a small hand-written trajectory without also exercising the resampler.
    constexpr int kMax = kTcnTimesteps;
    const int n = (count < kMax) ? count : kMax;
    float vx[kMax] = {};
    float vy[kMax] = {};

    // Central differences (forward/backward at the two ends), computed in a first pass because
    // acceleration below needs a velocity's neighbours, not just its own timestep.
    for (int i = 0; i < n; ++i) {
        const int prev = (i > 0) ? i - 1 : i;
        const int next = (i < n - 1) ? i + 1 : i;
        const float denom = static_cast<float>(next - prev);
        vx[i] = (denom > 0.f) ? (xs[next] - xs[prev]) / denom : 0.f;
        vy[i] = (denom > 0.f) ? (ys[next] - ys[prev]) / denom : 0.f;
    }

    float previousAngle = 0.f;
    for (int i = 0; i < n; ++i) {
        const int prev = (i > 0) ? i - 1 : i;
        const int next = (i < n - 1) ? i + 1 : i;
        const float denom = static_cast<float>(next - prev);
        const float ax = (denom > 0.f) ? (vx[next] - vx[prev]) / denom : 0.f;
        const float ay = (denom > 0.f) ? (vy[next] - vy[prev]) / denom : 0.f;
        const float speed = std::sqrt(vx[i] * vx[i] + vy[i] * vy[i]);

        const float angle = std::atan2(vy[i], vx[i]);
        float curvature = 0.f;
        if (i > 0) {
            float delta = angle - previousAngle;
            // Wrapped to (-pi, pi] before being read as a turn rate: a heading that crosses from
            // just under +pi to just under -pi is a small turn, not the almost-full-circle one
            // the unwrapped difference would say it was.
            while (delta > kPi) {
                delta -= 2.f * kPi;
            }
            while (delta < -kPi) {
                delta += 2.f * kPi;
            }
            curvature = delta;
        }
        curvature = curvature < -2.f ? -2.f : (curvature > 2.f ? 2.f : curvature);
        previousAngle = angle;

        float* const out = outFeatures + i * kTcnFeatureDim;
        out[0] = xs[i];
        out[1] = ys[i];
        out[2] = vx[i];
        out[3] = vy[i];
        out[4] = ax;
        out[5] = ay;
        out[6] = speed;
        out[7] = curvature;
    }
}

}  // namespace borderkeys
