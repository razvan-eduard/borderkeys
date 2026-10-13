// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PERSONAL_SCORE_HPP
#define BORDERKEYS_PERSONAL_SCORE_HPP

#include <cstddef>
#include <cstdint>

#include "user_model.hpp"

namespace borderkeys {

// What the personal dictionary adds to a candidate's score: a boost for a word learned on its
// own, and a bonus for a word learned after the words before the cursor, in the store of the
// language the request reads.
class PersonalScore {
public:
    /** The most a learned word's own uses add. */
    static constexpr float kMaxUserBoost = 3.0f;

    /** What a learned word gains per natural log of one plus its uses, up to kMaxUserBoost. */
    static constexpr float kUserBoostPerLogUse = 0.9f;

    /** Smoothing for a context's share, in uses: a word's share is `count / (total + prior)`. */
    static constexpr float kUserBigramPrior = 4.0f;

    /** The most a learned context adds to a word. */
    static constexpr float kMaxUserBigramBoost = 2.5f;

    /** The most the word boost and the context bonus add together. */
    static constexpr float kMaxPersonalScore = kMaxUserBoost + kMaxUserBigramBoost;

    PersonalScore(const UserModel& model, uint32_t learnAfterUses);

    void setEnabled(bool enabled) { enabled_ = enabled; }
    bool enabled() const { return enabled_; }

    void setLearnAfterUses(uint32_t uses) { learnAfterUses_ = uses; }

    /** Whether [count] uses make a word, pair or triple count. */
    bool learned(uint32_t count) const { return count >= learnAfterUses_; }

    /** Whether [entry] counts: chosen on purpose at least once, or used learnAfterUses times. */
    bool established(uint32_t entry) const;

    /**
     * Sets the context the requests that follow read: the links of [store] after [context1],
     * and after [context2] then [context1]; -1 for none.
     */
    void resolve(int32_t store, int32_t context1, int32_t context2);

    int32_t store() const { return store_; }
    int32_t context1() const { return context1_; }
    int32_t context2() const { return context2_; }

    /** A learned word's boost for [count] uses: logarithmic, up to kMaxUserBoost. */
    static float wordBoostForCount(uint32_t count);

    /**
     * The bonus of a link used [count] times in a context seen [total] times: its smoothed share
     * of the context, bounded by kMaxUserBigramBoost.
     */
    static float shareBonus(uint32_t count, uint32_t total);

    /** The entry spelled [text], case aside; -1 for none or when off. */
    int32_t entryFor(const char* text, uint32_t length) const;

    /**
     * The bonus [entry] gets as the learned successor of the resolved two words, else of the
     * resolved word; 0 when neither link is learned.
     */
    float contextBonus(uint32_t entry) const;

    /** The word boost of [entry] when established, plus its context bonus. */
    float scoreForEntry(uint32_t entry) const;

    /** [scoreForEntry] of the entry spelled [text], case aside; 0 for none or when off. */
    float scoreFor(const char* text, uint32_t length) const;

    /** The most [scoreFor] adds under the resolved context. */
    float ceiling() const { return kMaxUserBoost + largestBonus_; }

private:
    /** [shareBonus] of a link used [count] times in a context seen [total] times; 0 below learn-after. */
    float bonusFor(uint32_t count, uint32_t total) const;

    const UserModel& model_;
    uint32_t learnAfterUses_;
    bool enabled_ = true;
    int32_t store_ = -1;
    int32_t context1_ = -1;
    int32_t context2_ = -1;
    float largestBonus_ = 0.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PERSONAL_SCORE_HPP
