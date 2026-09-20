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

#ifdef BORDERKEYS_NEURAL_SWIPE
#include "gesture/tcn_decoder.hpp"
#endif

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

    /**
     * Makes `tags` the whole set of languages consulted, with their weights.
     *
     * Anything open that is not named is closed and its slot freed -- this is the only way a
     * slot ever comes back, so a caller replacing one language with another names the final set
     * here first and loads the newcomer after. An empty set (`count` 0, or null `tags`) closes
     * everything. At most kMaxPacks tags; the bridge clamps a longer list to the first kMaxPacks.
     */
    void setActiveLanguages(const char* const* tags, const float* weights, int count);
    bool setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                        int count, float keyWidth, float keyHeight);

    /**
     * How the dictionaries spell this word, written into `out` and returned as a byte count.
     *
     * The lookup folds case and diacritics, because that is how the trie is keyed, but what
     * comes back is the stored spelling -- and the caller compares it with what was typed. The
     * distinction is the whole point: for "Daca" this returns "dacă", which is a correction
     * worth making, while for "cana" it returns "cana", which is a word and must be left alone.
     *
     * Zero when no dictionary has it. One trie descent per active pack and one hash lookup, so
     * it costs a few microseconds and can sit beside the answer to a suggestion request.
     */
    int knownSpelling(const char* word, size_t length, char* out, int outBytes) const;

    /**
     * "Maria's" for "marias", written into [out], or zero when the word is not that.
     *
     * The productive half of apostrophe restoration. The bundled maps carry the possessives a
     * corpus happened to contain -- assassin's, germany's, valentine's -- and this covers the
     * name that was never written with one. Three conditions, and the middle one is the safety:
     * the word ends in s, no dictionary holds the word itself ("times" and "canvas" mean
     * themselves), and the stem is flagged a *name* by a pack that holds it, which is what keeps
     * "cats" from becoming "cat's".
     */
    int possessiveFor(const char* word, size_t length, char* out, int outBytes) const;

    /**
     * What [packIndex] alone would spell [word] as, ignoring whichever pack the engine currently
     * considers dominant -- the one place a caller gets to name a pack explicitly instead of
     * accepting [dominantPack]'s own verdict. Exists for exactly one question: "does the language
     * that just became dominant disagree with a correction already applied under a different
     * one" -- never used for live suggestion scoring, which is why there is no sentence context
     * here, only this pack's own best single-word answer.
     *
     * Returns 0 when [packIndex] is not open/active, or has nothing to offer past [word] itself.
     */
    int candidateForPack(int packIndex, const char* word, size_t wordLength, char* out,
                         int outBytes);

    /** The pack the conversation is currently considered written in, or -1 when undecided. See
     *  observeContextLanguage's own comment for how this is reached. */
    int32_t dominantPack() const { return dominantPack_; }

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

    /**
     * Loads tier B's trained weights, building the decoder to hold them if it is not there yet.
     * `plus`-only: a no-op that always returns false when this library was built without
     * `BORDERKEYS_NEURAL_SWIPE`, so the JNI bridge and its method table can stay identical
     * across flavors rather than forking on this one feature.
     */
    bool loadSwipeWeights(const uint8_t* data, size_t length);

    /**
     * Switches [decodeGesture] between tier A (always) and tier B (once weights are loaded and
     * this is true), and **frees tier B outright when turned off** -- the decoder holds its
     * weights by value, some two and a half megabytes of them, and the preference is off by
     * default, so keeping it resident for a feature nobody asked for is the wrong trade. The
     * next [loadSwipeWeights] builds it again. A no-op in a `core` build, for the same reason
     * as [loadSwipeWeights].
     */
    void setSwipeModelEnabled(bool enabled);

    /**
     * Decodes one synthetic gesture through tier B and throws the answer away, so that the
     * first gesture a person actually swipes is not also the first pass through the network.
     *
     * Goes straight to the decoder rather than through [decodeGesture]'s tier guard, so it does
     * not depend on [setSwipeModelEnabled] having run yet: the caller loads, warms, and only
     * then tells anyone the model is ready.
     *
     * Returns false, having done nothing, when there are no weights or no layout to trace a
     * stroke across. Warming is an optimisation and never a precondition -- a decode that
     * arrives first is correct either way, it just pays for the first run itself.
     */
    bool warmSwipeModel();

    // --- GestureScorer -------------------------------------------------------------------
    int packCount() const override { return kMaxPacks; }
    const PackedTrie* activeTrie(int packIndex) const override;
    float packWeightLog(int packIndex) const override;
    float contextLogProb(int packIndex, uint32_t wordIndex) const override;
    float userBoost(const char* text, uint32_t length) const override;

    void learn(const char* word, size_t wordLength, const char* previous1,
               size_t previous1Length, const char* previous2, size_t previous2Length,
               bool deliberateCapital = false);

    void loadUserWords(const char* const* words, const size_t* lengths, const int32_t* counts,
                       int count, const int32_t* deliberateCapitals = nullptr);

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
     * How much evidence an edit needs before it outranks a word spelled as typed. 1.0 is the
     * calibrated default (see kEditPenalty and kCorrectionSurcharge in engine.cpp); below 1.0
     * a correction needs less of a frequency gap to win, above 1.0 it needs more. Clamped to
     * [kMinCorrectionStrictness, kMaxCorrectionStrictness] -- past either end the strip either
     * stops correcting almost anything or corrects almost anything typed.
     */
    void setCorrectionStrictness(float scale);

    /**
     * How much one-sided evidence is wanted before the dictionaries for other languages stop
     * being searched. At or below zero they are always searched.
     */
    void setLanguageLock(float minimumEvidence, bool strict);

    /**
     * Which language answers before anything has been recognised. Null or empty clears it.
     *
     * *Preferred*, deliberately, and not *primary*: it says where detection starts, never what
     * wins. It is consulted only while [dominantPack] is undecided, it is outranked the moment
     * the evidence decides otherwise, and a word it has nothing for still falls through to every
     * other pack (see the empty-heap retry in suggest). A name implying a standing hierarchy
     * would invite exactly the thing this must never become -- a term in the score. It is not
     * one, and nothing in the scoring path reads it.
     *
     * This is what the pack *weight* used to have to stand in for, badly: weight is a scoring
     * term added to every candidate, so raising one language's weight to make it answer first
     * also biased every one of its words for ever, including after another language had become
     * dominant. Weight is left to be only what it says it is.
     *
     * The tag is kept rather than the slot it resolves to, because [setActiveLanguages] opens
     * and closes packs and a slot index does not survive that.
     */
    void setPreferredLanguage(const char* tag);

    /**
     * Forgets which language the conversation is in, as though nothing had been typed.
     *
     * Called when the field changes: a new field is a new conversation, which is the same stance
     * `LanguageSwitchCorrector.reset` already takes about the offsets it tracks. Without this the
     * verdict reached in one application is inherited by the next one opened, so a preferred
     * language never gets a look in after the first field of a session.
     */
    void resetLanguageEvidence();

    /** Whether two-word suggestions are offered at all. Off unless the user asks for them. */
    void setPhraseSuggestions(bool enabled) { phraseSuggestions_ = enabled; }

    /**
     * Whether the personal dictionary takes part in suggestions at all.
     *
     * Off for a private field -- a password, or one whose application asked for no personalised
     * learning. What this device learned from its owner must not be offered back into a field
     * that asked to be forgotten; that is the other half of not learning from it. The model
     * stays loaded and untouched, it is simply not consulted until an ordinary field switches
     * it back on.
     */
    void setPersonalModelEnabled(bool enabled) { personalModelEnabled_ = enabled; }

    /**
     * The tag of the pack the conversation is currently considered written in, or null while
     * undecided -- the same answer as dominantPack(), as a language rather than a slot.
     */
    const char* dominantLanguageTag() const;

    // Resolves a candidate to its display text. The pointer is owned by the mapping or by the
    // user model and stays valid until the pack is closed or the model is rewritten.
    const char* candidateText(const Candidate& candidate, uint32_t* lengthOut) const;

    /**
     * Where one candidate's score came from, for a reader rather than for the keyboard.
     *
     * A [Candidate] carries a single number, which is all the strip needs and all the ranking
     * needs -- and it is why every question about *why* a word won has had to be answered by
     * building a probe and reasoning backwards from a list. This says it directly.
     *
     * Deliberately outside the search. Nothing on the typing path calls it, no field is added to
     * [Candidate] (12 bytes of plain data crossing JNI on a path that must not allocate), and no
     * branch is added to the scoring loop. It re-runs a normal request and then decomposes the
     * winner's score from terms that are still in hand afterwards, which is exactly as accurate
     * as the loop and cannot drift from it, because it does not restate the formula.
     *
     * [ScoreParts::rest] is what remains once the language model and the pack weight are
     * accounted for, which is the edit cost and the completion penalty together. They are not
     * separated because the search does not keep them apart past the point where they are
     * applied; [ScoreParts::editDistance] is reported beside it so the reader can tell which of
     * the two is doing the work.
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

    /** Fills [out] for [candidate] as an answer to [typed]. False when the search does not
     *  offer that word at all, which is itself the answer to most questions asked of this. */
    bool explainScore(const char* typed, size_t typedLength, const char* candidate,
                      size_t candidateLength, ScoreParts* out);

    /**
     * The best word reached by an *edit* from the last [suggest] request, or null.
     *
     * What autocorrect should act on, and deliberately not the strip's first entry. A word that
     * merely carries on from what was typed is not a candidate for "what did you mean", however
     * well it ranks for "what are you writing" -- see [correctionHeap_] for why the two cannot
     * share a ranking.
     *
     * Valid until the next request on this engine. Nothing is decided here: whether the word is
     * applied is `AutoCorrection.correctionFor`'s to say, and it still applies every guard it
     * applied before.
     */
    const Candidate* bestCorrection() const {
        return hasBestCorrection_ ? &bestCorrection_ : nullptr;
    }

    // Whether the candidate is a name -- always capitalise it, the same override
    // PackedTrie::isProperNoun documents. A pack candidate answers this directly, from its own
    // flag. A phrase or a user-model entry carries no flag of its own -- neither this build's
    // packs nor a person's own typing classify anything -- so this falls back to looking its text
    // up (folded, case- and diacritic-insensitive) in every active pack instead: a name learned
    // from what someone typed is still the same name a pack would have flagged, the second time
    // it comes up.
    bool candidateIsProperNoun(const Candidate& candidate) const;
    /**
     * Whether every active pack that knows [folded] flags it a proper noun -- false when none
     * knows it, and false the moment one that knows it does not flag it. See
     * candidateIsProperNoun for why a single pack's flag is not enough on its own.
     */
    bool packsAgreeProperNoun(const uint32_t* folded, int foldedLength) const;

    /** Whether a word is common enough to be worth replacing someone's typing with -- the floor
     *  on what may enter the corrections heap. See kCorrectionFrequencyFloor. */
    bool plausibleCorrectionTarget(const LanguagePack& pack, uint32_t wordIndex) const;

    /** Whether [candidate] is what was typed carried on further, rather than reached by an
     *  edit -- the distinction the completion cap in suggest() is applied on. */
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
    // collectWords and searchNextWord's shared shape: look a word up and offer it only if the
    // maximum the personal-model boost could add would still beat the heap's current floor --
    // the trie's own wordText and userBoostFor's walk of the personal trie are each too
    // expensive to pay for a candidate that cannot possibly make the shortlist. Not shared with
    // searchFrequentWithPrefix, whose text is already resolved by the time it would call this,
    // from its own prefix match -- routing it through here would pay a second, redundant lookup
    // for a word it already has the text of.
    void offerScoredWord(TopK<Candidate>& heap, const PackedTrie& trie, int packIndex,
                         uint32_t wordIndex, float score) const;
    // Which language is being written, decided from the words already committed. dominantPack_
    // is -1 until the evidence is one-sided enough to be worth acting on.
    void observeContextLanguage(const uint32_t* folded, int length);

    /** Runs the prefix search over every active pack, or over [onlyPack] when it is not -1. */
    void searchPacks(const uint32_t* folded, int foldedLength, int onlyPack,
                     TopK<Candidate>& heap);

    // The edit-cost ceiling for the request being answered. A member rather than a parameter
    // because the fallback pass changes it between two runs over the same packs.
    float editCostCeiling_ = 0.0f;

    /** Whether the wider second pass is running. Its candidates fill the strip so it is not
     *  blank, and are kept out of the corrections heap: a word reachable only once the
     *  ceiling is opened is a guess worth showing and never one worth committing. */
    bool fallbackPass_ = false;

    /** The previous word's tag in each pack, resolved with contextWord1_. */
    uint32_t contextTag1_[kMaxPacks] = {};

    /** The active dictionary with the highest configured weight, or -1 when none is open. */
    int heaviestPack() const;

    float languageLockMinimum_ = 1.8f;
    bool strictLanguage_ = false;
    float languageEvidence_[kMaxPacks] = {};
    int dominantPack_ = -1;
    uint32_t lastObservedWord_ = 0;

    /** The preferred language as a tag, and the slot it currently resolves to (-1 for none).
     *  See setPreferredLanguage for why the tag is what is stored. */
    char preferredTag_[16] = {};
    int preferredPack_ = -1;
    /** Re-resolves [preferredPack_] from [preferredTag_]. Called whenever either the preference
     *  or the set of open packs changes, and never on the typing path. */
    void resolvePreferredPack();

    void resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                        size_t previous2Length);

    /**
     * Rescales `candidates[0..count)`'s scores in place to a fixed-temperature softmax over
     * [0, 1000].
     *
     * The gesture decoder's raw score is a log-probability sum with no fixed scale -- it runs
     * however far the language model and the geometry channels happen to push it, decode to
     * decode, and two different decodes are not comparable on it. This is what a caller would
     * need to show a confidence, compare it to a threshold, or blend it with a score from
     * elsewhere; a raw log-score cannot do any of those. Only [decodeGesture] calls this --
     * tap-typing's own candidates, scored and ranked the same way internally, are never
     * rescaled, so nothing about `nativeSuggest` changes.
     */
    static void normaliseGestureScores(Candidate* candidates, int count);

    LanguagePack packs_[kMaxPacks];
    KeyGeometry geometry_;
    /**
     * Tier A: geometric, ships in every build, and always what [decodeGesture] falls back to.
     * `core` never compiles anything else, so there is no `if (neural)` anywhere near a finger
     * in that flavor -- [neuralDecoder_] and [neuralEnabled_] do not exist in its binary at all.
     */
    std::unique_ptr<GestureDecoder> gestureDecoder_;

#ifdef BORDERKEYS_NEURAL_SWIPE
    /**
     * Tier B: `plus`-only, and only used once [loadSwipeWeights] has succeeded and
     * [setSwipeModelEnabled] has turned it on -- an "experimental swipe model" preference the
     * user opts into, off by default. The shipped checkpoint is trained under the fixed feature
     * scaling; the runtime scale-compensation shim an earlier one needed is gone from
     * `gesture/tcn_decoder.cpp`.
     */
    std::unique_ptr<TcnDecoder> neuralDecoder_;
    bool neuralEnabled_ = false;
#endif
    UserModel userModel_;
    Arena arena_;

    Candidate heapStorage_[kMaxCandidates];
    Candidate drainBuffer_[kMaxCandidates];

    /**
     * The same walk's answers, kept a second time with the completions left out.
     *
     * Autocorrect and the suggestion strip are asking different questions, and until now both
     * read the same answer. The strip asks "what are you writing", where a longer word carrying
     * on from what has been typed is a fine reply. Autocorrect asks "what did you mean", at a
     * point where the word is finished and a continuation of it is not a candidate at all.
     *
     * Ranking them together means pricing "a longer word starting with this" against "a
     * different word one slip away", and there is no honest exchange rate between those -- the
     * attempt to set one is why kEditPenalty is 40 and why a `static_assert` has to defend it.
     * Typing "teh" the strip holds tehran, tehran's, Tehan, Tehrani and six more before "the",
     * and since autocorrect read the first entry it applied none of them. Not two faults: the
     * strip was reporting the ranking honestly, and the ranking was answering the wrong
     * question.
     *
     * So corrections are collected again, alone, during the same walk and at the same moment --
     * no second pass over any dictionary, and nothing here changes what the strip shows. Four
     * entries because only the best is ever read; the rest are there so the best is the best of
     * several rather than the first one reached.
     */
    static constexpr int kMaxCorrections = 4;
    Candidate correctionStorage_[kMaxCorrections];
    TopK<Candidate> correctionHeap_;
    Candidate bestCorrection_{};
    bool hasBestCorrection_ = false;

    /**
     * The dictionary's own spelling of exactly the letters typed, when it holds one.
     *
     * Kept apart from the heap above rather than scored into it, because it is not competing
     * with those candidates -- it outranks all of them, and no score would say so reliably. A
     * proposal is admitted only within kCorrectionFrequencyFloor of the commonest word, but a
     * respelling is exempt (see collectWords), so the gap between the two is unbounded and any
     * constant large enough to win today erodes the moment a rarer word needs restoring.
     *
     * A tier, then, not a bonus: if the dictionary spells the typed letters, that spelling is
     * the answer, and whether it differs from what was typed at all is AutoCorrection's
     * question rather than this one's.
     */
    Candidate bestRespelling_{};
    bool hasBestRespelling_ = false;

    // Per-request context, resolved once per pack instead of once per candidate.
    /**
     * The context word's entry in the personal model, or -1.
     *
     * Resolved once per request beside the per-pack context indices, because every candidate
     * would otherwise fold and look up the same word again.
     */
    /** Multiplier on how fast the personal model gains ground. 1.0 is the default. */
    float learningSpeed_ = 1.0f;
    /** Multiplier on kEditPenalty and kCorrectionSurcharge. 1.0 is the calibrated default. */
    float correctionStrictness_ = 1.0f;

    int32_t userContext1_ = -1;
    /** The word before that one, in the personal model. -1 when there is none. */
    int32_t userContext2_ = -1;

    bool phraseSuggestions_ = false;
    bool personalModelEnabled_ = true;

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

    // The remaining node-visit allowance for the pack currently being searched. This, not a
    // timer, is what holds the 8 ms budget: a wall-clock check would make the result depend on
    // how busy the device happened to be, so two identical requests could return different
    // suggestions. Reset fresh for each active pack inside Engine::searchPacks, not once for the
    // whole request -- a shared counter let one pack's fuzzy walk exhaust it before a later
    // pack's ever ran, silently starving that pack's corrections for that keystroke.
    int32_t visitBudget_ = 0;

    float normalisedWeight_[kMaxPacks] = {};
    bool created_ = false;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_ENGINE_HPP
