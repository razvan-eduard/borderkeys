// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "personal_score.hpp"

#include <algorithm>
#include <cmath>

namespace borderkeys {

PersonalScore::PersonalScore(const WordTable& words, const ContextSlice& slice,
                             uint32_t learnAfterUses)
    : words_(words), slice_(slice), learnAfterUses_(learnAfterUses) {}

bool PersonalScore::established(uint32_t entry) const {
    if (words_.asserted(entry) > 0u) {
        return true;
    }
    return learned(words_.entryCount(entry));
}

void PersonalScore::resolve(const ContextSlice::Key& key) {
    context_ = enabled_ && slice_.matches(key);
    largestBonus_ = 0.0f;
    if (!context_) {
        return;
    }
    // The largest bonus: the first link's, chosen on purpose or else the most used, after one
    // word and after two.
    if (slice_.pairCount() > 0) {
        const ContextSlice::Link& top = slice_.pair(0);
        largestBonus_ = bonusFor(top.count, top.asserted, slice_.pairTotal());
    }
    if (hasTriples() && slice_.tripleCount() > 0) {
        const ContextSlice::Link& top = slice_.triple(0);
        largestBonus_ =
            std::max(largestBonus_, bonusFor(top.count, top.asserted, slice_.tripleTotal()));
    }
}

float PersonalScore::wordBoostForCount(uint32_t count) {
    if (count == 0) {
        return 0.0f;
    }
    const float boost = kUserBoostPerLogUse * std::log(1.0f + static_cast<float>(count));
    return (boost > kMaxUserBoost) ? kMaxUserBoost : boost;
}

int32_t PersonalScore::entryFor(const char* text, uint32_t length) const {
    if (!enabled_ || words_.size() == 0 || text == nullptr || length == 0) {
        return -1;
    }
    return words_.entryIndexFor(text, length);
}

float PersonalScore::shareBonus(uint32_t count, uint32_t total) {
    const float share = static_cast<float>(count) / (static_cast<float>(total) + kUserBigramPrior);
    return kMaxUserBigramBoost * share;
}

float PersonalScore::bonusFor(uint32_t count, uint32_t asserted, uint32_t total) const {
    if (!linkLearned(count, asserted) || total == 0u) {
        return 0.0f;
    }
    return asserted > 0u ? kMaxUserBigramBoost : shareBonus(count, total);
}

float PersonalScore::contextBonus(uint32_t entry) const {
    if (!context_) {
        return 0.0f;
    }
    const auto next = static_cast<int32_t>(entry);
    if (hasTriples()) {
        uint32_t tripleAsserted = 0u;
        const uint32_t triple = slice_.tripleCountOf(next, &tripleAsserted);
        if (linkLearned(triple, tripleAsserted)) {
            return bonusFor(triple, tripleAsserted, slice_.tripleTotal());
        }
    }
    uint32_t pairAsserted = 0u;
    const uint32_t pair = slice_.pairCountOf(next, &pairAsserted);
    return bonusFor(pair, pairAsserted, slice_.pairTotal());
}

float PersonalScore::wordBoost(uint32_t entry) const {
    return words_.added(entry) > 0u ? kMaxUserBoost : wordBoostForCount(words_.entryCount(entry));
}

float PersonalScore::scoreForEntry(uint32_t entry) const {
    const float boost = established(entry) ? wordBoost(entry) : 0.0f;
    return boost + contextBonus(entry);
}

float PersonalScore::scoreFor(const char* text, uint32_t length) const {
    const int32_t entry = entryFor(text, length);
    return entry < 0 ? 0.0f : scoreForEntry(static_cast<uint32_t>(entry));
}

}  // namespace borderkeys
