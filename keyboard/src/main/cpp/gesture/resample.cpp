// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "resample.hpp"

#include <cmath>
#include <cstddef>
#include <cstring>

namespace borderkeys {
namespace {

// Quadratic fit over seven samples. Denominator 21. The negative tails are what preserve a
// corner: a moving average would use seven equal weights and round it off.
constexpr int kWindow = 7;
constexpr int kHalfWindow = kWindow / 2;
constexpr float kCoefficients[kWindow] = {-2.f, 3.f, 6.f, 7.f, 6.f, 3.f, -2.f};
constexpr float kNormaliser = 1.f / 21.f;

}  // namespace

void savitzkyGolaySmooth(const float* in, int count, float* out) {
    if (in == nullptr || out == nullptr || count <= 0) {
        return;
    }
    if (count < kWindow) {
        std::memcpy(out, in, static_cast<size_t>(count) * sizeof(float));
        return;
    }
    // The first and last three samples have no full window. Clamping to the edge rather than
    // shrinking the window keeps the endpoints where the finger actually started and stopped,
    // and the endpoints are what the first pruning stage tests.
    for (int i = 0; i < count; ++i) {
        float sum = 0.f;
        for (int k = -kHalfWindow; k <= kHalfWindow; ++k) {
            int index = i + k;
            if (index < 0) {
                index = 0;
            } else if (index >= count) {
                index = count - 1;
            }
            sum += kCoefficients[k + kHalfWindow] * in[index];
        }
        out[i] = sum * kNormaliser;
    }
}

float pathLength(const float* xs, const float* ys, int count) {
    if (xs == nullptr || ys == nullptr || count < 2) {
        return 0.f;
    }
    float total = 0.f;
    for (int i = 1; i < count; ++i) {
        const float dx = xs[i] - xs[i - 1];
        const float dy = ys[i] - ys[i - 1];
        total += std::sqrt(dx * dx + dy * dy);
    }
    return total;
}

bool resamplePath(const float* xs, const float* ys, int count,
                  float* outX, float* outY, int outCount) {
    if (xs == nullptr || ys == nullptr || outX == nullptr || outY == nullptr || outCount < 2) {
        return false;
    }
    if (count <= 0) {
        return false;
    }
    if (count == 1) {
        for (int i = 0; i < outCount; ++i) {
            outX[i] = xs[0];
            outY[i] = ys[0];
        }
        return false;  // a tap is not a gesture, however well it resamples
    }

    const float total = pathLength(xs, ys, count);
    if (!(total > 0.f)) {
        for (int i = 0; i < outCount; ++i) {
            outX[i] = xs[0];
            outY[i] = ys[0];
        }
        return false;
    }

    const float step = total / static_cast<float>(outCount - 1);
    outX[0] = xs[0];
    outY[0] = ys[0];

    int written = 1;
    int segment = 1;
    float travelled = 0.f;      // distance covered up to the start of `segment`
    float target = step;

    while (segment < count && written < outCount - 1) {
        const float dx = xs[segment] - xs[segment - 1];
        const float dy = ys[segment] - ys[segment - 1];
        const float segmentLength = std::sqrt(dx * dx + dy * dy);
        if (segmentLength <= 0.f) {
            ++segment;
            continue;
        }
        // A single segment can contain several output points when the finger moved fast, so
        // this consumes as many as fit before advancing.
        while (travelled + segmentLength >= target && written < outCount - 1) {
            const float ratio = (target - travelled) / segmentLength;
            outX[written] = xs[segment - 1] + ratio * dx;
            outY[written] = ys[segment - 1] + ratio * dy;
            ++written;
            target += step;
        }
        travelled += segmentLength;
        ++segment;
    }

    // Floating point accumulation can leave the last slot or two unfilled; the endpoint is
    // exact by construction rather than by arithmetic.
    while (written < outCount) {
        outX[written] = xs[count - 1];
        outY[written] = ys[count - 1];
        ++written;
    }
    return true;
}

void normaliseShape(const float* xs, const float* ys, int count, float* outX, float* outY) {
    if (xs == nullptr || ys == nullptr || outX == nullptr || outY == nullptr || count <= 0) {
        return;
    }
    float sumX = 0.f;
    float sumY = 0.f;
    float minX = xs[0];
    float maxX = xs[0];
    float minY = ys[0];
    float maxY = ys[0];
    for (int i = 0; i < count; ++i) {
        sumX += xs[i];
        sumY += ys[i];
        if (xs[i] < minX) minX = xs[i];
        if (xs[i] > maxX) maxX = xs[i];
        if (ys[i] < minY) minY = ys[i];
        if (ys[i] > maxY) maxY = ys[i];
    }
    const float centreX = sumX / static_cast<float>(count);
    const float centreY = sumY / static_cast<float>(count);

    // The longer side, so an aspect ratio is preserved. Scaling each axis independently would
    // make a horizontal swipe and a vertical one the same shape.
    float extent = (maxX - minX) > (maxY - minY) ? (maxX - minX) : (maxY - minY);
    if (!(extent > 1e-4f)) {
        extent = 1.f;
    }
    const float inverse = 1.f / extent;
    for (int i = 0; i < count; ++i) {
        outX[i] = (xs[i] - centreX) * inverse;
        outY[i] = (ys[i] - centreY) * inverse;
    }
}

}  // namespace borderkeys
