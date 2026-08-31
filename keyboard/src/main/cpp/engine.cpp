// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "engine.hpp"

#include <sys/mman.h>
#include <unistd.h>

#include <cmath>
#include <cstring>

namespace borderkeys {
namespace {

// Scoring constants, all in natural log units so they add to log-probabilities directly.

// What one key width of finger error costs. Calibrated so that a neighbouring-key substitution
// (about 1.0 key widths) is worth roughly one order of magnitude of probability: a typo is
// forgiven when the intended word is much more likely, and not when it is not.
constexpr float kEditPenalty = 2.3f;

// Insertions and deletions in key-width units. Slightly below a full neighbour substitution,
// because a dropped or doubled letter is a more common slip than hitting the wrong key.
constexpr float kInsertCost = 0.85f;
constexpr float kDeleteCost = 0.85f;
// Transposition is one gesture gone out of order rather than two independent errors, so it
// costs less than the deletion plus insertion it would otherwise be decomposed into.
constexpr float kTransposeCost = 0.65f;

// Each character a completion adds beyond what was typed. Small: the unigram probability
// already prefers common words, and this only breaks ties towards the shorter one.
constexpr float kCompletionPenalty = 0.12f;

// Stupid backoff, factor 0.4 as in the literature. Deterministic and needing no normalisation
// at runtime, which is the whole reason it is used instead of a smoothed model.
constexpr float kBackoffLogFactor = -0.9162907f;  // ln(0.4)

// A language that is enabled can never be weighted below this, however badly its suggestions
// have been doing lately.
constexpr float kMinLanguageWeight = 0.15f;
// How fast the acceptance average moves. Low enough that one sentence in the other language
// does not reorder the whole keyboard.
constexpr float kWeightAdaptRate = 0.06f;

constexpr float kMaxUserBoost = 3.0f;

// The log-probability a word gets when the personal dictionary is the only place it exists.
// Scores from the user model cannot be derived from its own totals: a word confirmed forty
// times out of fifty is three quarters of *that* distribution, which on a language pack's scale
// would be a more likely word than "the". Anchoring to a fixed, deliberately pessimistic
// language-scale value and adding the same bounded boost a known word would get keeps the two
// sources comparable -- and keeps a word typed once from outranking the dictionary.
constexpr float kUserOnlyLogProb = -8.0f;

constexpr int kMaxEndpoints = 96;

// How many trie nodes a request may visit, by prefix length.
//
// Scaled rather than fixed, because the value of a visit is not constant. Under a one-character
// prefix there are tens of thousands of words and no budget crosses them all, so extra visits
// buy an arbitrary sample of a huge subtree -- the frequent-word shortlist answers that case
// properly and far more cheaply. By five characters the subtree is small, every node in it is a
// plausible completion, and the budget is generous enough never to bind.
//
// Measured on a 119k-word pack: a flat 20000 spent 900 us on "mas" and 40 us on "masina", for
// suggestions that were worse at the short end. This spends it where it changes the answer.
int nodeVisitBudgetFor(int length) {
    if (length <= 2) {
        return 3000;
    }
    if (length <= 4) {
        return 10000;
    }
    return 20000;
}
// An insertion advances the trie without consuming input, so on its own it would recurse
// forever. This bounds how far a candidate may run ahead of what was actually typed.
constexpr int kMaxRunAhead = 2;

constexpr size_t kArenaBytes = 512 * 1024;

// How much finger error to tolerate, by prefix length. One or two characters carry almost no
// information, so allowing substitutions there returns noise rather than corrections; from
// three characters on, the prefix constrains the search enough for fuzzy matching to help.
float maxEditCostFor(int length) {
    if (length <= 2) {
        return 0.0f;
    }
    if (length <= 4) {
        return 1.7f;
    }
    return 2.5f;
}

}  // namespace

// --------------------------------------------------------------------------------------
// LanguagePack
// --------------------------------------------------------------------------------------

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

    // An asset inside an APK starts at an arbitrary offset, and mmap only accepts page-aligned
    // ones. Map from the page below and keep the difference, rather than copying the pack out
    // of the APK to get an aligned file of our own.
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

    // The content checksum is the one linear pass over the file, and it is here on purpose.
    // Parsing stays O(1) -- nothing is deserialised, the sections are reinterpreted in place --
    // but integrity is not something that can be established in O(1), and this runs once, on a
    // background thread, before the pack is ever consulted. Skipping it would mean the first
    // evidence of a truncated download is a segfault during typing.
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

    std::memset(tag_, 0, sizeof(tag_));
    std::strncpy(tag_, tag, sizeof(tag_) - 1);

    buildFrequentList();
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
    frequentCount_ = 0;
    active = false;
    tag_[0] = '\0';
}

void LanguagePack::buildFrequentList() {
    // One pass over the quantised unigram column, keeping the kFrequentCount smallest values
    // (smallest quantised magnitude means highest probability). Paid once at load, alongside
    // the checksum pass that already touched these pages.
    frequentCount_ = 0;
    uint8_t worst = 0xFFu;
    const uint32_t words = trie_.wordCount();
    for (uint32_t i = 0; i < words; ++i) {
        const uint8_t quantised = trie_.wordFreqQuantised(i);
        if (frequentCount_ == kFrequentCount && quantised >= worst) {
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
    created_ = true;
    return true;
}

void Engine::destroy() {
    for (LanguagePack& pack : packs_) {
        pack.close();
    }
    userModel_.clear();
    arena_.release();
    created_ = false;
}

int Engine::packIndexForTag(const char* tag) const {
    if (tag == nullptr) {
        return -1;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && std::strcmp(packs_[i].tag(), tag) == 0) {
            return i;
        }
    }
    return -1;
}

int32_t Engine::loadLanguage(const char* tag, int fd, int64_t offset, int64_t length,
                             float weight) {
    if (!created_) {
        return kBkdErrArgument;
    }
    // Reloading a tag replaces it, so that re-importing a corrected pack does not need the
    // service restarted.
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
    packs_[slot].adaptiveWeight = packs_[slot].configuredWeight;
    packs_[slot].active = true;
    return kBkdOk;
}

void Engine::setActiveLanguages(const char* const* tags, const float* weights, int count) {
    for (LanguagePack& pack : packs_) {
        pack.active = false;
    }
    if (tags == nullptr) {
        return;
    }
    for (int i = 0; i < count; ++i) {
        const int slot = packIndexForTag(tags[i]);
        if (slot < 0) {
            continue;
        }
        packs_[slot].active = true;
        const float weight = (weights != nullptr && weights[i] > 0.0f) ? weights[i] : 1.0f;
        packs_[slot].configuredWeight = weight;
        // Adapting from the newly configured weight rather than keeping the old running value:
        // the user has just said what they want, and last week's acceptance rate is not an
        // argument against it.
        packs_[slot].adaptiveWeight = weight;
    }
}

bool Engine::setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                            int count, float keyWidth, float keyHeight) {
    return geometry_.set(codes, centersX, centersY, count, keyWidth, keyHeight);
}

void Engine::resolveContext(const char* previous1, size_t previous1Length, const char* previous2,
                            size_t previous2Length) {
    hasContext1_ = false;
    hasContext2_ = false;
    uint32_t folded[kMaxComposing];

    int length1 = -1;
    if (previous1 != nullptr && previous1Length > 0) {
        length1 = foldUtf8(previous1, previous1Length, folded, kMaxComposing);
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        contextWord1_[i] = -1;
        if (length1 > 0 && packs_[i].isOpen()) {
            contextWord1_[i] = packs_[i].trie().lookupFolded(folded, length1);
        }
        if (contextWord1_[i] >= 0) {
            hasContext1_ = true;
        }
    }

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
}

float Engine::contextLogProb(int packIndex, uint32_t wordIndex) const {
    const LanguagePack& pack = packs_[packIndex];
    const int32_t w1 = contextWord1_[packIndex];
    const int32_t w2 = contextWord2_[packIndex];

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
            // One level dropped when a trigram context existed, none when it did not: the
            // penalty is for what was skipped, not for what was never available.
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
    return unigram + kBackoffLogFactor * static_cast<float>(dropped);
}

float Engine::userBoostForCount(uint32_t count) {
    if (count == 0) {
        return 0.0f;
    }
    // Diminishing and capped: the tenth time a word is chosen should matter far less than the
    // second, and no amount of repetition should let one word crowd out the dictionary. A
    // linear boost does both of the things this avoids.
    const float boost = 0.9f * std::log(1.0f + static_cast<float>(count));
    return (boost > kMaxUserBoost) ? kMaxUserBoost : boost;
}

float Engine::userBoostFor(const char* text, uint32_t length) const {
    if (userModel_.size() == 0 || text == nullptr || length == 0) {
        return 0.0f;
    }
    return userBoostForCount(userModel_.countFor(text, length));
}

void Engine::offerCandidate(TopK<Candidate>& heap, const Candidate& candidate, const char* text,
                            uint32_t textLength) const {
    // The same word is reached by more than one path: a substitution and a deletion can land on
    // it, and two active languages can both contain it. Comparing the text rather than the
    // (pack, index) pair is what catches the cross-language case, which is the one a bilingual
    // user hits on every second word.
    Candidate* const items = heap.data();
    for (int i = 0; i < heap.size(); ++i) {
        uint32_t existingLength = 0;
        const char* const existing = candidateText(items[i], &existingLength);
        if (existing == nullptr || existingLength != textLength ||
            std::memcmp(existing, text, textLength) != 0) {
            continue;
        }
        if (candidate.score > items[i].score) {
            heap.replaceAt(i, candidate);
        }
        return;
    }
    heap.offer(candidate);
}

int Engine::collectEndpoints(const LanguagePack& pack, const uint32_t* folded, int foldedLength,
                             float maxCost, Endpoint* out, int maxOut) {
    struct Frame {
        int32_t node;
        int16_t inputPos;
        int16_t runAhead;
        float cost;
    };

    Frame* const stack = arena_.allocateArray<Frame>(512);
    if (stack == nullptr) {
        return 0;
    }
    int stackSize = 0;
    int written = 0;

    stack[stackSize++] = Frame{pack.trie().root(), 0, 0, 0.0f};

    const PackedTrie& trie = pack.trie();
    const bool fuzzy = maxCost > 0.0f && geometry_.isSet();

    while (stackSize > 0) {
        if (visitBudget_ <= 0) {
            break;
        }
        const Frame frame = stack[--stackSize];
        --visitBudget_;

        if (frame.inputPos >= foldedLength) {
            if (written < maxOut) {
                out[written++] = Endpoint{frame.node, frame.cost};
            } else {
                // Full: keep the cheapest set seen rather than the first set seen.
                int worst = 0;
                for (int i = 1; i < written; ++i) {
                    if (out[i].cost > out[worst].cost) {
                        worst = i;
                    }
                }
                if (frame.cost < out[worst].cost) {
                    out[worst] = Endpoint{frame.node, frame.cost};
                }
            }
            continue;
        }

        const uint32_t typed = folded[frame.inputPos];

        // Exact match first and always, geometry or not. This is the path that has to work
        // when the keyboard has not been measured yet, and it is the one that carries the
        // overwhelming majority of real input.
        const int exactSymbol = trie.symbolFor(typed);
        if (exactSymbol > 0) {
            const int32_t child = trie.walk(frame.node, exactSymbol);
            if (child >= 0 && stackSize < 512) {
                stack[stackSize++] =
                    Frame{child, static_cast<int16_t>(frame.inputPos + 1), 0, frame.cost};
            }
        }

        if (!fuzzy) {
            continue;
        }

        const uint32_t* neighbourCodes = nullptr;
        const float* neighbourCosts = nullptr;
        const int neighbourCount = geometry_.neighbours(typed, &neighbourCodes, &neighbourCosts);

        // Substitution: the finger landed one key over. Slot 0 is the exact match, already
        // pushed above.
        for (int i = 1; i < neighbourCount; ++i) {
            const float cost = frame.cost + neighbourCosts[i];
            if (cost > maxCost) {
                continue;  // the ring is sorted, but staying explicit costs one comparison
            }
            const int symbol = trie.symbolFor(neighbourCodes[i]);
            if (symbol <= 0) {
                continue;
            }
            const int32_t child = trie.walk(frame.node, symbol);
            if (child >= 0 && stackSize < 512) {
                stack[stackSize++] =
                    Frame{child, static_cast<int16_t>(frame.inputPos + 1), 0, cost};
            }
        }

        // Deletion: a character was typed that the word does not have. Consume it, stay put.
        if (frame.cost + kDeleteCost <= maxCost && stackSize < 512) {
            stack[stackSize++] = Frame{frame.node, static_cast<int16_t>(frame.inputPos + 1), 0,
                                       frame.cost + kDeleteCost};
        }

        // Insertion: a character of the word was missed. Advance the trie without consuming
        // input, bounded by runAhead so this cannot descend forever.
        if (frame.runAhead < kMaxRunAhead && frame.cost + kInsertCost <= maxCost) {
            for (int i = 0; i < neighbourCount && stackSize < 512; ++i) {
                const int symbol = trie.symbolFor(neighbourCodes[i]);
                if (symbol <= 0) {
                    continue;
                }
                const int32_t child = trie.walk(frame.node, symbol);
                if (child >= 0) {
                    stack[stackSize++] = Frame{child, frame.inputPos,
                                               static_cast<int16_t>(frame.runAhead + 1),
                                               frame.cost + kInsertCost};
                }
            }
        }

        // Transposition: two adjacent characters in the wrong order. Common enough on a phone
        // that decomposing it into a deletion plus an insertion mis-prices it.
        if (frame.inputPos + 1 < foldedLength && frame.cost + kTransposeCost <= maxCost) {
            const int firstSymbol = trie.symbolFor(folded[frame.inputPos + 1]);
            const int secondSymbol = trie.symbolFor(typed);
            if (firstSymbol > 0 && secondSymbol > 0) {
                const int32_t middle = trie.walk(frame.node, firstSymbol);
                if (middle >= 0) {
                    const int32_t child = trie.walk(middle, secondSymbol);
                    if (child >= 0 && stackSize < 512) {
                        stack[stackSize++] = Frame{child,
                                                   static_cast<int16_t>(frame.inputPos + 2), 0,
                                                   frame.cost + kTransposeCost};
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
    const float editComponent = -kEditPenalty * endpoint.cost;

    while (stackSize > 0) {
        if (visitBudget_ <= 0) {
            return;
        }
        const Frame frame = stack[--stackSize];
        --visitBudget_;

        const int32_t wordIndex = trie.terminalWordIndex(frame.node);
        if (wordIndex >= 0) {
            const float lengthPenalty = kCompletionPenalty * static_cast<float>(frame.depth);
            float score = weightLog + contextLogProb(packIndex,
                                                     static_cast<uint32_t>(wordIndex)) +
                          editComponent - lengthPenalty;
            // The user boost costs a fold and a walk of the personal trie, so it is only paid
            // when it could change the outcome: if even the maximum boost cannot reach the
            // heap's current floor, the answer is already known.
            if (score + kMaxUserBoost > heap.worstScore()) {
                uint32_t textLength = 0;
                const char* const text = trie.wordText(static_cast<uint32_t>(wordIndex),
                                                       &textLength);
                if (text != nullptr && textLength != 0) {
                    score += userBoostFor(text, textLength);
                    offerCandidate(heap, Candidate{packIndex, wordIndex, score}, text,
                                   textLength);
                }
            }
        }

        // Enumerating children in a double array means probing every alphabet symbol. Forty-odd
        // probes into two arrays is cheap and predictable; it is the price the structure
        // charges for its constant-time transitions, and it is bounded here by the visit budget
        // rather than by the size of the subtree.
        if (frame.depth >= 12) {
            continue;  // completions this long are noise, not help
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

    // Alphabet pruning, before anything else. The cheapest possible answer to "could this
    // language contain this word at all": typing Cyrillic never walks the Romanian trie.
    //
    // One character is allowed through when fuzzy matching is on, and that tolerance is not a
    // softening of the rule -- it is the rule being correct. A single character outside the
    // alphabet inside an otherwise-matching word is a finger slip, which is exactly what the
    // substitution and deletion operators below exist to undo. Refusing the pack on it would
    // mean the one situation the corrector was written for is the one where it never runs.
    // A word in a genuinely different script fails many characters, not one, and is still
    // rejected here without a single trie access.
    const float maxCost = maxEditCostFor(foldedLength);
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
        // Rewinding between endpoints keeps the arena flat: the deepest it ever gets is one
        // endpoint array plus one descent stack, not one per endpoint.
        arena_.rewind(innerMark);
    }
    arena_.rewind(mark);
}

void Engine::searchNextWord(int packIndex, TopK<Candidate>& heap) {
    const LanguagePack& pack = packs_[packIndex];
    const float weightLog = std::log(normalisedWeight_[packIndex]);
    const int32_t* const frequent = pack.frequentWords();
    const int count = pack.frequentWordCount();

    // Nothing has been typed, so there is no prefix to walk and no way to reach the trie. The
    // candidates are the language's most frequent words, reranked by the n-gram against what
    // came before -- which is exactly a next-word prediction, restricted to a shortlist.
    //
    // The shortlist is the honest limitation: a successor whose unigram frequency is low but
    // whose bigram after this particular word is high cannot be reached. Fixing that properly
    // means a successor index in the pack format, which is a format change and belongs with the
    // work that can measure whether it is worth the bytes.
    for (int i = 0; i < count; ++i) {
        const uint32_t wordIndex = static_cast<uint32_t>(frequent[i]);
        float score = weightLog + contextLogProb(packIndex, wordIndex);
        if (score + kMaxUserBoost > heap.worstScore()) {
            uint32_t textLength = 0;
            const char* const text = pack.trie().wordText(wordIndex, &textLength);
            if (text != nullptr && textLength != 0) {
                score += userBoostFor(text, textLength);
                offerCandidate(heap, Candidate{packIndex, frequent[i], score}, text, textLength);
            }
        }
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

        // Fold and compare one character at a time, bailing on the first mismatch. Almost every
        // word in the shortlist fails on its first character, so the average cost here is one
        // UTF-8 decode, not a whole word.
        const char* cursor = text;
        const char* const end = text + textLength;
        int matched = 0;
        bool matches = true;
        while (matched < foldedLength) {
            uint32_t codePoint = 0;
            cursor = utf8Decode(cursor, end, &codePoint);
            if (cursor == nullptr || foldCodePoint(codePoint) != folded[matched]) {
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

        float score = weightLog + contextLogProb(packIndex, wordIndex) -
                      kCompletionPenalty * static_cast<float>(extra);
        if (score + kMaxUserBoost > heap.worstScore()) {
            score += userBoostFor(text, textLength);
            offerCandidate(heap, Candidate{packIndex, frequent[i], score}, text, textLength);
        }
    }
}

void Engine::searchUserModel(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap) {
    if (userModel_.size() == 0) {
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
        if (completions[i].count == 0) {
            continue;
        }
        // Anchored to the language scale rather than to the user model's own totals, then given
        // exactly the boost a word already in a dictionary would get. A word confirmed once
        // therefore ranks below the dictionary, and a word confirmed fifty times ranks above
        // most of it -- which is the behaviour, and it is bounded.
        const float score = kUserOnlyLogProb + userBoostForCount(completions[i].count);
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

int Engine::suggest(const char* composing, size_t composingLength, const char* previous1,
                    size_t previous1Length, const char* previous2, size_t previous2Length,
                    Candidate* out, int maxOut) {
    if (!created_ || out == nullptr || maxOut <= 0) {
        return 0;
    }
    arena_.reset();

    uint32_t folded[kMaxComposing];
    int foldedLength = 0;
    if (composing != nullptr && composingLength > 0) {
        foldedLength = foldUtf8(composing, composingLength, folded, kMaxComposing);
        // Malformed input, or a "word" longer than any word: not something to guess about.
        if (foldedLength < 0) {
            return 0;
        }
    }

    // Weights are normalised across the active packs so that scores coming out of models with
    // different vocabulary sizes, and therefore different normalisations, land on one scale
    // before they compete for the same sixteen slots.
    float weightSum = 0.0f;
    for (int i = 0; i < kMaxPacks; ++i) {
        normalisedWeight_[i] = 0.0f;
        if (packs_[i].isOpen() && packs_[i].active) {
            const float weight = (packs_[i].adaptiveWeight < kMinLanguageWeight)
                                     ? kMinLanguageWeight
                                     : packs_[i].adaptiveWeight;
            normalisedWeight_[i] = weight;
            weightSum += weight;
        }
    }
    if (weightSum <= 0.0f) {
        // No language active: the personal dictionary is still the user's own data and is
        // still worth offering.
        weightSum = 1.0f;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        if (normalisedWeight_[i] > 0.0f) {
            normalisedWeight_[i] /= weightSum;
        }
    }

    resolveContext(previous1, previous1Length, previous2, previous2Length);
    visitBudget_ = nodeVisitBudgetFor(foldedLength);

    TopK<Candidate> heap;
    heap.reset(heapStorage_, kMaxCandidates);

    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        if (foldedLength == 0) {
            searchNextWord(i, heap);
        } else {
            // Shortlist first. It is cheap, it is bounded, and running it before the descent
            // raises the heap's floor -- which then lets the descent reject most of what it
            // finds on one comparison instead of scoring it.
            searchFrequentWithPrefix(i, folded, foldedLength, heap);
            searchPack(i, folded, foldedLength, heap);
        }
    }
    if (foldedLength > 0) {
        searchUserModel(folded, foldedLength, heap);
    }

    const int drained = heap.drainSorted(drainBuffer_, kMaxCandidates);
    const int written = (drained < maxOut) ? drained : maxOut;
    for (int i = 0; i < written; ++i) {
        out[i] = drainBuffer_[i];
    }
    return written;
}

void Engine::learn(const char* word, size_t wordLength, const char* previous1,
                   size_t previous1Length, const char* previous2, size_t previous2Length) {
    if (!created_ || word == nullptr || wordLength == 0) {
        return;
    }
    (void)previous1;
    (void)previous1Length;
    (void)previous2;
    (void)previous2Length;

    userModel_.learn(word, wordLength);

    // Language weight adaptation. Which packs contain the confirmed word is the only signal
    // available without asking the user which language they are writing in, and it is a good
    // one: the word they actually chose came from somewhere.
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, wordLength, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return;
    }
    bool known[kMaxPacks] = {};
    bool anyKnown = false;
    for (int i = 0; i < kMaxPacks; ++i) {
        if (packs_[i].isOpen() && packs_[i].active) {
            known[i] = packs_[i].trie().lookupFolded(folded, foldedLength) >= 0;
            anyKnown = anyKnown || known[i];
        }
    }
    if (!anyKnown) {
        // A word no active language knows says nothing about which language is being written;
        // it goes into the personal dictionary above and changes no weights.
        return;
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        const float target = known[i] ? 1.0f : 0.0f;
        float updated = packs_[i].adaptiveWeight +
                        kWeightAdaptRate * (target * packs_[i].configuredWeight -
                                            packs_[i].adaptiveWeight);
        const float floorValue = kMinLanguageWeight * packs_[i].configuredWeight;
        if (updated < floorValue) {
            updated = floorValue;
        }
        if (updated > packs_[i].configuredWeight) {
            updated = packs_[i].configuredWeight;
        }
        packs_[i].adaptiveWeight = updated;
    }
}

void Engine::loadUserWords(const char* const* words, const size_t* lengths,
                           const int32_t* counts, int count) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoad(words, lengths, counts, count);
}

bool Engine::snapshotUserModel(const char* path) {
    return created_ && userModel_.snapshot(path);
}

const char* Engine::candidateText(const Candidate& candidate, uint32_t* lengthOut) const {
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

}  // namespace borderkeys
