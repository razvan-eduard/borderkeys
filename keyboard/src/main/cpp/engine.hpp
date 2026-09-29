// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ENGINE_HPP
#define BORDERKEYS_ENGINE_HPP

#include <cstdint>

#include <memory>
#include <string>
#include <vector>

#include "arena.hpp"
#include "bkd_format.hpp"
#include "candidate.hpp"
#include "gesture/gesture_decoder.hpp"
#include "ngram_model.hpp"
#include "packed_trie.hpp"
#include "proximity.hpp"
#include "search_plan.hpp"
#include "topk.hpp"
#include "touch_model.hpp"
#include "user_model.hpp"

#ifdef BORDERKEYS_NEURAL_SWIPE
#include "gesture/tcn_decoder.hpp"
#endif

namespace borderkeys {

/** What a pack's validated header says about itself, filled by [bkdInspectPack]. */
struct PackInfo {
    char tag[16];
    uint32_t formatVersion;
    uint32_t wordCount;
    uint64_t fileBytes;
};

/**
 * Validates the `.bkd` in `[offset, offset + length)` of `fd` as the engine does before reading a
 * pack, and describes it. Maps and unmaps; the descriptor is not taken over. Returns a
 * `BkdStatus`; `out` is written only on `kBkdOk`.
 */
int32_t bkdInspectPack(int fd, int64_t offset, int64_t length, PackInfo* out);

// One mapped .bkd file, plus the per-language state the engine keeps at runtime.
class LanguagePack {
public:
    ~LanguagePack() { close(); }

    // Maps `length` bytes starting at `offset` of `fd`, validates, and binds the views.
    // Returns a BkdStatus. The caller closes the descriptor either way.
    int32_t open(const char* tag, int fd, int64_t offset, int64_t length);
    void close();

    bool isOpen() const { return mapping_ != nullptr; }
    const char* tag() const { return tag_; }

    const PackedTrie& trie() const { return trie_; }
    const NgramModel& ngrams() const { return ngrams_; }

    // The most frequent words in this language, computed once at load; searched when nothing is
    // typed and for short prefixes.
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

    /** Quantised -log P(tag | previousTag), on the n-gram values' scale. */
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
    // The weight configured for this language.
    float configuredWeight = 1.0f;

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

/** The engine, and the scoring surface the gesture decoder sees through [GestureScorer]. */
class Engine final : public GestureScorer {
public:
    static constexpr int kMaxPacks = 4;
    // The size of the candidate heap.
    static constexpr int kMaxCandidates = 16;
    static constexpr int kMaxComposing = 48;

    bool create();
    void destroy();

    int32_t loadLanguage(const char* tag, int fd, int64_t offset, int64_t length, float weight);

    /**
     * Makes `tags`, at most kMaxPacks, the whole set of languages consulted, with their weights;
     * anything open and not named is closed and its slot freed. An empty set closes everything.
     */
    void setActiveLanguages(const char* const* tags, const float* weights, int count);
    bool setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                        int count, float keyWidth, float keyHeight);

    /**
     * Whether the learned touch patterns count, how far they move a substitution's cost from the
     * default patterns' cost, and how many taps a key needs first; see TouchModel.
     */
    void setTouchModel(bool learned, float weight, int minTaps);

    /** Replaces the touch model's key patterns; see TouchModel::set. */
    void setTouchPatterns(const int32_t* codes, const float* taps, const float* meanX,
                          const float* meanY, const float* varianceX, const float* varianceY,
                          const float* covariance, int count);

    /**
     * How the dictionaries spell `word`, looked up folded, written into `out`; returns the byte
     * count, zero when no dictionary has it.
     */
    int knownSpelling(const char* word, size_t length, char* out, int outBytes) const;

    /**
     * Whether `word` may stand as the stem of a regular inflection: an active pack holds it
     * folded, not as a name, within kStemFrequencyFloor of the pack's commonest word -- or the
     * personal dictionary holds it established.
     */
    bool vouchesForStem(const char* word, size_t length) const;

    /** The language being written, by the evidence, or -1 when none leads. */
    int preferredPack() const;

    /** Locates a spelling the dictionaries hold that differs from `word` only by case. */
    bool exactSpelling(const char* word, size_t length, int* packOut, uint32_t* wordOut) const;

    /**
     * The possessive of a word missing its apostrophe, written into [out], or zero: the word
     * ends in s, no dictionary holds it, and an active pack flags its stem a name.
     */
    int possessiveFor(const char* word, size_t length, char* out, int outBytes) const;

    /**
     * What [packIndex] alone would spell [word] as, without context, written into [out]; 0 when
     * the pack is not open and active or offers nothing.
     */
    int candidateForPack(int packIndex, const char* word, size_t wordLength, char* out,
                         int outBytes);

    /** The pack the conversation is considered written in, or -1 when undecided. */
    int32_t dominantPack() const { return dominantPack_; }

    // Fills `out` with at most `maxOut` candidates, best first, and returns how many were
    // written. `composing` may be empty, in which case this answers "what word comes next".
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                Candidate* out, int maxOut);

    // suggest, with where each code point of `composing` was tapped, in the keyboard view's
    // pixels: `tapCount` entries, NaN for none. The touch model prices each tapped letter's
    // substitutions from them.
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                const float* tapX, const float* tapY, int tapCount, Candidate* out, int maxOut);

    /**
     * Decodes a swipe into candidates, best first, from raw touch points in view pixels,
     * historical ones included; the decoder smooths and resamples them.
     */
    int decodeGesture(const float* xs, const float* ys, const int64_t* ts, int count,
                      const char* previous1, size_t previous1Length, const char* previous2,
                      size_t previous2Length, Candidate* out, int maxOut);

    const char* gestureDecoderName() const;

    /** Whether the last [decodeGesture] went through the neural decoder. */
    bool lastDecodeUsedNeural() const { return lastDecodeUsedNeural_; }

    /**
     * Loads tier B's weights, building its decoder if needed. Always false without
     * `BORDERKEYS_NEURAL_SWIPE`.
     */
    bool loadSwipeWeights(const uint8_t* data, size_t length);

    /**
     * Switches [decodeGesture] to tier B once its weights are loaded, and frees tier B when turned
     * off. A no-op without `BORDERKEYS_NEURAL_SWIPE`.
     */
    void setSwipeModelEnabled(bool enabled);

    /**
     * Decodes one synthetic gesture through tier B and discards it, whether or not tier B is
     * enabled; false, doing nothing, without weights or a layout.
     */
    bool warmSwipeModel();

    // --- GestureScorer -------------------------------------------------------------------
    int packCount() const override { return kMaxPacks; }
    const PackedTrie* activeTrie(int packIndex) const override;
    float packWeightLog(int packIndex) const override;
    float contextLogProb(int packIndex, uint32_t wordIndex) const override;
    float userBoost(const char* text, uint32_t length) const override;
    int32_t offeredSpelling(int packIndex, uint32_t firstIndex) const override;

    /**
     * Records a committed word, and the pair and triple it makes with the words before it.
     * [deliberateCapital] and [asserted] are UserModel::learn's -- see [personalWordEstablished]
     * for what the second one gates.
     */
    void learn(const char* word, size_t wordLength, const char* previous1,
               size_t previous1Length, const char* previous2, size_t previous2Length,
               bool deliberateCapital = false, bool asserted = false);

    void loadUserWords(const char* const* words, const size_t* lengths, const int32_t* counts,
                       int count, const int32_t* deliberateCapitals = nullptr,
                       const int32_t* asserted = nullptr);

    /** Replaces the remembered word pairs; called after [loadUserWords]. */
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
     * How much evidence an edit needs to outrank a word spelled as typed: a multiplier on
     * kEditPenalty and kCorrectionSurcharge, 1.0 by default, clamped to
     * [kMinCorrectionStrictness, kMaxCorrectionStrictness].
     */
    void setCorrectionStrictness(float scale);

    /**
     * How much one-sided evidence is wanted before the dictionaries for other languages stop
     * being searched. At or below zero they are always searched.
     */
    void setLanguageLock(float minimumEvidence, bool strict);

    /**
     * Which language the search is restricted to while none has been recognised; null or empty
     * clears it. Not a scoring term.
     */
    void setPreferredLanguage(const char* tag);

    /** Forgets which language the conversation is in; called when the field changes. */
    void resetLanguageEvidence();

    /** Whether two-word suggestions are offered at all. Off by default. */
    void setPhraseSuggestions(bool enabled) { phraseSuggestions_ = enabled; }

    /** Whether the personal dictionary takes part in suggestions; off for a private field. */
    void setPersonalModelEnabled(bool enabled) { personalModelEnabled_ = enabled; }

    /**
     * Replaces the blocked words: spellings no search offers, corrects to or counts as known,
     * each matched exactly, case aside. An empty list clears them.
     */
    void setBlockedWords(const char* const* words, const size_t* lengths, int count);

    /** Whether [text] is a blocked spelling, case aside. */
    bool isBlocked(const char* text, uint32_t length) const;

    /**
     * The tag of the pack the conversation is currently considered written in, or null while
     * undecided -- the same answer as dominantPack(), as a language rather than a slot.
     */
    const char* dominantLanguageTag() const;

    // Resolves a candidate to its display text. The pointer is owned by the mapping or by the
    // user model and stays valid until the pack is closed or the model is rewritten.
    const char* candidateText(const Candidate& candidate, uint32_t* lengthOut) const;

    /**
     * Where one candidate's score came from, by term. [ScoreParts::rest] is the edit cost and the
     * completion penalty together.
     */
    struct ScoreParts {
        float total = 0.0f;
        float packWeight = 0.0f;
        float languageModel = 0.0f;
        float personal = 0.0f;
        float rest = 0.0f;
        int32_t packIndex = -1;
        int32_t rank = -1;
        int32_t editDistance = 0;
        int32_t addedCharacters = 0;
    };

    /** Fills [out] for [candidate] as an answer to [typed]; false when the search does not
     *  offer that word. */
    bool explainScore(const char* typed, size_t typedLength, const char* candidate,
                      size_t candidateLength, ScoreParts* out);

    /**
     * Autocorrect's answer from the last [suggest] request, or null: the best correction,
     * respelling or exact spelling of the letters typed. Valid until the next request.
     */
    const Candidate* bestCorrection() const {
        return hasBestCorrection_ ? &bestCorrection_ : nullptr;
    }

    // Whether the candidate is a name: a pack word flagged by every active pack that knows it, or
    // a personal word the user capitalised on purpose or the active packs agree is a name.
    bool candidateIsProperNoun(const Candidate& candidate) const;
    /** Whether every active pack that knows [folded] flags it a name; false when none knows it. */
    bool packsAgreeProperNoun(const uint32_t* folded, int foldedLength) const;

    /** Whether a word is within kCorrectionFrequencyFloor of its pack's commonest word. */
    bool plausibleCorrectionTarget(const LanguagePack& pack, uint32_t wordIndex) const;

    /** Whether [candidate] carries the typed letters on further, rather than being reached by an
     *  edit. */
    bool continuesTyped(const Candidate& candidate, const uint32_t* folded,
                        int foldedLength) const;

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
    // Scores the language's most frequent words that start with the typed prefix.
    void searchFrequentWithPrefix(int packIndex, const uint32_t* folded, int foldedLength,
                                  TopK<Candidate>& heap);
    void searchUserModel(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap);

    /**
     * Whether a personal entry was chosen on purpose at least once, or written
     * kMinPersonalEvidence effective times.
     */
    bool personalWordEstablished(uint32_t entryIndex) const;

    /** Whether any active pack holds this text, folded, in a spelling that is not blocked. */
    bool anyPackKnows(const char* text, uint32_t length) const;

    /** The first spelling of [trie]'s run at [firstIndex] that is not blocked, or -1. */
    int32_t firstUnblockedSpelling(const PackedTrie& trie, int32_t firstIndex) const;

    /** Offers the words this person writes after the context word; for the empty prefix. */
    void searchUserSuccessors(TopK<Candidate>& heap);

    /**
     * Offers a two-word continuation from the personal model when both links are habits, the
     * second held to a larger prior.
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
    /** Normalises the active packs' weights; used by tapping and swiping. */
    void refreshWeights();
    float userBoostForCount(uint32_t count) const;
    // Offers a candidate, replacing an entry for the same word instead of adding a second one.
    void offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                        uint32_t textLength) const;
    // Looks a word up and offers it, unless even the largest personal boost cannot reach the
    // heap's floor.
    void offerScoredWord(TopK<Candidate>& heap, const PackedTrie& trie, int packIndex,
                         uint32_t wordIndex, float score) const;
    // Which language is being written, decided from the words already committed. dominantPack_
    // is -1 until the evidence is one-sided enough to be worth acting on.
    void observeContextLanguage(const uint32_t* folded, int length);

    /** Runs the prefix search over every active pack, or over [onlyPack] when it is not -1. */
    void searchPacks(const uint32_t* folded, int foldedLength, int onlyPack,
                     TopK<Candidate>& heap);

    /** Runs one pass of kSearchPlan: sets its ceiling and searches its source. */
    void runPass(const PassSpec& spec, const uint32_t* folded, int foldedLength, int restrictTo,
                 TopK<Candidate>& heap);

    /**
     * Settles autocorrect's answer: the corrections heap's best, overridden by the respelling of
     * the typed letters, overridden by the dictionaries' exact spelling of them.
     */
    void settleCorrection(const char* composing, size_t composingLength);

    /**
     * Drains [heap] into [out], best first, keeping at most kMaxShownCompletions continuations of
     * the typed letters. Returns how many were written.
     */
    int writeStrip(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap,
                   Candidate* out, int maxOut);

    // The edit-cost ceiling of the pass running now.
    float editCostCeiling_ = 0.0f;

    /** The pass running now. Read by collectWords to route candidates. */
    Pass currentPass_ = Pass::Primary;

    /** Sets currentPass_ for one search and restores it on the way out. */
    class PassScope {
    public:
        PassScope(Engine& engine, Pass pass)
            : engine_(engine), previous_(engine.currentPass_) {
            engine_.currentPass_ = pass;
        }
        ~PassScope() { engine_.currentPass_ = previous_; }
        PassScope(const PassScope&) = delete;
        PassScope& operator=(const PassScope&) = delete;

    private:
        Engine& engine_;
        Pass previous_;
    };

    /** The previous word's tag in each pack, resolved with contextWord1_. */
    uint32_t contextTag1_[kMaxPacks] = {};

    /** The active dictionary with the highest configured weight, or -1 when none is open. */
    int heaviestPack() const;

    float languageLockMinimum_ = 1.8f;
    bool strictLanguage_ = false;
    float languageEvidence_[kMaxPacks] = {};
    int dominantPack_ = -1;
    uint32_t lastObservedWord_ = 0;

    /** The preferred language as a tag, and the slot it resolves to, or -1. */
    char preferredTag_[16] = {};
    int preferredPack_ = -1;
    /** Re-resolves [preferredPack_] from [preferredTag_] when either or the open packs change. */
    void resolvePreferredPack();

    void resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                        size_t previous2Length);

    /**
     * Rescales `candidates[0..count)`'s scores in place to a fixed-temperature softmax over
     * [0, 1000]; for gesture candidates only.
     */
    static void normaliseGestureScores(Candidate* candidates, int count);

    LanguagePack packs_[kMaxPacks];
    KeyGeometry geometry_;
    TouchModel touchModel_;

    // The request's taps, aligned with its folded code points; set only while suggest runs.
    float queryTapX_[kMaxComposing] = {};
    float queryTapY_[kMaxComposing] = {};
    bool queryTapped_ = false;
    /** Tier A, the geometric decoder, in every build. */
    std::unique_ptr<GestureDecoder> gestureDecoder_;

#ifdef BORDERKEYS_NEURAL_SWIPE
    /** Tier B, `plus` only, used once its weights are loaded and it is enabled. */
    std::unique_ptr<TcnDecoder> neuralDecoder_;
    bool neuralEnabled_ = false;
#endif
    bool lastDecodeUsedNeural_ = false;
#ifdef BORDERKEYS_NEURAL_SWIPE
#endif
    UserModel userModel_;
    Arena arena_;

    Candidate heapStorage_[kMaxCandidates];
    Candidate drainBuffer_[kMaxCandidates];

    /** The walk's candidates reached by an edit, completions left out, for autocorrect. */
    static constexpr int kMaxCorrections = 4;
    Candidate correctionStorage_[kMaxCorrections];
    TopK<Candidate> correctionHeap_;
    Candidate bestCorrection_{};
    bool hasBestCorrection_ = false;

    /** The dictionary's spelling of exactly the letters typed, which outranks the heap above. */
    Candidate bestRespelling_{};
    bool hasBestRespelling_ = false;

    /** Multiplier on how fast the personal model gains ground. 1.0 is the default. */
    float learningSpeed_ = 1.0f;
    /** Multiplier on kEditPenalty and kCorrectionSurcharge. 1.0 is the default. */
    float correctionStrictness_ = 1.0f;

    /** The context word's entry in the personal model, or -1; resolved once per request. */
    int32_t userContext1_ = -1;
    /** The word before that one, in the personal model. -1 when there is none. */
    int32_t userContext2_ = -1;

    bool phraseSuggestions_ = false;
    bool personalModelEnabled_ = true;

    /** The blocked spellings, lowered, sorted and distinct. */
    std::vector<std::string> blocked_;

    /** Text for the phrase candidates of the request being answered. */
    static constexpr int kMaxPhrases = 4;
    static constexpr int kMaxPhraseBytes = 96;
    char phraseText_[kMaxPhrases][kMaxPhraseBytes] = {};
    int phraseLength_[kMaxPhrases] = {};
    int phraseCount_ = 0;

    // The context words' indices in each pack, resolved once per request.
    int32_t contextWord1_[kMaxPacks] = {};
    int32_t contextWord2_[kMaxPacks] = {};
    bool hasContext1_ = false;
    bool hasContext2_ = false;

    // The remaining node-visit allowance for the pack being searched, reset per pack.
    int32_t visitBudget_ = 0;

    float normalisedWeight_[kMaxPacks] = {};
    bool created_ = false;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_ENGINE_HPP
