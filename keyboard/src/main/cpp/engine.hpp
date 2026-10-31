// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_ENGINE_HPP
#define BORDERKEYS_ENGINE_HPP

#include <cstdint>
#include <limits>

#include <memory>
#include <string>
#include <vector>

#include "arena.hpp"
#include "bkd_format.hpp"
#include "candidate.hpp"
#include "candidate_sets.hpp"
#include "context_slice.hpp"
#include "gesture/gesture_decoder.hpp"
#include "letter_model.hpp"
#include "marks.hpp"
#include "ngram_model.hpp"
#include "packed_trie.hpp"
#include "personal_score.hpp"
#include "proximity.hpp"
#include "search_plan.hpp"
#include "topk.hpp"
#include "touch_model.hpp"
#include "word_table.hpp"

#ifdef BORDERKEYS_NEURAL_SWIPE
#include "gesture/tcn_decoder.hpp"
#endif

namespace borderkeys {

// The tap decoder's building blocks, tap_decode.hpp.
struct DecoderTaps;
class DecoderLevel;
class DecodedWords;
struct WordPath;

/** What a pack's validated header says about itself, filled by [bkdInspectPack]. */
struct PackInfo {
    char tag[kLanguageTagBytes];
    uint32_t formatVersion;
    uint32_t wordCount;
    uint64_t fileBytes;
    // Of wordCount, the words the pack only knows and never offers.
    uint32_t knownOnlyCount;
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

    // How likely a run of letters is as a spelling of this language, built once at load.
    const LetterModel& letters() const { return letters_; }

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

    char tag_[kLanguageTagBytes] = {};
    PackedTrie trie_;
    NgramModel ngrams_;
    LetterModel letters_;

    int32_t frequent_[kFrequentCount] = {};
    int frequentCount_ = 0;
};

/** The engine, and the scoring surface the gesture decoder sees through [GestureScorer]. */
class Engine final : public GestureScorer {
public:
    static constexpr int kMaxPacks = 4;

    /** No pack: what the pack queries answer while none is decided, and what clears answerAs. */
    static constexpr int kNoPack = -1;

    /** A possessive's ending, folded: the apostrophe and the s [learnable] looks behind. */
    static constexpr uint32_t kPossessiveEnding[] = {kApostrophe, 's'};
    static constexpr int kPossessiveEndingLength =
        static_cast<int>(sizeof(kPossessiveEnding) / sizeof(kPossessiveEnding[0]));

    /** setLearnAfterUses' default: KeyboardPreferences.DEFAULT_LEARN_AFTER_USES. */
    static constexpr uint32_t kDefaultLearnAfterUses = 3;
    /** setLearnAfterUses' largest value: the last of KeyboardPreferences.LEARN_AFTER_USES_STEPS. */
    static constexpr uint32_t kMaxLearnAfterUses = 13;

    /** What one key width of finger error costs, in nats. The build may set it for a sweep. */
#ifdef BORDERKEYS_EDIT_PENALTY
    static constexpr float kEditPenalty = static_cast<float>(BORDERKEYS_EDIT_PENALTY);
#else
    static constexpr float kEditPenalty = 25.0f;
#endif

    /**
     * A flat cost for any candidate reached with an edit, on top of the per-edit cost, in nats.
     * The build may set it for a sweep.
     */
#ifdef BORDERKEYS_CORRECTION_SURCHARGE
    static constexpr float kCorrectionSurcharge = static_cast<float>(BORDERKEYS_CORRECTION_SURCHARGE);
#else
    static constexpr float kCorrectionSurcharge = 3.0f;
#endif

    /** The cost of the cheapest edit at strictness 1: one substitution at its floor, surcharged. */
    static constexpr float kCheapestEditCost =
        kEditPenalty * KeyGeometry::kMinSubstitutionCost + kCorrectionSurcharge;

    /**
     * setCorrectionStrictness' default, and the range it clamps to: a multiplier on kEditPenalty
     * and kCorrectionSurcharge. See KeyboardPreferences.
     */
    static constexpr float kDefaultCorrectionStrictness = 1.0f;
    static constexpr float kMinCorrectionStrictness = 0.5f;
    static constexpr float kMaxCorrectionStrictness = 2.0f;
    // How many candidates other than continuations of the letters typed one request keeps, the
    // most one request returns, and how many one swipe's decode keeps.
    static constexpr int kMaxCandidates = 16;
    // How many continuations of the letters typed one request keeps, the most the strip shows.
    static constexpr int kMaxShownCompletions = 4;
    static constexpr int kMaxComposing = 48;

    bool create();
    void destroy();

    int32_t loadLanguage(const char* tag, int fd, int64_t offset, int64_t length, float weight);

    /**
     * Makes `tags`, at most kMaxPacks, the whole set of languages consulted, with their weights;
     * anything open and not named is closed and its slot freed. An empty set closes everything.
     */
    void setActiveLanguages(const char* const* tags, const float* weights, int count);
    /**
     * The keys and their centres, and the long-press letters a swipe may reach on them:
     * `aliasCodes[i]` on the key of `aliasBases[i]`; see KeyGeometry::setAliases.
     */
    bool setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                        int count, float keyWidth, float keyHeight,
                        const int32_t* aliasCodes = nullptr, const int32_t* aliasBases = nullptr,
                        int aliasCount = 0);

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
     * personal dictionary holds it established. With [tag], only that language's pack answers,
     * the language whose ending made the stem.
     */
    bool vouchesForStem(const char* word, size_t length, const char* tag = nullptr) const;

    /**
     * The language being written: [answerAs]'s pack, else by the evidence, or [kNoPack] when none
     * leads.
     */
    int preferredPack() const;

    /**
     * The pack a request is restricted to: [answerAs]'s, else the detected language's, else the
     * preferred language's; [kNoPack] when none is.
     */
    int restrictedPack() const;

    /**
     * The pack an answer leans on: [restrictedPack], else the heaviest active one; [kNoPack] with
     * no active pack.
     */
    int answerPack() const;

    /**
     * Locates a spelling the dictionaries hold that differs from `word` only by case: the
     * language being written's, when one leads and holds the letters at all, else any pack's.
     */
    bool exactSpelling(const char* word, size_t length, int* packOut, uint32_t* wordOut) const;

    /**
     * The possessive of a word missing its apostrophe, written into [out], or zero: the word
     * ends in s, no dictionary holds it, and an active pack flags its stem a name.
     */
    int possessiveFor(const char* word, size_t length, char* out, int outBytes) const;

    /**
     * Has the requests that follow answer as if [packIndex] were the language being written,
     * whatever the evidence says, until called with [kNoPack]. False, with nothing changed, for a
     * pack that is not open and active. [dominantPack] and the evidence are left as they are.
     */
    bool answerAs(int packIndex);

    /**
     * Answers as [packIndex] for its lifetime, as [answerAs] does, and returns the requests to
     * [kNoPack] on every exit when it took the pack; [held] says whether it did.
     */
    class AnsweringScope {
    public:
        AnsweringScope(Engine& engine, int packIndex)
            : engine_(engine), held_(engine.answerAs(packIndex)) {}
        ~AnsweringScope() {
            if (held_) {
                engine_.answerAs(kNoPack);
            }
        }
        AnsweringScope(const AnsweringScope&) = delete;
        AnsweringScope& operator=(const AnsweringScope&) = delete;

        bool held() const { return held_; }

    private:
        Engine& engine_;
        const bool held_;
    };

    /** The pack the conversation is considered written in, or [kNoPack] when undecided. */
    int32_t dominantPack() const { return dominantPack_; }

    // Fills `out` with at most `maxOut` candidates, best first, and returns how many were
    // written. `composing` may be empty, in which case this answers "what word comes next".
    // The personal pairs and triples are the slice's when it is the one of `personalContext`
    // ([pushSlice]); else none are read.
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                Candidate* out, int maxOut, const ContextSlice::Key& personalContext = {});

    // suggest, with where each code point of `composing` was tapped, in the keyboard view's
    // pixels: `tapCount` entries, NaN for none. The touch model prices each tapped letter's
    // substitutions from them.
    int suggest(const char* composing, size_t composingLength, const char* previous1,
                size_t previous1Length, const char* previous2, size_t previous2Length,
                const float* tapX, const float* tapY, int tapCount, Candidate* out, int maxOut,
                const ContextSlice::Key& personalContext = {});

    /**
     * Decodes a swipe into candidates, best first, from raw touch points in view pixels,
     * historical ones included; the decoder smooths and resamples them. The personal pairs and
     * triples are read as [suggest] reads them.
     */
    int decodeGesture(const float* xs, const float* ys, const int64_t* ts, int count,
                      const char* previous1, size_t previous1Length, const char* previous2,
                      size_t previous2Length, Candidate* out, int maxOut,
                      const ContextSlice::Key& personalContext = {});

    const char* gestureDecoderName() const;

    /** Whether the last [decodeGesture] went through the neural decoder. */
    bool lastDecodeUsedNeural() const { return lastDecodeUsedNeural_; }

    /**
     * Loads tier B's weights for [script], building its decoder if needed: the slot already
     * holding [script], else a free one, else the one selected least recently. Always false
     * without `BORDERKEYS_NEURAL_SWIPE`.
     */
    bool loadSwipeWeights(int script, const uint8_t* data, size_t length);

    /** [loadSwipeWeights] for [kLatinSwipeScript]. */
    bool loadSwipeWeights(const uint8_t* data, size_t length) {
        return loadSwipeWeights(kLatinSwipeScript, data, length);
    }

    /**
     * Which script's model [decodeGesture] uses for the layout now set; one with no model loaded,
     * or [kNoSwipeScript], decodes with tier A.
     */
    void selectSwipeScript(int script);

    /** Whether a model for [script] is loaded. */
    bool hasSwipeModel(int script) const;

    /** The script the app's Latin layouts share, and the one meaning no model. */
    static constexpr int kLatinSwipeScript = 0;
    static constexpr int kNoSwipeScript = -1;

    /** How many models are held at once. */
    static constexpr int kMaxSwipeModels = 2;

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
    float personalScore(const char* text, uint32_t length) const override;
    int32_t offeredSpelling(int packIndex, uint32_t firstIndex) const override;

    /**
     * Whether `word` may be part of a learned pair or triple: an active pack knows it, its form
     * without apostrophes, the word before a final possessive, or every part between its hyphens;
     * or the personal model holds it established. Every word is, with no pack active.
     */
    bool learnable(const char* word, size_t length) const;

    /**
     * Replaces the personal words with the rows Kotlin hands over (WordTable::load), and empties
     * the slice, whose ids named the words before: each row's id goes to `outIds[i]`, and to
     * `outKnown[i]`, when given, whether an active pack knows the word.
     */
    int loadWordTable(const char* const* spellings, const size_t* lengths, const int32_t* fields,
                      int count, int32_t* outIds, bool* outKnown = nullptr);

    /**
     * Sets the personal words the rows name to what they hand (WordTable::put), a new word taking
     * the next id: each row's id goes to `outIds[i]`, and to `outKnown[i]`, when given, whether
     * an active pack knows the word.
     */
    int putWords(const int32_t* ids, const char* const* spellings, const size_t* lengths,
                 const int32_t* fields, int count, int32_t* outIds, bool* outKnown = nullptr);

    /** The personal words, as the last load and puts left them. */
    const WordTable& personalWords() const { return personalWords_; }

    /**
     * Writes to `outKnown[i]`, when given, whether an active pack knows the personal word with
     * id `ids[i]`; false for no word.
     */
    void markKnown(const int32_t* ids, int count, bool* outKnown) const;

    /**
     * The active packs that spell `word` in any case, a name only in its own (exactSpellingIn),
     * one bit per pack: bit i for the pack at slot i. None for a word that does not fold.
     */
    uint32_t packsSpelling(const char* word, size_t length) const;

    /**
     * Replaces the slice the requests read their personal pairs and triples from with the arrays
     * Kotlin hands over (ContextSlice::replace); false, the slice emptied, when they disagree.
     */
    bool pushSlice(const int32_t* header, size_t headerInts, const int32_t* links, size_t linkInts,
                   const int32_t* hops, size_t hopInts, const int64_t* seen, size_t seenLongs);

    /** The slice, as the last push left it. */
    const ContextSlice& slice() const { return slice_; }

    /**
     * How many uses a word, pair or triple needs before it is learned, from 1 to
     * kMaxLearnAfterUses; below it, it carries no weight. See KeyboardPreferences.
     */
    void setLearnAfterUses(int uses);

    /**
     * How common a word a pack only knows must be to count as spelled: its unigram
     * log-probability at least `minimumLogProb`. Positive infinity, the default, counts none.
     */
    void setKnownWordReach(float minimumLogProb) { knownWordMinLogProb_ = minimumLogProb; }

    /**
     * How much evidence an edit needs to outrank a word spelled as typed: a multiplier on
     * kEditPenalty and kCorrectionSurcharge, 1.0 by default, clamped to
     * [kMinCorrectionStrictness, kMaxCorrectionStrictness].
     */
    void setCorrectionStrictness(float scale);

    /**
     * How much one-sided evidence is wanted before a language is decided. Once one is, the other
     * dictionaries offer only words closer to the typed letters than the decided one reached, or
     * nothing at all when [strict]. At or below zero no language is ever decided. The evidence
     * already gathered is decided again under the new minimum.
     */
    void setLanguageLock(float minimumEvidence, bool strict);

    /**
     * Which language is searched first while none has been recognised, the others offering only
     * closer words; null or empty clears it. Not a scoring term.
     */
    void setPreferredLanguage(const char* tag);

    /** Forgets which language the conversation is in. */
    void resetLanguageEvidence();

    /** The evidence gathered for the open pack with [tag], or zero when no open pack has it. */
    float languageEvidence(const char* tag) const;

    /**
     * Sets the evidence for the open pack with [tag] and decides the language from all of it
     * again, as a committed word does; a tag no open pack has is ignored.
     */
    void setLanguageEvidence(const char* tag, float evidence);

    /** Whether two-word suggestions are offered at all. Off by default. */
    void setPhraseSuggestions(bool enabled) { phraseSuggestions_ = enabled; }

    /** Whether the personal dictionary takes part in suggestions; off for a private field. */
    void setPersonalModelEnabled(bool enabled) { personal_.setEnabled(enabled); }

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

    /** The tag of the pack in [slot], or null when the slot holds no open pack. */
    const char* packTag(int slot) const;

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
        // The part of [personal] the context before the cursor adds.
        float context = 0.0f;
        float rest = 0.0f;
        int32_t packIndex = kNoPack;
        int32_t rank = -1;
        int32_t editDistance = 0;
        int32_t addedCharacters = 0;
        // How the walk reached the word, and what each part of that cost on the strip.
        float editCost = 0.0f;
        int32_t edits = 0;
        int32_t pastTyped = 0;
        float editPenalty = 0.0f;
        float surcharge = 0.0f;
        float completion = 0.0f;
    };

    /** Fills [out] for [candidate] as an answer to [typed], the personal pairs and triples read
     *  as [suggest] reads them; false when the search does not offer that word. */
    bool explainScore(const char* typed, size_t typedLength, const char* candidate,
                      size_t candidateLength, ScoreParts* out,
                      const ContextSlice::Key& personalContext = {});

    /**
     * Autocorrect's answer from the last [suggest] request, or null: the best correction,
     * respelling or exact spelling of the letters typed. Valid until the next request.
     */
    const Candidate* bestCorrection() const {
        return hasBestCorrection_ ? &bestCorrection_ : nullptr;
    }

    /**
     * Autocorrect's candidates from the last [suggest] request, best first: the respelling of
     * the letters typed when there is one, then the correction heap. Returns how many, with
     * [out] pointing at them. Valid until the next request.
     */
    int corrections(const Candidate** out) const {
        *out = settled_;
        return settledCount_;
    }

    /**
     * Whether entry [index] of [corrections] stands: no single other reading of the taps, the
     * typed letters as an unknown word included, is likelier by more than kEvidenceRatio
     * (engine_decode.cpp). A respelling of the letters typed always stands, and every entry does
     * when the tap decoder did not run.
     */
    bool correctionConfident(int index) const {
        return index < 0 || index >= settledCount_ || settledConfident_[index];
    }

    /** The multiplier on kEditPenalty and kCorrectionSurcharge in force. */
    float correctionStrictness() const { return correctionStrictness_; }

    /**
     * The tap decoder's word from the last [suggest] request when it was accepted, or null: the
     * word the taps fit best with the language model, beating by kEvidenceRatio the typed
     * letters read as an unknown word and every other word the taps reach, together
     * (engine_decode.cpp). Valid until the next request.
     */
    const Candidate* decodedCorrection() const {
        return (hasDecoded_ && decodedAccepted_) ? &decoded_ : nullptr;
    }

    /**
     * The tap decoder's word from the last [suggest] request when it beat the typed letters but
     * not the other words together, or null: offered on the strip rather than applied.
     */
    const Candidate* decodedSuggestion() const {
        return (hasDecoded_ && decodedOffered_) ? &decoded_ : nullptr;
    }

    /**
     * The tap decoder's best word from the last [suggest] request, accepted or not, and its
     * margin in nats over the typed letters read as an unknown word; false when it found none.
     */
    bool decoderBest(Candidate* out, float* margin) const;

    // Whether the candidate is a name: a pack word flagged by every active pack that knows it, or
    // a personal word the user capitalised on purpose or the active packs agree is a name.
    bool candidateIsProperNoun(const Candidate& candidate) const;
    /** Whether every active pack that knows [folded] flags it a name; false when none knows it. */
    bool packsAgreeProperNoun(const uint32_t* folded, int foldedLength) const;

    /**
     * Whether a word is common enough to be a correction: within kCorrectionFrequencyFloor of
     * its pack's commonest word, or so in another active pack holding the same spelling.
     */
    bool plausibleCorrectionTarget(const LanguagePack& pack, uint32_t wordIndex) const;

    /** Whether a word is within kCorrectionFrequencyFloor of its own pack's commonest word. */
    bool commonIn(const LanguagePack& pack, uint32_t wordIndex) const;

    /**
     * Whether [text] carries the letters typed in this request on further, rather than being
     * reached by an edit; false with nothing typed.
     */
    bool continuesTyped(const char* text, uint32_t length) const;

    const KeyGeometry& geometry() const { return geometry_; }

private:
    struct Endpoint {
        int32_t node;
        float cost;
        uint8_t edits;
        uint8_t slips;
    };

    int packIndexForTag(const char* tag) const;
    void searchPack(int packIndex, const uint32_t* folded, int foldedLength,
                    CandidateSets& sets);
    void searchNextWord(int packIndex, CandidateSets& sets);
    // Scores the language's most frequent words that start with the typed prefix.
    void searchFrequentWithPrefix(int packIndex, const uint32_t* folded, int foldedLength,
                                  CandidateSets& sets);
    void searchUserModel(const uint32_t* folded, int foldedLength, CandidateSets& sets);

    /** The established personal entry spelled `word`, or -1; -1 while the personal model is off. */
    int32_t establishedPersonalEntry(const char* word, size_t length) const;

    /** Whether any active pack holds this text, folded, in a spelling that is not blocked. */
    bool anyPackKnows(const char* text, uint32_t length) const;

    /** [anyPackKnows] for text already folded. */
    bool anyPackKnowsFolded(const uint32_t* folded, int length) const;

    /** Whether any pack is open and active. */
    bool anyPackActive() const;

    /**
     * Exact lookup of an already folded word in `trie` that the pack offers, or only knows at
     * the reach setKnownWordReach set. Returns the word index or -1.
     */
    int32_t knownIn(const PackedTrie& trie, const uint32_t* folded, int count) const {
        return trie.lookupKnown(folded, count, knownWordMinLogProb_);
    }

    /** setKnownWordReach's minimum. */
    float knownWordMinLogProb_ = std::numeric_limits<float>::infinity();

    /** The first spelling of [trie]'s run at [firstIndex] that is not blocked, or -1. */
    int32_t firstUnblockedSpelling(const PackedTrie& trie, int32_t firstIndex) const;

    /** Offers the words this person writes after the context word; for the empty prefix. */
    void searchUserSuccessors(CandidateSets& sets);

    /**
     * Offers a two-word continuation from the personal model when each link has twice the
     * learn-after uses and holds its share of its context.
     */
    void searchUserPhrases(CandidateSets& sets);



    // Walks the fuzzy neighbourhood of the typed prefix, returning the trie nodes where the
    // whole input has been consumed, with what it cost to get there.
    int collectEndpoints(const LanguagePack& pack, const uint32_t* folded, int foldedLength,
                         float maxCost, Endpoint* out, int maxOut);
    // Descends from an endpoint collecting whole words.
    void collectWords(int packIndex, const LanguagePack& pack, const Endpoint& endpoint,
                      CandidateSets& sets);

    /** Normalises the active packs' weights; used by tapping and swiping. */
    void refreshWeights();
    // Offers a candidate to [heap], replacing an entry for the same word instead of adding a
    // second one.
    void offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                        uint32_t textLength) const;
    // Whether [score] stays at or below [floor] with even the largest personal boost added.
    bool outOfReach(float score, float floor) const;
    // Offers [candidate], spelled [text], to [heap] with its personal boost added, unless it is
    // out of reach of the heap's floor.
    void offerBoosted(TopK<Candidate>& heap, Candidate candidate, const char* text,
                      uint32_t textLength) const;
    // The candidate for [trie]'s word at [wordIndex], scored [score], with how it was reached.
    Candidate scoredCandidate(const PackedTrie& trie, int packIndex, uint32_t wordIndex,
                              float score, float editCost, int edits, int pastTyped,
                              int slips) const;
    // Looks a word up and offers it with how it was reached through offerBoosted; a word out of
    // reach of the heap's floor is passed over before its text is read.
    void offerScoredWord(TopK<Candidate>& heap, const PackedTrie& trie, int packIndex,
                         uint32_t wordIndex, float score, float editCost, int edits,
                         int pastTyped, int slips = 0) const;
    // The same, into the heap of [sets] the word belongs to; a word out of reach of the lower of
    // the two floors is passed over before its text is read.
    void offerScoredWord(CandidateSets& sets, const PackedTrie& trie, int packIndex,
                         uint32_t wordIndex, float score, float editCost, int edits,
                         int pastTyped, int slips = 0) const;
    // Which language is being written, decided from the words already committed. dominantPack_
    // is [kNoPack] until the evidence is one-sided enough to be worth acting on.
    void observeContextLanguage(const uint32_t* folded, int length);

    /** Sets dominantPack_ from languageEvidence_ and the lock's minimum. */
    void decideDominantPack();

    /**
     * Runs the prefix search over every active pack, or over [onlyPack] when it is not [kNoPack],
     * leaving out [skipPack] when it is not [kNoPack].
     */
    void searchPacks(const uint32_t* folded, int foldedLength, int onlyPack,
                     CandidateSets& sets, int skipPack = kNoPack);

    /** Locates, in the pack at [index], a spelling that differs from `word` only by case. */
    bool exactSpellingIn(int index, const char* word, size_t length, const uint32_t* folded,
                         int foldedLength, uint32_t* wordOut) const;

    /**
     * The spelling, in the pack at [index], that reads `word`'s letters -- keeping every mark
     * they were typed with and, for a name, the case -- or -1.
     */
    int32_t readingIn(int index, const char* word, size_t length, const uint32_t* folded,
                      int foldedLength) const;

    /**
     * How a word reads the typed letters, closest first, for an edit cost of zero: spelling them
     * with every mark typed, spelling them without a mark typed, or with letters past the typed
     * ones.
     */
    enum class Fit : uint8_t {
        Spells,
        DropsTypedMark,
        PastTyped,
    };

    /** How closely a word matches the typed letters: its edit cost, then its fit. */
    struct Closeness {
        float cost;
        Fit fit;
    };

    /** How the word [text] fits the typed letters when reached at no cost, [depth] past them. */
    Fit fitOf(int depth, const char* text, uint32_t length) const;

    /**
     * Whether [text], folding to [typedText]'s key, keeps every mark it was typed with and, with
     * [matchCase], which of its letters were typed as capitals.
     */
    static bool readsAsTyped(const char* typedText, uint32_t typedLength, const char* text,
                             uint32_t length, bool matchCase);

    /**
     * Records a word Primary reached as its closest reading so far: any ordinary word; a name
     * reached by an edit or with letters past the typed ones only when common enough to be a
     * correction, and one reached freely only in the case it was typed in.
     */
    void notePrimaryReach(const LanguagePack& pack, uint32_t wordIndex, float cost, Fit fit);

    /**
     * Whether a word at [cost] and [fit] reads the typed letters better than Primary's closest
     * reading: cheaper by kLanguageMargin, or as cheap and a closer fit.
     */
    bool closerThanPrimary(float cost, Fit fit) const;

    /** The closest reading Primary reached, while primaryReached_. */
    Closeness primaryClosest_ = {0.0f, Fit::Spells};
    bool primaryReached_ = false;

    /** The word being typed in one request: as typed, whether it carries a folded mark, folded. */
    struct TypedWord {
        const char* text = nullptr;
        uint32_t length = 0;
        bool carriesMark = false;
        const uint32_t* folded = nullptr;
        int foldedLength = 0;
    };

    /** The word being typed while one request's search runs; none outside it. */
    TypedWord typed_;

    /** Runs one pass of kSearchPlan: sets its ceiling and searches its source. */
    void runPass(const PassSpec& spec, const uint32_t* folded, int foldedLength, int restrictTo,
                 CandidateSets& sets);

    /**
     * Settles autocorrect's answer: the correction heap's best, overridden by the respelling of
     * the typed letters, overridden by the dictionaries' exact spelling of them.
     */
    void settleCorrection(const char* composing, size_t composingLength);

    // Places decodedSuggestion() in `out` as placeOffered places a word; returns how many `out`
    // holds.
    int placeDecodedSuggestion(Candidate* out, int written, int maxOut) const;

    // Puts the dictionaries' exact spelling of the typed letters into `out` last, in place of
    // the last entry when `out` is full, unless its spelling is already among the `written`;
    // returns how many `out` holds.
    int keepTypedSpelling(Candidate* out, int written, int maxOut) const;

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

    /** The active dictionary with the highest configured weight, or [kNoPack] when none is open. */
    int heaviestPack() const;

    float languageLockMinimum_ = 1.8f;
    bool strictLanguage_ = false;
    float languageEvidence_[kMaxPacks] = {};
    int dominantPack_ = kNoPack;
    /** The pack [answerAs] names, or kNoPack to answer as the evidence decides. */
    int answeringPack_ = kNoPack;
    uint32_t lastObservedWord_ = 0;

    /** The preferred language as a tag, and the slot it resolves to, or kNoPack. */
    char preferredTag_[kLanguageTagBytes] = {};
    int preferredPack_ = kNoPack;
    /** Re-resolves [preferredPack_] from [preferredTag_] when either or the open packs change. */
    void resolvePreferredPack();

    // Resolves the words before the request, and whether the slice is the one of `personalContext`.
    void resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                        size_t previous2Length, const ContextSlice::Key& personalContext);

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
    /** One loaded model: the script it serves, its decoder, and when it was last selected. */
    struct NeuralSlot {
        int script = kNoSwipeScript;
        std::unique_ptr<TcnDecoder> decoder;
        uint64_t selectedAt = 0;
    };
    /** Tier B, `plus` only, used once a model for the selected script is loaded and it is enabled. */
    NeuralSlot neuralSlots_[kMaxSwipeModels];
    int selectedScript_ = kLatinSwipeScript;
    uint64_t selectionClock_ = 0;
    bool neuralEnabled_ = false;

    /** The decoder for the selected script, loaded; null when there is none. */
    TcnDecoder* activeNeural() const;
#endif
    bool lastDecodeUsedNeural_ = false;
#ifdef BORDERKEYS_NEURAL_SWIPE
#endif
    /** The personal words, as Kotlin hands them over. */
    WordTable personalWords_;
    /** The personal links of the context Kotlin last handed over. */
    ContextSlice slice_;
    /** What the two add to a candidate's score, under the context each request resolves. */
    PersonalScore personal_{personalWords_, slice_, kDefaultLearnAfterUses};
    Arena arena_;

    /** The heaps of one request's two candidate sets, and the scratch their drain uses. */
    Candidate continuationStorage_[kMaxShownCompletions];
    Candidate otherStorage_[kMaxCandidates];
    Candidate continuationDrain_[kMaxShownCompletions];
    Candidate otherDrain_[kMaxCandidates];

    /** The heap one swipe's decode re-offers its words through, and its drain. */
    Candidate gestureStorage_[kMaxCandidates];
    Candidate gestureDrain_[kMaxCandidates];

    /** The walk's candidates reached by an edit, completions left out, for autocorrect. */
    static constexpr int kMaxCorrections = 4;
    Candidate correctionStorage_[kMaxCorrections];
    TopK<Candidate> correctionHeap_;
    Candidate bestCorrection_{};
    bool hasBestCorrection_ = false;

    /** The respelling, when there is one, then the correction heap drained best first. */
    Candidate settled_[1 + kMaxCorrections];
    int settledCount_ = 0;

    /** The dictionary's spelling of exactly the letters typed, which outranks the heap above. */
    Candidate bestRespelling_{};
    bool hasBestRespelling_ = false;

    /**
     * Whether a dictionary spells the letters of the last request exactly, or they are an
     * established personal word.
     */
    bool typedKnown_ = false;

    /** The dictionaries' exact spelling of the letters of the last request, when one offers it. */
    Candidate typedSpelling_{};
    bool hasTypedSpelling_ = false;

    // The tap decoder, engine_decode.cpp: runs for a typed word no dictionary spells that neither
    // starts nor ends with a mark, over the packs the request searches.
    void decodeTaps(const uint32_t* folded, int foldedLength, int restrictTo);
    // Clears the decoder's word.
    void forgetDecoded();
    // Clears the settled corrections, the best correction and respelling, the typed spelling and
    // whether the typed letters are known.
    void forgetSettled();
    // The touch model's log-likelihood of a tap at (x, y) for the key of `code`.
    float tapFit(uint32_t code, float x, float y) const;
    // The highest tapFit of any key for a tap at (x, y).
    float likeliestTapFit(float x, float y) const;
    // Where letter `index` of the request was tapped, or the centre of `typed`'s key when it has
    // no position; false when neither is known.
    bool tapPoint(int index, uint32_t typed, float* x, float* y) const;
    // Fills each tap's point and likeliest fit; returns how well the taps fit the typed keys.
    float readTaps(DecoderTaps* taps) const;
    // Whether the decoder searches pack `packIndex` for a request restricted to `restrictTo`.
    bool decodes(int packIndex, int restrictTo) const;
    // The log-probability of the typed letters as a word no pack holds: kUnknownWordLogProb and
    // the chance of the letters as a spelling of each active pack, by the pack's weight.
    float unknownWordLogProb(const DecoderTaps& taps) const;
    void decodePack(int packIndex, const DecoderTaps& taps, DecodedWords* words);
    // Runs the beam over pack `packIndex`, held to `path` unless it is null, and hands `finish`
    // the last level.
    template <typename Finish>
    void runBeam(int packIndex, const DecoderTaps& taps, const WordPath* path, Finish finish);
    // The trie nodes word `wordIndex` of pack `packIndex` is spelled through; false when its
    // spelling leaves the trie.
    bool wordPathOf(int packIndex, uint32_t wordIndex, WordPath* path) const;
    // How likely the taps spell `word`, the language model and the personal score included, over
    // every path the beam keeps along its spelling.
    float listedTotal(const Candidate& word, const DecoderTaps& taps);
    // What the personal dictionary adds to word `wordIndex` of `trie` under the request's context.
    float personalScoreOf(const PackedTrie& trie, uint32_t wordIndex) const;
    // Whether `word` has the typed letters exactly, accents and case aside.
    bool respells(const Candidate& word, const DecoderTaps& taps) const;
    // Adds autocorrect's entries to `words` as rivals and sets each entry's confidence.
    void weighListedCorrections(const DecoderTaps& taps, float literal, DecodedWords* words);
    bool settledConfident_[1 + kMaxCorrections] = {};
    // Whether the decoder may correct `taps` to the word: not blocked, not the typed letters
    // themselves, not a name, and a plausible correction target.
    bool decodable(int packIndex, uint32_t wordIndex, const DecoderTaps& taps) const;
    // Offers `words` every word `finals` reaches, scored with the language model and the personal
    // score.
    void offerDecodedWords(int packIndex, const DecoderTaps& taps, const DecoderLevel& finals,
                           DecodedWords* words) const;
    Candidate decoded_{};
    bool hasDecoded_ = false;
    bool decodedAccepted_ = false;
    bool decodedOffered_ = false;
    float decodedMargin_ = 0.0f;

    /** Multiplier on kEditPenalty and kCorrectionSurcharge. 1.0 is the default. */
    float correctionStrictness_ = kDefaultCorrectionStrictness;

    bool phraseSuggestions_ = false;

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
