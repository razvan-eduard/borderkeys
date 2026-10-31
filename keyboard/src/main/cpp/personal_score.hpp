// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_PERSONAL_SCORE_HPP
#define BORDERKEYS_PERSONAL_SCORE_HPP

#include <cmath>
#include <cstddef>
#include <cstdint>

#include "context_slice.hpp"
#include "word_table.hpp"

namespace borderkeys {

// What the personal dictionary adds to a candidate's score: a boost for a word learned on its
// own, from the word table, and a bonus for a word learned after the words before the cursor,
// from the context slice of the context the request names.
class PersonalScore {
public:
    /** The most a learned word's own uses add. */
    static constexpr float kMaxUserBoost = 3.0f;

    /** What a learned word gains per natural log of one plus its uses, up to kMaxUserBoost. */
    static constexpr float kUserBoostPerLogUse = 0.9f;

    /** Smoothing for a context's share, in uses: a word's share is `count / (total + prior)`. */
    static constexpr float kUserBigramPrior = 4.0f;

    /** How many times the learn-after uses each link of a phrase needs. */
    static constexpr uint32_t kPhraseUsesPerWordUses = 2;

    /** The share of its context a link must hold to be part of a phrase. */
    static constexpr float kPhraseMinShare = 0.34f;

    /** The most a learned context adds to a word. */
    static constexpr float kMaxUserBigramBoost = 2.5f;

    /** The most the word boost and the context bonus add together. */
    static constexpr float kMaxPersonalScore = kMaxUserBoost + kMaxUserBigramBoost;

    /**
     * What a link chosen on purpose carries above the log of its share, counted as certain: the
     * most the word boost and the context bonus add to any other candidate.
     */
    static constexpr float kChosenLinkBoost = kMaxPersonalScore;

    PersonalScore(const WordTable& words, const ContextSlice& slice, uint32_t learnAfterUses);

    void setEnabled(bool enabled) { enabled_ = enabled; }
    bool enabled() const { return enabled_; }

    void setLearnAfterUses(uint32_t uses) { learnAfterUses_ = uses; }

    /** Whether [count] uses make a word, pair or triple count. */
    bool learned(uint32_t count) const { return count >= learnAfterUses_; }

    /** Whether [count] uses make a link part of a phrase. */
    bool phraseLinkLearned(uint32_t count) const {
        return count >= kPhraseUsesPerWordUses * learnAfterUses_;
    }

    /** Whether a link used [count] times, chosen on purpose [asserted] times, counts as learned. */
    bool linkLearned(uint32_t count, uint32_t asserted) const {
        return asserted > 0u || learned(count);
    }

    /**
     * Whether a link used [count] times, chosen on purpose [asserted] times, holding [share] of
     * its context, is part of a phrase: chosen at all, or learned as a phrase's link with at least
     * [kPhraseMinShare].
     */
    bool phraseLink(uint32_t count, uint32_t asserted, float share) const {
        return asserted > 0u || (phraseLinkLearned(count) && share >= kPhraseMinShare);
    }

    /**
     * The share of a context seen [total] times that a link used [count] times holds, smoothed
     * by [kUserBigramPrior].
     */
    static float smoothedShare(uint32_t count, uint32_t total) {
        return static_cast<float>(count) / (static_cast<float>(total) + kUserBigramPrior);
    }

    /**
     * The log of the smoothed share a link used [count] times holds of a context seen [total]
     * times; [kChosenLinkBoost] once chosen on purpose [asserted] times, its share certain.
     */
    static float linkLogShare(uint32_t count, uint32_t asserted, uint32_t total) {
        return asserted > 0u ? kChosenLinkBoost : std::log(smoothedShare(count, total));
    }

    /**
     * The share of a context seen [total] times that a link used [count] times holds; 0 for a
     * context never seen.
     */
    static float share(uint32_t count, uint32_t total) {
        return total == 0u ? 0.0f : static_cast<float>(count) / static_cast<float>(total);
    }

    /** Whether [entry] counts: chosen on purpose at least once, or used learnAfterUses times. */
    bool established(uint32_t entry) const;

    /**
     * Sets the context the requests that follow read: the slice's links when it is the one of
     * [key]'s context and the personal model is on, else none.
     */
    void resolve(const ContextSlice::Key& key);

    /** Whether the requests read the slice's pairs, and its triples when [hasTriples]. */
    bool hasContext() const { return context_; }

    /** Whether the resolved context names the word two before, so the slice's triples count. */
    bool hasTriples() const { return context_ && slice_.key().context2 != ContextSlice::kNone; }

    /** The slice the requests read, as handed. */
    const ContextSlice& slice() const { return slice_; }

    /** A learned word's boost for [count] uses: logarithmic, up to kMaxUserBoost. */
    static float wordBoostForCount(uint32_t count);

    /** The word boost of [entry]: kMaxUserBoost once added by hand, else [wordBoostForCount] of its uses. */
    float wordBoost(uint32_t entry) const;

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
    /**
     * The bonus of a link used [count] times, chosen on purpose [asserted] times, in a context
     * seen [total] times: kMaxUserBigramBoost once chosen, its share certain; else [shareBonus];
     * 0 below learn-after.
     */
    float bonusFor(uint32_t count, uint32_t asserted, uint32_t total) const;

    const WordTable& words_;
    const ContextSlice& slice_;
    uint32_t learnAfterUses_;
    bool enabled_ = true;
    bool context_ = false;
    float largestBonus_ = 0.0f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_PERSONAL_SCORE_HPP
