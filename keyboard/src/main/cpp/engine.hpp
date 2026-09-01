// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ENGINE_HPP
#define BORDERKEYS_ENGINE_HPP

#include <cstdint>

#include <memory>

#include "arena.hpp"
#include "bkd_format.hpp"
#include "candidate.hpp"
#include "gesture/gesture_decoder.hpp"
#include "ngram_model.hpp"
#include "packed_trie.hpp"
#include "proximity.hpp"
#include "topk.hpp"
#include "user_model.hpp"

namespace borderkeys {

/**
 * What a pack says about itself, once its header has been validated.
 *
 * Filled by [bkdInspectPack] so that the settings UI can name a pack it has just been handed --
 * its language, its size, how many words it holds -- without a second parser for the format in
 * Kotlin. There is one implementation of this header layout, it is in C++, and everything else
 * asks it.
 */
struct PackInfo {
    char tag[16];
    uint32_t formatVersion;
    uint32_t wordCount;
    uint64_t fileBytes;
};

/**
 * Validates the `.bkd` in `[offset, offset + length)` of `fd` and describes it.
 *
 * The same validation the engine performs before it will read a pack: magic, version, header
 * size and checksum, every section offset against the real file size, and the content checksum.
 * Maps and unmaps; nothing is retained and the descriptor is not taken over.
 *
 * Returns a `BkdStatus`. On anything but `kBkdOk`, `out` is left untouched -- a caller that
 * ignored the status would otherwise show a language tag read out of a file that failed.
 */
int32_t bkdInspectPack(int fd, int64_t offset, int64_t length, PackInfo* out);

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

    /** The tag for a word, or kNoPosTag when this pack carries no grammar or does not know it. */
    uint32_t posTag(int32_t wordIndex) const {
        if (wordTags_ == nullptr || wordIndex < 0) {
            return kNoPosTag;
        }
        const uint32_t tag = wordTags_[wordIndex];
        return (tag < posTagCount_) ? tag : kNoPosTag;
    }

    /**
     * Quantised -log P(tag | previousTag), on the same scale as the n-gram values so the two
     * can be added without converting either. Two array reads and a multiply.
     */
    uint8_t posTransition(uint32_t previousTag, uint32_t tag) const {
        if (posTransitions_ == nullptr || previousTag >= posTagCount_ || tag >= posTagCount_) {
            return 0;
        }
        return posTransitions_[previousTag * posTagCount_ + tag];
    }

    bool hasGrammar() const { return posTransitions_ != nullptr; }

    /** No tag: the pack has no grammar, or the treebank never contained this word. */
    static constexpr uint32_t kNoPosTag = 0xFFFFFFFFu;
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

    const uint8_t* wordTags_ = nullptr;
    const uint8_t* posTransitions_ = nullptr;
    uint32_t posTagCount_ = 0;

    char tag_[16] = {};
    PackedTrie trie_;
    NgramModel ngrams_;

    int32_t frequent_[kFrequentCount] = {};
    int frequentCount_ = 0;
};

/**
 * The engine, and the scoring surface the gesture decoder sees.
 *
 * Implementing [GestureScorer] rather than handing the decoder a pointer to itself is what
 * keeps the two headers from including each other, and what lets a decoder be exercised against
 * a stub in the replay harness without an engine existing at all.
 */
class Engine final : public GestureScorer {
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

    /**
     * Decodes a swipe into candidates, best first.
     *
     * The samples are raw touch points in view pixels, exactly as the driver reported them,
     * including the historical ones inside each motion event. Smoothing and resampling belong
     * to the decoder, not to the caller: tier A and tier B want the same features and must not
     * disagree about how they were produced.
     */
    int decodeGesture(const float* xs, const float* ys, const int64_t* ts, int count,
                      const char* previous1, size_t previous1Length, const char* previous2,
                      size_t previous2Length, Candidate* out, int maxOut);

    const char* gestureDecoderName() const;

    // --- GestureScorer -------------------------------------------------------------------
    int packCount() const override { return kMaxPacks; }
    const PackedTrie* activeTrie(int packIndex) const override;
    float packWeightLog(int packIndex) const override;
    float contextLogProb(int packIndex, uint32_t wordIndex) const override;
    float userBoost(const char* text, uint32_t length) const override;

    void learn(const char* word, size_t wordLength, const char* previous1,
               size_t previous1Length, const char* previous2, size_t previous2Length);

    void loadUserWords(const char* const* words, const size_t* lengths, const int32_t* counts,
                       int count);

    /** Replaces the remembered word pairs. Called right after [loadUserWords], from the same
     *  database read, so both halves of a pair are already known words. */
    void loadUserBigrams(const char* const* previous, const size_t* previousLengths,
                         const char* const* next, const size_t* nextLengths,
                         const int32_t* counts, int count);

    /** Replaces the remembered three-word sequences. Called after the pairs. */
    void loadUserTrigrams(const char* const* previous2, const size_t* previous2Lengths,
                          const char* const* previous1, const size_t* previous1Lengths,
                          const char* const* next, const size_t* nextLengths,
                          const int32_t* counts, int count);

    /** How readily what the user writes outranks the dictionary. See KeyboardPreferences. */
    void setLearningSpeed(float speed);

    /**
     * How much one-sided evidence is wanted before the dictionaries for other languages stop
     * being searched. At or below zero they are always searched.
     */
    void setLanguageLock(float minimumEvidence);

    /** Whether two-word suggestions are offered at all. Off unless the user asks for them. */
    void setPhraseSuggestions(bool enabled) { phraseSuggestions_ = enabled; }
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

    /**
     * Offers the words this person has been seen to write after the current context word.
     *
     * Only for the empty prefix: this is the "what comes next" case, where there is nothing to
     * walk a trie with and the alternative is the language's most frequent words regardless of
     * what was just written.
     */
    void searchUserSuccessors(TopK<Candidate>& heap);

    /**
     * Offers a two-word continuation as a single suggestion.
     *
     * Only from the personal model, and only when both links are habits. A corpus can chain any
     * two frequent bigrams into something grammatical and meaningless -- "de la a" -- because
     * frequency says nothing about whether the pair was ever written together by this person.
     * A phrase both of whose links this person has repeatedly written is a different claim.
     *
     * The second link is held to a stricter bar than the first, because it is a longer guess:
     * getting a word wrong costs a glance, getting two wrong costs the same glance plus the
     * suspicion that the keyboard is inventing things.
     */
    void searchUserPhrases(TopK<Candidate>& heap);



    /** How much a personal pair argues for this word, given the context. Zero without one. */
    float userBigramBonusFor(uint32_t entryIndex) const;

    // Walks the fuzzy neighbourhood of the typed prefix, returning the trie nodes where the
    // whole input has been consumed, with what it cost to get there.
    int collectEndpoints(const LanguagePack& pack, const uint32_t* folded, int foldedLength,
                         float maxCost, Endpoint* out, int maxOut);
    // Descends from an endpoint collecting whole words.
    void collectWords(int packIndex, const LanguagePack& pack, const Endpoint& endpoint,
                      TopK<Candidate>& heap);

    float userBoostFor(const char* text, uint32_t length) const;
    /** Normalises the active packs' weights onto one scale. Shared by tapping and swiping. */
    void refreshWeights();
    float userBoostForCount(uint32_t count) const;
    // Offers a candidate, replacing an entry for the same word instead of adding a second one.
    void offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                        uint32_t textLength) const;
    // Which language is being written, decided from the words already committed. dominantPack_
    // is -1 until the evidence is one-sided enough to be worth acting on.
    void observeContextLanguage(const uint32_t* folded, int length);

    /** Runs the prefix search over every active pack, or over [onlyPack] when it is not -1. */
    void searchPacks(const uint32_t* folded, int foldedLength, int onlyPack,
                     TopK<Candidate>& heap);

    // The edit-cost ceiling for the request being answered. A member rather than a parameter
    // because the fallback pass changes it between two runs over the same packs.
    float editCostCeiling_ = 0.0f;

    /** The previous word's tag in each pack, resolved with contextWord1_. */
    uint32_t contextTag1_[kMaxPacks] = {};

    float languageLockMinimum_ = 1.8f;
    float languageEvidence_[kMaxPacks] = {};
    int dominantPack_ = -1;
    uint32_t lastObservedWord_ = 0;

    void resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                        size_t previous2Length);

    LanguagePack packs_[kMaxPacks];
    KeyGeometry geometry_;
    /**
     * Chosen once, at create(), from a compile-time flag. There is no `if (neural)` anywhere
     * near a finger, and in the free build the neural tier is not compiled at all -- so the
     * shipped library contains no trace of it rather than dead code the linker removed.
     */
    std::unique_ptr<GestureDecoder> gestureDecoder_;
    UserModel userModel_;
    Arena arena_;

    Candidate heapStorage_[kMaxCandidates];
    Candidate drainBuffer_[kMaxCandidates];

    // Per-request context, resolved once per pack instead of once per candidate.
    /**
     * The context word's entry in the personal model, or -1.
     *
     * Resolved once per request beside the per-pack context indices, because every candidate
     * would otherwise fold and look up the same word again.
     */
    /** Multiplier on how fast the personal model gains ground. 1.0 is the default. */
    float learningSpeed_ = 1.0f;

    int32_t userContext1_ = -1;
    /** The word before that one, in the personal model. -1 when there is none. */
    int32_t userContext2_ = -1;

    bool phraseSuggestions_ = false;

    /**
     * Text for the phrase candidates of the request being answered.
     *
     * Fixed and owned by the engine: composing a phrase needs somewhere to put it, the arena is
     * rewound between searches, and returning a pointer into a temporary would hand the caller
     * a dangling one. Four slots because a strip shows between three and eight suggestions and
     * phrases should never be most of them.
     */
    static constexpr int kMaxPhrases = 4;
    static constexpr int kMaxPhraseBytes = 96;
    char phraseText_[kMaxPhrases][kMaxPhraseBytes] = {};
    int phraseLength_[kMaxPhrases] = {};
    int phraseCount_ = 0;

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
