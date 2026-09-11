// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEXT_ASSIST_HPP
#define BORDERKEYS_TEXT_ASSIST_HPP

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;

namespace borderkeys {

/**
 * A single loaded language model, and the one operation this application asks of it.
 *
 * Everything about this class is shaped by where it runs: a separate process, started when the
 * user asks for something and killed shortly afterwards. It is not a service, it holds no
 * queue, and it has no concept of a conversation. The user selects text, picks an action, gets
 * one answer, and the model goes away again.
 *
 * Not thread safe. One request at a time, from the service's worker thread.
 *
 * One exception to "no concept of a conversation": [run]'s `reuseSharedPrefix` lets consecutive
 * calls share the part of the prompt neither one needed to recompute -- see [run]'s own doc. This
 * is not state carried between actions; it is one action's own chunks of one long selection,
 * decided entirely by the caller.
 */
class TextAssist {
public:
    enum Status : int32_t {
        kOk = 0,
        kErrNoModel = -1,
        kErrLoadFailed = -2,
        kErrContext = -3,
        kErrTooLong = -4,
        kErrTokenise = -5,
        kErrDecode = -6,
        kErrBusy = -7,
        kErrArgument = -8,
        /** A C++ exception was caught at the JNI boundary instead of being let cross it -- see
         *  assist_jni.cpp's try/catch around load and run. Distinct from every error above so it
         *  shows up as itself in logs rather than masquerading as one of them. */
        kErrException = -9,
    };

    ~TextAssist();

    /**
     * Loads a GGUF model from an absolute path.
     *
     * The caller has already verified the file's SHA-256 against the known-model registry; this
     * takes the path on that basis and does not re-check. `contextTokens` is clamped to what the
     * model was trained for, because asking for more silently produces nonsense rather than an
     * error.
     */
    int32_t load(const char* path, int contextTokens, int threads);

    /** Frees the model and its context. Called on the idle timeout, and before the process dies. */
    void unload();

    bool isLoaded() const { return context_ != nullptr; }

    int contextTokens() const { return contextTokens_; }

    /**
     * This model's own chars-per-token ratio, measured against a fixed sample at [load] rather
     * than assumed -- the "four characters to a token" rule of thumb [ChunkedAssistRunner] falls
     * back to before this is ever known is a guess averaged across many tokenisers, and any one
     * model's real vocabulary can sit meaningfully off it. 0 before a model has been loaded.
     */
    float charsPerToken() const { return charsPerToken_; }

    /**
     * Replaces the sampler's temperature and nucleus (top-p) with the given values, clamping
     * anything out of range rather than rejecting it -- the caller is a stored preference, not a
     * one-off argument, and a bad file should not mean requests silently do nothing.
     *
     * Safe to call before [load] (the values are simply remembered for when the chain is first
     * built) or any time after (the chain is freed and rebuilt on the spot, no model reload).
     * The fixed sampler seed is untouched either way -- see kSamplerSeed in text_assist.cpp.
     */
    void setSamplingParams(float temperature, float topP);

    /**
     * Runs one instruction over one piece of text and returns the whole answer.
     *
     * Streaming is deliberately absent. The result is shown in a sheet with a Replace button
     * next to it, so a half-finished answer has nothing to be done with -- and a token-by-token
     * callback across a process boundary would cost an IPC per token.
     *
     * `cleanFormatting` gates the half of cleanResult's cleanup that can disagree with what was
     * actually asked for -- see that function's own doc for why a custom, user-written
     * instruction is the one case this needs to be off for.
     *
     * `outputRatio` and `minOutputTokens` come from the task being run --
     * [com.borderkeys.data.assist.AssistTask.outputRatio] and its `minOutputTokens`, the same
     * pair that class's own doc describes -- and `maxOutputTokensCeiling` is that class's shared
     * `MAX_OUTPUT_TOKENS`. The actual token budget for this request is computed from these
     * against the prompt's exact tokenised size, not guessed from the input's character count.
     * When `useRemainingContext` is true that budget is only a floor: the exact number of tokens
     * actually left in the context window is at least as large (or this call would already have
     * been refused) and generation is allowed to run to that instead, so `outputRatio` guessing
     * low cannot cut a correct answer off mid-sentence. When false, the computed budget is the
     * real stop -- see [com.borderkeys.data.assist.AssistTask.usesRemainingContext]'s own doc for
     * which tasks want which.
     *
     * `outTruncated`, when not null, is set on a [kOk] return to whether generation stopped for
     * a reason other than the model itself choosing to end the answer -- exhausting the token
     * budget, or [requestCancel]. Left untouched on every other return, since only a [kOk] answer
     * is something a truncation flag describes.
     *
     * `reuseSharedPrefix` is true only for a chunk after the first within one
     * [com.borderkeys.assist.ChunkedAssistRunner] job -- see this class's own doc for what it
     * changes about how the prompt is decoded, and [com.borderkeys.assist.ChunkedAssistRunner]'s
     * for why chunks of one job are the one case two requests may share anything of each other's
     * state at all.
     */
    int32_t run(const char* instruction, const char* text, float outputRatio,
                int minOutputTokens, int maxOutputTokensCeiling, bool useRemainingContext,
                bool reuseSharedPrefix, bool cleanFormatting, std::string* out,
                bool* outTruncated);

    /**
     * Asks the current run to stop at the next token boundary. Safe from another thread -- and
     * only actually safe because [cancelRequested_] is `std::atomic`: this is the one field on
     * this otherwise single-threaded class written from a thread other than the worker thread
     * that owns everything else here, so it is the one field that has to be.
     */
    void requestCancel() { cancelRequested_ = true; }

private:
    std::string applyChatTemplate(const char* instruction, const char* text) const;

    /** Frees [sampler_] if set and builds a new chain from [temperature_] and [topP_]. */
    void rebuildSampler();

    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    llama_sampler* sampler_ = nullptr;
    int contextTokens_ = 0;
    float charsPerToken_ = 0.0f;
    std::atomic<bool> cancelRequested_{false};
    bool running_ = false;
    // The exact tokens the context's memory currently holds as a prompt, in the positions
    // decoding them originally put them at -- empty whenever that is not true of anything in the
    // memory (nothing loaded yet, or the last attempt to establish it failed partway through).
    // The one thing run's reuseSharedPrefix compares a new prompt against; see that parameter's
    // own doc and its implementation in text_assist.cpp for why emptying this before a decode
    // attempt and only restoring it after that attempt succeeds is what keeps it trustworthy.
    std::vector<int32_t> lastPromptTokens_;
    // Low temperature and a tight nucleus by default -- see rebuildSampler's own reasoning in
    // text_assist.cpp for why.
    float temperature_ = 0.3f;
    float topP_ = 0.9f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TEXT_ASSIST_HPP
