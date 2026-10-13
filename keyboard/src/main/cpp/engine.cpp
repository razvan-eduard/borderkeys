// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "engine.hpp"
#include "marks.hpp"
#include "reading.hpp"
#include "tap_decode.hpp"

#include "gesture/shark2_decoder.hpp"
#ifdef BORDERKEYS_NEURAL_SWIPE
#include "gesture/tcn_decoder.hpp"
#endif


#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>
#include <string>
#include <string_view>
#include <sys/mman.h>
#include <unistd.h>

namespace borderkeys {
namespace {

// Scoring constants, in natural log units.

// What an inserted letter costs, in key widths.
constexpr float kInsertCost = 0.85f;

// What a letter the word has past the last one typed costs autocorrect, in key widths. The
// build may set it for a sweep.
#ifdef BORDERKEYS_RUN_ON_COST
constexpr float kRunOnCost = static_cast<float>(BORDERKEYS_RUN_ON_COST);
#else
constexpr float kRunOnCost = 0.5f;
#endif

// The factor on a neighbouring key's distance when the finger landed on it instead; the result
// is floored at KeyGeometry::kMinSubstitutionCost. A straight neighbour then costs less than an
// insertion, a diagonal one more. The build may set it for a sweep.
#ifdef BORDERKEYS_SLIP_SCALE
constexpr float kSlipScale = static_cast<float>(BORDERKEYS_SLIP_SCALE);
#else
constexpr float kSlipScale = 0.8f;
#endif

// kApostrophe, kHyphen and isMark are in marks.hpp; carriesFoldedMark, kMaxCorrectionCompletion
// and Reading in reading.hpp.

// What a mark missing from the typed word costs, in key widths.
constexpr float kMarkInsertCost = 0.02f;

// What discarding a typed mark costs, in key widths: above maxEditCostFor's largest value and
// below kFallbackEditCost.
constexpr float kMarkDeleteCost = 3.0f;

// What discarding a typed letter costs, in key widths.
#ifdef BORDERKEYS_DELETE_COST
constexpr float kDeleteCost = static_cast<float>(BORDERKEYS_DELETE_COST);
#else
constexpr float kDeleteCost = 1.6f;
#endif

// What discarding a letter typed right after the same letter costs, in key widths.
constexpr float kRepeatDeleteCost = 0.75f;

// What swapping two adjacent letters costs, in key widths.
#ifdef BORDERKEYS_TRANSPOSE_COST
constexpr float kTransposeCost = static_cast<float>(BORDERKEYS_TRANSPOSE_COST);
#else
constexpr float kTransposeCost = 0.80f;
#endif

// What each character a completion adds beyond what was typed costs.
constexpr float kCompletionPenalty = 0.5f;

// How far below a language's commonest word a correction target may sit, in nats.
constexpr float kCorrectionFrequencyFloor = 9.0f;

// How far below the pack's commonest word a stem may sit and still vouch for its inflection, in
// nats; see Engine::vouchesForStem.
constexpr float kStemFrequencyFloor = 10.5f;

// How many continuations of what was typed may hold strip slots at once.
constexpr int kMaxShownCompletions = 4;
// Stupid backoff's factor.
constexpr float kBackoffLogFactor = -0.9162907f;  // ln(0.4)

// A word one cheapest edit from the typed letters cannot pass a correctly typed word on its word
// boost alone at any strictness, nor on its word boost and context bonus from the default
// strictness up.
static_assert(Engine::kMinCorrectionStrictness * Engine::kCheapestEditCost >
                  PersonalScore::kMaxUserBoost,
              "the word boost can lift a one-edit word over a correctly typed one");
static_assert(Engine::kDefaultCorrectionStrictness * Engine::kCheapestEditCost >
                  PersonalScore::kMaxPersonalScore,
              "the personal score can lift a one-edit word over a correctly typed one");
static_assert(Engine::kMinCorrectionStrictness <= Engine::kDefaultCorrectionStrictness &&
                  Engine::kDefaultCorrectionStrictness <= Engine::kMaxCorrectionStrictness,
              "the default strictness lies outside the range it is clamped to");

// The log-probability of a word only the personal dictionary holds, before its boost.
constexpr float kUserOnlyLogProb = -8.0f;

/**
 * The most a phrase this person writes may outrank what the corpus says follows, scaled by a
 * confidence that grows with its count.
 */
constexpr float kUserChainPreference = 1.5f;

/** Observations at which the preference above reaches half its ceiling. */
constexpr float kUserChainHalfLife = 3.0f;

/** How much larger the second link's prior is than the first's in a two-word suggestion. */
constexpr float kPhraseSecondLinkFactor = 1.5f;

/** The share of its context a link must hold to be part of a phrase. */
constexpr float kPhraseMinShare = 0.34f;

constexpr int kMaxEndpoints = 96;

// How many trie nodes a request may visit, by prefix length.
int nodeVisitBudgetFor(int length) {
    if (length <= 2) {
        return 3000;
    }
    if (length <= 4) {
        return 10000;
    }
    return 20000;
}
// How far a candidate may run ahead of what was typed through insertions.
constexpr int kMaxRunAhead = 2;

constexpr size_t kArenaBytes = 512 * 1024;

// How much finger error to tolerate, by prefix length; none below three characters. The build
// may set each band for a sweep.
#ifndef BORDERKEYS_CEILING_3
#define BORDERKEYS_CEILING_3 1.7f
#endif
#ifndef BORDERKEYS_CEILING_4
#define BORDERKEYS_CEILING_4 1.7f
#endif
#ifndef BORDERKEYS_CEILING_5
#define BORDERKEYS_CEILING_5 2.5f
#endif
#ifndef BORDERKEYS_CEILING_8
#define BORDERKEYS_CEILING_8 2.5f
#endif
float maxEditCostFor(int length) {
    if (length <= 2) {
        return 0.0f;
    }
    if (length == 3) {
        return static_cast<float>(BORDERKEYS_CEILING_3);
    }
    if (length == 4) {
        return static_cast<float>(BORDERKEYS_CEILING_4);
    }
    if (length <= 7) {
        return static_cast<float>(BORDERKEYS_CEILING_5);
    }
    return static_cast<float>(BORDERKEYS_CEILING_8);
}

// What a path may have spent before the typed position reaches 4, then 7; the ceiling itself
// when the build sets neither.
float depthCeilingFor(int inputPos, float maxCost) {
#ifdef BORDERKEYS_DEPTH_CAP_4
    if (inputPos < 4) {
        return std::min(maxCost, static_cast<float>(BORDERKEYS_DEPTH_CAP_4));
    }
#endif
#ifdef BORDERKEYS_DEPTH_CAP_7
    if (inputPos < 7) {
        return std::min(maxCost, static_cast<float>(BORDERKEYS_DEPTH_CAP_7));
    }
#endif
    (void)inputPos;
    return maxCost;
}

// The edit ceiling for the second pass, run only when the first found nothing.
constexpr float kFallbackEditCost = 4.2f;

// Each completed word multiplies every language's evidence by this and adds to the languages
// that hold it.
constexpr float kLanguageEvidenceDecay = 0.85f;

// The unigram log-probability gap, in nats, that earns a word a full point of evidence; a
// smaller gap earns in proportion.
constexpr float kLanguageEvidenceFullGap = 2.302585f;

// The share of the evidence one language must hold to count as the one being written.
constexpr float kLanguageDominanceShare = 0.7f;

// How much cheaper, in edit cost, another language's word must read the typed letters than the
// decided language's closest reading.
constexpr float kLanguageMargin = 0.5f;

// How much the part-of-speech transition counts where the n-gram model has nothing.
constexpr float kGrammarWeight = 0.75f;

// The reserved context for "a sentence began here"; see NgramModel::kSentenceStartContext.
constexpr uint32_t kSentenceStartIndex = NgramModel::kSentenceStartContext;

// How many of a context word's successors are scored in full when nothing has been typed:
// the strongest by their pair value, chosen in one pass over the list.
constexpr int kSuccessorWalk = 64;


// The scale the pack quantises log probabilities on, the same as tools/build_pos.py's.
constexpr float kLogProbScale = 10.0f;

// The longest blocked spelling, in bytes.
constexpr int kMaxBlockedBytes = 256;

// The order the blocked spellings are kept and searched in.
bool blockedBefore(const std::string& entry, std::string_view value) {
    return std::string_view(entry) < value;
}

}  // namespace

// --------------------------------------------------------------------------------------
// LanguagePack
// --------------------------------------------------------------------------------------

int32_t bkdInspectPack(int fd, int64_t offset, int64_t length, PackInfo* out) {
    if (out == nullptr || fd < 0 || offset < 0 || length <= 0) {
        return kBkdErrArgument;
    }
    if (static_cast<uint64_t>(length) > kMaxPackBytes) {
        return kBkdErrTooLarge;
    }
    if (static_cast<uint64_t>(length) < sizeof(BkdHeader)) {
        return kBkdErrTooSmall;
    }

    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        return kBkdErrMmap;
    }
    const int64_t delta = offset % pageSize;
    const size_t mapBytes = static_cast<size_t>(length + delta);
    void* const mapping = mmap(nullptr, mapBytes, PROT_READ, MAP_PRIVATE, fd,
                               static_cast<off_t>(offset - delta));
    if (mapping == MAP_FAILED) {
        return kBkdErrMmap;
    }
    const uint8_t* const base = static_cast<const uint8_t*>(mapping) + delta;
    const uint64_t baseBytes = static_cast<uint64_t>(length);

    BkdHeader header;
    std::memcpy(&header, base, sizeof(header));

    int32_t status = bkdValidateHeader(header, baseBytes);
    if (status == kBkdOk && (header.flags & kBkdFlagContentCrc) != 0u) {
        const uint64_t contentBytes = baseBytes - header.headerBytes;
        if (crc32(base + header.headerBytes, static_cast<size_t>(contentBytes)) !=
            header.contentCrc32) {
            status = kBkdErrContentCrc;
        }
    }

    if (status == kBkdOk) {
        // Copied whole and terminated.
        std::memcpy(out->tag, header.languageTag, sizeof(out->tag));
        out->tag[sizeof(out->tag) - 1] = '\0';
        out->formatVersion = header.formatVersion;
        out->wordCount = header.wordCount;
        out->fileBytes = header.fileBytes;
        out->knownOnlyCount = 0;
        const BkdSection& flags = header.sections[kSectionWordFlags];
        if (flags.length >= header.wordCount) {
            const uint8_t* const word = base + flags.offset;
            for (uint32_t i = 0; i < header.wordCount; ++i) {
                out->knownOnlyCount += (word[i] & kWordFlagKnownOnly) != 0u ? 1u : 0u;
            }
        }
    }

    munmap(mapping, mapBytes);
    return status;
}

int32_t LanguagePack::open(const char* tag, int fd, int64_t offset, int64_t length) {
    close();
    if (tag == nullptr || fd < 0 || offset < 0 || length <= 0) {
        return kBkdErrArgument;
    }
    if (static_cast<uint64_t>(length) > kMaxPackBytes) {
        return kBkdErrTooLarge;
    }
    if (static_cast<uint64_t>(length) < sizeof(BkdHeader)) {
        return kBkdErrTooSmall;
    }

    // Mapped from the page boundary below the offset.
    const long pageSize = sysconf(_SC_PAGESIZE);
    if (pageSize <= 0) {
        return kBkdErrMmap;
    }
    const int64_t delta = offset % pageSize;
    const int64_t mapOffset = offset - delta;
    const size_t mapBytes = static_cast<size_t>(length + delta);

    void* const mapping = mmap(nullptr, mapBytes, PROT_READ, MAP_PRIVATE, fd,
                               static_cast<off_t>(mapOffset));
    if (mapping == MAP_FAILED) {
        return kBkdErrMmap;
    }

    mapping_ = mapping;
    mappingBytes_ = mapBytes;
    base_ = static_cast<const uint8_t*>(mapping) + delta;
    baseBytes_ = static_cast<uint64_t>(length);

    BkdHeader header;
    std::memcpy(&header, base_, sizeof(header));

    const int32_t status = bkdValidateHeader(header, baseBytes_);
    if (status != kBkdOk) {
        close();
        return status;
    }

    // The content checksum, the one linear pass over the file.
    if ((header.flags & kBkdFlagContentCrc) != 0u) {
        const uint64_t contentBytes = baseBytes_ - header.headerBytes;
        const uint32_t actual = crc32(base_ + header.headerBytes,
                                      static_cast<size_t>(contentBytes));
        if (actual != header.contentCrc32) {
            close();
            return kBkdErrContentCrc;
        }
    }

    if (!trie_.bind(base_, baseBytes_, header) || !ngrams_.bind(base_, baseBytes_, header)) {
        close();
        return kBkdErrSectionBounds;
    }

    // Grammar, when the pack was built with a treebank; posTagCount is zero otherwise.
    posTagCount_ = header.posTagCount;
    if (posTagCount_ != 0) {
        wordTags_ = base_ + header.sections[kSectionWordTags].offset;
        posTransitions_ = base_ + header.sections[kSectionPosTransitions].offset;
    } else {
        wordTags_ = nullptr;
        posTransitions_ = nullptr;
    }

    std::memset(tag_, 0, sizeof(tag_));
    std::strncpy(tag_, tag, sizeof(tag_) - 1);

    trie_.hideKnownOnly();
    buildFrequentList();
    letters_.build(trie_);
    return kBkdOk;
}

void LanguagePack::close() {
    if (mapping_ != nullptr) {
        munmap(mapping_, mappingBytes_);
    }
    mapping_ = nullptr;
    mappingBytes_ = 0;
    base_ = nullptr;
    baseBytes_ = 0;
    wordTags_ = nullptr;
    posTransitions_ = nullptr;
    posTagCount_ = 0;
    frequentCount_ = 0;
    letters_.clear();
    active = false;
    tag_[0] = '\0';
}

void LanguagePack::buildFrequentList() {
    // Keeps the kFrequentCount smallest quantised values, the most probable words.
    frequentCount_ = 0;
    uint8_t worst = 0xFFu;
    const uint32_t words = trie_.wordCount();
    for (uint32_t i = 0; i < words; ++i) {
        const uint8_t quantised = trie_.wordFreqQuantised(i);
        if (trie_.isKnownOnly(i) || (frequentCount_ == kFrequentCount && quantised >= worst)) {
            continue;
        }
        int position = frequentCount_;
        if (frequentCount_ < kFrequentCount) {
            ++frequentCount_;
        } else {
            position = kFrequentCount - 1;
        }
        while (position > 0 &&
               trie_.wordFreqQuantised(static_cast<uint32_t>(frequent_[position - 1])) >
                   quantised) {
            frequent_[position] = frequent_[position - 1];
            --position;
        }
        frequent_[position] = static_cast<int32_t>(i);
        worst = trie_.wordFreqQuantised(static_cast<uint32_t>(frequent_[frequentCount_ - 1]));
    }
}

// --------------------------------------------------------------------------------------
// Engine
// --------------------------------------------------------------------------------------

bool Engine::create() {
    if (created_) {
        return true;
    }
    if (!arena_.init(kArenaBytes)) {
        return false;
    }
    geometry_.clear();
    userModel_.clear();

    // Tier A, the geometric decoder, is always built; tier B is built by loadSwipeWeights().
    gestureDecoder_.reset(new (std::nothrow) Shark2Decoder(*this));
    if (!gestureDecoder_) {
        arena_.release();
        return false;
    }

    created_ = true;
    return true;
}

void Engine::destroy() {
    gestureDecoder_.reset();
#ifdef BORDERKEYS_NEURAL_SWIPE
    for (NeuralSlot& slot : neuralSlots_) {
        slot.decoder.reset();
        slot.script = kNoSwipeScript;
    }
    neuralEnabled_ = false;
#endif
    for (LanguagePack& pack : packs_) {
        pack.close();
    }
    userModel_.clear();
    blocked_.clear();
    arena_.release();
    created_ = false;
}

bool Engine::loadSwipeWeights(int script, const uint8_t* data, size_t length) {
#ifdef BORDERKEYS_NEURAL_SWIPE
    if (!created_ || script < 0) {
        return false;
    }
    // The slot holding [script], else a free one, else the one selected least recently.
    NeuralSlot* target = nullptr;
    for (NeuralSlot& slot : neuralSlots_) {
        if (slot.script == script) {
            target = &slot;
        }
    }
    for (NeuralSlot& slot : neuralSlots_) {
        if (target == nullptr && !slot.decoder) {
            target = &slot;
        }
    }
    if (target == nullptr) {
        target = &neuralSlots_[0];
        for (NeuralSlot& slot : neuralSlots_) {
            if (slot.selectedAt < target->selectedAt) {
                target = &slot;
            }
        }
    }
    // Builds tier B; on failure tier A carries on.
    if (!target->decoder || target->script != script) {
        target->decoder.reset(new (std::nothrow) TcnDecoder(*this));
        target->script = script;
        if (!target->decoder) {
            target->script = kNoSwipeScript;
            return false;
        }
    }
    if (!target->decoder->loadWeights(data, length)) {
        // A failed load frees the decoder.
        target->decoder.reset();
        target->script = kNoSwipeScript;
        return false;
    }
    if (script == selectedScript_) {
        // Loaded for the script in use: that counts as its selection.
        target->selectedAt = ++selectionClock_;
        if (geometry_.isSet()) {
            target->decoder->setLayout(geometry_);
        }
    }
    return true;
#else
    (void)script;
    (void)data;
    (void)length;
    return false;
#endif
}

void Engine::selectSwipeScript(int script) {
#ifdef BORDERKEYS_NEURAL_SWIPE
    selectedScript_ = script;
    TcnDecoder* const decoder = activeNeural();
    if (decoder != nullptr) {
        for (NeuralSlot& slot : neuralSlots_) {
            if (slot.decoder.get() == decoder) {
                slot.selectedAt = ++selectionClock_;
            }
        }
        if (geometry_.isSet()) {
            decoder->setLayout(geometry_);
        }
    }
#else
    (void)script;
#endif
}

bool Engine::hasSwipeModel(int script) const {
#ifdef BORDERKEYS_NEURAL_SWIPE
    for (const NeuralSlot& slot : neuralSlots_) {
        if (slot.script == script && slot.decoder && slot.decoder->hasWeights()) {
            return true;
        }
    }
#else
    (void)script;
#endif
    return false;
}

#ifdef BORDERKEYS_NEURAL_SWIPE
TcnDecoder* Engine::activeNeural() const {
    if (selectedScript_ < 0) {
        return nullptr;
    }
    for (const NeuralSlot& slot : neuralSlots_) {
        if (slot.script == selectedScript_ && slot.decoder && slot.decoder->hasWeights()) {
            return slot.decoder.get();
        }
    }
    return nullptr;
}
#endif

void Engine::setSwipeModelEnabled(bool enabled) {
#ifdef BORDERKEYS_NEURAL_SWIPE
    neuralEnabled_ = enabled;
    if (!enabled) {
        // The decoders and their weights are freed.
        for (NeuralSlot& slot : neuralSlots_) {
            slot.decoder.reset();
            slot.script = kNoSwipeScript;
        }
    }
#else
    (void)enabled;
#endif
}

bool Engine::warmSwipeModel() {
#ifdef BORDERKEYS_NEURAL_SWIPE
    TcnDecoder* const neural = activeNeural();
    if (!created_ || neural == nullptr || !geometry_.isSet() || geometry_.keyCount() < 2) {
        return false;
    }
    // A straight stroke between the layout's first and last keys, decoded once.
    float fromX = 0.0f;
    float fromY = 0.0f;
    float toX = 0.0f;
    float toY = 0.0f;
    if (!geometry_.centreOf(geometry_.codeAt(0), &fromX, &fromY) ||
        !geometry_.centreOf(geometry_.codeAt(geometry_.keyCount() - 1), &toX, &toY)) {
        return false;
    }

    constexpr int kWarmPoints = 32;
    float xs[kWarmPoints];
    float ys[kWarmPoints];
    int64_t ts[kWarmPoints];
    for (int i = 0; i < kWarmPoints; ++i) {
        const float t = static_cast<float>(i) / static_cast<float>(kWarmPoints - 1);
        xs[i] = fromX + (toX - fromX) * t;
        ys[i] = fromY + (toY - fromY) * t;
        // Ten milliseconds a sample.
        ts[i] = static_cast<int64_t>(i) * 10;
    }

    // Straight to the decoder, bypassing the candidate heap and the context.
    Candidate discarded[kMaxCandidates];
    (void)neural->decode(xs, ys, ts, kWarmPoints, discarded, kMaxCandidates);
    return true;
#else
    return false;
#endif
}

int Engine::packIndexForTag(const char* tag) const {
    if (tag == nullptr) {
        return kNoPack;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && std::strcmp(packs_[i].tag(), tag) == 0) {
            return i;
        }
    }
    return kNoPack;
}

int32_t Engine::loadLanguage(const char* tag, int fd, int64_t offset, int64_t length,
                             float weight) {
    if (!created_) {
        return kBkdErrArgument;
    }
    // Loading an open tag replaces it.
    int slot = packIndexForTag(tag);
    if (slot < 0) {
        for (int i = 0; i < kMaxPacks; ++i) {
            if (!packs_[i].isOpen()) {
                slot = i;
                break;
            }
        }
    }
    if (slot < 0) {
        return kBkdErrNoSlot;
    }

    const int32_t status = packs_[slot].open(tag, fd, offset, length);
    if (status != kBkdOk) {
        return status;
    }
    packs_[slot].configuredWeight = (weight > 0.0f) ? weight : 1.0f;
    packs_[slot].active = true;
    return kBkdOk;
}

void Engine::setActiveLanguages(const char* const* tags, const float* weights, int count) {
    // An open pack no longer named is closed, its slot's context and evidence cleared.
    for (int i = 0; i < kMaxPacks; ++i) {
        LanguagePack& pack = packs_[i];
        pack.active = false;
        if (!pack.isOpen()) {
            continue;
        }
        bool named = false;
        for (int j = 0; tags != nullptr && j < count; ++j) {
            if (tags[j] != nullptr && std::strcmp(pack.tag(), tags[j]) == 0) {
                named = true;
                break;
            }
        }
        if (!named) {
            pack.close();
            languageEvidence_[i] = 0.0f;
            contextWord1_[i] = -1;
            contextWord2_[i] = -1;
            contextTag1_[i] = LanguagePack::kNoPosTag;
            if (dominantPack_ == i) {
                dominantPack_ = kNoPack;
            }
            if (answeringPack_ == i) {
                answeringPack_ = kNoPack;
            }
        }
    }
    for (int i = 0; tags != nullptr && i < count; ++i) {
        const int slot = packIndexForTag(tags[i]);
        if (slot < 0) {
            continue;
        }
        packs_[slot].active = true;
        const float weight = (weights != nullptr && weights[i] > 0.0f) ? weights[i] : 1.0f;
        packs_[slot].configuredWeight = weight;
    }
    // The preferred tag is resolved again against the slots.
    resolvePreferredPack();
}

bool Engine::setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                            int count, float keyWidth, float keyHeight,
                            const int32_t* aliasCodes, const int32_t* aliasBases, int aliasCount) {
    if (!geometry_.set(codes, centersX, centersY, count, keyWidth, keyHeight)) {
        return false;
    }
    geometry_.setAliases(aliasCodes, aliasBases, aliasCount);
    // The decoders rebuild their gesture templates for the new key centres.
    if (gestureDecoder_) {
        gestureDecoder_->setLayout(geometry_);
    }
#ifdef BORDERKEYS_NEURAL_SWIPE
    if (TcnDecoder* const neural = activeNeural()) {
        neural->setLayout(geometry_);
    }
#endif
    return true;
}

void Engine::setTouchModel(bool learned, float weight, int minTaps) {
    touchModel_.configure(learned, weight, minTaps);
}

void Engine::setTouchPatterns(const int32_t* codes, const float* taps, const float* meanX,
                              const float* meanY, const float* varianceX, const float* varianceY,
                              const float* covariance, int count) {
    touchModel_.set(codes, taps, meanX, meanY, varianceX, varianceY, covariance, count);
}

const PackedTrie* Engine::activeTrie(int packIndex) const {
    if (packIndex < 0 || packIndex >= kMaxPacks) {
        return nullptr;
    }
    const LanguagePack& pack = packs_[packIndex];
    return (pack.isOpen() && pack.active) ? &pack.trie() : nullptr;
}

void Engine::setLanguageLock(float minimumEvidence, bool strict) {
    languageLockMinimum_ = minimumEvidence;
    strictLanguage_ = strict;
    // Decided again under the new minimum; turning the lock off releases a locked language.
    decideDominantPack();
}

void Engine::resolvePreferredPack() {
    preferredPack_ = kNoPack;
    if (preferredTag_[0] == '\0') {
        return;
    }
    // The preferred pack has to be open and active.
    const int index = packIndexForTag(preferredTag_);
    if (index >= 0 && packs_[index].active) {
        preferredPack_ = index;
    }
}

void Engine::setPreferredLanguage(const char* tag) {
    if (tag == nullptr || tag[0] == '\0') {
        preferredTag_[0] = '\0';
    } else {
        std::strncpy(preferredTag_, tag, sizeof(preferredTag_) - 1);
        preferredTag_[sizeof(preferredTag_) - 1] = '\0';
    }
    resolvePreferredPack();
}

void Engine::resetLanguageEvidence() {
    for (int i = 0; i < kMaxPacks; ++i) {
        languageEvidence_[i] = 0.0f;
    }
    dominantPack_ = kNoPack;
    // The de-duplication guard is reset too.
    lastObservedWord_ = 0;
}

float Engine::languageEvidence(const char* tag) const {
    const int index = (tag == nullptr) ? -1 : packIndexForTag(tag);
    return (index < 0) ? 0.0f : languageEvidence_[index];
}

void Engine::setLanguageEvidence(const char* tag, float evidence) {
    const int index = (tag == nullptr) ? -1 : packIndexForTag(tag);
    if (index < 0) {
        return;
    }
    languageEvidence_[index] = (evidence > 0.0f) ? evidence : 0.0f;
    decideDominantPack();
}

Engine::Fit Engine::fitOf(int depth, const char* text, uint32_t length) const {
    if (depth > 0) {
        return Fit::RunsOn;
    }
    if (!typedCarriesMark_ || text == nullptr || length == 0) {
        return Fit::Spells;
    }
    return readsAsTyped(typedText_, typedTextLength_, text, length, false) ? Fit::Spells
                                                                            : Fit::DropsTypedMark;
}

bool Engine::readsAsTyped(const char* typedText, uint32_t typedLength, const char* text,
                          uint32_t length, bool matchCase) {
    if (typedText == nullptr || text == nullptr) {
        return true;
    }
    const char* typed = typedText;
    const char* const typedEnd = typedText + typedLength;
    const char* word = text;
    const char* const wordEnd = text + length;
    while (typed != nullptr && typed < typedEnd) {
        uint32_t typedCode = 0;
        typed = utf8Decode(typed, typedEnd, &typedCode);
        if (typed == nullptr) {
            return true;
        }
        const uint32_t typedFold = foldCodePoint(typedCode);
        if (typedFold == kDroppedCodePoint) {
            continue;
        }
        uint32_t wordCode = 0;
        uint32_t wordFold = kDroppedCodePoint;
        while (wordFold == kDroppedCodePoint && word != nullptr && word < wordEnd) {
            word = utf8Decode(word, wordEnd, &wordCode);
            wordFold = (word == nullptr) ? kDroppedCodePoint : foldCodePoint(wordCode);
        }
        if (wordFold == kDroppedCodePoint) {
            return true;
        }
        // A typed letter the fold changes beyond its case carries a mark.
        const uint32_t typedLower = lowerCodePoint(typedCode);
        if (typedFold != typedLower && lowerCodePoint(wordCode) != typedLower) {
            return false;
        }
        if (matchCase && (typedCode != typedLower) != (wordCode != lowerCodePoint(wordCode))) {
            return false;
        }
    }
    return true;
}

void Engine::notePrimaryReach(const LanguagePack& pack, uint32_t wordIndex, float cost, Fit fit) {
    if (pack.trie().isProperNoun(wordIndex)) {
        if (cost > 0.0f || fit == Fit::RunsOn) {
            if (!plausibleCorrectionTarget(pack, wordIndex)) {
                return;
            }
        } else {
            uint32_t length = 0;
            const char* const text = pack.trie().wordText(wordIndex, &length);
            if (text == nullptr ||
                !readsAsTyped(typedText_, typedTextLength_, text, length, true)) {
                return;
            }
        }
    }
    if (!primaryReached_ || cost < primaryClosest_.cost ||
        (cost == primaryClosest_.cost && fit < primaryClosest_.fit)) {
        primaryClosest_ = Closeness{cost, fit};
        primaryReached_ = true;
    }
}

bool Engine::closerThanPrimary(float cost, Fit fit) const {
    // Where Primary reached no reading, every word is closer.
    if (!primaryReached_) {
        return true;
    }
    if (cost + kLanguageMargin <= primaryClosest_.cost) {
        return true;
    }
    // At the same cost, the closer fit.
    return cost == primaryClosest_.cost && fit < primaryClosest_.fit;
}

int Engine::heaviestPack() const {
    int best = -1;
    float weight = -1.0f;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && packs_[i].active && packs_[i].configuredWeight > weight) {
            weight = packs_[i].configuredWeight;
            best = i;
        }
    }
    return best;
}

void Engine::observeContextLanguage(const uint32_t* folded, int length) {
    if (length <= 0) {
        return;
    }
    // Each completed word counts once, though it arrives with every keystroke after it.
    uint32_t hash = 2166136261u;
    for (int i = 0; i < length; ++i) {
        hash = (hash ^ folded[i]) * 16777619u;
    }
    if (hash == lastObservedWord_) {
        return;
    }
    lastObservedWord_ = hash;

    // The evidence is how much better one pack knows the word than the next; a word only one pack
    // knows earns a full point, and every word ages the window.
    int owner = -1;
    int knowers = 0;
    float ownerLogProb = 0.0f;
    float rivalLogProb = 0.0f;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active || contextWord1_[i] < 0) {
            continue;
        }
        const float logProb =
            packs_[i].trie().unigramLogProb(static_cast<uint32_t>(contextWord1_[i]));
        if (knowers == 0) {
            owner = i;
            ownerLogProb = logProb;
        } else if (logProb > ownerLogProb) {
            rivalLogProb = ownerLogProb;
            owner = i;
            ownerLogProb = logProb;
        } else if (knowers == 1 || logProb > rivalLogProb) {
            rivalLogProb = logProb;
        }
        ++knowers;
    }

    float award = 0.0f;
    if (knowers == 1) {
        award = 1.0f;
    } else if (knowers > 1) {
        award = (ownerLogProb - rivalLogProb) / kLanguageEvidenceFullGap;
        if (award > 1.0f) {
            award = 1.0f;
        } else if (award < 0.0f) {
            award = 0.0f;
        }
    }

    for (int i = 0; i < kMaxPacks; ++i) {
        languageEvidence_[i] *= kLanguageEvidenceDecay;
        if (i == owner) {
            languageEvidence_[i] += award;
        }
    }
    decideDominantPack();
}

void Engine::decideDominantPack() {
    float total = 0.0f;
    float best = 0.0f;
    int bestIndex = -1;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && packs_[i].active) {
            total += languageEvidence_[i];
            if (languageEvidence_[i] > best) {
                best = languageEvidence_[i];
                bestIndex = i;
            }
        }
    }
    // A minimum at or below zero never locks.
    dominantPack_ = (languageLockMinimum_ > 0.0f && total >= languageLockMinimum_ &&
                     best >= total * kLanguageDominanceShare)
                        ? bestIndex
                        : -1;
}

float Engine::packWeightLog(int packIndex) const {
    if (packIndex < 0 || packIndex >= kMaxPacks || !(normalisedWeight_[packIndex] > 0.f)) {
        return -30.f;
    }
    return std::log(normalisedWeight_[packIndex]);
}

float Engine::personalScore(const char* text, uint32_t length) const {
    return personal_.scoreFor(text, length);
}

int32_t Engine::offeredSpelling(int packIndex, uint32_t firstIndex) const {
    if (packIndex < 0 || packIndex >= kMaxPacks || !packs_[packIndex].isOpen()) {
        return -1;
    }
    return firstUnblockedSpelling(packs_[packIndex].trie(), static_cast<int32_t>(firstIndex));
}

void Engine::setBlockedWords(const char* const* words, const size_t* lengths, int count) {
    blocked_.clear();
    if (words == nullptr || lengths == nullptr || count <= 0) {
        return;
    }
    blocked_.reserve(static_cast<size_t>(count));
    char lowered[kMaxBlockedBytes];
    for (int i = 0; i < count; ++i) {
        if (words[i] == nullptr || lengths[i] == 0) {
            continue;
        }
        const int length = lowerUtf8(words[i], lengths[i], lowered, kMaxBlockedBytes);
        if (length > 0) {
            blocked_.emplace_back(lowered, static_cast<size_t>(length));
        }
    }
    std::sort(blocked_.begin(), blocked_.end());
    blocked_.erase(std::unique(blocked_.begin(), blocked_.end()), blocked_.end());
}

bool Engine::isBlocked(const char* text, uint32_t length) const {
    if (blocked_.empty() || text == nullptr || length == 0) {
        return false;
    }
    char lowered[kMaxBlockedBytes];
    const int loweredLength = lowerUtf8(text, length, lowered, kMaxBlockedBytes);
    if (loweredLength <= 0) {
        return false;
    }
    const std::string_view key(lowered, static_cast<size_t>(loweredLength));
    const auto found = std::lower_bound(blocked_.begin(), blocked_.end(), key, blockedBefore);
    return found != blocked_.end() && std::string_view(*found) == key;
}

int32_t Engine::firstUnblockedSpelling(const PackedTrie& trie, int32_t firstIndex) const {
    if (firstIndex < 0 || blocked_.empty()) {
        return firstIndex;
    }
    const uint32_t spellings = trie.spellingsFrom(static_cast<uint32_t>(firstIndex));
    for (uint32_t offset = 0; offset < spellings; ++offset) {
        const uint32_t wordIndex = static_cast<uint32_t>(firstIndex) + offset;
        uint32_t length = 0;
        const char* const text = trie.wordText(wordIndex, &length);
        if (text != nullptr && length != 0 && !isBlocked(text, length)) {
            return static_cast<int32_t>(wordIndex);
        }
    }
    return -1;
}

void Engine::refreshWeights() {
    // The active packs' weights, normalised to sum to one.
    float weightSum = 0.0f;
    for (int i = 0; i < kMaxPacks; ++i) {
        normalisedWeight_[i] = 0.0f;
        if (packs_[i].isOpen() && packs_[i].active) {
            normalisedWeight_[i] = packs_[i].configuredWeight;
            weightSum += packs_[i].configuredWeight;
        }
    }
    if (weightSum <= 0.0f) {
        weightSum = 1.0f;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        if (normalisedWeight_[i] > 0.0f) {
            normalisedWeight_[i] /= weightSum;
        }
    }
}

int Engine::decodeGesture(const float* xs, const float* ys, const int64_t* ts, int count,
                          const char* previous1, size_t previous1Length, const char* previous2,
                          size_t previous2Length, Candidate* out, int maxOut) {
    if (!created_ || !gestureDecoder_ || out == nullptr || maxOut <= 0) {
        return 0;
    }
    refreshWeights();
    resolveContext(previous1, previous1Length, previous2, previous2Length);

    GestureDecoder* decoder = gestureDecoder_.get();
#ifdef BORDERKEYS_NEURAL_SWIPE
    // Tier B decodes the whole request when enabled and loaded for the layout's script, tier A otherwise.
    if (neuralEnabled_) {
        if (TcnDecoder* const neural = activeNeural()) {
            decoder = neural;
        }
    }
#endif
    lastDecodeUsedNeural_ = decoder != gestureDecoder_.get();

    Candidate raw[kMaxCandidates];
    int produced = decoder->decode(xs, ys, ts, count, raw, kMaxCandidates);
    if (produced <= 0 && lastDecodeUsedNeural_) {
        // A gesture tier B finds nothing for is sent to tier A once.
        lastDecodeUsedNeural_ = false;
        produced = gestureDecoder_->decode(xs, ys, ts, count, raw, kMaxCandidates);
    }
    if (produced <= 0) {
        return 0;
    }

    // Re-offered through the engine's heap, which merges one spelling from two packs.
    TopK<Candidate> heap;
    heap.reset(heapStorage_, kMaxCandidates);
    for (int i = 0; i < produced; ++i) {
        uint32_t textLength = 0;
        const char* const text = candidateText(raw[i], &textLength);
        if (text != nullptr && textLength != 0) {
            offerCandidate(heap, raw[i], text, textLength);
        }
    }
    const int drained = heap.drainSorted(drainBuffer_, kMaxCandidates);
    const int written = (drained < maxOut) ? drained : maxOut;
    for (int i = 0; i < written; ++i) {
        out[i] = drainBuffer_[i];
    }
    normaliseGestureScores(out, written);
    return written;
}

void Engine::normaliseGestureScores(Candidate* candidates, int count) {
    if (count <= 0) {
        return;
    }
    float maxScore = candidates[0].score;
    for (int i = 1; i < count; ++i) {
        maxScore = std::fmax(maxScore, candidates[i].score);
    }
    float expScores[kMaxCandidates];
    float sumExp = 0.f;
    for (int i = 0; i < count; ++i) {
        expScores[i] = std::exp(candidates[i].score - maxScore);
        sumExp += expScores[i];
    }
    // The best candidate's own term is exp(0) = 1, so sumExp is always at least 1 here.
    for (int i = 0; i < count; ++i) {
        candidates[i].score = (expScores[i] / sumExp) * 1000.f;
    }
}

const char* Engine::gestureDecoderName() const {
#ifdef BORDERKEYS_NEURAL_SWIPE
    if (neuralEnabled_) {
        if (const TcnDecoder* const neural = activeNeural()) {
            return neural->name();
        }
    }
#endif
    return gestureDecoder_ ? gestureDecoder_->name() : "none";
}

void Engine::resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                            size_t previous2Length) {
    hasContext1_ = false;
    hasContext2_ = false;
    uint32_t folded[kMaxComposing];

    int length1 = -1;
    int32_t userContext1 = -1;
    int32_t userContext2 = -1;
    if (previous1 != nullptr && previous1Length > 0) {
        length1 = foldUtf8(previous1, previous1Length, folded, kMaxComposing);
        if (personal_.enabled()) {
            userContext1 = userModel_.entryIndexFor(previous1, previous1Length);
        }
    } else if (personal_.enabled()) {
        // A sentence began here: the words this person opens sentences with are its successors.
        userContext1 = userModel_.entryIndexFor(kUserSentenceStart, kUserSentenceStartLength);
    }
    // Left at -1 while the personal model is off, which turns every personal-context path off.
    if (personal_.enabled() && previous2 != nullptr && previous2Length > 0) {
        userContext2 = userModel_.entryIndexFor(previous2, previous2Length);
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        contextWord1_[i] = -1;
        contextTag1_[i] = LanguagePack::kNoPosTag;
        if (length1 > 0 && packs_[i].isOpen()) {
            contextWord1_[i] = packs_[i].trie().lookupFolded(folded, length1);
        }
        if (contextWord1_[i] >= 0) {
            hasContext1_ = true;
            // Resolved once per request.
            contextTag1_[i] = packs_[i].posTag(contextWord1_[i]);
        }
    }

    // The word just written is the language signal.
    observeContextLanguage(folded, length1);

    int length2 = -1;
    if (previous2 != nullptr && previous2Length > 0) {
        length2 = foldUtf8(previous2, previous2Length, folded, kMaxComposing);
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        contextWord2_[i] = -1;
        if (length2 > 0 && packs_[i].isOpen()) {
            contextWord2_[i] = packs_[i].trie().lookupFolded(folded, length2);
        }
        if (contextWord2_[i] >= 0) {
            hasContext2_ = true;
        }
    }

    resolveLanguage();
    const int32_t store =
        personal_.enabled()
            ? userModel_.findStore(personalLanguage_.data(), personalLanguage_.size())
            : -1;
    personal_.resolve(store, userContext1, userContext2);
}

void Engine::resolveLanguage() {
    personalLanguage_ = currentPersonalLanguage();
}

float Engine::contextLogProb(int packIndex, uint32_t wordIndex) const {
    const LanguagePack& pack = packs_[packIndex];
    const int32_t w1 = contextWord1_[packIndex];
    const int32_t w2 = contextWord2_[packIndex];

    // Nothing before the cursor: the pack's sentence-start pairs.
    if (w1 < 0 && !hasContext1_) {
        const float value = pack.ngrams().bigram(kSentenceStartIndex, wordIndex);
        if (value <= 0.0f) {
            return value;
        }
    }

    if (w1 >= 0 && w2 >= 0) {
        const float value = pack.ngrams().trigram(static_cast<uint32_t>(w2),
                                                  static_cast<uint32_t>(w1), wordIndex);
        if (value <= 0.0f) {
            return value;
        }
    }
    if (w1 >= 0) {
        const float value = pack.ngrams().bigram(static_cast<uint32_t>(w1), wordIndex);
        if (value <= 0.0f) {
            // Backed off one level only when a trigram context was skipped.
            return value + ((w2 >= 0) ? kBackoffLogFactor : 0.0f);
        }
    }
    const float unigram = pack.trie().unigramLogProb(wordIndex);
    int dropped = 0;
    if (w1 >= 0) {
        ++dropped;
    }
    if (w1 >= 0 && w2 >= 0) {
        ++dropped;
    }
    float score = unigram + kBackoffLogFactor * static_cast<float>(dropped);

    // Grammar applies only here, where no bigram exists.
    if (dropped > 0 && pack.hasGrammar()) {
        const uint32_t previousTag = contextTag1_[packIndex];
        if (previousTag != LanguagePack::kNoPosTag) {
            const uint32_t tag = pack.posTag(static_cast<int32_t>(wordIndex));
            if (tag != LanguagePack::kNoPosTag) {
                const float transition =
                    -static_cast<float>(pack.posTransition(previousTag, tag)) / kLogProbScale;
                score += kGrammarWeight * transition;
            }
        }
    }
    return score;
}

void Engine::loadUserTrigrams(const char* const* languages, const size_t* languageLengths,
                              const char* const* previous2, const size_t* previous2Lengths,
                              const char* const* previous1, const size_t* previous1Lengths,
                              const char* const* next, const size_t* nextLengths,
                              const int32_t* counts, int count, const uint64_t* contextSeen,
                              const uint64_t* languageSeen) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoadTrigrams(languages, languageLengths, previous2, previous2Lengths, previous1,
                                previous1Lengths, next, nextLengths, counts, count, contextSeen,
                                languageSeen);
}

void Engine::setLearnAfterUses(int uses) {
    const uint32_t clamped = std::min(static_cast<uint32_t>(uses), kMaxLearnAfterUses);
    personal_.setLearnAfterUses(uses < 1 ? kDefaultLearnAfterUses : clamped);
}

void Engine::setCorrectionStrictness(float scale) {
    if (!(scale > 0.0f)) {
        correctionStrictness_ = kDefaultCorrectionStrictness;
        return;
    }
    correctionStrictness_ = std::clamp(scale, kMinCorrectionStrictness, kMaxCorrectionStrictness);
}

void Engine::offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                            uint32_t textLength) const {
    if (isBlocked(text, textLength)) {
        return;
    }
    // One spelling, compared case aside, is one entry however it was reached; the better score
    // stays.
    Candidate* const items = heap.data();
    for (int i = 0; i < heap.size(); ++i) {
        uint32_t existingLength = 0;
        const char* const existing = candidateText(items[i], &existingLength);
        if (existing == nullptr ||
            !sameSpellingIgnoringCase(existing, existingLength, text, textLength)) {
            continue;
        }
        if (candidate.score > items[i].score) {
            heap.replaceAt(i, candidate);
        }
        return;
    }
    heap.offer(candidate);
}

void Engine::offerScoredWord(TopK<Candidate>& heap, const PackedTrie& trie, int packIndex,
                             uint32_t wordIndex, float score, float editCost, int edits,
                             int runOn, int slips) const {
    // Skipped when even the largest boost cannot reach the heap's floor.
    if (score + personal_.ceiling() <= heap.worstScore()) {
        return;
    }
    uint32_t textLength = 0;
    const char* const text = trie.wordText(wordIndex, &textLength);
    if (text != nullptr && textLength != 0) {
        score += personal_.scoreFor(text, textLength);
        Candidate candidate{packIndex, static_cast<int32_t>(wordIndex), score};
        candidate.editCost = editCost;
        candidate.edits = static_cast<uint8_t>(edits);
        candidate.runOn = static_cast<uint8_t>(runOn);
        candidate.slips = static_cast<uint8_t>(slips);
        candidate.unigram = trie.unigramLogProb(wordIndex);
        offerCandidate(heap, candidate, text, textLength);
    }
}

int Engine::collectEndpoints(const LanguagePack& pack, const uint32_t* folded, int foldedLength,
                             float maxCost, Endpoint* out, int maxOut) {
    struct Frame {
        int32_t node;
        int16_t inputPos;
        int16_t runAhead;
        float cost;
        int8_t edits;
        // Of `edits`, the substitutions: a neighbouring key in place of the typed one.
        int8_t slips;
    };

    Frame* const stack = arena_.allocateArray<Frame>(512);
    if (stack == nullptr) {
        return 0;
    }
    int stackSize = 0;
    int written = 0;

    stack[stackSize++] = Frame{pack.trie().root(), 0, 0, 0.0f, 0, 0};

    const PackedTrie& trie = pack.trie();
    const bool fuzzy = maxCost > 0.0f && geometry_.isSet();

    while (stackSize > 0) {
        if (visitBudget_ <= 0) {
            break;
        }
        const Frame frame = stack[--stackSize];
        --visitBudget_;

        if (frame.inputPos >= foldedLength) {
            const Endpoint reached{frame.node, frame.cost, static_cast<uint8_t>(frame.edits),
                                   static_cast<uint8_t>(frame.slips)};
            if (written < maxOut) {
                out[written++] = reached;
            } else {
                // Full: keep the cheapest set seen rather than the first set seen.
                int worst = 0;
                for (int i = 1; i < written; ++i) {
                    if (out[i].cost > out[worst].cost) {
                        worst = i;
                    }
                }
                if (frame.cost < out[worst].cost) {
                    out[worst] = reached;
                }
            }
            continue;
        }

        const uint32_t typed = folded[frame.inputPos];

        // The exact match first, with or without geometry.
        const int exactSymbol = trie.symbolFor(typed);
        if (exactSymbol > 0) {
            const int32_t child = trie.walk(frame.node, exactSymbol);
            if (child >= 0 && stackSize < 512) {
                stack[stackSize++] = Frame{child, static_cast<int16_t>(frame.inputPos + 1), 0,
                                           frame.cost, frame.edits, frame.slips};
            }
        }

        if (!fuzzy) {
            continue;
        }
        const float maxCostHere = depthCeilingFor(frame.inputPos, maxCost);

        const uint32_t* neighbourCodes = nullptr;
        const float* neighbourCosts = nullptr;
        const int neighbourCount = geometry_.neighbours(typed, &neighbourCodes, &neighbourCosts);

        // Substitution: the finger landed one key over. Slot 0 is the exact match, already
        // pushed above. With the request's taps, the touch model prices each neighbour; the
        // distance is scaled by kSlipScale and floored.
        for (int i = 1; i < neighbourCount; ++i) {
            const float distance =
                queryTapped_
                    ? touchModel_.substitutionCost(geometry_, typed, neighbourCodes[i],
                                                   queryTapX_[frame.inputPos],
                                                   queryTapY_[frame.inputPos], neighbourCosts[i])
                    : neighbourCosts[i];
            const float step =
                std::max(kSlipScale * distance, KeyGeometry::kMinSubstitutionCost);
            const float cost = frame.cost + step;
            if (cost > maxCostHere) {
                continue;
            }
            const int symbol = trie.symbolFor(neighbourCodes[i]);
            if (symbol <= 0) {
                continue;
            }
            const int32_t child = trie.walk(frame.node, symbol);
            if (child >= 0 && stackSize < 512) {
                stack[stackSize++] = Frame{child, static_cast<int16_t>(frame.inputPos + 1), 0,
                                           cost, static_cast<int8_t>(frame.edits + 1),
                                           static_cast<int8_t>(frame.slips + 1)};
            }
        }

        const int8_t edited = static_cast<int8_t>(frame.edits + 1);

        // Deletion: a typed character the word does not have, priced by whether it is a mark and
        // whether it repeats the character typed before it.
        const bool repeat =
            frame.inputPos > 0 && folded[frame.inputPos] == folded[frame.inputPos - 1];
        const float deleteCost = isMark(folded[frame.inputPos]) ? kMarkDeleteCost
                                 : repeat                       ? kRepeatDeleteCost
                                                                : kDeleteCost;
        if (frame.cost + deleteCost <= maxCostHere && stackSize < 512) {
            stack[stackSize++] = Frame{frame.node, static_cast<int16_t>(frame.inputPos + 1), 0,
                                       frame.cost + deleteCost, edited, frame.slips};
        }

        // Insertion: a character of the word was missed. Every alphabet symbol the trie has from
        // here, a mark at kMarkInsertCost, without consuming input, bounded by runAhead.
        if (frame.runAhead < kMaxRunAhead && frame.cost + kMarkInsertCost <= maxCostHere) {
            const int alphabetSize = trie.alphabetSize();
            const int markSymbols[] = {trie.symbolFor(kApostrophe), trie.symbolFor(kHyphen)};
            const auto isMarkSymbol = [&markSymbols](int symbol) {
                return symbol == markSymbols[0] || symbol == markSymbols[1];
            };
            for (int symbol = 1; symbol <= alphabetSize && stackSize < 512; ++symbol) {
                if (isMarkSymbol(symbol) || frame.cost + kInsertCost > maxCostHere) {
                    continue;
                }
                const int32_t child = trie.walk(frame.node, symbol);
                if (child >= 0) {
                    stack[stackSize++] = Frame{child, frame.inputPos,
                                               static_cast<int16_t>(frame.runAhead + 1),
                                               frame.cost + kInsertCost, edited, frame.slips};
                }
            }
            // Marks are pushed last, so they are explored first.
            for (const int symbol : markSymbols) {
                if (symbol <= 0 || stackSize >= 512) {
                    continue;
                }
                const int32_t child = trie.walk(frame.node, symbol);
                if (child >= 0) {
                    stack[stackSize++] = Frame{child, frame.inputPos,
                                               static_cast<int16_t>(frame.runAhead + 1),
                                               frame.cost + kMarkInsertCost, edited, frame.slips};
                }
            }
        }

        // Transposition: two adjacent characters in the wrong order.
        if (frame.inputPos + 1 < foldedLength && frame.cost + kTransposeCost <= maxCostHere) {
            const int firstSymbol = trie.symbolFor(folded[frame.inputPos + 1]);
            const int secondSymbol = trie.symbolFor(typed);
            if (firstSymbol > 0 && secondSymbol > 0) {
                const int32_t middle = trie.walk(frame.node, firstSymbol);
                if (middle >= 0) {
                    const int32_t child = trie.walk(middle, secondSymbol);
                    if (child >= 0 && stackSize < 512) {
                        stack[stackSize++] = Frame{child,
                                                   static_cast<int16_t>(frame.inputPos + 2), 0,
                                                   frame.cost + kTransposeCost, edited,
                                                   frame.slips};
                    }
                }
            }
        }
    }
    return written;
}

void Engine::collectWords(int packIndex, const LanguagePack& pack, const Endpoint& endpoint,
                          TopK<Candidate>& heap) {
    struct Frame {
        int32_t node;
        int16_t depth;
    };

    // How far the walk may carry past this endpoint: kMaxFreeCompletion uncorrected,
    // kMaxCompletionAfterEdit after an edit, and in OtherPacks only while it outmatches Primary.
    int completionLimit =
        (endpoint.cost <= 0.0f) ? kMaxFreeCompletion : kMaxCompletionAfterEdit;
    if (currentPass_ == Pass::OtherPacks) {
        if (!closerThanPrimary(endpoint.cost, Fit::Spells)) {
            return;
        }
        if (!closerThanPrimary(endpoint.cost, Fit::RunsOn)) {
            completionLimit = 0;
        }
    }

    Frame* const stack = arena_.allocateArray<Frame>(1024);
    if (stack == nullptr) {
        return;
    }
    int stackSize = 0;
    stack[stackSize++] = Frame{endpoint.node, 0};

    const PackedTrie& trie = pack.trie();
    const int alphabetSize = trie.alphabetSize();
    const float weight = normalisedWeight_[packIndex];
    const float weightLog = std::log(weight);
    const float editComponent = endpoint.cost > 0.0f
        ? -correctionStrictness_ * (kEditPenalty * endpoint.cost + kCorrectionSurcharge)
        : 0.0f;

    while (stackSize > 0) {
        if (visitBudget_ <= 0) {
            return;
        }
        const Frame frame = stack[--stackSize];
        --visitBudget_;

        // Every spelling in the terminal's folded-key run is its own candidate.
        const int32_t firstIndex = trie.terminalWordIndex(frame.node);
        if (firstIndex >= 0) {
            const float lengthPenalty = kCompletionPenalty * static_cast<float>(frame.depth);
            const uint32_t spellings = trie.spellingsFrom(static_cast<uint32_t>(firstIndex));

            for (uint32_t offset = 0; offset < spellings; ++offset) {
                const uint32_t wordIndex = static_cast<uint32_t>(firstIndex) + offset;

                // text tells Exact from Respelling, gives the fit, and marks a blocked spelling.
                uint32_t textLength = 0;
                const char* text = nullptr;
                if ((endpoint.cost <= 0.0f && frame.depth == 0) ||
                    (currentPass_ == Pass::Primary && !blocked_.empty())) {
                    text = trie.wordText(wordIndex, &textLength);
                }
                const Fit fit = (frame.depth > 0)        ? Fit::RunsOn
                                : (endpoint.cost > 0.0f) ? Fit::Spells
                                                         : fitOf(0, text, textLength);
                if (currentPass_ == Pass::Primary) {
                    if (!isBlocked(text, textLength)) {
                        notePrimaryReach(pack, wordIndex, endpoint.cost, fit);
                    }
                } else if (currentPass_ == Pass::OtherPacks &&
                           !closerThanPrimary(endpoint.cost, fit)) {
                    continue;
                }

                const float languageModel = weightLog + contextLogProb(packIndex, wordIndex);
                const float score = languageModel + editComponent - lengthPenalty;
                offerScoredWord(heap, trie, packIndex, wordIndex, score, endpoint.cost,
                                endpoint.edits, frame.depth, endpoint.slips);

                // Everything reaches the strip; only a committing pass reaches the respelling
                // tier and the correction heap.
                if (endpoint.cost > 0.0f || frame.depth > 0) {
                    text = nullptr;
                    textLength = 0;
                }
                const Reading reading = readingOf(endpoint.cost, frame.depth, text, textLength);

                if (commits(currentPass_) && takesRespellingTier(reading) && text != nullptr &&
                    textLength != 0 && !isBlocked(text, textLength)) {
                    // Boosted as offerScoredWord boosts, without a frequency floor.
                    const float boosted = score + personal_.scoreFor(text, textLength);
                    if (!hasBestRespelling_ || boosted > bestRespelling_.score) {
                        bestRespelling_ =
                            Candidate{packIndex, static_cast<int32_t>(wordIndex), boosted};
                        hasBestRespelling_ = true;
                    }
                }
                if (commits(currentPass_) && reachesCorrectionHeap(reading) &&
                    plausibleCorrectionTarget(pack, wordIndex)) {
                    // The correction heap's key: run-on letters at kRunOnCost, the surcharge on
                    // any departure from the typed letters.
                    const float runOnPenalty =
                        correctionStrictness_ * kEditPenalty * kRunOnCost *
                        static_cast<float>(frame.depth);
                    const float surcharge = correctionStrictness_ * kCorrectionSurcharge;
                    const float heapScore = languageModel -
                                            correctionStrictness_ * kEditPenalty * endpoint.cost -
                                            surcharge - runOnPenalty;
                    offerScoredWord(correctionHeap_, trie, packIndex, wordIndex, heapScore,
                                    endpoint.cost, endpoint.edits, frame.depth, endpoint.slips);
                }
            }
        }

        // A corrected endpoint that lands on a word is not continued.
        if (endpoint.cost > 0.0f && firstIndex >= 0) {
            continue;
        }
        if (frame.depth >= completionLimit) {
            continue;
        }
        for (int symbol = 1; symbol <= alphabetSize && stackSize < 1024; ++symbol) {
            const int32_t child = trie.walk(frame.node, symbol);
            if (child >= 0) {
                stack[stackSize++] = Frame{child, static_cast<int16_t>(frame.depth + 1)};
            }
        }
    }
}

void Engine::searchPack(int packIndex, const uint32_t* folded, int foldedLength,
                        TopK<Candidate>& heap) {
    LanguagePack& pack = packs_[packIndex];
    const PackedTrie& trie = pack.trie();

    // Alphabet pruning: a word with characters outside the pack's alphabet skips the pack, one
    // such character allowed when fuzzy matching is on.
    const float maxCost = editCostCeiling_;
    const int allowedStrangers = (maxCost > 0.0f && geometry_.isSet()) ? 1 : 0;
    int strangers = 0;
    for (int i = 0; i < foldedLength; ++i) {
        if (!trie.alphabetContains(folded[i])) {
            ++strangers;
            if (strangers > allowedStrangers) {
                return;
            }
        }
    }

    const size_t mark = arena_.used();
    Endpoint* const endpoints = arena_.allocateArray<Endpoint>(kMaxEndpoints);
    if (endpoints == nullptr) {
        return;
    }

    const int endpointCount =
        collectEndpoints(pack, folded, foldedLength, maxCost, endpoints, kMaxEndpoints);
    for (int i = 0; i < endpointCount; ++i) {
        const size_t innerMark = arena_.used();
        collectWords(packIndex, pack, endpoints[i], heap);
        // The arena is rewound between endpoints.
        arena_.rewind(innerMark);
    }
    arena_.rewind(mark);
}

void Engine::searchNextWord(int packIndex, TopK<Candidate>& heap) {
    const LanguagePack& pack = packs_[packIndex];
    const float weightLog = std::log(normalisedWeight_[packIndex]);
    const int32_t context = contextWord1_[packIndex];

    // Nothing typed: the context word's successors from the pack's index, or the sentence-start
    // ones, then the most frequent words, all scored against the context.
    uint32_t previous = 0;
    bool listed = false;
    if (context >= 0) {
        previous = static_cast<uint32_t>(context);
        listed = true;
    } else if (!hasContext1_) {
        previous = kSentenceStartIndex;
        listed = true;
    }
    if (listed) {
        // The kSuccessorWalk strongest pairs, kept in one pass, are scored in full.
        uint32_t first = 0;
        const uint32_t successors = pack.ngrams().successors(previous, &first);
        uint32_t kept[kSuccessorWalk];
        float keptValue[kSuccessorWalk];
        int keptCount = 0;
        for (uint32_t i = 0; i < successors; ++i) {
            const float value = pack.ngrams().successorLogProb(first + i);
            if (keptCount == kSuccessorWalk && value <= keptValue[keptCount - 1]) {
                continue;
            }
            int position = keptCount;
            if (keptCount < kSuccessorWalk) {
                ++keptCount;
            } else {
                position = kSuccessorWalk - 1;
            }
            while (position > 0 && keptValue[position - 1] < value) {
                kept[position] = kept[position - 1];
                keptValue[position] = keptValue[position - 1];
                --position;
            }
            kept[position] = first + i;
            keptValue[position] = value;
        }
        for (int i = 0; i < keptCount; ++i) {
            const uint32_t wordIndex = pack.ngrams().successorWord(kept[i]);
            if (wordIndex >= pack.trie().wordCount() ||
                static_cast<int32_t>(wordIndex) == context) {
                continue;
            }
            const float score = weightLog + contextLogProb(packIndex, wordIndex);
            offerScoredWord(heap, pack.trie(), packIndex, wordIndex, score, 0.0f, 0, 0);
        }
    }

    const int32_t* const frequent = pack.frequentWords();
    const int count = pack.frequentWordCount();
    for (int i = 0; i < count; ++i) {
        const uint32_t wordIndex = static_cast<uint32_t>(frequent[i]);
        if (static_cast<int32_t>(wordIndex) == context) {
            // Not the word just written.
            continue;
        }
        const float score = weightLog + contextLogProb(packIndex, wordIndex);
        offerScoredWord(heap, pack.trie(), packIndex, wordIndex, score, 0.0f, 0, 0);
    }
}

void Engine::searchFrequentWithPrefix(int packIndex, const uint32_t* folded, int foldedLength,
                                      TopK<Candidate>& heap) {
    const LanguagePack& pack = packs_[packIndex];
    const PackedTrie& trie = pack.trie();
    const float weightLog = std::log(normalisedWeight_[packIndex]);
    const int32_t* const frequent = pack.frequentWords();
    const int count = pack.frequentWordCount();

    for (int i = 0; i < count; ++i) {
        const uint32_t wordIndex = static_cast<uint32_t>(frequent[i]);
        uint32_t textLength = 0;
        const char* const text = trie.wordText(wordIndex, &textLength);
        if (text == nullptr || textLength == 0) {
            continue;
        }

        // Folds and compares one character at a time, stopping at the first mismatch.
        const char* cursor = text;
        const char* const end = text + textLength;
        int matched = 0;
        bool matches = true;
        while (matched < foldedLength) {
            uint32_t codePoint = 0;
            cursor = utf8Decode(cursor, end, &codePoint);
            if (cursor == nullptr) {
                matches = false;
                break;
            }
            const uint32_t foldedCodePoint = foldCodePoint(codePoint);
            if (foldedCodePoint == kDroppedCodePoint) {
                continue;
            }
            if (foldedCodePoint != folded[matched]) {
                matches = false;
                break;
            }
            ++matched;
        }
        if (!matches) {
            continue;
        }

        int extra = 0;
        while (cursor != nullptr && cursor < end) {
            uint32_t codePoint = 0;
            cursor = utf8Decode(cursor, end, &codePoint);
            ++extra;
        }

        const Fit fit = fitOf(extra, text, textLength);
        if (currentPass_ == Pass::Primary) {
            if (!isBlocked(text, textLength)) {
                notePrimaryReach(pack, wordIndex, 0.0f, fit);
            }
        } else if (currentPass_ == Pass::OtherPacks && !closerThanPrimary(0.0f, fit)) {
            continue;
        }

        float score = weightLog + contextLogProb(packIndex, wordIndex) -
                      kCompletionPenalty * static_cast<float>(extra);
        if (score + personal_.ceiling() > heap.worstScore()) {
            score += personal_.scoreFor(text, textLength);
            Candidate candidate{packIndex, frequent[i], score};
            candidate.runOn = static_cast<uint8_t>(extra);
            offerCandidate(heap, candidate, text, textLength);
        }
    }
}

void Engine::searchUserPhrases(TopK<Candidate>& heap) {
    if (!phraseSuggestions_ || personal_.context1() < 0 || userModel_.size() == 0) {
        return;
    }
    const uint32_t firstTotal = userModel_.successorTotal(personal_.store(), personal_.context1());
    if (firstTotal == 0u) {
        return;
    }

    constexpr int kMaxFirst = 3;
    UserModel::Successor first[kMaxFirst];
    const int firstCount =
        userModel_.successors(personal_.store(), personal_.context1(), first, kMaxFirst);

    for (int i = 0; i < firstCount && phraseCount_ < kMaxPhrases; ++i) {
        // Each link has to be learned.
        if (!personal_.learned(first[i].count)) {
            continue;
        }
        const float firstShare = static_cast<float>(first[i].count) /
                                 (static_cast<float>(firstTotal) + PersonalScore::kUserBigramPrior);
        if (firstShare < kPhraseMinShare) {
            continue;
        }

        const int32_t middle = static_cast<int32_t>(first[i].entryIndex);
        const uint32_t secondTotal = userModel_.successorTotal(personal_.store(), middle);
        if (secondTotal == 0u) {
            continue;
        }
        UserModel::Successor second[1];
        if (userModel_.successors(personal_.store(), middle, second, 1) != 1 ||
            !personal_.learned(second[0].count)) {
            continue;
        }
        // The second link is smoothed against a larger prior.
        const float secondShare =
            static_cast<float>(second[0].count) /
            (static_cast<float>(secondTotal) +
             PersonalScore::kUserBigramPrior * kPhraseSecondLinkFactor);
        if (secondShare < kPhraseMinShare) {
            continue;
        }

        uint32_t firstLength = 0;
        uint32_t secondLength = 0;
        const char* const firstText = userModel_.entryText(first[i].entryIndex, &firstLength);
        const char* const secondText = userModel_.entryText(second[0].entryIndex, &secondLength);
        if (firstText == nullptr || secondText == nullptr || firstLength == 0 ||
            secondLength == 0) {
            continue;
        }
        if (isBlocked(firstText, firstLength) || isBlocked(secondText, secondLength)) {
            continue;
        }
        // Both words have to be established, or a pack's own.
        if (!personal_.established(first[i].entryIndex) && !anyPackKnows(firstText, firstLength)) {
            continue;
        }
        if (!personal_.established(second[0].entryIndex) &&
            !anyPackKnows(secondText, secondLength)) {
            continue;
        }
        const size_t needed = firstLength + 1 + secondLength;
        if (needed + 1 > static_cast<size_t>(kMaxPhraseBytes)) {
            continue;
        }

        const int slot = phraseCount_;
        char* const target = phraseText_[slot];
        std::memcpy(target, firstText, firstLength);
        target[firstLength] = ' ';
        std::memcpy(target + firstLength + 1, secondText, secondLength);
        target[needed] = '\0';
        phraseLength_[slot] = static_cast<int>(needed);
        ++phraseCount_;

        // Scored as both links together, plus the first link's preference.
        const float score = std::log(firstShare) + std::log(secondShare) +
                            kUserChainPreference *
                                (static_cast<float>(first[i].count) /
                                 (static_cast<float>(first[i].count) + kUserChainHalfLife));
        offerCandidate(heap, Candidate{Candidate::kPhrasePack, slot, score}, target,
                       static_cast<uint32_t>(needed));
    }
}

void Engine::searchUserSuccessors(TopK<Candidate>& heap) {
    if (personal_.context1() < 0 || userModel_.size() == 0) {
        return;
    }

    // The triples after the last two words first, else the pairs after the last one.
    constexpr int kMaxSuccessors = 8;
    UserModel::Successor successors[kMaxSuccessors];
    int found = 0;
    uint32_t total = 0u;
    if (personal_.context2() >= 0) {
        total = userModel_.trigramTotal(personal_.store(), personal_.context2(),
                                        personal_.context1());
        if (total > 0u) {
            found = userModel_.trigramSuccessors(personal_.store(), personal_.context2(),
                                                 personal_.context1(), successors, kMaxSuccessors);
        }
    }
    if (found == 0) {
        total = userModel_.successorTotal(personal_.store(), personal_.context1());
        if (total == 0u) {
            return;
        }
        found = userModel_.successors(personal_.store(), personal_.context1(), successors,
                                      kMaxSuccessors);
    }
    for (int i = 0; i < found; ++i) {
        // A pair or triple is offered once learned.
        if (!personal_.learned(successors[i].count)) {
            continue;
        }
        // A smoothed conditional probability plus the preference.
        const float count = static_cast<float>(successors[i].count);
        const float share = count / (static_cast<float>(total) + PersonalScore::kUserBigramPrior);
        const float confidence = count / (count + kUserChainHalfLife);
        const float score = std::log(share) + kUserChainPreference * confidence;
        uint32_t textLength = 0;
        const Candidate candidate{Candidate::kUserPack,
                                  static_cast<int32_t>(successors[i].entryIndex), score};
        const char* const text = candidateText(candidate, &textLength);
        if (text == nullptr || textLength == 0) {
            continue;
        }
        // The word itself has to be established, or a pack's own.
        if (!personal_.established(successors[i].entryIndex) && !anyPackKnows(text, textLength)) {
            continue;
        }
        offerCandidate(heap, candidate, text, textLength);
    }
}

int32_t Engine::establishedPersonalEntry(const char* word, size_t length) const {
    const int32_t entry = personal_.enabled() ? userModel_.entryIndexFor(word, length) : -1;
    return (entry >= 0 && personal_.established(static_cast<uint32_t>(entry))) ? entry : -1;
}

bool Engine::anyPackKnows(const char* text, uint32_t length) const {
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(text, length, folded, kMaxComposing);
    return foldedLength > 0 && anyPackKnowsFolded(folded, foldedLength);
}

bool Engine::anyPackKnowsFolded(const uint32_t* folded, int length) const {
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && packs_[i].active &&
            firstUnblockedSpelling(packs_[i].trie(), knownIn(packs_[i].trie(), folded, length)) >=
                0) {
            return true;
        }
    }
    return false;
}

bool Engine::anyPackActive() const {
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && packs_[i].active) {
            return true;
        }
    }
    return false;
}

bool Engine::learnable(const char* word, size_t length) const {
    if (word == nullptr || length == 0) {
        return false;
    }
    if (length == kUserSentenceStartLength && std::memcmp(word, kUserSentenceStart, length) == 0) {
        return true;
    }
    if (!anyPackActive()) {
        return true;
    }
    const int32_t entry = userModel_.entryIndexFor(word, length);
    if (entry >= 0 && personal_.established(static_cast<uint32_t>(entry))) {
        return true;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return false;
    }
    if (anyPackKnowsFolded(folded, foldedLength)) {
        return true;
    }
    // Without its apostrophes.
    uint32_t bare[kMaxComposing];
    int bareLength = 0;
    for (int i = 0; i < foldedLength; ++i) {
        if (folded[i] != kApostrophe) {
            bare[bareLength++] = folded[i];
        }
    }
    if (bareLength > 0 && bareLength < foldedLength && anyPackKnowsFolded(bare, bareLength)) {
        return true;
    }
    // The word before a final possessive.
    const int stemLength = foldedLength - kPossessiveEndingLength;
    if (stemLength > 0 &&
        std::equal(kPossessiveEnding, kPossessiveEnding + kPossessiveEndingLength,
                   folded + stemLength) &&
        anyPackKnowsFolded(folded, stemLength)) {
        return true;
    }
    // Every part between its hyphens.
    if (std::find(folded, folded + foldedLength, kHyphen) == folded + foldedLength) {
        return false;
    }
    int partStart = 0;
    for (int i = 0; i <= foldedLength; ++i) {
        if (i < foldedLength && folded[i] != kHyphen) {
            continue;
        }
        if (i == partStart || !anyPackKnowsFolded(folded + partStart, i - partStart)) {
            return false;
        }
        partStart = i + 1;
    }
    return true;
}

void Engine::searchUserModel(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap) {
    if (!personal_.enabled() || userModel_.size() == 0) {
        return;
    }
    const size_t mark = arena_.used();
    constexpr int kMaxUserCompletions = 32;
    UserModel::Completion* const completions =
        arena_.allocateArray<UserModel::Completion>(kMaxUserCompletions);
    if (completions == nullptr) {
        return;
    }
    const int found =
        userModel_.completions(folded, foldedLength, completions, kMaxUserCompletions);
    for (int i = 0; i < found; ++i) {
        if (!personal_.established(completions[i].entryIndex)) {
            continue;
        }
        // kUserOnlyLogProb plus the boost a dictionary word would get, plus its context bonus.
        const float score = kUserOnlyLogProb + personal_.wordBoostForCount(completions[i].count) +
                            personal_.contextBonus(completions[i].entryIndex);
        const Candidate candidate{Candidate::kUserPack,
                                  static_cast<int32_t>(completions[i].entryIndex), score};
        uint32_t textLength = 0;
        const char* const text = candidateText(candidate, &textLength);
        if (text != nullptr && textLength != 0) {
            offerCandidate(heap, candidate, text, textLength);
        }
    }
    arena_.rewind(mark);
}

void Engine::searchPacks(const uint32_t* folded, int foldedLength, int onlyPack,
                         TopK<Candidate>& heap, int skipPack) {
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        if ((onlyPack >= 0 && i != onlyPack) || i == skipPack) {
            continue;
        }
        if (foldedLength == 0) {
            searchNextWord(i, heap);
        } else {
            // The shortlist first, raising the heap's floor before the descent.
            searchFrequentWithPrefix(i, folded, foldedLength, heap);
            // Each pack gets its own node budget.
            visitBudget_ = nodeVisitBudgetFor(foldedLength);
            searchPack(i, folded, foldedLength, heap);
        }
    }
}

int Engine::possessiveFor(const char* word, size_t length, char* out, int outBytes) const {
    if (!created_ || word == nullptr || out == nullptr || outBytes <= 0 || length < 3) {
        return 0;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    // At least three letters, the last an "s".
    if (foldedLength < 3 || folded[foldedLength - 1] != 's') {
        return 0;
    }
    // Not a word the dictionaries hold.
    for (int index = 0; index < kMaxPacks; ++index) {
        const LanguagePack& pack = packs_[index];
        if (pack.isOpen() && pack.active &&
            firstUnblockedSpelling(pack.trie(), pack.trie().lookupFolded(folded, foldedLength)) >=
                0) {
            return 0;
        }
    }
    // The stem has to be a name in an active pack.
    for (int index = 0; index < kMaxPacks; ++index) {
        const LanguagePack& pack = packs_[index];
        if (!pack.isOpen() || !pack.active) {
            continue;
        }
        const int32_t stem =
            firstUnblockedSpelling(pack.trie(), pack.trie().lookupFolded(folded, foldedLength - 1));
        if (stem < 0 || !pack.trie().isProperNoun(static_cast<uint32_t>(stem))) {
            continue;
        }
        uint32_t stemLength = 0;
        const char* const text = pack.trie().wordText(static_cast<uint32_t>(stem), &stemLength);
        if (text == nullptr || stemLength == 0 ||
            static_cast<int>(stemLength) + 2 > outBytes) {
            continue;
        }
        std::memcpy(out, text, stemLength);
        out[stemLength] = '\'';
        out[stemLength + 1] = 's';
        if (isBlocked(out, stemLength + 2)) {
            continue;
        }
        return static_cast<int>(stemLength) + 2;
    }
    return 0;
}

// How much evidence has to have accumulated before one language answers alone.
constexpr float kPreferredEvidenceMinimum = 3.0f;

// The language being written: the pack answerAs names, else from languageEvidence_ whatever the
// language lock says: the only active pack, or the one holding kLanguageDominanceShare of at
// least kPreferredEvidenceMinimum; -1 otherwise.
int Engine::preferredPack() const {
    if (answeringPack_ >= 0) {
        return answeringPack_;
    }
    int active = -1;
    int activeCount = 0;
    float total = 0.0f;
    float best = 0.0f;
    int bestIndex = -1;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        active = i;
        ++activeCount;
        total += languageEvidence_[i];
        if (languageEvidence_[i] > best) {
            best = languageEvidence_[i];
            bestIndex = i;
        }
    }
    if (activeCount == 1) {
        return active;
    }
    if (bestIndex < 0 || total < kPreferredEvidenceMinimum) {
        return kNoPack;
    }
    return (best >= total * kLanguageDominanceShare) ? bestIndex : kNoPack;
}

std::string Engine::currentPersonalLanguage() const {
    const int pack = personalLanguagePack();
    const char* const tag = pack >= 0 ? packs_[pack].tag() : nullptr;
    return primaryLanguageOf(tag, tag != nullptr ? std::strlen(tag) : 0);
}

int Engine::restrictedPack() const {
    if (answeringPack_ >= 0) {
        return answeringPack_;
    }
    return (dominantPack_ >= 0) ? dominantPack_ : preferredPack_;
}

int Engine::personalLanguagePack() const {
    const int restricted = restrictedPack();
    return (restricted >= 0) ? restricted : heaviestPack();
}

bool Engine::exactSpelling(const char* word, size_t length, int* packOut,
                           uint32_t* wordOut) const {
    if (!created_ || word == nullptr || length == 0 ||
        isBlocked(word, static_cast<uint32_t>(length))) {
        return false;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return false;
    }
    // The language being written answers first: its own spelling, or nothing when it holds the
    // letters only with other marks. Otherwise every pack.
    const int preferred = preferredPack();
    if (preferred >= 0 && packs_[preferred].isOpen() && packs_[preferred].active) {
        if (exactSpellingIn(preferred, word, length, folded, foldedLength, wordOut)) {
            if (packOut != nullptr) {
                *packOut = preferred;
            }
            return true;
        }
        if (readingIn(preferred, word, length, folded, foldedLength) >= 0) {
            return false;
        }
    }
    for (int index = 0; index < kMaxPacks; ++index) {
        if (index == preferred || !packs_[index].isOpen() || !packs_[index].active) {
            continue;
        }
        if (exactSpellingIn(index, word, length, folded, foldedLength, wordOut)) {
            if (packOut != nullptr) {
                *packOut = index;
            }
            return true;
        }
    }
    return false;
}

int32_t Engine::readingIn(int index, const char* word, size_t length, const uint32_t* folded,
                          int foldedLength) const {
    const PackedTrie& trie = packs_[index].trie();
    const int32_t firstIndex = knownIn(trie, folded, foldedLength);
    if (firstIndex < 0) {
        return -1;
    }
    const uint32_t spellings = trie.spellingsFrom(static_cast<uint32_t>(firstIndex));
    for (uint32_t offset = 0; offset < spellings; ++offset) {
        const uint32_t wordIndex = static_cast<uint32_t>(firstIndex) + offset;
        uint32_t textLength = 0;
        const char* const text = trie.wordText(wordIndex, &textLength);
        if (text != nullptr && textLength != 0 && !isBlocked(text, textLength) &&
            readsAsTyped(word, static_cast<uint32_t>(length), text, textLength,
                         trie.isProperNoun(wordIndex))) {
            return static_cast<int32_t>(wordIndex);
        }
    }
    return -1;
}

bool Engine::exactSpellingIn(int index, const char* word, size_t length, const uint32_t* folded,
                             int foldedLength, uint32_t* wordOut) const {
    const LanguagePack& pack = packs_[index];
    const int32_t firstIndex = knownIn(pack.trie(), folded, foldedLength);
    if (firstIndex < 0) {
        return false;
    }
    const uint32_t spellings = pack.trie().spellingsFrom(static_cast<uint32_t>(firstIndex));
    for (uint32_t offset = 0; offset < spellings; ++offset) {
        const uint32_t wordIndex = static_cast<uint32_t>(firstIndex) + offset;
        uint32_t candidateLength = 0;
        const char* const candidate = pack.trie().wordText(wordIndex, &candidateLength);
        if (candidate == nullptr ||
            !sameSpellingIgnoringCase(candidate, candidateLength, word, length)) {
            continue;
        }
        // A name matches only in its own case.
        if (pack.trie().isProperNoun(wordIndex) &&
            (candidateLength != length || std::memcmp(candidate, word, length) != 0)) {
            continue;
        }
        if (wordOut != nullptr) {
            *wordOut = wordIndex;
        }
        return true;
    }
    return false;
}

int Engine::knownSpelling(const char* word, size_t length, char* out, int outBytes) const {
    if (!created_ || word == nullptr || out == nullptr || outBytes <= 0 || length == 0) {
        return 0;
    }
    // The exact spelling when a pack holds it, else the first unblocked spelling of the folded
    // key, the preferred language's before the other packs'.
    int exactPack = -1;
    uint32_t exactWord = 0;
    if (exactSpelling(word, length, &exactPack, &exactWord)) {
        uint32_t exactLength = 0;
        const char* const exact = packs_[exactPack].trie().wordText(exactWord, &exactLength);
        if (exact != nullptr && exactLength != 0 &&
            exactLength <= static_cast<uint32_t>(outBytes)) {
            std::memcpy(out, exact, exactLength);
            return static_cast<int>(exactLength);
        }
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return 0;
    }
    // Step -1 visits the preferred pack; the steps after it visit the others in order. The
    // first round takes a reading of the letters, the second any spelling.
    const int preferred = preferredPack();
    for (int round = 0; round < 2; ++round) {
        for (int step = -1; step < kMaxPacks; ++step) {
            const int index = (step < 0) ? preferred : step;
            if (index < 0 || (step >= 0 && index == preferred)) {
                continue;
            }
            const LanguagePack& pack = packs_[index];
            if (!pack.isOpen() || !pack.active) {
                continue;
            }
            const int32_t spelling =
                (round == 0)
                    ? readingIn(index, word, length, folded, foldedLength)
                    : firstUnblockedSpelling(pack.trie(),
                                             knownIn(pack.trie(), folded, foldedLength));
            if (spelling < 0) {
                continue;
            }
            uint32_t textLength = 0;
            const char* const text = pack.trie().wordText(static_cast<uint32_t>(spelling),
                                                          &textLength);
            if (text == nullptr || textLength == 0 ||
                textLength > static_cast<uint32_t>(outBytes)) {
                continue;
            }
            std::memcpy(out, text, textLength);
            return static_cast<int>(textLength);
        }
    }
    // Then an established personal word, while the personal model is on.
    const int32_t entry = establishedPersonalEntry(word, length);
    if (entry >= 0) {
        uint32_t textLength = 0;
        const char* const text = userModel_.entryText(static_cast<uint32_t>(entry), &textLength);
        if (text != nullptr && textLength > 0 && textLength <= static_cast<uint32_t>(outBytes) &&
            !isBlocked(text, textLength)) {
            std::memcpy(out, text, textLength);
            return static_cast<int>(textLength);
        }
    }
    return 0;
}

bool Engine::vouchesForStem(const char* word, size_t length, const char* tag) const {
    if (!created_ || word == nullptr || length == 0) {
        return false;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return false;
    }
    const bool oneLanguage = tag != nullptr && tag[0] != '\0';
    const int onlyPack = oneLanguage ? packIndexForTag(tag) : -1;
    for (int i = 0; i < kMaxPacks; ++i) {
        const LanguagePack& pack = packs_[i];
        if (!pack.isOpen() || !pack.active || (oneLanguage && i != onlyPack)) {
            continue;
        }
        const int32_t index =
            firstUnblockedSpelling(pack.trie(), pack.trie().lookupFolded(folded, foldedLength));
        if (index < 0 || pack.trie().isProperNoun(static_cast<uint32_t>(index))) {
            continue;
        }
        if (pack.frequentWordCount() <= 0) {
            return true;
        }
        const float commonest =
            pack.trie().unigramLogProb(static_cast<uint32_t>(pack.frequentWords()[0]));
        if (pack.trie().unigramLogProb(static_cast<uint32_t>(index)) >=
            commonest - kStemFrequencyFloor) {
            return true;
        }
    }
    if (!personal_.enabled() || isBlocked(word, static_cast<uint32_t>(length))) {
        return false;
    }
    const int32_t entry = userModel_.entryIndexFor(word, length);
    return entry >= 0 && personal_.established(static_cast<uint32_t>(entry));
}

bool Engine::answerAs(int packIndex) {
    if (packIndex == kNoPack) {
        answeringPack_ = kNoPack;
        return true;
    }
    if (packIndex < 0 || packIndex >= kMaxPacks || !packs_[packIndex].isOpen() ||
        !packs_[packIndex].active) {
        return false;
    }
    answeringPack_ = packIndex;
    return true;
}

int Engine::suggest(const char* composing, size_t composingLength, const char* previous1,
                    size_t previous1Length, const char* previous2, size_t previous2Length,
                    Candidate* out, int maxOut) {
    return suggest(composing, composingLength, previous1, previous1Length, previous2,
                   previous2Length, nullptr, nullptr, 0, out, maxOut);
}

int Engine::suggest(const char* composing, size_t composingLength, const char* previous1,
                    size_t previous1Length, const char* previous2, size_t previous2Length,
                    const float* tapX, const float* tapY, int tapCount, Candidate* out,
                    int maxOut) {
    if (!created_ || out == nullptr || maxOut <= 0) {
        return 0;
    }
    arena_.reset();
    // The phrase slots, the decoder's word and the settled corrections belong to this request.
    phraseCount_ = 0;
    forgetDecoded();
    forgetSettled();

    uint32_t folded[kMaxComposing];
    int source[kMaxComposing];
    int foldedLength = 0;
    if (composing != nullptr && composingLength > 0) {
        foldedLength = foldUtf8(composing, composingLength, folded, kMaxComposing, source);
        // Malformed or overlong input gets no suggestions.
        if (foldedLength < 0) {
            resolveLanguage();
            return 0;
        }
    }

    // The taps line up with the folded code points; each folded one takes its source's tap.
    struct TapsScope {
        bool& tapped;
        ~TapsScope() { tapped = false; }
    } tapsScope{queryTapped_};
    queryTapped_ = tapX != nullptr && tapY != nullptr;
    if (queryTapped_) {
        const float none = std::numeric_limits<float>::quiet_NaN();
        for (int i = 0; i < foldedLength; ++i) {
            const bool inRange = source[i] >= 0 && source[i] < tapCount;
            queryTapX_[i] = inRange ? tapX[source[i]] : none;
            queryTapY_[i] = inRange ? tapY[source[i]] : none;
        }
    }

    refreshWeights();

    resolveContext(previous1, previous1Length, previous2, previous2Length);

    TopK<Candidate> heap;
    heap.reset(heapStorage_, kMaxCandidates);
    correctionHeap_.reset(correctionStorage_, kMaxCorrections);

    // The search is restricted to [restrictedPack], else, when strict, the heaviest; otherwise
    // every pack answers.
    const int restricted = restrictedPack();
    const int restrictTo = (restricted >= 0) ? restricted : (strictLanguage_ ? heaviestPack() : -1);
    primaryReached_ = false;
    typedText_ = composing;
    typedTextLength_ = static_cast<uint32_t>(composingLength);
    typedCarriesMark_ = composing != nullptr && carriesFoldedMark(composing, typedTextLength_);
    for (const PassSpec& spec : kSearchPlan) {
        // Whether a word from another pack could still read the letters more closely.
        const bool outmatchable = !primaryReached_ || primaryClosest_.cost >= kLanguageMargin ||
                                  primaryClosest_.fit != Fit::Spells;
        const PlanState state{foldedLength, restrictTo >= 0, strictLanguage_, heap.size(),
                              outmatchable};
        if (!spec.runs(state)) {
            continue;
        }
        PassScope pass(*this, spec.pass);
        runPass(spec, folded, foldedLength, restrictTo, heap);
    }
    typedText_ = nullptr;
    typedTextLength_ = 0;

    settleCorrection(composing, composingLength);
    if (strictLanguage_ && restrictTo >= 0 && typedSpelling_.packIndex != restrictTo) {
        hasTypedSpelling_ = false;
    }
    decodeTaps(folded, foldedLength, restrictTo);
    return keepTypedSpelling(
        out,
        placeDecodedSuggestion(out, writeStrip(folded, foldedLength, heap, out, maxOut), maxOut),
        maxOut);
}

void Engine::runPass(const PassSpec& spec, const uint32_t* folded, int foldedLength,
                     int restrictTo, TopK<Candidate>& heap) {
    switch (spec.ceiling) {
        case PassCeiling::ByLength:
            editCostCeiling_ = maxEditCostFor(foldedLength);
            break;
        case PassCeiling::PrimaryClosest:
            editCostCeiling_ = maxEditCostFor(foldedLength);
            if (primaryReached_) {
                const float outmatching = (primaryClosest_.fit != Fit::Spells)
                                              ? primaryClosest_.cost
                                              : primaryClosest_.cost - kLanguageMargin;
                editCostCeiling_ = std::max(0.0f, std::min(editCostCeiling_, outmatching));
            }
            break;
        case PassCeiling::Fallback:
            editCostCeiling_ = kFallbackEditCost;
            break;
        case PassCeiling::None:
            break;
    }
    switch (spec.source) {
        case PassSource::RestrictedPacks:
            searchPacks(folded, foldedLength, restrictTo, heap);
            break;
        case PassSource::AllPacks:
            searchPacks(folded, foldedLength, -1, heap);
            break;
        case PassSource::OtherPacks:
            searchPacks(folded, foldedLength, -1, heap, restrictTo);
            break;
        case PassSource::PersonalWords:
            searchUserModel(folded, foldedLength, heap);
            break;
        case PassSource::PersonalNextWords:
            searchUserSuccessors(heap);
            searchUserPhrases(heap);
            break;
    }
}

void Engine::forgetSettled() {
    settledCount_ = 0;
    hasBestCorrection_ = false;
    hasBestRespelling_ = false;
    hasTypedSpelling_ = false;
    typedKnown_ = false;
}

void Engine::settleCorrection(const char* composing, size_t composingLength) {
    settledCount_ = 0;
    if (hasBestRespelling_) {
        settled_[settledCount_++] = bestRespelling_;
    }
    const int drained = correctionHeap_.drainSorted(settled_ + settledCount_, kMaxCorrections);
    if (drained > 0) {
        bestCorrection_ = settled_[settledCount_];
        hasBestCorrection_ = true;
    }
    settledCount_ += drained;
    // Overridden by the best respelling of the letters typed.
    if (hasBestRespelling_) {
        bestCorrection_ = bestRespelling_;
        hasBestCorrection_ = true;
    }

    // Overridden in turn by a dictionary spelling that matches the typed letters exactly.
    int typedPack = -1;
    uint32_t typedWord = 0;
    const bool typed = composing != nullptr && composingLength > 0;
    const bool spelled = typed && exactSpelling(composing, composingLength, &typedPack, &typedWord);
    if (spelled) {
        bestCorrection_ = Candidate{typedPack, static_cast<int32_t>(typedWord), 0.0f};
        hasBestCorrection_ = true;
    }
    hasTypedSpelling_ = spelled && !packs_[typedPack].trie().isKnownOnly(typedWord);
    if (hasTypedSpelling_) {
        typedSpelling_ = Candidate{typedPack, static_cast<int32_t>(typedWord), 0.0f};
    }
    typedKnown_ = spelled || (typed && establishedPersonalEntry(composing, composingLength) >= 0);
}

int Engine::writeStrip(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap,
                       Candidate* out, int maxOut) {
    const int drained = heap.drainSorted(drainBuffer_, kMaxCandidates);
    int written = 0;
    int continuations = 0;
    for (int i = 0; i < drained && written < maxOut; ++i) {
        if (foldedLength > 0 && continuesTyped(drainBuffer_[i], folded, foldedLength)) {
            if (continuations >= kMaxShownCompletions) {
                continue;
            }
            ++continuations;
        }
        out[written++] = drainBuffer_[i];
    }
    return written;
}

int Engine::placeDecodedSuggestion(Candidate* out, int written, int maxOut) const {
    const Candidate* const offered = decodedSuggestion();
    if (offered == nullptr) {
        return written;
    }
    return placeOffered(out, written, maxOut, *offered,
                        [this](const Candidate& listed, const Candidate& word) {
                            uint32_t listedLength = 0;
                            uint32_t wordLength = 0;
                            const char* const listedText = candidateText(listed, &listedLength);
                            const char* const wordText = candidateText(word, &wordLength);
                            return listedText != nullptr && wordText != nullptr &&
                                   sameSpellingIgnoringCase(listedText, listedLength, wordText,
                                                            wordLength);
                        });
}

int Engine::keepTypedSpelling(Candidate* out, int written, int maxOut) const {
    if (!hasTypedSpelling_ || maxOut <= 0) {
        return written;
    }
    uint32_t typedLength = 0;
    const char* const typedText = candidateText(typedSpelling_, &typedLength);
    if (typedText == nullptr) {
        return written;
    }
    for (int i = 0; i < written; ++i) {
        uint32_t length = 0;
        const char* const text = candidateText(out[i], &length);
        if (text != nullptr && sameSpellingIgnoringCase(text, length, typedText, typedLength)) {
            return written;
        }
    }
    if (written < maxOut) {
        Candidate kept = typedSpelling_;
        kept.score = written > 0 ? out[written - 1].score : 0.0f;
        out[written] = kept;
        return written + 1;
    }
    Candidate kept = typedSpelling_;
    kept.score = out[written - 1].score;
    out[written - 1] = kept;
    return written;
}

bool Engine::continuesTyped(const Candidate& candidate, const uint32_t* folded,
                            int foldedLength) const {
    uint32_t length = 0;
    const char* const text = candidateText(candidate, &length);
    if (text == nullptr || length == 0) {
        return false;
    }
    uint32_t wordFolded[kMaxComposing];
    const int wordLength = foldUtf8(text, length, wordFolded, kMaxComposing);
    if (wordLength <= foldedLength) {
        return false;  // no longer than what was typed
    }
    for (int i = 0; i < foldedLength; ++i) {
        if (wordFolded[i] != folded[i]) {
            return false;  // reached by an edit
        }
    }
    return true;
}

Engine::Learned Engine::learn(const char* word, size_t wordLength, const char* previous1,
                              size_t previous1Length, const char* previous2,
                              size_t previous2Length, bool deliberateCapital, bool asserted,
                              const char* language, size_t languageLength, bool tally) {
    Learned learned;
    if (!created_ || word == nullptr || wordLength == 0) {
        return learned;
    }
    // Counts the word, then learns its pair with the word before and its triple with the two
    // before in the store of the language it was written in, each when its words are learnable.
    const int32_t counted =
        tally ? userModel_.learn(word, wordLength, deliberateCapital, asserted) : -1;
    const bool pairs = previous1 != nullptr && previous1Length > 0 &&
                       learnable(word, wordLength) && learnable(previous1, previous1Length);
    if (!pairs) {
        return learned;
    }
    const bool named = language != nullptr && languageLength > 0;
    const std::string current = named ? std::string() : currentPersonalLanguage();
    const int32_t store = named ? userModel_.storeFor(language, languageLength)
                                : userModel_.storeFor(current.data(), current.size());
    // A word not counted, and the words before, are held as contexts, never counted or offered for
    // it.
    const int32_t wordIndex = tally ? counted : userModel_.reserve(word, wordLength);
    const int32_t index1 = userModel_.reserve(previous1, previous1Length);
    learned.pair = userModel_.learnBigram(store, index1, wordIndex);
    if (previous2 != nullptr && previous2Length > 0 && learnable(previous2, previous2Length)) {
        const int32_t index2 = userModel_.reserve(previous2, previous2Length);
        learned.triple = userModel_.learnTrigram(store, index2, index1, wordIndex);
    }
    return learned;
}

void Engine::loadUserWords(const char* const* words, const size_t* lengths,
                           const int32_t* counts, int count, const int32_t* deliberateCapitals,
                           const int32_t* asserted) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoad(words, lengths, counts, count, deliberateCapitals, asserted);
    // Reserves the sentence-start context the pairs loaded next refer to.
    userModel_.reserve(kUserSentenceStart, kUserSentenceStartLength);
}

void Engine::loadUserBigrams(const char* const* languages, const size_t* languageLengths,
                             const char* const* previous, const size_t* previousLengths,
                             const char* const* next, const size_t* nextLengths,
                             const int32_t* counts, int count, const uint64_t* contextSeen,
                             const uint64_t* languageSeen) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoadBigrams(languages, languageLengths, previous, previousLengths, next,
                               nextLengths, counts, count, contextSeen, languageSeen);
}

void Engine::loadUserPairTotals(const char* const* languages, const size_t* languageLengths,
                                const char* const* previous, const size_t* previousLengths,
                                const uint32_t* totals, int count, const uint64_t* seen) {
    if (!created_) {
        return;
    }
    userModel_.loadPairTotals(languages, languageLengths, previous, previousLengths, totals, count,
                              seen);
}

void Engine::loadUserTripleTotals(const char* const* languages, const size_t* languageLengths,
                                  const char* const* previous2, const size_t* previous2Lengths,
                                  const char* const* previous1, const size_t* previous1Lengths,
                                  const uint32_t* totals, int count, const uint64_t* seen) {
    if (!created_) {
        return;
    }
    userModel_.loadTripleTotals(languages, languageLengths, previous2, previous2Lengths, previous1,
                                previous1Lengths, totals, count, seen);
}

void Engine::loadUserClocks(const char* const* languages, const size_t* languageLengths,
                            const uint64_t* pairClocks, const uint64_t* tripleClocks, int count) {
    if (!created_) {
        return;
    }
    userModel_.loadClocks(languages, languageLengths, pairClocks, tripleClocks, count);
}

int Engine::userContextLinks(const char* language, size_t languageLength, const char* previous2,
                             size_t previous2Length, const char* previous1, size_t previous1Length,
                             UserModel::ContextLink* out, int maxOut,
                             UserModel::ContextCount* context) const {
    if (context != nullptr) {
        *context = UserModel::ContextCount{0u, 0u};
    }
    if (!created_) {
        return 0;
    }
    const int32_t store = userModel_.findStore(language, languageLength);
    const int32_t index1 = userModel_.entryIndexFor(previous1, previous1Length);
    if (previous2 == nullptr || previous2Length == 0) {
        return userModel_.pairsAfter(store, index1, out, maxOut, context);
    }
    const int32_t index2 = userModel_.entryIndexFor(previous2, previous2Length);
    return userModel_.triplesAfter(store, index2, index1, out, maxOut, context);
}

int Engine::userLinksHeld(const char* language, size_t languageLength, bool triples) const {
    if (!created_) {
        return 0;
    }
    const int32_t store = userModel_.findStore(language, languageLength);
    return triples ? userModel_.triplesHeld(store) : userModel_.pairsHeld(store);
}

bool Engine::userLinkAt(const char* language, size_t languageLength, bool triples, int index,
                        StoredLink* out) const {
    if (!created_ || out == nullptr) {
        return false;
    }
    const int32_t store = userModel_.findStore(language, languageLength);
    const int held = triples ? userModel_.triplesHeld(store) : userModel_.pairsHeld(store);
    if (index < 0 || index >= held) {
        return false;
    }
    *out = StoredLink{-1, -1, -1, 0u, 0u, 0u};
    if (triples) {
        userModel_.tripleAt(store, index, &out->previous2Index, &out->previous1Index,
                            &out->nextIndex, &out->count, &out->languageSeen, &out->contextTotal);
    } else {
        userModel_.pairAt(store, index, &out->previous1Index, &out->nextIndex, &out->count,
                          &out->languageSeen, &out->contextTotal);
    }
    return true;
}

const char* Engine::userEntryKey(uint32_t entryIndex, uint32_t* lengthOut) const {
    return created_ ? userModel_.entryKey(entryIndex, lengthOut) : nullptr;
}

void Engine::dropUserPairs(const char* const* languages, const size_t* languageLengths,
                           const char* const* previous, const size_t* previousLengths,
                           const char* const* next, const size_t* nextLengths, int count) {
    if (!created_) {
        return;
    }
    userModel_.dropBigrams(languages, languageLengths, previous, previousLengths, next, nextLengths,
                           count);
}

void Engine::dropUserTriples(const char* const* languages, const size_t* languageLengths,
                             const char* const* previous2, const size_t* previous2Lengths,
                             const char* const* previous1, const size_t* previous1Lengths,
                             const char* const* next, const size_t* nextLengths, int count) {
    if (!created_) {
        return;
    }
    userModel_.dropTrigrams(languages, languageLengths, previous2, previous2Lengths, previous1,
                            previous1Lengths, next, nextLengths, count);
}

const char* Engine::dominantLanguageTag() const {
    return packTag(dominantPack_);
}

const char* Engine::packTag(int slot) const {
    if (slot < 0 || slot >= kMaxPacks || !packs_[slot].isOpen()) {
        return nullptr;
    }
    return packs_[slot].tag();
}

const char* Engine::candidateText(const Candidate& candidate, uint32_t* lengthOut) const {
    if (candidate.packIndex == Candidate::kPhrasePack) {
        const int slot = candidate.wordIndex;
        if (slot < 0 || slot >= phraseCount_) {
            return nullptr;
        }
        if (lengthOut != nullptr) {
            *lengthOut = static_cast<uint32_t>(phraseLength_[slot]);
        }
        return phraseText_[slot];
    }
    if (candidate.packIndex == Candidate::kUserPack) {
        return userModel_.entryText(static_cast<uint32_t>(candidate.wordIndex), lengthOut);
    }
    if (candidate.packIndex < 0 || candidate.packIndex >= kMaxPacks) {
        return nullptr;
    }
    const LanguagePack& pack = packs_[candidate.packIndex];
    if (!pack.isOpen() || candidate.wordIndex < 0) {
        return nullptr;
    }
    return pack.trie().wordText(static_cast<uint32_t>(candidate.wordIndex), lengthOut);
}

namespace {

/** Plain Levenshtein distance over folded code points, for explainScore only. */
int reportedEditDistance(const uint32_t* a, int aLength, const uint32_t* b, int bLength) {
    constexpr int kCap = 64;
    if (aLength > kCap || bLength > kCap) {
        return -1;
    }
    int previous[kCap + 1];
    int current[kCap + 1];
    for (int j = 0; j <= bLength; ++j) {
        previous[j] = j;
    }
    for (int i = 1; i <= aLength; ++i) {
        current[0] = i;
        for (int j = 1; j <= bLength; ++j) {
            const int substitution = previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1);
            const int deletion = previous[j] + 1;
            const int insertion = current[j - 1] + 1;
            int best = substitution < deletion ? substitution : deletion;
            current[j] = best < insertion ? best : insertion;
        }
        for (int j = 0; j <= bLength; ++j) {
            previous[j] = current[j];
        }
    }
    return previous[bLength];
}

}  // namespace

bool Engine::plausibleCorrectionTarget(const LanguagePack& pack, uint32_t wordIndex) const {
    if (commonIn(pack, wordIndex)) {
        return true;
    }
    // Or common in another active pack that holds the same spelling.
    bool othersActive = false;
    for (int i = 0; i < kMaxPacks; ++i) {
        othersActive = othersActive || (&packs_[i] != &pack && packs_[i].isOpen() &&
                                        packs_[i].active);
    }
    if (!othersActive) {
        return false;
    }
    uint32_t length = 0;
    const char* const text = pack.trie().wordText(wordIndex, &length);
    uint32_t folded[kMaxComposing];
    const int foldedLength =
        (text == nullptr || length == 0) ? 0 : foldUtf8(text, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return false;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        const LanguagePack& other = packs_[i];
        if (&other == &pack || !other.isOpen() || !other.active) {
            continue;
        }
        const int32_t firstIndex = other.trie().lookupFolded(folded, foldedLength);
        if (firstIndex < 0) {
            continue;
        }
        const uint32_t spellings = other.trie().spellingsFrom(static_cast<uint32_t>(firstIndex));
        for (uint32_t offset = 0; offset < spellings; ++offset) {
            const uint32_t index = static_cast<uint32_t>(firstIndex) + offset;
            uint32_t otherLength = 0;
            const char* const otherText = other.trie().wordText(index, &otherLength);
            if (otherText != nullptr &&
                sameSpellingIgnoringCase(otherText, otherLength, text, length) &&
                commonIn(other, index)) {
                return true;
            }
        }
    }
    return false;
}

bool Engine::commonIn(const LanguagePack& pack, uint32_t wordIndex) const {
    // Without a frequent list, every target is plausible.
    if (pack.frequentWordCount() <= 0) {
        return true;
    }
    const float commonest =
        pack.trie().unigramLogProb(static_cast<uint32_t>(pack.frequentWords()[0]));
    return pack.trie().unigramLogProb(wordIndex) >= commonest - kCorrectionFrequencyFloor;
}

bool Engine::explainScore(const char* typed, size_t typedLength, const char* candidate,
                          size_t candidateLength, ScoreParts* out) {
    if (typed == nullptr || candidate == nullptr || out == nullptr) {
        return false;
    }
    *out = ScoreParts{};

    Candidate results[kMaxCandidates];
    const int found = suggest(typed, typedLength, nullptr, 0, nullptr, 0, results, kMaxCandidates);
    for (int i = 0; i < found; ++i) {
        uint32_t length = 0;
        const char* const text = candidateText(results[i], &length);
        if (text == nullptr || length != candidateLength ||
            std::memcmp(text, candidate, length) != 0) {
            continue;
        }
        out->rank = i;
        out->total = results[i].score;
        out->packIndex = results[i].packIndex;
        // Set below from the folded lengths.
        out->addedCharacters = 0;

        // Read against the context the request resolved.
        if (results[i].packIndex >= 0 && results[i].packIndex < kMaxPacks) {
            out->packWeight = packWeightLog(results[i].packIndex);
            out->languageModel = contextLogProb(results[i].packIndex,
                                                static_cast<uint32_t>(results[i].wordIndex));
        } else if (results[i].packIndex == Candidate::kUserPack) {
            out->languageModel = kUserOnlyLogProb;
        }
        const int32_t entry = personal_.entryFor(candidate, static_cast<uint32_t>(candidateLength));
        if (entry >= 0) {
            out->personal = personal_.scoreForEntry(static_cast<uint32_t>(entry));
            out->context = personal_.contextBonus(static_cast<uint32_t>(entry));
        }

        // The rest of the total: the edit cost and the completion penalty together.
        out->rest = out->total - out->packWeight - out->languageModel - out->personal;
        out->editCost = results[i].editCost;
        out->edits = results[i].edits;
        out->runOn = results[i].runOn;
        out->editPenalty = correctionStrictness_ * kEditPenalty * results[i].editCost;
        out->surcharge =
            results[i].editCost > 0.0f ? correctionStrictness_ * kCorrectionSurcharge : 0.0f;
        out->completion = kCompletionPenalty * static_cast<float>(results[i].runOn);

        uint32_t typedFolded[kMaxComposing];
        uint32_t wordFolded[kMaxComposing];
        const int typedCount = foldUtf8(typed, typedLength, typedFolded, kMaxComposing);
        const int wordCount = foldUtf8(candidate, candidateLength, wordFolded, kMaxComposing);
        out->editDistance = (typedCount <= 0 || wordCount <= 0)
            ? -1
            : reportedEditDistance(typedFolded, typedCount, wordFolded, wordCount);
        if (typedCount > 0 && wordCount > 0) {
            out->addedCharacters = wordCount - typedCount;
        }
        return true;
    }
    return false;
}

bool Engine::packsAgreeProperNoun(const uint32_t* folded, int foldedLength) const {
    bool known = false;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        const int32_t wordIndex = packs_[i].trie().lookupFolded(folded, foldedLength);
        if (wordIndex < 0) {
            continue;
        }
        if (!packs_[i].trie().isProperNoun(static_cast<uint32_t>(wordIndex))) {
            return false;
        }
        known = true;
    }
    return known;
}

bool Engine::candidateIsProperNoun(const Candidate& candidate) const {
    if (candidate.packIndex >= 0 && candidate.packIndex < kMaxPacks) {
        const LanguagePack& pack = packs_[candidate.packIndex];
        if (!pack.isOpen() || candidate.wordIndex < 0 ||
            !pack.trie().isProperNoun(static_cast<uint32_t>(candidate.wordIndex))) {
            return false;
        }
        // Every active pack that knows the word has to agree it is a name.
        uint32_t length = 0;
        const char* text = pack.trie().wordText(static_cast<uint32_t>(candidate.wordIndex), &length);
        if (text == nullptr || length == 0) {
            return true;
        }
        uint32_t folded[kMaxComposing];
        const int foldedLength = foldUtf8(text, length, folded, kMaxComposing);
        return foldedLength <= 0 || packsAgreeProperNoun(folded, foldedLength);
    }
    // A personal word the user capitalised with shift at least once is a name.
    if (candidate.packIndex == Candidate::kUserPack && candidate.wordIndex >= 0 &&
        userModel_.deliberateCapitals(static_cast<uint32_t>(candidate.wordIndex)) > 0) {
        return true;
    }
    // Otherwise a name when the active packs, looked up by folded text, agree it is one.
    uint32_t length = 0;
    const char* text = candidateText(candidate, &length);
    if (text == nullptr || length == 0) {
        return false;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(text, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return false;
    }
    return packsAgreeProperNoun(folded, foldedLength);
}

}  // namespace borderkeys
