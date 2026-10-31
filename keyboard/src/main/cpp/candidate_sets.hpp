// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_CANDIDATE_SETS_HPP
#define BORDERKEYS_CANDIDATE_SETS_HPP

#include <algorithm>

#include "candidate.hpp"
#include "topk.hpp"

namespace borderkeys {

// The candidates one request keeps, in two bounded heaps over storage the caller supplies: the
// continuations of the letters typed, and every other candidate.
class CandidateSets {
public:
    void reset(Candidate* continuations, int continuationCapacity, Candidate* others,
               int otherCapacity) {
        continuations_.reset(continuations, continuationCapacity);
        others_.reset(others, otherCapacity);
        continuationCapacity_ = continuationCapacity;
        otherCapacity_ = otherCapacity;
    }

    // The heap a candidate that continues the letters typed, or does not, belongs to.
    TopK<Candidate>& heapFor(bool continues) { return continues ? continuations_ : others_; }

    // The heap of the candidates that do not continue the letters typed.
    TopK<Candidate>& others() { return others_; }

    // The lower of the two heaps' floors: a score at or below it is kept by neither.
    float lowestFloor() const { return std::min(continuations_.worstScore(), others_.worstScore()); }

    // How many both hold.
    int size() const { return continuations_.size() + others_.size(); }

    // Empties both into `out`, best first, at most `maxOut`; `continuationScratch` and
    // `otherScratch` hold each heap's drain, with room for its capacity.
    int drain(Candidate* out, int maxOut, Candidate* continuationScratch, Candidate* otherScratch) {
        const int continuationCount =
            continuations_.drainSorted(continuationScratch, continuationCapacity_);
        const int otherCount = others_.drainSorted(otherScratch, otherCapacity_);
        int written = 0;
        int continuation = 0;
        int other = 0;
        while (written < maxOut && (continuation < continuationCount || other < otherCount)) {
            const bool takeOther =
                continuation >= continuationCount ||
                (other < otherCount &&
                 continuationScratch[continuation].ranksBelow(otherScratch[other]));
            out[written++] = takeOther ? otherScratch[other++] : continuationScratch[continuation++];
        }
        return written;
    }

private:
    TopK<Candidate> continuations_;
    TopK<Candidate> others_;
    int continuationCapacity_ = 0;
    int otherCapacity_ = 0;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_CANDIDATE_SETS_HPP
