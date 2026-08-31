// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ENGINE_HPP
#define BORDERKEYS_ENGINE_HPP

#include <cstdint>

#include "arena.hpp"
#include "bkd_format.hpp"
#include "ngram_model.hpp"
#include "packed_trie.hpp"
#include "proximity.hpp"
#include "topk.hpp"
#include "user_model.hpp"

namespace borderkeys {

// A scored suggestion. Plain data on purpose: this crosses into the gesture decoder in step 6
// and into the JNI bridge, and neither place may allocate or destruct anything.
struct Candidate {
    // Index into the engine's pack table, or kUserPack for a word from the personal dictionary.
    int32_t packIndex;
    // Word index inside that pack, or entry index inside the user model.
    int32_t wordIndex;
    float score;

    static constexpr int32_t kUserPack = -1;
};

// One mapped .bkd file, plus the per-language state the engine adapts at runtime.
class LanguagePack {
public:
    ~LanguagePack() { close(); }

    // Maps `length` bytes starting at `offset` of `fd`, validates, and binds the views.
    // Returns a BkdStatus. The descriptor is not taken over: the caller closes it either way,
    // because the mapping keeps the file alive on its own.
    int32_t open(const char* tag, int fd, int64_t offset, int64_t length);
    void close();

    bool isOpen() const { return mapping_ != nullptr; }
    const char* tag() const { return tag_; }

    const PackedTrie& trie() const { return trie_; }
    const NgramModel& ngrams() const { return ngrams_; }

    // The most frequent words in this language, computed once at load.
    //
    // Two jobs. It answers "what word comes next" when nothing has been typed, where there is
    // no prefix to walk from at all. And it backs up the trie descent for short prefixes, where
    // the subtree under one or two characters is far larger than any visit budget can cross --
    // there the descent returns whichever words it happened to reach first, and this returns
    // the ones a user would actually have meant.
    static constexpr int kFrequentCount = 512;
    const int32_t* frequentWords() const { return frequent_; }
    int frequentWordCount() const { return frequentCount_; }

    bool active = false;
    // Configured weight, and the floor it may never adapt below. Without a floor a language
    // used rarely decays to nothing and can never recover, which the user experiences as the
    // keyboard having silently forgotten a language they never disabled.
    float configuredWeight = 1.0f;
    float adaptiveWeight = 1.0f;

private:
    void buildFrequentList();

    void* mapping_ = nullptr;
    size_t mappingBytes_ = 0;
    const uint8_t* base_ = nullptr;
    uint64_t baseBytes_ = 0;

    char tag_[16] = {};
    PackedTrie trie_;
    NgramModel ngrams_;

    int32_t frequent_[kFrequentCount] = {};
    int frequentCount_ = 0;
};

class Engine {
public:
    static constexpr int kMaxPacks = 4;
    // Sixteen is the ceiling the design fixes for the top-K heap. The suggestion strip shows
    // three; the rest exist so that the gesture decoder and the reranking in step 6 have
    // something to choose from.
    static constexpr int kMaxCandidates = 16;
    static constexpr int kMaxComposing = 48;

    bool create();
    void destroy();

    int32_t loadLanguage(const char* tag, int fd, int64_t offset, int64_t length, float weight);
    void setActiveLanguages(const char* const* tags, const float* weights, int count);
    bool setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                        int count, float keyWidth, float keyHeight);

    // Fills `out` with at most `maxOut` candidates, best first, and returns how many were
    // written. `composing` may be empty, in which case this answers "what word comes next".
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                Candidate* out, int maxOut);

    void learn(const char* word, size_t wordLength, const char* previous1,
               size_t previous1Length, const char* previous2, size_t previous2Length);

    void loadUserWords(const char* const* words, const size_t* lengths, const int32_t* counts,
                       int count);
    bool snapshotUserModel(const char* path);

    // Resolves a candidate to its display text. The pointer is owned by the mapping or by the
    // user model and stays valid until the pack is closed or the model is rewritten.
    const char* candidateText(const Candidate& candidate, uint32_t* lengthOut) const;

    const KeyGeometry& geometry() const { return geometry_; }

private:
    struct Endpoint {
        int32_t node;
        float cost;
    };

    int packIndexForTag(const char* tag) const;
    void searchPack(int packIndex, const uint32_t* folded, int foldedLength,
                    TopK<Candidate>& heap);
    void searchNextWord(int packIndex, TopK<Candidate>& heap);
    // Scores the language's most frequent words that start with the typed prefix. Bounded by
    // the shortlist, not by the size of the subtree, so its cost does not depend on how much
    // of the dictionary the prefix matches.
    void searchFrequentWithPrefix(int packIndex, const uint32_t* folded, int foldedLength,
                                  TopK<Candidate>& heap);
    void searchUserModel(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap);

    // Walks the fuzzy neighbourhood of the typed prefix, returning the trie nodes where the
    // whole input has been consumed, with what it cost to get there.
    int collectEndpoints(const LanguagePack& pack, const uint32_t* folded, int foldedLength,
                         float maxCost, Endpoint* out, int maxOut);
    // Descends from an endpoint collecting whole words.
    void collectWords(int packIndex, const LanguagePack& pack, const Endpoint& endpoint,
                      TopK<Candidate>& heap);

    // Stupid backoff over trigram, bigram and unigram, in natural log units.
    float contextLogProb(int packIndex, uint32_t wordIndex) const;
    float userBoostFor(const char* text, uint32_t length) const;
    static float userBoostForCount(uint32_t count);
    // Offers a candidate, replacing an entry for the same word instead of adding a second one.
    void offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                        uint32_t textLength) const;
    void resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                        size_t previous2Length);

    LanguagePack packs_[kMaxPacks];
    KeyGeometry geometry_;
    UserModel userModel_;
    Arena arena_;

    Candidate heapStorage_[kMaxCandidates];
    Candidate drainBuffer_[kMaxCandidates];

    // Per-request context, resolved once per pack instead of once per candidate.
    int32_t contextWord1_[kMaxPacks] = {};
    int32_t contextWord2_[kMaxPacks] = {};
    bool hasContext1_ = false;
    bool hasContext2_ = false;

    // The remaining node-visit allowance for the current request. This, not a timer, is what
    // holds the 8 ms budget: a wall-clock check would make the result depend on how busy the
    // device happened to be, so two identical requests could return different suggestions.
    int32_t visitBudget_ = 0;

    float normalisedWeight_[kMaxPacks] = {};
    bool created_ = false;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_ENGINE_HPP
