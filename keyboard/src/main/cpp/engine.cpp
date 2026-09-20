// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#include "engine.hpp"

#include "gesture/shark2_decoder.hpp"
#ifdef BORDERKEYS_NEURAL_SWIPE
#include "gesture/tcn_decoder.hpp"
#endif


#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>

namespace borderkeys {
namespace {

// Scoring constants, all in natural log units so they add to log-probabilities directly.

// What one key width of finger error costs.
//
// High enough that no realistic frequency gap ever buys an extra edit. "jicat" reaching "cât"
// (delete two characters) ahead of "jucat" (substitute one) is the case that set this: "cât" was
// only about five times more frequent than "jucat" and still won, because the old value (2.3)
// priced a whole extra edit at less than an order of magnitude of probability -- cheap enough
// for an unremarkable word to outbid a much closer match. That is backwards. Two edits is a
// claim that the user made two mistakes; it should lose to a one-edit reading of the same typing
// almost regardless of which word is more common, the same way a real, correctly spelled word
// already never loses to a frequent correction of it (see kCorrectionSurcharge below).
//
// Calibrated against the worst case actually shipped: the least common word in a bundled pack
// against the most common. This is deliberately not a fixed number in this comment any more --
// it was 320,000 to one when this constant was last tuned, then read as roughly 640,000 to one
// after the dictionaries were regrown twice without anyone coming back to update the figure
// here, and it will move again the next time a pack grows or its dedup changes (see
// tools/build_dict.py's frequency-summing dedup, added alongside a Romanian dictionary rebuild).
// What has to stay true, checked by hand against whatever the packs' current worst ratio is
// rather than asserted in code (the ratio is data, not a compile-time constant): one transposed
// character (kTransposeCost, the cheapest possible edit) is the smallest gap between two
// candidates that differ by one edit, so kEditPenalty * kTransposeCost has to clear ln(worst
// ratio) with room to spare for a pack larger or more skewed than anything bundled today --
// comfortably true at kTransposeCost's current 0.80 against every pack shipped as of this
// comment, with roughly a two-times margin even at the least favourable (English).
//
// Frequency still decides between candidates at the *same* cost -- that part of a suggestion
// strip is unchanged, and completions (cost zero) are untouched entirely, per kCorrectionSurcharge.
constexpr float kEditPenalty = 40.0f;

// Insertions and deletions in key-width units. Slightly below a full neighbour substitution,
// because a dropped or doubled letter is a more common slip than hitting the wrong key.
constexpr float kInsertCost = 0.85f;

// A missing apostrophe, which is a convention dropped rather than a key missed -- see the
// insertion branch in the trie walk for the measurements behind this. Small and not zero: at
// kEditPenalty 40 this is 0.8 points of score, enough that a word typed exactly as it is spelled
// still outranks a contraction reached by inserting one, and little enough that a genuinely more
// common contraction wins on its own frequency.
constexpr float kApostropheInsertCost = 0.02f;
constexpr float kDeleteCost = 0.85f;
// Transposition is one gesture gone out of order rather than two independent errors, so it
// costs a little less than the insertion or deletion it would otherwise be decomposed into --
// deliberately a little rather than a lot now that kEditPenalty is 40: at the old value (0.65,
// noticeably cheaper than 0.85) this and kInsertCost priced two categories of equally common
// typing slips as though one were roughly a thousand times more likely than the other, which is
// what let "acm" reach "cam" (one transposition) so cheaply that it crowded every insertion-based
// candidate out of the sixteen kept, including "acum" itself -- a real word one inserted letter
// away. A gap this small still keeps transposition the tie-breaker it was always meant to be
// without letting that tie-break decide a candidate's fate on its own.
constexpr float kTransposeCost = 0.80f;

// Each character a completion adds beyond what was typed.
//
// Was 0.12, on the reasoning that the unigram probability already prefers common words and this
// only had to break ties towards the shorter one. It did not: at that price a longer, commoner
// word beat the word actually typed, and the typed word was not merely outranked but pushed out
// of the sixteen kept entirely. Typing "car" offered "care", "cartea", "carol" and "carmen"
// with "car" nowhere in the list, though it is in both dictionaries; "inform" ranked sixth
// behind its own continuations.
//
// 0.5 is measured rather than guessed. Against native-tests/data/suggest_en.tsv, first-place
// accuracy goes from 61.5% to 68.8% and mean rank from 1.87 to 1.67, with no case regressing.
// The ceiling is real and the corpus shows it: past about 1.0 the penalty starts costing the
// half-typed words completion exists for, and "information" typed in full falls to third.
constexpr float kCompletionPenalty = 0.5f;

// How far below a language's commonest word a correction target may sit, in nats.
//
// Autocorrect replaces something a person wrote, so the word it replaces it with has to be one
// they might plausibly have meant. Without a floor the corrections list will offer whatever it
// reached: for "car" it proposes "cr", a deletion, which only the known-word guard then refuses.
//
// Nine nats is 3.9 on the Zipf scale, and measured against the English pack that separates the
// two cleanly -- every real target sits above it ("occurred" is the lowest at 4.84, "receive"
// 5.09, "the" 7.81) and the junk below ("cr" 3.78, "eh" 3.32). Expressed against the pack's own
// commonest word rather than as an absolute, so a smaller corpus does not silently raise the bar
// on itself.
constexpr float kCorrectionFrequencyFloor = 9.0f;

// How many continuations of what was typed may hold strip slots at once. See the filter at the
// end of suggest() for why a cap and not a price: the continuations are correctly scored, there
// are simply more of them than the strip has room for, and they arrive as a block that pushes
// every correction out of sight.
constexpr int kMaxShownCompletions = 4;

// How far past the typed letters a *completion* may go and still count as an answer to "what
// did you mean", rather than only to "what are you writing".
//
// The corrections heap was built to hold nothing but edits, on the reasoning that a word merely
// carrying on from the typed letters is not a guess at what was meant. That is true of "teh"
// reaching "tehran" and false of "believ" reaching "believe", and the difference is length: one
// is a different word, the other is the same word with its last letter not typed yet.
//
// Measured rather than argued, and swept rather than picked. Two corpora of 200 words each,
// generated from the pack's own most frequent words so neither could be chosen to flatter the
// answer -- `native-tests/data/autocorrect_{midword,typo}_en.tsv`, read by
// `suggest_eval --autocorrect`, which asks what the space bar commits:
//
//               mid-word   typo      (mid-word = last letter not yet typed;
//   excluded      12.0%    100.0%     typo = two middle letters transposed)
//   depth <= 1    98.5%    100.0%
//   depth <= 2    88.0%    100.0%
//   depth <= 3    88.0%    100.0%
//   depth <= 4    85.5%    100.0%
//
// One is the measured optimum and not a round number chosen for looking like one: past it the
// longer continuations start outbidding the single missing letter, which is the same failure in
// the other direction -- "believed" taking the place of "believe". The typo column is what the
// exclusion existed to protect and it never moves, so the fix costs nothing it was buying.
//
// Before this, a word someone was in the middle of typing had a *different word* committed over
// it two times in three. That is not an edge case, and it was reported from a device long before
// this measurement existed: "believ" committed "belief", which the same search ranked seventh
// and sixty points worse than "believe".
constexpr int kMaxCorrectionCompletion = 1;

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

// A flat surcharge for having needed a correction at all, on top of the per-edit cost.
//
// The per-edit cost alone prices an edit against a probability ratio, and loses when the ratio
// is large. In Romanian the case is not hypothetical: "si" is roughly eighty times more
// frequent than "stiu", so deleting two characters to reach it costs 2 * 0.85 * 2.3 = 3.9
// against a gap of about 4.4, and the keyboard offered "si" first to someone who had typed
// "stiu" correctly. The leading entry of the strip was not the user's word.
//
// This is charged once, to any candidate reached with cost above zero, so it changes how
// corrections rank against words that needed none -- and nothing else. Completions are
// untouched: "carte" after "car" costs no edits, so it still competes with "car" on frequency
// alone, which is what a suggestion strip is for. Corrections still appear; they just stop
// displacing a word that was spelled correctly.
//
// Three units of log-probability is about twenty to one. By default nothing is applied
// automatically, which is most of why it is safe to be firm: ranking the typed word first costs
// nothing, since the correction is still one tap away. That stops being true when
// autoCorrectOnSpace is on -- see AutoCorrection.kt -- which commits the top suggestion with no
// tap, for a word the dictionary has simply never seen (a name, a neologism) rather than a
// known one, since only known words get that protection. This surcharge does not change that
// trade-off; it only decides how firmly a needed correction competes against words that needed
// none, and the existing controls on the auto-apply itself (off by default, one keystroke to
// revert, the correctionStrictness multiplier) are what actually mitigate it.
//
// Note that "no correction needed" is measured after folding, so typing "totusi" reaches
// "totuși" at zero cost. That is the point. On a Romanian keyboard a diacritic-free spelling
// that counted as a correction would put every accented word behind whatever short word happens
// to be more frequent.
constexpr float kCorrectionSurcharge = 3.0f;

// The range setCorrectionStrictness() clamps to. Below the low end an edit costs so little that
// the correction strip starts second-guessing words that were spelled correctly; above the high
// end almost nothing outbids a typo left exactly as typed. 1.0 is kEditPenalty and
// kCorrectionSurcharge exactly as calibrated above -- this is a multiplier on both of them
// together, not a third constant with its own reasoning.
constexpr float kMinCorrectionStrictness = 0.5f;
constexpr float kMaxCorrectionStrictness = 2.0f;

// kMaxUserBoost happens to equal kCorrectionSurcharge exactly (both 3.0), which is fine only
// because no edit this engine prices ever gets cheap enough for that coincidence to matter --
// checked here, at compile time, against the smallest cost either an edit-distance operation or
// a real key substitution (KeyGeometry::kMinSubstitutionCost, see its own comment) can produce,
// and against the most lenient end of the correction-strictness range a user can dial in. If a
// future change to any of these five numbers lets a heavily-used personal word reached by one
// cheap edit tie or beat a correctly-typed real word, this fails the build instead of waiting
// for another live report.
static_assert(
    kMinCorrectionStrictness *
            (kEditPenalty * KeyGeometry::kMinSubstitutionCost + kCorrectionSurcharge) >
        kMaxUserBoost,
    "the correction-vs-personal-word safety margin has eroded -- see the comment above");

// The log-probability a word gets when the personal dictionary is the only place it exists.
// Scores from the user model cannot be derived from its own totals: a word confirmed forty
// times out of fifty is three quarters of *that* distribution, which on a language pack's scale
// would be a more likely word than "the". Anchoring to a fixed, deliberately pessimistic
// language-scale value and adding the same bounded boost a known word would get keeps the two
// sources comparable -- and keeps a word typed once from outranking the dictionary.
constexpr float kUserOnlyLogProb = -8.0f;

// How much evidence a learned word needs before it is offered at all, in effective counts (the
// raw count times the learning speed). Two, so the balanced default asks for a second use.
//
// Not a fourth constant to tune against the other three: it is the same number the settings
// screen already describes. At the cautious setting's 0.35 multiplier it is reached at six
// repetitions, which is the "about six" that explanation has always claimed, and at the
// immediate setting's 3.0 the very first use clears it, which is what "the first time counts"
// means. The default sits between them at two, and the word is still learned and still listed
// in the personal dictionary the whole time -- this governs only what reaches the strip.
constexpr float kMinPersonalEvidence = 2.0f;

/**
 * Smoothing for a personal pair, in observations.
 *
 * A phrase written once is not a certainty, and `count / total` would say it is: one "vreau să"
 * out of one "vreau" is not evidence that "să" always follows. Dividing by `total + prior`
 * instead makes the first observation worth about a fifth of the way there and each repetition
 * worth more, which is the shape the evidence actually has.
 *
 * Four rather than one because this competes against a language model built from a corpus. A
 * pair has to be a habit before it displaces what the language says, and a habit is what this
 * is for.
 */
constexpr float kUserBigramPrior = 4.0f;

/** The most a personal pair may add to a word that was already being suggested. */
constexpr float kMaxUserBigramBoost = 2.5f;

/**
 * How far a phrase this person actually writes may outrank what the corpus says follows.
 *
 * Without it the two estimates compete on the smoothed share alone, and a strong corpus bigram
 * wins for ever: the pack says "să" follows "trebuie" six times in ten, and a user who has
 * written "trebuie mult" six times still reads "să" first. That is the wrong answer for a
 * keyboard that is supposed to be theirs. A corpus says what people write; a pair here says what
 * *this* person writes, and about this person it is the better evidence.
 *
 * Not a flat preference, though, because one observation is not evidence of a habit. The
 * preference is scaled by a confidence that starts near zero and saturates, so:
 *
 *   written once      +0.4   -- stays behind a strong corpus bigram, where it belongs
 *   written 3 times   +0.8   -- takes the lead
 *   written 20 times  +1.3   -- and keeps it
 *
 * The ceiling matters as much as the growth. A phrase written a thousand times must not be able
 * to bury every alternative, because people do change what they write.
 */
constexpr float kUserChainPreference = 1.5f;

/** Observations at which the preference above reaches half its ceiling. */
constexpr float kUserChainHalfLife = 3.0f;

/**
 * How much more evidence the second word of a two-word suggestion needs than the first.
 *
 * A single-word suggestion that is wrong costs a glance. A two-word one that is wrong costs the
 * same glance and the suspicion that the keyboard is making things up, and it takes twice as
 * long to undo. So the second link is smoothed against a larger prior than the first.
 *
 * Measured on a chain written over and over: a single-word suggestion leads after two
 * repetitions, a two-word one appears after four. Twice the evidence for twice the guess, which
 * is the relationship worth having and the reason this is a factor rather than a second
 * hand-tuned threshold.
 */
constexpr float kPhraseSecondLinkFactor = 1.5f;

/**
 * The share of its context a link must hold before it can be part of a phrase.
 *
 * Not a count: a word followed by one thing nine times in ten is a habit, and the same word
 * followed by nine different things is not, however many times each was written.
 */
constexpr float kPhraseMinShare = 0.34f;

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

// The ceiling for the second pass, run only when the first found nothing at all.
//
// A word far enough from every entry to fail the normal ceiling is exactly the word whose
// author most needs a suggestion -- and an empty strip tells them nothing about why. Wide
// enough to reach a word four slips away, which is well past what a finger does by accident,
// and paid for only on the requests that would otherwise have shown nothing.
constexpr float kFallbackEditCost = 4.2f;

// How the evidence for "which language is being written" ages.
//
// Each completed word multiplies every language's evidence by this and adds one to the
// languages that contain it, so the count is a weighted sum over roughly the last seven words.
// Long enough not to swing on one borrowed noun, short enough that switching language mid
// conversation is followed within a sentence.
constexpr float kLanguageEvidenceDecay = 0.85f;

// The gap in unigram log-probability that earns a word its full point of evidence: one order of
// magnitude, in nats, because the packs store natural logs (see build_dict.py's
// quantise_log_prob). A word ten times commoner in one language than in every other is as clear
// a signal about what is being written as a word only that language has at all. Below that the
// award is scaled down in proportion, and a word both languages know equally contributes
// nothing, which is what the old exclusivity rule got right and is kept here.
constexpr float kLanguageEvidenceFullGap = 2.302585f;

// The share of the evidence one language must hold before it counts as the one being written.
//
// Not a setting: it is a statement about how one-sided a measurement has to be to act on, which
// is not something anyone can answer by trying values. How *much* evidence to wait for is the
// question a person can actually have an opinion about, and that one comes from the settings as
// languageLockMinimum_.
constexpr float kLanguageDominanceShare = 0.7f;

// How much the part-of-speech transition counts where the n-gram model has nothing.
//
// 0.75 came out of a sweep on held-out text: below it the term barely moves the ranking, above
// it grammatically plausible but rare words start displacing frequent ones and the fifth slot
// suffers for no further gain in the first. The trade is deliberate -- the first chip is what
// people tap, and nobody reads the fifth.
constexpr float kGrammarWeight = 0.75f;

// The reserved bigram context for "a sentence began here". Mirrors tools/build_dict.py; it is
// outside any possible word index, and the hash stores index+1, so it stays inside 32 bits.
constexpr uint32_t kSentenceStartIndex = 0xFFFFFFFEu;


// The scale the pack quantises log probabilities on, mirrored from tools/build_pos.py.
constexpr float kLogProbScale = 10.0f;

// Once a language is detected, the other dictionaries are not consulted at all.
//
// A penalty was tried first and does not work, for a reason that is obvious afterwards: the
// most frequent words of any language outscore mid-frequency words of another by far more than
// any penalty one would dare apply. Writing five English words and then "car" still produced
// "a", "ar", "cu" -- Romanian function words winning on raw frequency from three and a half
// nats down. Frequency is the wrong axis to fight on, so the search does not enter that pack.
//
// The user model is deliberately outside this rule. A phrase someone actually writes is
// evidence about *them*, and someone who drops one English word into every Romanian sentence
// has said what they want more clearly than any detector can contradict.

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
        // languageTag is NUL padded and NUL terminated by the format, and bkdValidateHeader has
        // already established that. Copied whole rather than with strncpy so a header that
        // somehow lost its terminator cannot walk off the end here.
        std::memcpy(out->tag, header.languageTag, sizeof(out->tag));
        out->tag[sizeof(out->tag) - 1] = '\0';
        out->formatVersion = header.formatVersion;
        out->wordCount = header.wordCount;
        out->fileBytes = header.fileBytes;
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

    // Grammar, if this pack was built with a treebank. Both sections are empty when it was not,
    // and posTagCount is then zero, which is what every read below tests against -- a pack with
    // no grammar scores exactly as packs did before the sections existed.
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
    wordTags_ = nullptr;
    posTransitions_ = nullptr;
    posTagCount_ = 0;
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

    // Tier A, always built: geometric, and every flavor's decoder until tier B is both compiled
    // in (BORDERKEYS_NEURAL_SWIPE, `plus` only) and switched on by the user.
    //
    // An earlier neural attempt was rejected outright -- recorded in docs/licensing.md section
    // 2.3 -- because the published implementation depended on ExecuTorch and CMake 3.29, putting
    // PyTorch's runtime inside the module with the tightest latency budget in the application.
    // The GestureScorer/GestureDecoder split is what made a second, dependency-free attempt
    // (tools/swipe_model/, gesture/tcn_*.{cpp,hpp}) possible without touching this one: a second
    // decoder is a second implementation of one interface, not a rewrite of the first.
    gestureDecoder_.reset(new (std::nothrow) Shark2Decoder(*this));
    if (!gestureDecoder_) {
        arena_.release();
        return false;
    }

    // Tier B is deliberately *not* built here. It holds its weights by value -- about two and a
    // half megabytes -- and the "experimental swipe model" preference is off by default, so an
    // engine that constructed it at startup spent that memory on a feature most people never
    // turn on. loadSwipeWeights() builds it when the preference asks for it and
    // setSwipeModelEnabled(false) frees it again; every read of neuralDecoder_ already null
    // checks, so "not built yet" and "switched off" are the same state to the decode path.

    created_ = true;
    return true;
}

void Engine::destroy() {
    gestureDecoder_.reset();
#ifdef BORDERKEYS_NEURAL_SWIPE
    neuralDecoder_.reset();
    neuralEnabled_ = false;
#endif
    for (LanguagePack& pack : packs_) {
        pack.close();
    }
    userModel_.clear();
    arena_.release();
    created_ = false;
}

bool Engine::loadSwipeWeights(const uint8_t* data, size_t length) {
#ifdef BORDERKEYS_NEURAL_SWIPE
    if (!created_) {
        return false;
    }
    // Built on demand rather than at engine creation: this is the first moment anything is
    // known to want tier B. A construction failure is not fatal -- tier A already exists and
    // tier B is optional by design -- so it reports false and the geometric decoder carries on.
    if (!neuralDecoder_) {
        neuralDecoder_.reset(new (std::nothrow) TcnDecoder(*this));
        if (!neuralDecoder_) {
            return false;
        }
    }
    if (!neuralDecoder_->loadWeights(data, length)) {
        // Nothing half-loaded is worth keeping: TcnDecoder::loadWeights leaves the encoder
        // without weights on failure, and holding the empty two and a half megabytes would be
        // the cost of tier B with none of it.
        neuralDecoder_.reset();
        return false;
    }
    return true;
#else
    (void)data;
    (void)length;
    return false;
#endif
}

void Engine::setSwipeModelEnabled(bool enabled) {
#ifdef BORDERKEYS_NEURAL_SWIPE
    neuralEnabled_ = enabled;
    if (!enabled) {
        // The weights go with it. They are reloaded from the asset the next time the preference
        // is turned back on, which is the whole point: off should cost nothing.
        neuralDecoder_.reset();
    }
#else
    (void)enabled;
#endif
}

bool Engine::warmSwipeModel() {
#ifdef BORDERKEYS_NEURAL_SWIPE
    // Two distinct keys or there is no stroke to trace: a layout with one key would collapse to
    // a point, which exercises none of the path this is here to warm.
    if (!created_ || !neuralDecoder_ || !neuralDecoder_->hasWeights() || !geometry_.isSet() ||
        geometry_.keyCount() < 2) {
        return false;
    }
    // A straight stroke between the first and last key of the current layout. What it spells is
    // irrelevant -- the point is that every buffer the decoder allocates lazily, every page of
    // the weights, and the whole forward pass have all been touched once before a finger is on
    // the glass. Two keys far apart make the resampler and the key-sequence walk do real work
    // rather than collapsing to a point.
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
        // Ten milliseconds a sample, the rate a real gesture arrives at.
        ts[i] = static_cast<int64_t>(i) * 10;
    }

    // Straight to the decoder, not through decodeGesture: this must work before
    // setSwipeModelEnabled(true) has been seen, and nothing here should reach the candidate
    // heap or the context the next real request will set up for itself.
    Candidate discarded[kMaxCandidates];
    (void)neuralDecoder_->decode(xs, ys, ts, kWarmPoints, discarded, kMaxCandidates);
    return true;
#else
    return false;
#endif
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
    // A pack that is open and no longer named is closed, not merely deactivated. The slot it
    // held is what a pack switched on in its place needs -- loadLanguage replaces an open tag in
    // place but takes a free slot for a new one, and a pack that was only deactivated kept its
    // slot for the life of the process, so the fourth language a user switched off and the fifth
    // they switched on could never be loaded at all. The per-slot context and evidence go with
    // it: the next pack in the slot is a different language, and an index into a trie that is
    // no longer mapped is not something to leave lying around for it.
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
                dominantPack_ = -1;
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
        // Adapting from the newly configured weight rather than keeping the old running value:
        // the user has just said what they want, and last week's acceptance rate is not an
        // argument against it.
        packs_[slot].adaptiveWeight = weight;
    }
    // Slots have just been closed, opened and switched on or off, so whichever one the preferred
    // tag named a moment ago is not necessarily the one it names now -- which is exactly why the
    // preference is stored as a tag and resolved here rather than kept as an index.
    resolvePreferredPack();
}

bool Engine::setKeyGeometry(const int32_t* codes, const float* centersX, const float* centersY,
                            int count, float keyWidth, float keyHeight) {
    if (!geometry_.set(codes, centersX, centersY, count, keyWidth, keyHeight)) {
        return false;
    }
    // Every cached gesture template is a path through key centres that have just moved.
    if (gestureDecoder_) {
        gestureDecoder_->setLayout(geometry_);
    }
#ifdef BORDERKEYS_NEURAL_SWIPE
    if (neuralDecoder_) {
        neuralDecoder_->setLayout(geometry_);
    }
#endif
    return true;
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
    // Turning it off has to take effect on the next word, not on the next sentence: the
    // evidence already gathered would otherwise keep a language locked after the user said
    // they did not want that.
    if (minimumEvidence <= 0.0f) {
        dominantPack_ = -1;
    }
}

void Engine::resolvePreferredPack() {
    preferredPack_ = -1;
    if (preferredTag_[0] == '\0') {
        return;
    }
    // packIndexForTag answers "open", which is not enough: a pack can be open and switched off,
    // and restricting a search to one of those would return nothing at all.
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
    dominantPack_ = -1;
    // The de-duplication guard too: the first word of the new field must count, and it would be
    // swallowed if it happened to hash to whatever the last field ended on.
    lastObservedWord_ = 0;
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
    // The same word arrives on every keystroke of the word after it. Counting it once is the
    // difference between a window over words and a window over keystrokes, and only the first
    // is a measure of what language is being written.
    uint32_t hash = 2166136261u;
    for (int i = 0; i < length; ++i) {
        hash = (hash ^ folded[i]) * 16777619u;
    }
    if (hash == lastObservedWord_) {
        return;
    }
    lastObservedWord_ = hash;

    // How much better one pack knows this word than any other is the evidence. A word only one
    // pack knows at all is the strongest case of that, not a separate rule.
    //
    // Demanding exclusivity was the original rule and it starves. Even a perfectly filtered
    // list shares its short function words with its neighbours: "eu" and "ca" are both in the
    // English dictionary, as the lower-cased EU and CA, so "eu credeam ca suntem" offered two
    // countable words out of four and stalled at 1.72 against Balanced's 1.8 -- four
    // unmistakably Romanian words that left the keyboard undecided and its strip half English.
    //
    // The ratio thrown away there is not marginal. "ca" is 2.52 orders of magnitude commoner in
    // Romanian than in English and "eu" 0.83, and the old rule scored both as exactly zero
    // because English happened to hold the string at all. Scoring the gap decides that sentence
    // on its third word instead of never.
    //
    // A word no pack knows -- a name, a typo -- is still not evidence, and neither is one both
    // know equally. Both cases age the window, so a language that has stopped being written
    // stops being detected.
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

    float total = 0.0f;
    float best = 0.0f;
    int bestIndex = -1;
    for (int i = 0; i < kMaxPacks; ++i) {
        languageEvidence_[i] *= kLanguageEvidenceDecay;
        if (i == owner) {
            languageEvidence_[i] += award;
        }
        if (packs_[i].isOpen() && packs_[i].active) {
            total += languageEvidence_[i];
            if (languageEvidence_[i] > best) {
                best = languageEvidence_[i];
                bestIndex = i;
            }
        }
    }
    // At or below zero the user has asked for every dictionary to stay in play, so no amount
    // of evidence locks anything.
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

float Engine::userBoost(const char* text, uint32_t length) const {
    return userBoostFor(text, length);
}

void Engine::refreshWeights() {
    // Scores from packs with different vocabulary sizes carry different normalisations, so they
    // are brought onto one scale before competing for the same sixteen slots.
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
    // Switches the whole request between tiers rather than blending them: the two decoders are
    // scored on different scales (see normaliseGestureScores) and were never designed to have
    // their raw candidates merged. Falls back to tier A whenever tier B has no weights loaded
    // yet, not just when the preference is off, so a request arriving in the window between
    // engine creation and the async weights load still gets an answer.
    if (neuralEnabled_ && neuralDecoder_ && neuralDecoder_->hasWeights()) {
        decoder = neuralDecoder_.get();
    }
#endif

    Candidate raw[kMaxCandidates];
    const int produced = decoder->decode(xs, ys, ts, count, raw, kMaxCandidates);
    if (produced <= 0) {
        return 0;
    }

    // Re-offered through the engine's own heap so that the same word reached from two active
    // languages collapses into one entry -- the decoder deduplicates by word index, which
    // cannot see that "the" in two packs is one suggestion to a reader.
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
    if (neuralEnabled_ && neuralDecoder_ && neuralDecoder_->hasWeights()) {
        return neuralDecoder_->name();
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
    userContext1_ = -1;
    userContext2_ = -1;
    if (previous1 != nullptr && previous1Length > 0) {
        length1 = foldUtf8(previous1, previous1Length, folded, kMaxComposing);
        if (personalModelEnabled_) {
            userContext1_ = userModel_.entryIndexFor(previous1, previous1Length);
        }
    }
    // Left at -1 for a private field: every personal-context path below -- the pair bonus, the
    // phrase and successor searches -- keys off these two, so this one gate is what keeps a
    // password field's context from being answered out of the owner's own history.
    if (personalModelEnabled_ && previous2 != nullptr && previous2Length > 0) {
        userContext2_ = userModel_.entryIndexFor(previous2, previous2Length);
    }
    for (int i = 0; i < kMaxPacks; ++i) {
        contextWord1_[i] = -1;
        contextTag1_[i] = LanguagePack::kNoPosTag;
        if (length1 > 0 && packs_[i].isOpen()) {
            contextWord1_[i] = packs_[i].trie().lookupFolded(folded, length1);
        }
        if (contextWord1_[i] >= 0) {
            hasContext1_ = true;
            // Resolved once per request rather than once per candidate: the previous word does
            // not change while sixteen candidates are being scored against it.
            contextTag1_[i] = packs_[i].posTag(contextWord1_[i]);
        }
    }

    // Which languages contain the word just written is the language signal, and it has already
    // been computed above for the n-grams. Reading it here costs nothing and needs no new call
    // from the Java side: every suggestion request carries the last word the user completed.
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
}

float Engine::contextLogProb(int packIndex, uint32_t wordIndex) const {
    const LanguagePack& pack = packs_[packIndex];
    const int32_t w1 = contextWord1_[packIndex];
    const int32_t w2 = contextWord2_[packIndex];

    // Nothing before the cursor: this is the first word of something. Raw frequency is a poor
    // answer -- the most common words in any language are the ones that join clauses, and
    // nobody opens a message with "de" or "and". The pack stores what sentences actually begin
    // with, under an index reserved for the purpose, so ask that instead.
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
    float score = unigram + kBackoffLogFactor * static_cast<float>(dropped);

    // Grammar, and only here.
    //
    // This branch is the one where the model has no evidence about this pair and is ranking by
    // raw frequency -- the same five words whatever came before. Everywhere above, a bigram
    // exists, and a bigram encodes the same grammar more precisely than a tag class can: it
    // knows what follows *this word*, not merely what follows its part of speech. Adding the
    // term there would be a worse signal arguing with a better one.
    //
    // Measured on held-out text, restricted to exactly these positions: the first suggestion
    // goes from 8.6% to 11.0% correct. See docs/pos-tagging.md.
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

float Engine::userBoostForCount(uint32_t count) const {
    if (count == 0) {
        return 0.0f;
    }
    // Diminishing and capped: the tenth time a word is chosen should matter far less than the
    // second, and no amount of repetition should let one word crowd out the dictionary. A
    // linear boost does both of the things this avoids.
    //
    // The learning speed multiplies the count rather than the boost, so it moves the curve
    // along rather than scaling its ceiling: a cautious setting needs more repetitions to reach
    // the same place, it does not put a lower place at the end of them.
    const float effective = static_cast<float>(count) * learningSpeed_;
    const float boost = 0.9f * std::log(1.0f + effective);
    return (boost > kMaxUserBoost) ? kMaxUserBoost : boost;
}

void Engine::loadUserTrigrams(const char* const* previous2, const size_t* previous2Lengths,
                              const char* const* previous1, const size_t* previous1Lengths,
                              const char* const* next, const size_t* nextLengths,
                              const int32_t* counts, int count) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoadTrigrams(previous2, previous2Lengths, previous1, previous1Lengths, next,
                                nextLengths, counts, count);
}

void Engine::setLearningSpeed(float speed) {
    // Clamped rather than trusted: this crosses JNI from a stored preference, and a zero or a
    // negative here would silently turn personalisation off or invert it.
    if (!(speed > 0.0f)) {
        learningSpeed_ = 1.0f;
        return;
    }
    learningSpeed_ = (speed > 8.0f) ? 8.0f : speed;
}

void Engine::setCorrectionStrictness(float scale) {
    if (!(scale > 0.0f)) {
        correctionStrictness_ = 1.0f;
        return;
    }
    correctionStrictness_ = std::clamp(scale, kMinCorrectionStrictness, kMaxCorrectionStrictness);
}

float Engine::userBoostFor(const char* text, uint32_t length) const {
    if (!personalModelEnabled_ || userModel_.size() == 0 || text == nullptr || length == 0) {
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

void Engine::offerScoredWord(TopK<Candidate>& heap, const PackedTrie& trie, int packIndex,
                             uint32_t wordIndex, float score) const {
    // The user boost costs a fold and a walk of the personal trie, so it is only paid when it
    // could change the outcome: if even the maximum boost cannot reach the heap's current
    // floor, the answer is already known -- and the wordText lookup below is skipped right
    // alongside it, not just the boost itself.
    if (score + kMaxUserBoost <= heap.worstScore()) {
        return;
    }
    uint32_t textLength = 0;
    const char* const text = trie.wordText(wordIndex, &textLength);
    if (text != nullptr && textLength != 0) {
        score += userBoostFor(text, textLength);
        offerCandidate(heap, Candidate{packIndex, static_cast<int32_t>(wordIndex), score}, text,
                       textLength);
    }
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
        //
        // Every alphabet symbol, not just the neighbours of the next key -- a missed character
        // is a keystroke that never happened at all, which has nothing to do with where the
        // finger was next. "Beause" reaching "because" needs a 'c' inserted before an 'a', and
        // 'c' is nowhere near 'a' on a keyboard; restricting the search to nearby keys meant
        // "because" was never even a candidate, not merely a losing one. This is the same
        // exhaustive-probe trick collectWords already uses for completions: trie.walk() on a
        // symbol with no edge from this node is one array read, not a branch, so the symbols
        // that lead nowhere from here cost nothing and only the ones the trie actually has push
        // a frame.
        //
        // The apostrophe is charged separately, and almost nothing, because leaving it out is
        // not a slip of the finger -- it is how people type. Priced as an ordinary insertion it
        // could never be recovered: one edit is kEditPenalty (40) times kInsertCost, some 34
        // points of score, while every word that merely *continues* the prefix costs nothing.
        // "cant" therefore reached "cantor", "canton" and "cantrell" and never "can't", and the
        // same for "dont", "im", "thats" and the other 4,500 apostrophe words in the pack.
        //
        // Cheap rather than free, so an exactly-typed spelling still wins a tie and the budget
        // check below still terminates. What decides instead is frequency, which was measured
        // on the shipped English pack against every contraction that collides with a real word
        // once its apostrophe is dropped: "cant" yields "can't" (3,713 against 108) and "dont"
        // yields "don't", while "its", "were", "well" and "ill" all stay the ordinary word they
        // already were. 1,629 such collisions exist and frequency settles them the right way.
        if (frame.runAhead < kMaxRunAhead && frame.cost + kApostropheInsertCost <= maxCost) {
            const int alphabetSize = trie.alphabetSize();
            const int apostrophe = trie.symbolFor(0x27u);
            for (int symbol = 1; symbol <= alphabetSize && stackSize < 512; ++symbol) {
                if (symbol == apostrophe || frame.cost + kInsertCost > maxCost) {
                    continue;
                }
                const int32_t child = trie.walk(frame.node, symbol);
                if (child >= 0) {
                    stack[stackSize++] = Frame{child, frame.inputPos,
                                               static_cast<int16_t>(frame.runAhead + 1),
                                               frame.cost + kInsertCost};
                }
            }
            // Pushed after the others so the stack pops it first. This walk is depth-first from
            // the top of the stack and bounded by visitBudget_, so the apostrophe -- symbol 1,
            // and therefore the first pushed and the last explored -- was being starved under
            // any prefix with many completions. That alone was the difference between "dont"
            // finding "don't" and "cant" never finding "can't": the cost was already right, the
            // budget simply ran out before the branch was reached.
            if (apostrophe > 0 && stackSize < 512) {
                const int32_t child = trie.walk(frame.node, apostrophe);
                if (child >= 0) {
                    stack[stackSize++] = Frame{child, frame.inputPos,
                                               static_cast<int16_t>(frame.runAhead + 1),
                                               frame.cost + kApostropheInsertCost};
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
    const float editComponent = endpoint.cost > 0.0f
        ? -correctionStrictness_ * (kEditPenalty * endpoint.cost + kCorrectionSurcharge)
        : 0.0f;
    // A corrected endpoint has already spent its one claim on the user's intent: this many
    // edits reach this word. Completing past it charges nothing extra beyond kCompletionPenalty
    // for however many further characters get guessed -- so a frequent long relative of the
    // corrected word (an inflected form, usually) routinely outscored the correction itself.
    // "rasuns" reached "raspuns" at a real edit cost, then kept walking for free and surfaced
    // "raspunsul" ahead of it; "saptea" reached "șapte" the same way and lost to "săptămânii".
    // Both are the same shape: an uncertain correction stacked with an unspoken guess about
    // what comes after it, outbidding the plain reading on frequency alone. A word typed clean
    // still completes -- "car" finding "carte" costs no edit at all, so this never touches it.
    const bool allowCompletion = endpoint.cost <= 0.0f;

    while (stackSize > 0) {
        if (visitBudget_ <= 0) {
            return;
        }
        const Frame frame = stack[--stackSize];
        --visitBudget_;

        const int32_t wordIndex = trie.terminalWordIndex(frame.node);
        if (wordIndex >= 0) {
            const float lengthPenalty = kCompletionPenalty * static_cast<float>(frame.depth);
            const float score = weightLog + contextLogProb(packIndex,
                                                            static_cast<uint32_t>(wordIndex)) +
                                editComponent - lengthPenalty;
            offerScoredWord(heap, trie, packIndex, static_cast<uint32_t>(wordIndex), score);
            // And again, into the corrections-only heap, when this word was reached by an edit
            // rather than by carrying the typed letters on. Same score, same moment, no second
            // search -- the only thing being kept is the distinction the merged heap discards.
            // Cheap because it is the minority branch and the heap holds four: a word every
            // completion outranks can still be the best *correction*, which is the whole point.
            // A completion counts too when it adds barely anything -- see
            // kMaxCorrectionCompletion. frame.depth is exactly how many characters past the
            // typed letters this word goes, so the two cases are told apart by one comparison.
            const bool shortCompletion = endpoint.cost <= 0.0f && frame.depth > 0 &&
                                         frame.depth <= kMaxCorrectionCompletion;
            if ((endpoint.cost > 0.0f || shortCompletion) &&
                plausibleCorrectionTarget(pack, static_cast<uint32_t>(wordIndex))) {
                offerScoredWord(correctionHeap_, trie, packIndex,
                                static_cast<uint32_t>(wordIndex), score);
            }
        }

        // Enumerating children in a double array means probing every alphabet symbol. Forty-odd
        // probes into two arrays is cheap and predictable; it is the price the structure
        // charges for its constant-time transitions, and it is bounded here by the visit budget
        // rather than by the size of the subtree.
        if (!allowCompletion || frame.depth >= 12) {
            continue;  // corrected endpoints stop at the word reached; long completions are noise
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
        if (static_cast<int32_t>(wordIndex) == contextWord1_[packIndex]) {
            // Not the word that was just written. With no bigram to go on this list is ordered
            // by raw frequency, so the most common word in the language is offered as its own
            // successor: "the" after "the", "și" after "și". It is never what was meant, and it
            // takes the slot a real prediction would have had.
            continue;
        }
        const float score = weightLog + contextLogProb(packIndex, wordIndex);
        offerScoredWord(heap, pack.trie(), packIndex, wordIndex, score);
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

float Engine::userBigramBonusFor(uint32_t entryIndex) const {
    if (userContext1_ < 0) {
        return 0.0f;
    }
    const uint32_t pair = userModel_.bigramCount(userContext1_, static_cast<int32_t>(entryIndex));
    if (pair == 0u) {
        return 0.0f;
    }
    const uint32_t total = userModel_.successorTotal(userContext1_);
    if (total == 0u) {
        return 0.0f;
    }
    // The same smoothed share used below, expressed as a bounded bonus rather than as a score:
    // here the word is already a candidate on its own merits and this only says the context
    // agrees.
    const float share = static_cast<float>(pair) /
                        (static_cast<float>(total) + kUserBigramPrior / learningSpeed_);
    const float bonus = kMaxUserBigramBoost * share;
    return bonus;
}

void Engine::searchUserPhrases(TopK<Candidate>& heap) {
    if (!phraseSuggestions_ || userContext1_ < 0 || userModel_.size() == 0) {
        return;
    }
    const uint32_t firstTotal = userModel_.successorTotal(userContext1_);
    if (firstTotal == 0u) {
        return;
    }

    constexpr int kMaxFirst = 3;
    UserModel::Successor first[kMaxFirst];
    const int firstCount = userModel_.successors(userContext1_, first, kMaxFirst);

    for (int i = 0; i < firstCount && phraseCount_ < kMaxPhrases; ++i) {
        const float firstShare = static_cast<float>(first[i].count) /
                                 (static_cast<float>(firstTotal) + kUserBigramPrior);
        if (firstShare < kPhraseMinShare) {
            continue;
        }

        const int32_t middle = static_cast<int32_t>(first[i].entryIndex);
        const uint32_t secondTotal = userModel_.successorTotal(middle);
        if (secondTotal == 0u) {
            continue;
        }
        UserModel::Successor second[1];
        if (userModel_.successors(middle, second, 1) != 1) {
            continue;
        }
        // The stricter bar. Written as a larger prior rather than a larger share so that the
        // requirement is "more evidence" rather than "more dominance": a second word written
        // three times out of four is admitted, one written once out of one is not.
        const float secondShare =
            static_cast<float>(second[0].count) /
            (static_cast<float>(secondTotal) + kUserBigramPrior * kPhraseSecondLinkFactor);
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

        // Scored as the two links together, which is what it is: the probability of writing
        // both. Multiplying the shares means a phrase can only outrank its own first word when
        // the second link is close to certain, and never outranks a better single suggestion.
        const float score = std::log(firstShare) + std::log(secondShare) +
                            kUserChainPreference *
                                (static_cast<float>(first[i].count) /
                                 (static_cast<float>(first[i].count) + kUserChainHalfLife));
        offerCandidate(heap, Candidate{Candidate::kPhrasePack, slot, score}, target,
                       static_cast<uint32_t>(needed));
    }
}

void Engine::searchUserSuccessors(TopK<Candidate>& heap) {
    if (userContext1_ < 0 || userModel_.size() == 0) {
        return;
    }

    // The triple first, exactly as the pack's own n-grams back off: evidence about these two
    // words beats evidence about the last one alone, when there is any. A triple seen twice
    // still says more than a pair seen twenty times, because it is a statement about a longer
    // and rarer context -- which is why the confidence below is computed from its own count
    // rather than borrowed from the pair.
    constexpr int kMaxSuccessors = 8;
    UserModel::Successor successors[kMaxSuccessors];
    int found = 0;
    uint32_t total = 0u;
    if (userContext2_ >= 0) {
        total = userModel_.trigramTotal(userContext2_, userContext1_);
        if (total > 0u) {
            found = userModel_.trigramSuccessors(userContext2_, userContext1_, successors,
                                                 kMaxSuccessors);
        }
    }
    if (found == 0) {
        total = userModel_.successorTotal(userContext1_);
        if (total == 0u) {
            return;
        }
        found = userModel_.successors(userContext1_, successors, kMaxSuccessors);
    }
    for (int i = 0; i < found; ++i) {
        // A proper conditional probability on the same scale as the packs', so a phrase someone
        // repeats competes with the language model instead of being bolted on top of it, plus
        // the preference that lets an established habit actually win.
        // Both the smoothing prior and the confidence half-life scale with the learning speed,
        // because they are the same question asked twice: how much evidence is demanded before
        // this is believed. Moving only one of them was tried first and made the three settings
        // almost indistinguishable -- three, two and two repetitions -- because whichever term
        // was left fixed went on dominating.
        const float count = static_cast<float>(successors[i].count);
        const float prior = kUserBigramPrior / learningSpeed_;
        const float halfLife = kUserChainHalfLife / learningSpeed_;
        const float share = count / (static_cast<float>(total) + prior);
        const float confidence = count / (count + halfLife);
        const float score = std::log(share) + kUserChainPreference * confidence;
        uint32_t textLength = 0;
        const Candidate candidate{Candidate::kUserPack,
                                  static_cast<int32_t>(successors[i].entryIndex), score};
        const char* const text = candidateText(candidate, &textLength);
        if (text != nullptr && textLength != 0) {
            offerCandidate(heap, candidate, text, textLength);
        }
    }
}

void Engine::searchUserModel(const uint32_t* folded, int foldedLength, TopK<Candidate>& heap) {
    if (!personalModelEnabled_ || userModel_.size() == 0) {
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
        // Confirmed, not merely seen. A word written once is a word that might have been a
        // name, a typo, or something quoted out of a message and never wanted again -- and
        // until today it went straight into the strip and stayed there, because the anchor
        // below is a fixed value that beats any dictionary word rarer than itself. Typing a
        // class name once put it above every real word sharing its prefix.
        //
        // Measured against the *effective* count, which is the raw count times the learning
        // speed, so this defers to the setting rather than overruling it: "the first time
        // counts" (3x) still offers a word written once, the balanced default asks for a
        // second, and the cautious setting needs about six -- which is what its own
        // explanation in the settings screen has always promised and, for single words, did
        // not previously deliver.
        if (static_cast<float>(completions[i].count) * learningSpeed_ < kMinPersonalEvidence) {
            continue;
        }
        // Anchored to the language scale rather than to the user model's own totals, then given
        // exactly the boost a word already in a dictionary would get. A word confirmed once
        // therefore ranks below the dictionary, and a word confirmed fifty times ranks above
        // most of it -- which is the behaviour, and it is bounded.
        const float score = kUserOnlyLogProb + userBoostForCount(completions[i].count) +
                            userBigramBonusFor(completions[i].entryIndex);
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
                         TopK<Candidate>& heap) {
    for (int i = 0; i < kMaxPacks; ++i) {
        if (!packs_[i].isOpen() || !packs_[i].active) {
            continue;
        }
        if (onlyPack >= 0 && i != onlyPack) {
            continue;
        }
        if (foldedLength == 0) {
            searchNextWord(i, heap);
        } else {
            // Shortlist first. It is cheap, it is bounded, and running it before the descent
            // raises the heap's floor -- which then lets the descent reject most of what it
            // finds on one comparison instead of scoring it.
            searchFrequentWithPrefix(i, folded, foldedLength, heap);
            // Reset per pack, not once for the whole request: visitBudget_ is a consumable
            // counter spent inside collectEndpoints/collectWords, and a request with more than
            // one active pack used to share a single allowance across all of them. A large
            // fuzzy walk in an earlier pack's slot could exhaust it before a later pack ever got
            // to try -- that pack's exact matches and frequent-prefix shortlist still worked, so
            // the strip was never empty, just silently missing that pack's fuzzy corrections for
            // no reason discoverable from the strip itself. Each pack now gets its own full
            // budget, matching what this field's own doc comment already claims is true.
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
    // Needs a trailing "s" and something in front of it worth owning anything.
    if (foldedLength < 3 || folded[foldedLength - 1] != 's') {
        return 0;
    }
    // A word the dictionaries already hold is not a possessive missing its apostrophe, whatever
    // it looks like: "times", "ones" and "canvas" all end in s and all mean themselves.
    for (int index = 0; index < kMaxPacks; ++index) {
        const LanguagePack& pack = packs_[index];
        if (pack.isOpen() && pack.active &&
            pack.trie().lookupFolded(folded, foldedLength) >= 0) {
            return 0;
        }
    }
    // The stem has to be a *name*. That restriction is the whole safety of this: it is what
    // keeps "cats" from becoming "cat's", and it is a flag the packs already carry rather than
    // a judgement made here.
    for (int index = 0; index < kMaxPacks; ++index) {
        const LanguagePack& pack = packs_[index];
        if (!pack.isOpen() || !pack.active) {
            continue;
        }
        const int32_t stem = pack.trie().lookupFolded(folded, foldedLength - 1);
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
        return static_cast<int>(stemLength) + 2;
    }
    return 0;
}

int Engine::knownSpelling(const char* word, size_t length, char* out, int outBytes) const {
    if (!created_ || word == nullptr || out == nullptr || outBytes <= 0 || length == 0) {
        return 0;
    }
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, length, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return 0;
    }
    for (int index = 0; index < kMaxPacks; ++index) {
        const LanguagePack& pack = packs_[index];
        if (!pack.isOpen() || !pack.active) {
            continue;
        }
        const int32_t wordIndex = pack.trie().lookupFolded(folded, foldedLength);
        if (wordIndex < 0) {
            continue;
        }
        uint32_t textLength = 0;
        const char* const text = pack.trie().wordText(static_cast<uint32_t>(wordIndex),
                                                      &textLength);
        if (text == nullptr || textLength == 0 || textLength > static_cast<uint32_t>(outBytes)) {
            continue;
        }
        std::memcpy(out, text, textLength);
        return static_cast<int>(textLength);
    }
    // The personal dictionary counts. A word this device has learned is a word this device
    // should not be arguing with -- except in a private field, where it is not consulted at all.
    const int32_t entry = personalModelEnabled_ ? userModel_.entryIndexFor(word, length) : -1;
    if (entry >= 0) {
        uint32_t textLength = 0;
        const char* const text = userModel_.entryText(static_cast<uint32_t>(entry), &textLength);
        if (text != nullptr && textLength > 0 && textLength <= static_cast<uint32_t>(outBytes)) {
            std::memcpy(out, text, textLength);
            return static_cast<int>(textLength);
        }
    }
    return 0;
}

int Engine::candidateForPack(int packIndex, const char* word, size_t wordLength, char* out,
                             int outBytes) {
    if (!created_ || word == nullptr || wordLength == 0 || out == nullptr || outBytes <= 0) {
        return 0;
    }
    if (packIndex < 0 || packIndex >= kMaxPacks || !packs_[packIndex].isOpen() ||
        !packs_[packIndex].active) {
        return 0;
    }
    arena_.reset();
    phraseCount_ = 0;
    uint32_t folded[kMaxComposing];
    const int foldedLength = foldUtf8(word, wordLength, folded, kMaxComposing);
    if (foldedLength <= 0) {
        return 0;
    }
    TopK<Candidate> heap;
    heap.reset(heapStorage_, kMaxCandidates);
    // No resolveContext call here on purpose: this is an isolated, on-demand lookup that must
    // not perturb dominantPack_/languageEvidence_, which live suggestion requests do update.
    editCostCeiling_ = maxEditCostFor(foldedLength);
    searchPacks(folded, foldedLength, packIndex, heap);
    const int drained = heap.drainSorted(drainBuffer_, kMaxCandidates);
    if (drained <= 0) {
        return 0;
    }
    uint32_t textLength = 0;
    const char* const text = candidateText(drainBuffer_[0], &textLength);
    if (text == nullptr || textLength == 0 || textLength > static_cast<uint32_t>(outBytes)) {
        return 0;
    }
    std::memcpy(out, text, textLength);
    return static_cast<int>(textLength);
}

int Engine::suggest(const char* composing, size_t composingLength, const char* previous1,
                    size_t previous1Length, const char* previous2, size_t previous2Length,
                    Candidate* out, int maxOut) {
    if (!created_ || out == nullptr || maxOut <= 0) {
        return 0;
    }
    arena_.reset();
    // The phrase slots belong to the request being answered. The previous request's text is not
    // referenced any more: its candidates were read out before this call could be made, on the
    // one thread both of them run on.
    phraseCount_ = 0;

    uint32_t folded[kMaxComposing];
    int foldedLength = 0;
    if (composing != nullptr && composingLength > 0) {
        foldedLength = foldUtf8(composing, composingLength, folded, kMaxComposing);
        // Malformed input, or a "word" longer than any word: not something to guess about.
        if (foldedLength < 0) {
            return 0;
        }
    }

    refreshWeights();

    resolveContext(previous1, previous1Length, previous2, previous2Length);
    // visitBudget_ itself is now reset per pack, inside searchPacks -- see its own comment there.

    TopK<Candidate> heap;
    heap.reset(heapStorage_, kMaxCandidates);
    // Reset beside the heap it shadows, and for the same reason: both hold one request's answers
    // and neither may carry anything into the next.
    correctionHeap_.reset(correctionStorage_, kMaxCorrections);
    hasBestCorrection_ = false;

    editCostCeiling_ = maxEditCostFor(foldedLength);
    // Undecided, and told never to guess: one dictionary rather than all of them. The heaviest
    // is the one the user weighted highest, which is the closest thing to "the language I
    // write" available before any evidence has arrived.
    // Three questions in order, and only the first two are about this request. What has the
    // conversation been recognised as? Failing that, what did the user say to start from? Failing
    // both, the old answer: one dictionary if they asked never to guess, otherwise all of them.
    //
    // The preferred pack sits *below* the detected one and not above it, which is the whole
    // meaning of the word: it decides where to start, never what wins. A user who set Romanian
    // and then wrote four English words gets English, because by then it is no longer a guess.
    const int restrictTo = (dominantPack_ >= 0)  ? dominantPack_
                           : (preferredPack_ >= 0) ? preferredPack_
                                                   : (strictLanguage_ ? heaviestPack() : -1);
    searchPacks(folded, foldedLength, restrictTo, heap);
    // A language that turns out to have nothing for this word must not leave the strip empty --
    // true of a detected one, where the detector is a guess about the sentence rather than a
    // verdict on the next word, and true of a preferred one, where a preference that refused to
    // yield for a word it does not hold would not be a preference. Strict is the one setting that
    // asked for exactly that, and it is the reason this is still conditional.
    if (heap.size() == 0 && restrictTo >= 0 && !strictLanguage_) {
        searchPacks(folded, foldedLength, -1, heap);
    }
    if (foldedLength > 0) {
        searchUserModel(folded, foldedLength, heap);
        // Nothing at all, for a word someone is in the middle of writing. That happens when the
        // word is further from every entry than the ordinary ceiling allows -- which is the
        // case where an empty strip is least useful, because the writer cannot tell whether the
        // keyboard has no idea or has stopped working. One wider pass, on the requests that
        // would otherwise show nothing -- the per-pack budget searchPacks resets internally
        // means this pass starts fresh for every pack too, without a separate reset here.
        if (heap.size() == 0) {
            editCostCeiling_ = kFallbackEditCost;
            searchPacks(folded, foldedLength, -1, heap);
        }
    } else {
        // Nothing typed: this is the next-word case, and the phrases this person repeats are
        // the best evidence there is about what follows the word they just wrote.
        searchUserSuccessors(heap);
        searchUserPhrases(heap);
    }

    // Drained before the main heap, into its own buffer, so autocorrect's answer is settled
    // whatever the strip's filtering below decides to show.
    Candidate corrections[kMaxCorrections];
    if (correctionHeap_.drainSorted(corrections, kMaxCorrections) > 0) {
        bestCorrection_ = corrections[0];
        hasBestCorrection_ = true;
    }

    const int drained = heap.drainSorted(drainBuffer_, kMaxCandidates);

    // A word may be continued only so many times before the rest of the strip is worth more.
    //
    // Nothing here is mis-scored: every one of these earned its place. The trouble is that a
    // short stem has a great many continuations and they arrive as a block, so a correction --
    // which pays kEditPenalty and can never outbid a free continuation -- is pushed past the
    // three or four slots anyone looks at. Typing "teh" offered tehran, Tehan, tehran's,
    // Tehrani, tehsil, tehama, tehachapi, tehsildar and tehreek-e-insaf before "the", which is
    // one transposition away and the commonest word in the language. Measured through
    // explainScore: "the" is 7.8 points *better* on the language model and loses by 35 points
    // of edit penalty, a constant floored at 15 by the static_assert above and so not
    // adjustable. Taking slots back from the block is the move left.
    //
    // Applied after draining, never during the search: the cap decides how many continuations
    // are *shown*, and the heap still ranks them all first, so the ones kept are the best of
    // them rather than whichever the walk happened to reach.
    //
    // A continuation is recognised from the text rather than recorded on the candidate --
    // [Candidate] is twelve bytes of plain data crossing JNI on a path that may not allocate,
    // and this is the only place the distinction is wanted.
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
        return false;  // the same word, or shorter: nothing was guessed past what was typed
    }
    for (int i = 0; i < foldedLength; ++i) {
        if (wordFolded[i] != folded[i]) {
            return false;  // reached by an edit, not by carrying on
        }
    }
    return true;
}

void Engine::learn(const char* word, size_t wordLength, const char* previous1,
                   size_t previous1Length, const char* previous2, size_t previous2Length,
                   bool deliberateCapital) {
    if (!created_ || word == nullptr || wordLength == 0) {
        return;
    }
    // The word, and the fact that it followed the one before it. The second is what makes a
    // phrase someone repeats -- "vreau să", "să mă", "mă duc" -- come back as a prediction
    // rather than having to be typed out every time.
    //
    // Both the pair and the triple. The triple fires only when the last two words match, so it
    // is rarer and narrower; the pair is what covers the common case. Keeping both is what lets
    // the scorer prefer the more specific evidence when there is any and fall back when there
    // is not, which is the same shape the language pack's own n-grams use.

    const int32_t wordIndex = userModel_.learn(word, wordLength, deliberateCapital);
    if (previous1 != nullptr && previous1Length > 0) {
        const int32_t index1 = userModel_.entryIndexFor(previous1, previous1Length);
        userModel_.learnBigram(index1, wordIndex);
        if (previous2 != nullptr && previous2Length > 0) {
            const int32_t index2 = userModel_.entryIndexFor(previous2, previous2Length);
            userModel_.learnTrigram(index2, index1, wordIndex);
        }
    }

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
                           const int32_t* counts, int count, const int32_t* deliberateCapitals) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoad(words, lengths, counts, count, deliberateCapitals);
}

void Engine::loadUserBigrams(const char* const* previous, const size_t* previousLengths,
                             const char* const* next, const size_t* nextLengths,
                             const int32_t* counts, int count) {
    if (!created_) {
        return;
    }
    userModel_.bulkLoadBigrams(previous, previousLengths, next, nextLengths, counts, count);
}

const char* Engine::dominantLanguageTag() const {
    if (dominantPack_ < 0 || dominantPack_ >= kMaxPacks || !packs_[dominantPack_].isOpen()) {
        return nullptr;
    }
    return packs_[dominantPack_].tag();
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

/**
 * Plain Levenshtein over folded code points, for reporting only.
 *
 * Not the engine's own notion of distance, which is a weighted cost over key geometry and is
 * what the search actually spends -- this is the simple count a reader wants when asking "how
 * far is this word from what I typed". Kept local to the explain path so nothing can mistake it
 * for the real one.
 */
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
    // No frequent list means nothing to measure against, and refusing every correction would be
    // a worse answer than allowing them -- the guards in AutoCorrection still stand behind this.
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
        out->addedCharacters =
            static_cast<int32_t>(candidateLength) - static_cast<int32_t>(typedLength);

        // The context the request resolved is still standing, so the language-model term can be
        // asked for again rather than recomputed from a copy of the formula.
        if (results[i].packIndex >= 0 && results[i].packIndex < kMaxPacks) {
            out->packWeight = packWeightLog(results[i].packIndex);
            out->languageModel = contextLogProb(results[i].packIndex,
                                                static_cast<uint32_t>(results[i].wordIndex));
        } else if (results[i].packIndex == Candidate::kUserPack) {
            out->personal = userBoostFor(candidate, static_cast<uint32_t>(candidateLength));
            out->languageModel = kUserOnlyLogProb;
        }

        // Whatever the total is not explained by the above: the edit cost and the completion
        // penalty, which the search applies and does not keep apart afterwards.
        out->rest = out->total - out->packWeight - out->languageModel - out->personal;

        uint32_t typedFolded[kMaxComposing];
        uint32_t wordFolded[kMaxComposing];
        const int typedCount = foldUtf8(typed, typedLength, typedFolded, kMaxComposing);
        const int wordCount = foldUtf8(candidate, candidateLength, wordFolded, kMaxComposing);
        out->editDistance = (typedCount <= 0 || wordCount <= 0)
            ? -1
            : reportedEditDistance(typedFolded, typedCount, wordFolded, wordCount);
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
        // Its own pack's flag is necessary, not sufficient: with several packs active, a word
        // that is a name in one language and an ordinary word in another -- "Si" is a family
        // name to the English list and "and" (și, typed without its accent) to the Romanian
        // one -- must not come out capitalised every time it is typed. Every active pack that
        // knows the word has to agree it is a name.
        uint32_t length = 0;
        const char* text = pack.trie().wordText(static_cast<uint32_t>(candidate.wordIndex), &length);
        if (text == nullptr || length == 0) {
            return true;
        }
        uint32_t folded[kMaxComposing];
        const int foldedLength = foldUtf8(text, length, folded, kMaxComposing);
        return foldedLength <= 0 || packsAgreeProperNoun(folded, foldedLength);
    }
    // A word the user has deliberately capitalised themselves at least once -- shift physically
    // pressed for that letter, never auto-capitalise's own doing -- is treated as a name from
    // then on, regardless of how it happens to be typed the next time. See UserModel::learn's
    // own doc for exactly what earns this, and BorderKeysService's capture of the distinction.
    if (candidate.packIndex == Candidate::kUserPack && candidate.wordIndex >= 0 &&
        userModel_.deliberateCapitals(static_cast<uint32_t>(candidate.wordIndex)) > 0) {
        return true;
    }
    // Beyond that, a personal-dictionary or phrase candidate carries no proper-noun flag of its
    // own -- the user model learns spelling and frequency, not classification on its own, and a
    // phrase is never a name. But a name typed once, corrected, and learned is still a name the
    // second time even without a deliberate capital of its own to point to (a correction picked
    // from the strip, say): cross-checked by text (folded, so case and diacritics both wash out,
    // matching how the trie itself is keyed) against every active pack's own dictionary, rather
    // than just answering false and leaving AutoCorrection.matchCase with nothing but typed's own
    // case to go on -- which is exactly backwards for a name someone typed in the middle of a
    // sentence, lower case, on purpose, because that is where the word was.
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
    // Agreement, not any one pack's say-so -- the same rule as for a pack's own candidate above.
    return packsAgreeProperNoun(folded, foldedLength);
}

}  // namespace borderkeys
