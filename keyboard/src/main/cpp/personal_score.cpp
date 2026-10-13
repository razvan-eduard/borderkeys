// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "personal_score.hpp"

#include <algorithm>
#include <cmath>

namespace borderkeys {

PersonalScore::PersonalScore(const UserModel& model, uint32_t learnAfterUses)
    : model_(model), learnAfterUses_(learnAfterUses) {}

bool PersonalScore::established(uint32_t entry) const {
    if (model_.asserted(entry) > 0u) {
        return true;
    }
    return learned(model_.entryCount(entry));
}

void PersonalScore::resolve(int32_t store, int32_t context1, int32_t context2) {
    store_ = store;
    context1_ = context1;
    context2_ = context2;
    largestBonus_ = 0.0f;
    if (context1_ < 0) {
        return;
    }
    // The largest bonus: the most used link's, after one word and after two.
    UserModel::Successor top{};
    if (model_.successors(store_, context1_, &top, 1) == 1) {
        largestBonus_ = bonusFor(top.count, model_.successorTotal(store_, context1_));
    }
    if (context2_ >= 0 && model_.trigramSuccessors(store_, context2_, context1_, &top, 1) == 1) {
        largestBonus_ = std::max(
            largestBonus_, bonusFor(top.count, model_.trigramTotal(store_, context2_, context1_)));
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
    if (!enabled_ || model_.size() == 0 || text == nullptr || length == 0) {
        return -1;
    }
    return model_.entryIndexFor(text, length);
}

float PersonalScore::shareBonus(uint32_t count, uint32_t total) {
    const float share = static_cast<float>(count) / (static_cast<float>(total) + kUserBigramPrior);
    return kMaxUserBigramBoost * share;
}

float PersonalScore::bonusFor(uint32_t count, uint32_t total) const {
    if (!learned(count) || total == 0u) {
        return 0.0f;
    }
    return shareBonus(count, total);
}

float PersonalScore::contextBonus(uint32_t entry) const {
    if (context1_ < 0) {
        return 0.0f;
    }
    const auto next = static_cast<int32_t>(entry);
    if (context2_ >= 0) {
        const uint32_t triple = model_.trigramCount(store_, context2_, context1_, next);
        if (learned(triple)) {
            return bonusFor(triple, model_.trigramTotal(store_, context2_, context1_));
        }
    }
    return bonusFor(model_.bigramCount(store_, context1_, next),
                    model_.successorTotal(store_, context1_));
}

float PersonalScore::scoreForEntry(uint32_t entry) const {
    const float boost = established(entry) ? wordBoostForCount(model_.entryCount(entry)) : 0.0f;
    return boost + contextBonus(entry);
}

float PersonalScore::scoreFor(const char* text, uint32_t length) const {
    const int32_t entry = entryFor(text, length);
    return entry < 0 ? 0.0f : scoreForEntry(static_cast<uint32_t>(entry));
}

}  // namespace borderkeys
