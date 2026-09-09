// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 BorderKeys contributors

#ifndef BORDERKEYS_TEXT_ASSIST_HPP
#define BORDERKEYS_TEXT_ASSIST_HPP

#include <cstddef>
#include <cstdint>
#include <string>

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
     * `maxOutputTokens` is a starting guess, from the caller's own length-based estimate. When
     * `useRemainingContext` is true it is only a floor: once the prompt is tokenised, the exact
     * number of tokens actually left in the context window is at least as large (a guess can
     * only have asked for too little, never too much, or this call would already have been
     * refused) and generation is allowed to run to that instead, so a guess that undershot what
     * the answer needed cannot cut it off mid-sentence. When false, `maxOutputTokens` is the real
     * stop -- see [com.borderkeys.data.assist.AssistTask.usesRemainingContext]'s own doc for
     * which tasks want which.
     */
    int32_t run(const char* instruction, const char* text, int maxOutputTokens,
                bool useRemainingContext, bool cleanFormatting, std::string* out);

    /** Asks the current run to stop at the next token boundary. Safe from another thread. */
    void requestCancel() { cancelRequested_ = true; }

private:
    std::string applyChatTemplate(const char* instruction, const char* text) const;

    /** Frees [sampler_] if set and builds a new chain from [temperature_] and [topP_]. */
    void rebuildSampler();

    llama_model* model_ = nullptr;
    llama_context* context_ = nullptr;
    llama_sampler* sampler_ = nullptr;
    int contextTokens_ = 0;
    bool cancelRequested_ = false;
    bool running_ = false;
    // Low temperature and a tight nucleus by default -- see rebuildSampler's own reasoning in
    // text_assist.cpp for why.
    float temperature_ = 0.3f;
    float topP_ = 0.9f;
};

}  // namespace borderkeys

#endif  // BORDERKEYS_TEXT_ASSIST_HPP
