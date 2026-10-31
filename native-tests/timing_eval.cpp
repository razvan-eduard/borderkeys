// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

/**
 * Times each engine call a keystroke or a swipe makes, one call at a time, for scripts/timing.sh:
 * every workload with no personal model, then, given a history, every workload again with the
 * history learned. Each call alone is timed by the steady clock; after it, one line goes to the
 * calls file: its workload, language, model, kind and microseconds, tab-separated, under a header
 * line, as scripts/timing_summary.py reads them.
 *
 * The workloads:
 * - `typed`: each row a `suggest_eval --context` reading asks of the runs (context_runs.hpp), a
 *   word after the two words before it with its first letters typed; the kind is the letters
 *   typed, 0 for the next word.
 * - `taps`: each word of a tap corpus typed with a slip into a string no dictionary spells, as
 *   touch_eval measures them (replay_corpora.hpp), with its taps on the default touch patterns and
 *   nothing before it; the tap decoder runs inside the call. The kind is `slipped`.
 * - `swipe`: each gesture of at least two points decoded through Engine::decodeGesture with
 *   nothing before it, the kind `shark2` for the geometric decoder, the neural one switched off,
 *   and, given the weights, `tcn` for the neural one, switched on as the keyboard switches it on.
 * - `push`: with a history, the slice of a call's context handed to the engine
 *   (Engine::pushSlice) when the call names another context than the call before it, as the
 *   keyboard hands it; the kind is `slice`.
 *
 * The model is `none`, or `history`: each run of the history file committed word by word after
 * the two words before it, the sentence start before its first, through WorkingCopyFixture, the
 * test copy of the keyboard's lookup, under the pack's tag. `--limit N` times the first N calls of
 * each workload and model. The report on stdout counts the calls, the candidates they returned,
 * and the `tcn` calls the neural decoder answered.
 *
 * Usage:
 *     timing_eval <dict dir> <tag> <layout> <calls.tsv> [--context <runs.txt>]
 *                 [--history <runs.txt>] [--taps <taps.tsv>] [--gestures <gestures.csv>]
 *                 [--weights <weights.bkw>] [--limit N]
 */

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <vector>

#include "context_runs.hpp"
#include "engine.hpp"
#include "replay_corpora.hpp"
#include "test_support.hpp"

using namespace borderkeys;
using borderkeys_eval::Asked;
using borderkeys_eval::Gesture;
using borderkeys_eval::Layout;
using borderkeys_eval::Run;
using borderkeys_eval::TappedWord;
using borderkeys_eval::forEachAsked;
using borderkeys_eval::loadGestures;
using borderkeys_eval::loadLayout;
using borderkeys_eval::readRuns;
using borderkeys_eval::readTapCorpus;
using borderkeys_eval::spelledAsTyped;
using borderkeys_eval::typedWithSlip;
using borderkeys_test::WorkingCopyFixture;

namespace {

using Clock = std::chrono::steady_clock;

// The names the calls file gives the workloads, the models and the kinds.
constexpr char kTyped[] = "typed";
constexpr char kTaps[] = "taps";
constexpr char kSwipe[] = "swipe";
constexpr char kPush[] = "push";
constexpr char kNoModel[] = "none";
constexpr char kHistoryModel[] = "history";
constexpr char kSlipped[] = "slipped";
constexpr char kGeometric[] = "shark2";
constexpr char kNeural[] = "tcn";
constexpr char kSliceKind[] = "slice";

/** The fewest points a gesture is decoded from, as the replay tools decode one. */
constexpr size_t kFewestPoints = 2;

/** Where each argument stands on the command line: the four named, then the options in pairs. */
enum Argument : int { kDictionaries = 1, kTag, kLayoutFile, kCallsFile, kFirstOption };

/** An option's arguments: its name and its value. */
constexpr int kOptionArguments = 2;

/** The calls file: its header, then one line per timed call; and the counts the report gives. */
class CallsFile {
public:
    CallsFile(FILE* file, const char* language) : file_(file), language_(language) {
        std::fputs("workload\tlanguage\tmodel\tkind\tmicroseconds\n", file_);
    }

    /** A call of [workload] with [model] and [kind] that took [spent] and found [found] words. */
    void write(const char* workload, const char* model, const char* kind, Clock::duration spent,
               int found = 0) {
        std::fprintf(file_, "%s\t%s\t%s\t%s\t%.3f\n", workload, language_, model, kind,
                     std::chrono::duration<double, std::micro>(spent).count());
        ++calls_;
        candidates_ += found;
    }

    /** Counts a `tcn` call, and whether it went through the neural decoder. */
    void neuralCall(bool decodedByNeural) {
        ++neuralCalls_;
        neuralDecodes_ += decodedByNeural ? 1 : 0;
    }

    long calls() const { return calls_; }
    long candidates() const { return candidates_; }
    long neuralCalls() const { return neuralCalls_; }
    long neuralDecodes() const { return neuralDecodes_; }

private:
    FILE* file_;
    const char* language_;
    long calls_ = 0;
    long candidates_ = 0;
    long neuralCalls_ = 0;
    long neuralDecodes_ = 0;
};

bool sameKey(const ContextSlice::Key& a, const ContextSlice::Key& b) {
    return a.language == b.language && a.context1 == b.context1 && a.context2 == b.context2;
}

/** [text]'s characters, or null for none, and its length. */
const char* textOrNull(const std::string* text) {
    return text != nullptr ? text->c_str() : nullptr;
}
size_t lengthOf(const std::string* text) { return text != nullptr ? text->size() : 0; }

/**
 * The personal model calls are timed with: none, naming no slice, or a history in a
 * WorkingCopyFixture, whose slice of a call's context is handed to the engine before the call
 * unless the slice handed last has the same key.
 */
class Model {
public:
    /** No personal model. */
    Model() = default;

    /** The history [copy] holds, in [language]'s store. */
    Model(WorkingCopyFixture* copy, const std::string& language)
        : copy_(copy), language_(language) {}

    const char* name() const { return copy_ == nullptr ? kNoModel : kHistoryModel; }

    /**
     * The key of a call after [previous1] and [previous2], each null for none: no key without a
     * model; with one, its slice handed to the engine first, the hand-over timed into [calls],
     * unless the slice handed last has the same key.
     */
    ContextSlice::Key keyFor(Engine& engine, const std::string* previous1,
                             const std::string* previous2, CallsFile& calls) {
        if (copy_ == nullptr) {
            return ContextSlice::Key{};
        }
        const WorkingCopyFixture::Slice slice =
            copy_->sliceOf(textOrNull(previous1), lengthOf(previous1), textOrNull(previous2),
                           lengthOf(previous2), language_.c_str(), language_.size());
        if (handed_ && sameKey(slice.key, pushed_)) {
            return pushed_;
        }
        const auto started = Clock::now();
        engine.pushSlice(slice.header.data(), slice.header.size(), slice.links.data(),
                         slice.links.size(), slice.hops.data(), slice.hops.size(), slice.seen.data(),
                         slice.seen.size());
        calls.write(kPush, name(), kSliceKind, Clock::now() - started);
        handed_ = true;
        pushed_ = slice.key;
        return pushed_;
    }

private:
    WorkingCopyFixture* copy_ = nullptr;
    std::string language_;
    // The key of the slice last handed, and whether one was.
    bool handed_ = false;
    ContextSlice::Key pushed_;
};

/** What a run times, read once. */
struct Workloads {
    std::vector<Run> runs;
    std::vector<TappedWord> slipped;
    std::vector<Gesture> gestures;
    // The Latin model's weights, or none.
    const std::string* weights = nullptr;
    long limit = std::numeric_limits<long>::max();
};

/**
 * Switches the neural decoder on with [weights], as the keyboard does when its switch goes on:
 * the weights loaded, their script selected, the model warmed; false when they are refused.
 */
bool useNeural(Engine& engine, const std::string& weights) {
    engine.setSwipeModelEnabled(true);
    if (!engine.loadSwipeWeights(Engine::kLatinSwipeScript,
                                 reinterpret_cast<const uint8_t*>(weights.data()), weights.size())) {
        return false;
    }
    engine.selectSwipeScript(Engine::kLatinSwipeScript);
    engine.warmSwipeModel();
    return true;
}

/**
 * Times every call of [workloads] with [model] into [calls], the geometric decoder's swipes with
 * the neural decoder switched off and its weights freed; false when the weights are refused.
 */
bool timeCalls(Engine& engine, const Workloads& workloads, Model& model, CallsFile& calls) {
    Candidate out[Engine::kMaxCandidates];
    long typed = 0;
    forEachAsked(workloads.runs, false, [&](const Asked& asked) {
        if (typed >= workloads.limit) {
            return;
        }
        ++typed;
        const ContextSlice::Key key = model.keyFor(engine, asked.previous1, asked.previous2, calls);
        const auto started = Clock::now();
        const int found = engine.suggest(
            asked.target.c_str(), asked.typedBytes, textOrNull(asked.previous1),
            lengthOf(asked.previous1), textOrNull(asked.previous2), lengthOf(asked.previous2), out,
            Engine::kMaxCandidates, key);
        const Clock::duration spent = Clock::now() - started;
        calls.write(kTyped, model.name(), std::to_string(asked.depth).c_str(), spent, found);
    });
    long tapped = 0;
    for (const TappedWord& word : workloads.slipped) {
        if (tapped >= workloads.limit) {
            break;
        }
        ++tapped;
        const ContextSlice::Key key = model.keyFor(engine, nullptr, nullptr, calls);
        const auto started = Clock::now();
        const int found = engine.suggest(word.typed.c_str(), word.typed.size(), nullptr, 0, nullptr,
                                         0, word.xs.data(), word.ys.data(),
                                         static_cast<int>(word.xs.size()), out,
                                         Engine::kMaxCandidates, key);
        const Clock::duration spent = Clock::now() - started;
        calls.write(kTaps, model.name(), kSlipped, spent, found);
    }
    for (const bool neural : {false, true}) {
        if (neural && workloads.weights == nullptr) {
            break;
        }
        if (!neural) {
            engine.setSwipeModelEnabled(false);
        } else if (!useNeural(engine, *workloads.weights)) {
            return false;
        }
        long swiped = 0;
        for (const Gesture& gesture : workloads.gestures) {
            if (gesture.xs.size() < kFewestPoints) {
                continue;
            }
            if (swiped >= workloads.limit) {
                break;
            }
            ++swiped;
            const ContextSlice::Key key = model.keyFor(engine, nullptr, nullptr, calls);
            const auto started = Clock::now();
            const int found = engine.decodeGesture(
                gesture.xs.data(), gesture.ys.data(), gesture.times.data(),
                static_cast<int>(gesture.xs.size()), nullptr, 0, nullptr, 0, out,
                Engine::kMaxCandidates, key);
            const Clock::duration spent = Clock::now() - started;
            calls.write(kSwipe, model.name(), neural ? kNeural : kGeometric, spent, found);
            if (neural) {
                calls.neuralCall(engine.lastDecodeUsedNeural());
            }
        }
    }
    return true;
}

/**
 * Commits each run of [history] word by word into [copy] as the keyboard learns typed words: each
 * word after the two before it, the first after the sentence start, in [language]'s store.
 * Returns how many words it committed.
 */
long learn(WorkingCopyFixture& copy, const std::vector<Run>& history, const std::string& language) {
    long committed = 0;
    for (const Run& run : history) {
        for (size_t at = 0; at < run.words.size(); ++at) {
            const std::string& word = run.words[at];
            const char* previous1 = WorkingCopyFixture::kSentenceStart;
            size_t previous1Length = WorkingCopyFixture::kSentenceStartLength;
            if (at >= 1) {
                previous1 = run.words[at - 1].c_str();
                previous1Length = run.words[at - 1].size();
            }
            const std::string* previous2 = at >= 2 ? &run.words[at - 2] : nullptr;
            copy.commit(word.c_str(), word.size(), previous1, previous1Length, textOrNull(previous2),
                        lengthOf(previous2), false, false, language.c_str(), language.size());
            ++committed;
        }
    }
    return committed;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < kFirstOption) {
        std::printf("usage: timing_eval <dict dir> <tag> <layout> <calls.tsv> [--context <runs.txt>] "
                    "[--history <runs.txt>] [--taps <taps.tsv>] [--gestures <gestures.csv>] "
                    "[--weights <weights.bkw>] [--limit N]\n");
        return 2;
    }
    const std::string tag = argv[kTag];
    const char* contextPath = nullptr;
    const char* historyPath = nullptr;
    const char* tapsPath = nullptr;
    const char* gesturesPath = nullptr;
    const char* weightsPath = nullptr;
    Workloads workloads;
    for (int i = kFirstOption; i < argc; i += kOptionArguments) {
        if (i + 1 >= argc) {
            std::printf("%s needs a value\n", argv[i]);
            return 2;
        }
        if (std::strcmp(argv[i], "--context") == 0) {
            contextPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--history") == 0) {
            historyPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--taps") == 0) {
            tapsPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--gestures") == 0) {
            gesturesPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--weights") == 0) {
            weightsPath = argv[i + 1];
        } else if (std::strcmp(argv[i], "--limit") == 0) {
            workloads.limit = std::strtol(argv[i + 1], nullptr, 10);
        } else {
            std::printf("unknown option %s\n", argv[i]);
            return 2;
        }
    }

    Layout layout;
    if (!loadLayout(argv[kLayoutFile], &layout)) {
        std::printf("could not read the layout %s\n", argv[kLayoutFile]);
        return 1;
    }
    Engine engine;
    if (!engine.create() || !borderkeys_test::loadPack(engine, argv[kDictionaries], tag)) {
        std::printf("the engine would not start on %s\n", tag.c_str());
        return 1;
    }
    const char* tags[1] = {tag.c_str()};
    const float weights[1] = {1.0f};
    engine.setActiveLanguages(tags, weights, 1);
    // Undecided, as on the first word typed.
    engine.setLanguageLock(0.0f, false);
    if (!engine.setKeyGeometry(layout.codes, layout.xs, layout.ys, layout.count, layout.keyWidth,
                               layout.keyHeight)) {
        std::printf("the layout was refused\n");
        return 1;
    }

    if (contextPath != nullptr) {
        workloads.runs = readRuns(contextPath);
        if (workloads.runs.empty()) {
            std::printf("no runs read from %s\n", contextPath);
            return 1;
        }
    }
    if (tapsPath != nullptr) {
        for (TappedWord& word : readTapCorpus(tapsPath)) {
            if (typedWithSlip(word) && !spelledAsTyped(engine, word.typed)) {
                workloads.slipped.push_back(std::move(word));
            }
        }
    }
    if (gesturesPath != nullptr && !loadGestures(gesturesPath, &workloads.gestures)) {
        std::printf("could not read the gestures %s\n", gesturesPath);
        return 1;
    }
    const std::string swipeWeights =
        weightsPath != nullptr ? borderkeys_test::readFile(weightsPath) : std::string();
    if (weightsPath != nullptr) {
        workloads.weights = &swipeWeights;
    }
    std::vector<Run> history;
    if (historyPath != nullptr) {
        history = readRuns(historyPath);
        if (history.empty()) {
            std::printf("no runs read from %s\n", historyPath);
            return 1;
        }
    }

    std::unique_ptr<FILE, int (*)(FILE*)> out(std::fopen(argv[kCallsFile], "w"), &std::fclose);
    if (out == nullptr) {
        std::printf("cannot write %s\n", argv[kCallsFile]);
        return 1;
    }
    CallsFile calls(out.get(), tag.c_str());
    Model none;
    bool timed = timeCalls(engine, workloads, none, calls);
    long learned = 0;
    if (timed && !history.empty()) {
        WorkingCopyFixture copy(engine);
        // The keyboard's load of an empty personal dictionary: the sentence start alone.
        copy.loadWords(nullptr, nullptr, nullptr, 0);
        learned = learn(copy, history, tag);
        Model model(&copy, tag);
        timed = timeCalls(engine, workloads, model, calls);
    }
    engine.destroy();
    if (!timed) {
        std::printf("the weights were refused: %s\n", weightsPath);
        return 1;
    }
    std::printf("timing_eval %s: %ld calls timed, %ld candidates returned, %ld history words "
                "committed, %ld of %ld tcn calls through the neural decoder\n",
                tag.c_str(), calls.calls(), calls.candidates(), learned, calls.neuralDecodes(),
                calls.neuralCalls());
    return 0;
}
